# Spring Cache 리팩터링 구현 계획

> 연관 스펙: `docs/superpowers/specs/2026-05-06-spring-cache-refactor-design.md`  
> Issue #52 — NearCache 직접 조작 → Spring @Cacheable 어노테이션 전환  
> 작성일: 2026-05-06

---

## 현재 코드 상태 (확인 완료)

### ServiceConfig.kt 현황
- `MASTER_CACHE_LOCAL_SIZE = 500L`, `MASTER_CACHE_LOCAL_TTL = 10분`, `MASTER_CACHE_REDIS_TTL = 1시간` 상수 3개
- `clinicDoctorsCache`, `clinicEquipmentsCache`, `clinicTreatmentTypesCache` 빈 메서드 3개 (`LettuceCaches.nearCache` DSL 사용)
- `doctorRepository(clinicDoctorsCache)`, `equipmentRepository(clinicEquipmentsCache)`, `treatmentTypeRepository(clinicTreatmentTypesCache)` — NearCache 파라미터 주입

### CacheConfig.kt 현황
- `redisClient` 빈만 존재 (`@EnableCaching` 없음)

### Repository 현황 (3개 동일 패턴)
- 생성자에 `NearCacheOperations<List<XxxRecord>>? = null` 파라미터
- `findByClinicId`: `cache?.get(key) ?: DB쿼리.also { if (it.isNotEmpty()) cache?.put(key, it) }` 패턴

### 의존성 현황
- `appointment-api/build.gradle.kts`: `spring-boot-starter-cache` 미포함
- `appointment-core/build.gradle.kts`: `bluetape4k.cache.core` (api 스코프) → `NearCacheOperations` 인터페이스 참조 가능하나, 리팩터링 후 제거 대상

### 삭제 대상 테스트
- `DoctorRepositoryCacheTest.kt`, `EquipmentRepositoryCacheTest.kt`, `TreatmentTypeRepositoryCacheTest.kt` 확인됨

---

## 태스크 목록

### T-01: 의존성 추가 — appointment-api build.gradle.kts
**complexity: low**

**파일:** `appointment-api/build.gradle.kts`

**작업:**
- `dependencies` 블록에 `implementation("org.springframework.boot:spring-boot-starter-cache")` 추가
- 위치: `implementation("org.springframework.boot:spring-boot-starter-web")` 바로 아래

**DoD:**
- `./gradlew :appointment-api:compileKotlin` 성공
- `rg "spring-boot-starter-cache" appointment-api/build.gradle.kts` 결과 1줄

---

### T-02: 의존성 확인 — appointment-core build.gradle.kts
**complexity: low**

**파일:** `appointment-core/build.gradle.kts`

**작업:**
- `@Cacheable` 어노테이션은 `spring-context` JAR에 포함됨
- 현재 `bluetape4k.cache.core`가 api 스코프에 있으므로 `spring-context` 전이 의존성 여부 확인
- `bluetape4k.cache.core`가 `spring-context`를 전이하지 않을 경우: `compileOnly("org.springframework:spring-context")` 또는 `implementation("org.springframework.boot:spring-boot-starter-cache")` 추가
- 리팩터링 후 `bluetape4k.cache.core`를 `api` 스코프에서 `testImplementation` 스코프로 이동 (NearCacheOperations가 main 코드에서 사라지면 필요 없음)

> **참고:** `bluetape4k.cache.core`가 현재 `api` 스코프이므로 `spring-context`를 전이할 가능성 있음.
> T-05(Repository 변경) 완료 후 컴파일 실패 시 이 태스크에서 의존성 조정.

**DoD:**
- `appointment-core` 컴파일 성공 (`@Cacheable` import 오류 없음)
- `NearCacheOperations`가 main 소스에서 제거된 후 `bluetape4k.cache.core`가 testImplementation으로만 존재

**blockedBy:** T-05

---

### T-03: NearCacheAdapter 구현
**complexity: high**

**파일 (신규):** `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/NearCacheAdapter.kt`

**패키지:** `io.bluetape4k.clinic.appointment.api.config`

**작업:**
```kotlin
class NearCacheAdapter<V : Any>(
    private val name: String,
    private val delegate: NearCacheOperations<V>,
) : AbstractValueAdaptingCache(/* allowNullValues = */ false)
```

구현 메서드:
- `getName(): String` — `name` 반환
- `getNativeCache(): Any` — `delegate` 반환
- `lookup(key: Any): Any?` — `delegate.get(key.toString())`, Redis 예외 → `null` + warn 로그
- `put(key, value)` — `null` / `emptyList()` 체크 후 skip; `delegate.put(key.toString(), value as V)`, 예외 → warn 로그
- `putIfAbsent(key, value): Cache.ValueWrapper?` — `null` / `emptyList()` 체크; `delegate.putIfAbsent(key.toString(), value as V)`로 원자적 위임; 기존 값 있으면 `SimpleValueWrapper(prev)` 반환
- `evict(key)` — `delegate.remove(key.toString())`, 예외 → warn 로그
- `evictIfPresent(key): Boolean` — `delegate.remove(key.toString())` + `true` 반환, 예외 → `false` 반환
- `clear()` — `delegate.clearAll()`, 예외 → warn 로그

**설계 주의사항:**
- `@Suppress("UNCHECKED_CAST")` 필요 (`value as V` 캐스팅)
- 모든 public 메서드에 KDoc 한국어 주석
- `companion object : KLogging()` 로거 추가

**DoD:**
- `./gradlew :appointment-api:compileKotlin` 성공
- `NearCacheAdapterTest` (T-08)에서 전체 시나리오 통과

---

### T-04: NearCacheCacheManager 구현
**complexity: low**

**파일 (신규):** `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/NearCacheCacheManager.kt`

**패키지:** `io.bluetape4k.clinic.appointment.api.config`

**작업:**
```kotlin
class NearCacheCacheManager(
    private val adapters: List<Cache>,
) : AbstractCacheManager() {
    override fun loadCaches(): Collection<Cache> = adapters
}
```

- KDoc 한국어 주석 포함

**DoD:**
- `./gradlew :appointment-api:compileKotlin` 성공
- `NearCacheCacheManager`가 `CacheManager` 타입으로 빈 등록 가능함을 CacheConfig(T-05)에서 확인

**blockedBy:** 없음 (T-03과 병렬 실행 가능)

---

### T-05: CacheConfig 수정 — @EnableCaching + NearCacheCacheManager 빈 등록
**complexity: medium**

**파일:** `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/CacheConfig.kt`

**현재 상태:** `redisClient` 빈 1개만 존재

**추가 작업:**
1. `@EnableCaching` 어노테이션 추가
2. `companion object : KLogging()` 내에 상수 추가:
   ```kotlin
   private const val MASTER_CACHE_LOCAL_SIZE = 500L
   private val MASTER_CACHE_LOCAL_TTL: Duration = Duration.ofMinutes(10)
   private val MASTER_CACHE_REDIS_TTL: Duration = Duration.ofHours(1)
   ```
3. `clinicDoctorsCache` 빈 메서드 추가 (ServiceConfig에서 이전, `LettuceCaches.nearCache` DSL 동일 패턴)
4. `clinicEquipmentsCache` 빈 메서드 추가
5. `clinicTreatmentTypesCache` 빈 메서드 추가
6. `cacheManager` 빈 추가:
   ```kotlin
   @Bean
   fun cacheManager(
       clinicDoctorsCache: NearCacheOperations<List<DoctorRecord>>,
       clinicEquipmentsCache: NearCacheOperations<List<EquipmentRecord>>,
       clinicTreatmentTypesCache: NearCacheOperations<List<TreatmentTypeRecord>>,
   ): CacheManager = NearCacheCacheManager(
       listOf(
           NearCacheAdapter("clinic-doctors", clinicDoctorsCache),
           NearCacheAdapter("clinic-equipments", clinicEquipmentsCache),
           NearCacheAdapter("clinic-treatment-types", clinicTreatmentTypesCache),
       )
   )
   ```

**DoD:**
- `./gradlew :appointment-api:compileKotlin` 성공
- `@EnableCaching` 존재 확인: `rg "@EnableCaching" appointment-api/src/main`

**blockedBy:** T-03, T-04

---

### T-06: ServiceConfig 정리 — NearCache 빈 및 Repository 파라미터 제거
**complexity: medium**

**파일:** `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ServiceConfig.kt`

**제거 항목:**
1. `companion object` 내 상수 3개 (`MASTER_CACHE_LOCAL_SIZE`, `MASTER_CACHE_LOCAL_TTL`, `MASTER_CACHE_REDIS_TTL`)
2. `clinicDoctorsCache` 빈 메서드
3. `clinicEquipmentsCache` 빈 메서드
4. `clinicTreatmentTypesCache` 빈 메서드
5. `doctorRepository(clinicDoctorsCache: NearCacheOperations<...>)` → `doctorRepository()` (파라미터 제거, 생성자 `DoctorRepository()`)
6. `equipmentRepository(clinicEquipmentsCache: NearCacheOperations<...>)` → `equipmentRepository()`
7. `treatmentTypeRepository(clinicTreatmentTypesCache: NearCacheOperations<...>)` → `treatmentTypeRepository()`

**제거 후 import 정리:**
- `LettuceCaches`, `NearCacheOperations`, `DoctorRecord`, `EquipmentRecord`, `TreatmentTypeRecord` import 제거
- `RedisClient` import 제거 (더 이상 ServiceConfig에서 사용 안 함)

**DoD:**
- `rg "NearCacheOperations" appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ServiceConfig.kt` 결과 없음
- `./gradlew :appointment-api:compileKotlin` 성공

**blockedBy:** T-05 (CacheConfig에 빈이 먼저 등록되어야 ServiceConfig에서 제거 가능)

---

### T-07: Repository 3개 @Cacheable 전환
**complexity: medium**

**파일 3개 (동일 패턴):**
- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/DoctorRepository.kt`
- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/EquipmentRepository.kt`
- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/TreatmentTypeRepository.kt`

**각 파일 변경 내용:**

**DoctorRepository:**
1. `@Repository` 어노테이션 추가 (kotlin-plugin.spring allopen 트리거)
2. 생성자 파라미터 제거: `class DoctorRepository : LongJdbcRepository<DoctorRecord>`
3. `NearCacheOperations` import 제거
4. `findByClinicId` 메서드 교체:
   ```kotlin
   @Cacheable(cacheNames = ["clinic-doctors"], key = "#clinicId", unless = "#result.isEmpty()")
   fun findByClinicId(clinicId: Long): List<DoctorRecord> =
       Doctors.selectAll()
           .where { Doctors.clinicId eq clinicId }
           .map { it.toDoctorRecord() }
   ```
5. KDoc 업데이트: NearCacheOperations 언급 제거, `@Cacheable` 동작 설명으로 교체

**EquipmentRepository:**
- 동일 패턴, 캐시 이름 `"clinic-equipments"`, 쿼리는 `Equipments.selectAll().where { Equipments.clinicId eq clinicId }.map { it.toEquipmentRecord() }`

**TreatmentTypeRepository:**
- 동일 패턴, 캐시 이름 `"clinic-treatment-types"`, 쿼리는 `TreatmentTypes.selectAll().where { TreatmentTypes.clinicId eq clinicId }.map { it.toTreatmentTypeRecord() }`

**DoD:**
- `rg "@Repository" appointment-core/src/main` — 3개 Repository 모두 확인 (allopen CGLIB 프록시 사전 검증)
- `rg "NearCacheOperations" appointment-core/src/main` 결과 없음
- `./gradlew :appointment-core:compileKotlin` 성공

**blockedBy:** T-01, T-05

---

### T-08: NearCacheAdapterTest 단위 테스트 작성
**complexity: medium**

**파일 (신규):** `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/NearCacheAdapterTest.kt`

**패키지:** `io.bluetape4k.clinic.appointment.api.config`

**사용 프레임워크:** Kotest + MockK (`io.mockk:mockk` — 이미 spring-boot-starter-test 전이 의존성에 포함)

**검증 시나리오 (11개):**
1. `lookup(key)`: delegate가 값 반환 → 해당 값 반환
2. `lookup(key)`: delegate가 `null` 반환 → `null` 반환
3. `lookup(key)`: delegate에서 Redis 예외 → `null` 반환 (예외 전파 없음)
4. `put(key, null)`: delegate.put 미호출
5. `put(key, emptyList())`: delegate.put 미호출
6. `put(key, nonEmptyList)`: delegate.put 1회 호출
7. `put(key, value)` Redis 예외: 예외 삼키고 정상 완료
8. `evict(key)`: `delegate.remove(key)` 1회 호출 (`delegate.evict` 아님)
9. `clear()`: `delegate.clearAll()` 1회 호출 (`delegate.clear` 아님)
10. `putIfAbsent(key, value)`: 키 없을 때 `delegate.putIfAbsent` 호출, `null` 반환
11. `putIfAbsent(key, value)`: 키 있을 때 `SimpleValueWrapper(prev)` 반환

**DoD:**
- `./gradlew :appointment-api:test --tests "*.NearCacheAdapterTest"` 전체 통과
- 11개 시나리오 전부 구현

**blockedBy:** T-03

---

### T-09: CacheIntegrationTest 통합 테스트 작성
**complexity: high**

**파일 (신규):** `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/CacheIntegrationTest.kt`

**패키지:** `io.bluetape4k.clinic.appointment.api.controller`

**상속:** `AbstractApiIntegrationTest`

**의존성 주입:**
```kotlin
@Autowired lateinit var cacheManager: CacheManager
// ⚠️ @SpyBean 주의: CGLIB(@Cacheable) + MockK spy 이중 프록시 충돌 위험
// 캐시 히트 검증은 CacheManager.getCache(name)?.get(key) 방식을 primary로 사용
// @SpyBean은 DB 호출 횟수 부가 검증 목적으로만 사용하고, CGLIB 충돌 시 제거
@SpyBean lateinit var doctorRepository: DoctorRepository
```

**검증 시나리오 (5개):**
1. **DoctorRepository 캐시 히트**: 동일 `clinicId` 2회 조회 후 `cacheManager.getCache("clinic-doctors")?.get(clinicId.toString())` → non-null 엔트리 존재 확인. 결과 값 일치 검증
2. **EquipmentRepository 캐시 히트** 동일 패턴 (`clinic-equipments`)
3. **TreatmentTypeRepository 캐시 히트** 동일 패턴 (`clinic-treatment-types`)
4. **빈 결과 미캐싱**: 존재하지 않는 clinicId로 조회 → `cacheManager.getCache("clinic-doctors")?.get(nonExistentId.toString())` → `null`
5. **멀티 clinicId 키 격리**: clinicId A, B 각각 데이터 삽입 후 조회 → 두 캐시 엔트리가 독립적으로 존재 (`get(clinicIdA)` ≠ `get(clinicIdB)`)

**주의사항:**
- 각 테스트 전 캐시 clear (`cacheManager.cacheNames.forEach { cacheManager.getCache(it)?.clear() }`)
- `AbstractApiIntegrationTest`가 Redis Testcontainer를 DynamicProperty로 주입함 → 별도 Redis 설정 불필요
- `@SpyBean`이 CGLIB 프록시 위에 재래핑되어 `verify` 실패 시 `CacheManager` 방식으로만 검증하도록 전환

**DoD:**
- `./gradlew :appointment-api:test --tests "*.CacheIntegrationTest"` 전체 통과
- CGLIB 프록시 생성 성공 (`@Cacheable`이 실제로 인터셉트됨) 확인
- 빈 결과 미캐싱 검증 통과

**blockedBy:** T-05, T-06, T-07, T-08

---

### T-10: 기존 CacheTest 3개 삭제
**complexity: low**

**삭제 파일:**
- `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/repository/DoctorRepositoryCacheTest.kt`
- `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/repository/EquipmentRepositoryCacheTest.kt`
- `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/repository/TreatmentTypeRepositoryCacheTest.kt`

**DoD:**
- `fd "CacheTest" appointment-core/src/test` 결과 없음
- `./gradlew :appointment-core:test` 성공 (기존 비-캐시 테스트는 유지)

**blockedBy:** T-07 (Repository에서 NearCacheOperations가 제거된 후 삭제)

---

### T-11: 전체 빌드 및 테스트 검증
**complexity: medium**

**작업:**
1. `./gradlew :appointment-api:build` — 컴파일 + 전체 테스트
2. `./gradlew :appointment-core:test` — core 테스트 (CacheTest 삭제 후 기존 테스트 유지 확인)
3. DoD 체크리스트 전항목 수동 확인

**DoD 체크리스트 (스펙 기준):**
- [ ] D-1: `./gradlew :appointment-api:build` 오류 없음
- [ ] D-2: `CacheIntegrationTest` 통과, 캐시 엔트리 확인
- [ ] D-3: `rg "NearCacheOperations" appointment-core/src/main` 결과 없음
- [ ] D-4: `./gradlew :appointment-api:test` 전체 통과
- [ ] D-5: NearCacheAdapter, NearCacheCacheManager, Repository 메서드 KDoc 완비
- [ ] D-6: CacheTest 3개 소스 삭제 확인
- [ ] D-7: `spring-boot-starter-cache` 의존성 추가 확인

**blockedBy:** T-01~T-10 전체

---

## 태스크 의존 관계 요약

```
T-01 (의존성 추가) ─────────────────────────────┐
T-03 (NearCacheAdapter) ─────┐                   │
T-04 (NearCacheCacheManager) ┤                   │
                              ├─ T-05 (CacheConfig) ─ T-06 (ServiceConfig 정리)
                                                  │         │
T-01 ─────────────────────── T-07 (Repository) ──┘         │
                                    │                       │
T-07 ─── T-02 (core 의존성 조정)    │                       │
T-07 ─── T-10 (CacheTest 삭제)      │                       │
T-03 ─── T-08 (NearCacheAdapterTest)│                       │
                                    │                       │
                T-05 + T-06 + T-07 ─┴── T-09 (CacheIntegrationTest)
                                                            │
                                    T-01~T-10 ──────── T-11 (빌드 검증)
```

### 병렬 실행 가능 태스크
- **Phase 1 (동시 시작):** T-01, T-03, T-04
- **Phase 2 (T-03+T-04 완료 후):** T-05
- **Phase 3 (T-05 완료 후):** T-06, T-07 (병렬)
- **Phase 4 (T-07 완료 후):** T-02, T-10 (병렬)
- **Phase 5 (T-03 완료 후):** T-08 (T-03 완료 시점에 시작 가능)
- **Phase 6 (T-05+T-06+T-07 완료 후):** T-09
- **Phase 7 (전체 완료 후):** T-11

---

## 리스크 대응 계획

| 리스크 | 감지 방법 | 대응 |
|--------|-----------|------|
| R-1: @Cacheable 무효 (allopen 미적용) | CacheIntegrationTest에서 DB 2회 호출 | `@Repository` 스테레오타입 확인, kotlin-plugin.spring 플러그인 적용 여부 확인 |
| R-2: 빈 결과 캐싱 오동작 | CacheIntegrationTest 시나리오 4 실패 | `NearCacheAdapter.put()` 빈 리스트 체크 + `unless = "#result.isEmpty()"` 이중 방어 |
| R-3: Redis 장애 시 예외 전파 | `lookup()` / `put()` 예외 시 서비스 다운 | try-catch로 예외 삼킴 + warn 로그 (T-03 구현 시 필수) |
| appointment-core @Cacheable 컴파일 오류 | `./gradlew :appointment-core:compileKotlin` 실패 | T-02에서 spring-context 의존성 추가 |
