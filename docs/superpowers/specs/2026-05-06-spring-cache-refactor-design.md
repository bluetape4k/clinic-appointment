# Spring Cache 리팩터링 설계 명세

> Issue #52 — NearCache 직접 조작 → Spring @Cacheable 어노테이션 전환  
> 작성일: 2026-05-06  
> 상태: 승인됨 (구현 진행 중)

---

## 1. 배경 & 목표

### 배경

[Issue #52](https://github.com/bluetape4k/clinic-appointment/issues/52)

현재 `DoctorRepository`, `EquipmentRepository`, `TreatmentTypeRepository` 세 클래스는
`NearCacheOperations<V>` 인스턴스를 **생성자 파라미터**로 직접 주입받아 캐싱 로직을 내부에서 직접 호출한다.
이는 Repository의 비즈니스 로직(DB 쿼리)과 캐싱 인프라 코드가 같은 메서드 안에 혼재하는 구조로,
테스트 복잡도가 높고 캐시 전략 변경 시 Repository 코드를 직접 수정해야 하는 문제가 있다.

### 전환 목표

1. **Spring Cache 추상화 도입** — Repository 메서드에 `@Cacheable` / `@CacheEvict` 어노테이션을 적용하여
   캐싱 관심사를 비즈니스 로직에서 완전히 분리한다.
2. **NearCache 2-tier 구조 유지** — 기존 Caffeine(L1 로컬) + Redis(L2 원격) 2단계 캐시 성능을 그대로 보존한다.
3. **appointment-core 모듈 순수화** — appointment-core에서 NearCacheOperations 의존성을 제거하여
   모듈이 Spring Cache 추상화(spring-context)만 알도록 한다.

---

## 2. 현재 문제점 (As-Is)

### 2.1 Repository의 NearCache 직접 조작 패턴

세 Repository 모두 동일한 패턴을 반복한다:

```kotlin
// DoctorRepository.findByClinicId (현재)
fun findByClinicId(clinicId: Long): List<DoctorRecord> {
    val cacheKey = clinicId.toString()
    return clinicDoctorsCache?.get(cacheKey)     // L1/L2 캐시 직접 조회
        ?: Doctors.selectAll()
            .where { Doctors.clinicId eq clinicId }
            .map { it.toDoctorRecord() }
            .also { if (it.isNotEmpty()) clinicDoctorsCache?.put(cacheKey, it) }  // 직접 저장
}
```

동일 패턴이 `EquipmentRepository.findByClinicId`와 `TreatmentTypeRepository.findByClinicId`에도 중복된다.

### 2.2 구체적인 문제점

| 문제 | 설명 |
|------|------|
| 비즈니스-인프라 혼재 | DB 쿼리(비즈니스)와 캐시 get/put(인프라)이 같은 메서드에 공존 |
| 모듈 경계 위반 | appointment-core가 `bluetape4k-cache-lettuce` 런타임 구현체에 의존 (테스트 스코프여야 마땅함) |
| 테스트 중복 | 3개 Repository 각각에 4가지 시나리오 × 복수 DB 방언으로 동일한 캐시 CacheTest 12개 중복 존재 |
| 캐시 전략 변경 어려움 | TTL 변경, evict 정책 변경 시 Repository 코드를 직접 수정해야 함 |
| 빈 결과 처리 불일치 | `if (it.isNotEmpty()) cache?.put(...)` 로직이 분산되어 일관성 없음 |

### 2.3 의존성 현황

- `appointment-core/build.gradle.kts`: `bluetape4k-cache-lettuce`가 **testImplementation** 스코프에만 있으나,
  Repository 본 코드에서 `NearCacheOperations` 인터페이스(`bluetape4k-cache-core`) 직접 참조 중
- `appointment-api/build.gradle.kts`: `spring-boot-starter-cache` 미포함 (추가 필요)

---

## 3. 접근법 비교 (Brainstorming)

### 옵션 A: 커스텀 CacheManager 어댑터 (NearCache 2-tier 유지) — **선택**

`NearCacheOperations<V>`를 Spring `Cache` 인터페이스로 감싸는 `NearCacheAdapter<V>`를 구현하고,
이를 관리하는 `NearCacheCacheManager`를 `AbstractCacheManager`로 구현한다.

**장점:**
- 기존 NearCache 2-tier 성능(Caffeine L1 + Redis L2)을 100% 보존
- appointment-core는 `@Cacheable`만 알면 되고 인프라 구현체는 appointment-api에 격리
- Spring Cache 추상화의 모든 기능(@CacheEvict, @CachePut, 조건부 캐싱) 활용 가능

**단점:**
- NearCacheAdapter 구현 코드 약 50줄 추가 필요
- Spring의 빈 결과 처리와 NearCache의 빈 결과 처리를 어댑터에서 일치시켜야 함

### 옵션 B: CaffeineCacheManager (Redis 포기)

Spring Boot의 `CaffeineCacheManager`를 그대로 사용하고 Redis 레이어를 제거한다.

**장점:**
- 구현이 가장 단순 (설정 10줄)
- 외부 인프라(Redis) 의존 없음

**단점:**
- 다중 인스턴스 배포 시 캐시 일관성 깨짐 (각 인스턴스가 별도 L1만 보유)
- 현재 Redis 기반 원격 캐시 기능을 완전히 포기 — 요구사항과 불일치
- 클러스터 환경에서 실용성 없음

### 옵션 C: Service 레이어 @Cacheable (Repository는 순수 DB)

Repository에서 캐시를 제거하고 `SlotCalculationService` 등 Service 레이어에 `@Cacheable`을 적용한다.

**장점:**
- Repository가 순수 DB 접근 계층이 됨 (책임 명확)

**단점:**
- Service 메서드의 파라미터 조합이 복잡하여 캐시 키 설계가 어려움
- Service는 여러 Repository를 조합하는 로직이라 캐시 히트율이 낮음
- 기존 캐시 전략(병원 ID별 의사/장비/시술 목록)과 완전히 다른 구조가 됨

### 결론: 옵션 A 선택

기존 NearCache 2-tier 성능 보존과 Spring Cache 추상화 도입을 동시에 달성할 수 있는
옵션 A를 선택한다. 구현 복잡도는 낮고 모듈 경계 개선 효과가 가장 크다.

### 3.1 설계 리스크 / 실패 모드

| 번호 | 리스크 | 증상 | 대응 |
|------|--------|------|------|
| R-1 | **Kotlin allopen 미적용 → @Cacheable 무효** | Repository 메서드에 `@Cacheable`을 붙여도 캐시가 전혀 동작하지 않음. CGLIB 프록시 생성 실패 | Repository 클래스에 `@Repository` 스테레오타입 추가. kotlin-plugin.spring이 `@Repository` 감지 시 자동 open 처리. 통합 테스트로 실제 캐싱 동작 검증 필수 |
| R-2 | **빈 결과 캐싱 오동작** | DB에 데이터가 없는 clinicId 조회 시 빈 리스트가 캐시에 저장되어, 이후 데이터 추가 후에도 빈 결과 반환 | `NearCacheAdapter.put()`에서 `value == null || (value is List<*> && value.isEmpty())` 조건일 때 캐시 저장을 건너뜀. `@Cacheable(unless = "#result.isEmpty()")` 추가로 Spring Cache 레벨에서도 방어 |
| R-3 | **Redis 장애 시 캐시 전파 중단** | Redis가 다운되면 `NearCacheOperations.put()` 내부에서 예외가 발생하여 L1(Caffeine)에도 저장 안 될 수 있음 | `NearCacheAdapter.put()`에서 예외를 catch하여 로그만 남기고 정상 흐름 유지. L1 캐시는 Redis 상태와 독립적으로 동작하도록 NearCache 구현체 동작 확인 필요 |

---

## 4. To-Be 설계

### 4.1 컴포넌트 다이어그램

```
┌─────────────────────────────────────────────────────────────────┐
│  appointment-api                                                │
│                                                                 │
│  ┌──────────────────┐    ┌──────────────────────────────────┐  │
│  │   CacheConfig    │───>│   NearCacheCacheManager          │  │
│  │  @EnableCaching  │    │  (AbstractCacheManager 상속)     │  │
│  │                  │    │  - loadCaches(): NearCacheAdapter │  │
│  │  - redisClient   │    │    × 3개 등록                    │  │
│  │  - 3× NearCache  │    └──────────────┬───────────────────┘  │
│  │    빈 소유권     │                   │                      │
│  └──────────────────┘                   │ implements            │
│                                         ▼                      │
│                              ┌──────────────────────┐          │
│                              │  NearCacheAdapter<V>  │          │
│                              │  implements Cache     │          │
│                              │  - get(key)           │          │
│                              │  - put(key, value)    │          │
│                              │  - evict(key)         │          │
│                              │  - clear()            │          │
│                              └──────────┬────────────┘          │
│                                         │ wraps                 │
│                                         ▼                      │
│                          ┌──────────────────────────┐          │
│                          │  NearCacheOperations<V>   │          │
│                          │  (bluetape4k-cache-core)  │          │
│                          │  L1: Caffeine             │          │
│                          │  L2: Redis (Lettuce)      │          │
│                          └──────────────────────────┘          │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│  appointment-core                                               │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │   DoctorRepository  (@Repository, open class)            │  │
│  │   +findByClinicId(@Cacheable("clinic-doctors"))          │  │
│  └──────────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │   EquipmentRepository  (@Repository, open class)         │  │
│  │   +findByClinicId(@Cacheable("clinic-equipments"))       │  │
│  └──────────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │   TreatmentTypeRepository  (@Repository, open class)     │  │
│  │   +findByClinicId(@Cacheable("clinic-treatment-types"))  │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

### 4.2 NearCacheAdapter 설계

위치: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/NearCacheAdapter.kt`

```kotlin
/**
 * [NearCacheOperations]를 Spring [Cache] 인터페이스로 브릿지하는 어댑터.
 *
 * [AbstractValueAdaptingCache]를 상속하여 [get(key, type)] / [get(key, valueLoader)] 기본 구현을 위임한다.
 * - 빈 리스트(emptyList)는 캐시에 저장하지 않는다 (`put`, `putIfAbsent` 모두 적용).
 * - Redis 장애 시 예외를 삼키고 로그를 남겨 서비스 가용성을 유지한다.
 * - Reactive 경로(`retrieve()`)는 미지원 — Spring 기본 [UnsupportedOperationException] 위임.
 *
 * @param V 캐시 값 타입. `NearCacheCacheManager`가 명시적 타입으로 생성하므로 런타임 타입 안전성이 보장된다.
 * @param name 캐시 이름 (Spring CacheManager 식별자)
 * @param delegate NearCacheOperations 구현체 (L1: Caffeine, L2: Redis)
 */
class NearCacheAdapter<V : Any>(
    private val name: String,
    private val delegate: NearCacheOperations<V>,
) : AbstractValueAdaptingCache(/* allowNullValues = */ false) {

    override fun getName(): String = name
    override fun getNativeCache(): Any = delegate

    /**
     * 캐시에서 값을 조회한다. `get(key, type)` / `get(key, valueLoader)` 는 부모 클래스가 처리한다.
     * Redis 장애 시 null을 반환하여 캐시 미스로 처리 — DB fallback이 자동으로 수행된다.
     */
    override fun lookup(key: Any): Any? {
        return try {
            delegate.get(key.toString())
        } catch (e: Exception) {
            log.warn(e) { "캐시 조회 실패: name=$name, key=$key" }
            null
        }
    }

    override fun put(key: Any, value: Any?) {
        if (value == null) return
        if (value is List<*> && value.isEmpty()) return   // 빈 결과 캐싱 방지
        try {
            @Suppress("UNCHECKED_CAST")  // NearCacheCacheManager가 명시적 타입으로 생성하므로 안전
            delegate.put(key.toString(), value as V)
        } catch (e: Exception) {
            log.warn(e) { "캐시 저장 실패: name=$name, key=$key" }
        }
    }

    /**
     * 키가 없을 때만 저장한다. [NearCacheOperations.putIfAbsent]의 원자적 구현을 직접 위임하여
     * `get + put` 비원자적 경쟁 조건을 방지한다.
     */
    override fun putIfAbsent(key: Any, value: Any?): Cache.ValueWrapper? {
        if (value == null) return null
        if (value is List<*> && value.isEmpty()) return null
        return try {
            @Suppress("UNCHECKED_CAST")
            val prev = delegate.putIfAbsent(key.toString(), value as V)
            prev?.let { SimpleValueWrapper(it) }
        } catch (e: Exception) {
            log.warn(e) { "캐시 putIfAbsent 실패: name=$name, key=$key" }
            null
        }
    }

    override fun evict(key: Any) {
        try {
            delegate.remove(key.toString())   // NearCacheOperations.remove() — evict() 없음
        } catch (e: Exception) {
            log.warn(e) { "캐시 evict 실패: name=$name, key=$key" }
        }
    }

    override fun evictIfPresent(key: Any): Boolean {
        // Spring Cache 계약: 키가 실제로 존재했을 때만 true 반환
        // NearCacheOperations.get()으로 사전 확인 후 존재하면 remove
        return try {
            val existed = delegate.get(key.toString()) != null
            if (existed) delegate.remove(key.toString())
            existed
        } catch (e: Exception) {
            log.warn(e) { "캐시 evictIfPresent 실패: name=$name, key=$key" }
            false
        }
    }

    override fun clear() {
        try {
            delegate.clearAll()   // NearCacheOperations.clearAll() — clear() 없음
        } catch (e: Exception) {
            log.warn(e) { "캐시 전체 삭제 실패: name=$name" }
        }
    }
}
```

**빈 결과 처리 원칙:**
- `put(key, emptyList())` 호출 시 저장 건너뜀 — NearCacheAdapter 내부에서 처리
- `@Cacheable(unless = "#result.isEmpty()")` — Spring Cache 레벨에서 이중 방어
- 두 방어선을 동시에 적용하여 빈 결과가 캐시에 유입되는 경로를 완전히 차단

### 4.3 NearCacheCacheManager 설계

위치: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/NearCacheCacheManager.kt`

```kotlin
/**
 * [NearCacheAdapter] 목록을 Spring [CacheManager]로 노출하는 구현체.
 *
 * [AbstractCacheManager.loadCaches]를 구현하여 초기화 시 어댑터 목록을 등록한다.
 */
class NearCacheCacheManager(
    private val adapters: List<Cache>,
) : AbstractCacheManager() {

    override fun loadCaches(): Collection<Cache> = adapters
}
```

### 4.4 CacheConfig 변경 사항

`CacheConfig`가 다음 역할을 통합한다:
- `@EnableCaching` 활성화
- `RedisClient` 빈 (기존 유지)
- 3개 `NearCacheOperations` 빈 소유권 이전 (현재 `ServiceConfig`에 위치)
- `NearCacheCacheManager` 빈 등록

```kotlin
@Configuration(proxyBeanMethods = false)
@EnableCaching
class CacheConfig {
    companion object : KLogging() {
        private const val MASTER_CACHE_LOCAL_SIZE = 500L
        private val MASTER_CACHE_LOCAL_TTL: Duration = Duration.ofMinutes(10)
        private val MASTER_CACHE_REDIS_TTL: Duration = Duration.ofHours(1)
    }

    @Bean(destroyMethod = "shutdown")
    fun redisClient(@Value("\${spring.data.redis.url:redis://localhost:6379}") url: String): RedisClient {
        return RedisClient.create(url)
    }

    @Bean(destroyMethod = "close")
    fun clinicDoctorsCache(redisClient: RedisClient): NearCacheOperations<List<DoctorRecord>> {
        // ⚠️ LettuceCaches.nearCache API: 첫 번째 인자는 RedisClient, cacheName은 람다 내 프로퍼티
        return LettuceCaches.nearCache(redisClient) {
            cacheName = "clinic-doctors"
            maxLocalSize = MASTER_CACHE_LOCAL_SIZE
            frontExpireAfterWrite = MASTER_CACHE_LOCAL_TTL
            redisTtl = MASTER_CACHE_REDIS_TTL
        }
    }

    @Bean(destroyMethod = "close")
    fun clinicEquipmentsCache(redisClient: RedisClient): NearCacheOperations<List<EquipmentRecord>> {
        return LettuceCaches.nearCache(redisClient) {
            cacheName = "clinic-equipments"
            maxLocalSize = MASTER_CACHE_LOCAL_SIZE
            frontExpireAfterWrite = MASTER_CACHE_LOCAL_TTL
            redisTtl = MASTER_CACHE_REDIS_TTL
        }
    }

    @Bean(destroyMethod = "close")
    fun clinicTreatmentTypesCache(redisClient: RedisClient): NearCacheOperations<List<TreatmentTypeRecord>> {
        return LettuceCaches.nearCache(redisClient) {
            cacheName = "clinic-treatment-types"
            maxLocalSize = MASTER_CACHE_LOCAL_SIZE
            frontExpireAfterWrite = MASTER_CACHE_LOCAL_TTL
            redisTtl = MASTER_CACHE_REDIS_TTL
        }
    }

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
}
```

### 4.5 ServiceConfig 정리 내용

`ServiceConfig`에서 제거할 항목:
- NearCache 상수 (`MASTER_CACHE_LOCAL_SIZE`, `MASTER_CACHE_LOCAL_TTL`, `MASTER_CACHE_REDIS_TTL`)
- `clinicDoctorsCache` 빈 메서드
- `clinicEquipmentsCache` 빈 메서드
- `clinicTreatmentTypesCache` 빈 메서드
- `doctorRepository(clinicDoctorsCache)` 파라미터 제거 → `DoctorRepository()` 무인수 생성
- `treatmentTypeRepository(clinicTreatmentTypesCache)` 파라미터 제거
- `equipmentRepository(clinicEquipmentsCache)` 파라미터 제거

### 4.6 Repository 변경 사항

**공통 변경:**
1. `@Repository` 스테레오타입 추가 — kotlin-plugin.spring의 allopen 처리 트리거
2. 생성자에서 `NearCacheOperations` 파라미터 제거
3. `NearCacheOperations` import 제거
4. `findByClinicId` 메서드에 `@Cacheable` 추가

> **참고:** `@CacheEvict`는 현재 컨트롤러 3개가 모두 GET 전용이므로 이번 PR 범위에 포함하지 않는다.
> 의사/장비/시술 데이터 변경 API 추가 시 해당 메서드에 `@CacheEvict(cacheNames = [...], allEntries = true)` 적용을 별도 이슈로 추적한다.

**DoctorRepository After:**

```kotlin
@Repository
class DoctorRepository : LongJdbcRepository<DoctorRecord> {

    @Cacheable(cacheNames = ["clinic-doctors"], key = "#clinicId", unless = "#result.isEmpty()")
    fun findByClinicId(clinicId: Long): List<DoctorRecord> =
        Doctors.selectAll()
            .where { Doctors.clinicId eq clinicId }
            .map { it.toDoctorRecord() }
}
```

동일 패턴을 `EquipmentRepository`(`clinic-equipments`), `TreatmentTypeRepository`(`clinic-treatment-types`)에 적용한다.

**캐시 키 전략:**
- 캐시 이름: 기존 NearCache `cacheName`과 동일하게 유지 (`clinic-doctors`, `clinic-equipments`, `clinic-treatment-types`)
- 캐시 키: `clinicId` (Long) → SpEL `#clinicId`

---

## 5. 구현 범위 (파일 목록)

### 5.1 신규 생성 (2개)

| 파일 | 모듈 | 설명 |
|------|------|------|
| `appointment-api/src/main/kotlin/.../config/NearCacheAdapter.kt` | appointment-api | NearCacheOperations → Spring Cache 브릿지 |
| `appointment-api/src/main/kotlin/.../config/NearCacheCacheManager.kt` | appointment-api | AbstractCacheManager 구현체 |

### 5.2 수정 (5개)

| 파일 | 모듈 | 변경 내용 |
|------|------|-----------|
| `appointment-api/.../config/CacheConfig.kt` | appointment-api | @EnableCaching 추가, NearCache 빈 3개 + CacheManager 빈 추가 |
| `appointment-api/.../config/ServiceConfig.kt` | appointment-api | NearCache 빈 3개 및 관련 Repository 파라미터 제거 |
| `appointment-core/.../repository/DoctorRepository.kt` | appointment-core | @Repository 추가, NearCacheOperations 파라미터/로직 제거, @Cacheable 추가 |
| `appointment-core/.../repository/EquipmentRepository.kt` | appointment-core | 동일 패턴 적용 |
| `appointment-core/.../repository/TreatmentTypeRepository.kt` | appointment-core | 동일 패턴 적용 |

### 5.3 삭제 (3개)

| 파일 | 모듈 | 삭제 이유 |
|------|------|-----------|
| `appointment-core/.../repository/DoctorRepositoryCacheTest.kt` | appointment-core | Spring @Cacheable 통합 테스트로 대체 |
| `appointment-core/.../repository/EquipmentRepositoryCacheTest.kt` | appointment-core | 동일 |
| `appointment-core/.../repository/TreatmentTypeRepositoryCacheTest.kt` | appointment-core | 동일 |

---

## 6. 테스트 전략

### 6.1 NearCacheAdapterTest (단위 테스트, MockK)

위치: `appointment-api/src/test/kotlin/.../config/NearCacheAdapterTest.kt`

MockK로 `NearCacheOperations`를 모킹하여 NearCacheAdapter의 동작만 격리 검증한다.

검증 시나리오:
- `get(key)`: delegate에서 값 반환 시 `SimpleValueWrapper`로 감쌈
- `get(key)`: delegate에서 null 반환 시 null 반환
- `put(key, null)`: delegate.put 미호출
- `put(key, emptyList())`: delegate.put 미호출 (빈 결과 방지)
- `put(key, nonEmptyList)`: delegate.put 호출
- `evict(key)`: delegate.evict 호출
- `clear()`: delegate.clear 호출
- `put(key, value)` 중 Redis 예외 발생: 예외 삼키고 정상 완료
- `get(key)` 중 Redis 예외 발생: null 반환

### 6.2 CacheIntegrationTest (통합 테스트, @SpringBootTest)

위치: `appointment-api/src/test/kotlin/.../controller/CacheIntegrationTest.kt`

`AbstractApiIntegrationTest`를 상속하여 실제 Spring 컨텍스트에서 캐싱 동작을 검증한다.

검증 시나리오:
- 동일 `clinicId`로 `DoctorRepository.findByClinicId` 2회 호출 시 DB 쿼리가 1회만 발생하는지 확인
  (CacheManager에서 해당 캐시 엔트리 존재 여부로 간접 검증, 또는 SpyBean으로 DB 호출 횟수 검증)
- `EquipmentRepository`, `TreatmentTypeRepository` 동일 패턴 검증
- 빈 결과 반환 시 캐시 미저장 검증 (빈 clinicId로 조회 후 캐시 엔트리 없음 확인)
- @Cacheable이 실제로 동작함을 확인 (CGLIB 프록시 생성 성공 여부)

### 6.3 기존 Controller 테스트 (E2E 커버리지 유지)

기존 `DoctorControllerTest`, `EquipmentControllerTest`, `TreatmentTypeControllerTest`는 변경 없이
그대로 통과해야 한다. 이 테스트들이 실질적인 E2E 캐시 동작 검증을 포함한다.

---

## 7. DoD (완료 기준)

| 번호 | 기준 | 검증 방법 |
|------|------|-----------|
| D-1 | 컴파일 통과 | `./gradlew :appointment-api:build` 오류 없음 |
| D-2 | @Cacheable이 실제로 캐싱 동작하는 것을 통합 테스트로 검증 | `CacheIntegrationTest` 통과, SpyBean 또는 CacheManager로 캐시 엔트리 확인 |
| D-3 | NearCache 직접 조작 코드 0줄 | `rg "NearCacheOperations" appointment-core/src/main` 결과 없음 |
| D-4 | 기존 Controller 테스트 통과 | `./gradlew :appointment-api:test` 전체 통과 |
| D-5 | KDoc 한국어 주석 완비 | NearCacheAdapter, NearCacheCacheManager, 변경된 Repository 메서드 모두 KDoc 포함 |
| D-6 | 삭제 대상 테스트 3개 제거 | `DoctorRepositoryCacheTest`, `EquipmentRepositoryCacheTest`, `TreatmentTypeRepositoryCacheTest` 소스 삭제 |
| D-7 | appointment-api build.gradle.kts에 spring-boot-starter-cache 추가 | 의존성 확인 |

---

## 부록: 캐시 이름 매핑

| 캐시 이름 | Repository 메서드 | 캐시 키 | TTL(L1) | TTL(L2) |
|-----------|-------------------|---------|---------|---------|
| `clinic-doctors` | `DoctorRepository.findByClinicId` | clinicId | 10분 | 1시간 |
| `clinic-equipments` | `EquipmentRepository.findByClinicId` | clinicId | 10분 | 1시간 |
| `clinic-treatment-types` | `TreatmentTypeRepository.findByClinicId` | clinicId | 10분 | 1시간 |
