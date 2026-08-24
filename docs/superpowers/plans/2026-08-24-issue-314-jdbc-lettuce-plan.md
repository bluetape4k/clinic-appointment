# Issue #314 `jdbc-lettuce` master-data 파일럿 구현 계획

> **For agentic workers:** 승인된 설계와 이 계획을 따라 작업 단위별로 체크하고, 각 단계의 실패 증거를 읽은 뒤 다음 단계로 이동한다.

**Goal:** 운영 캐시 경로를 바꾸지 않고 `bluetape4k-exposed-jdbc-lettuce`의 실제
`AbstractJdbcLettuceRepository` 계약을 세 master-data 타입의 테스트 전용
파일럿으로 검증한다.

**Architecture:** `appointment-api`의 `testImplementation`에 BOM 관리
artifact만 추가하고, 기존 `Doctors`·`Equipments`·`TreatmentTypes`와
`DoctorRecord`·`EquipmentRecord`·`TreatmentTypeRecord`를 얇은 테스트 probe로
연결한다. probe는 `READ_ONLY`, 타입별 explicit Jackson3 codec, 고유 test-only
namespace를 사용하며 production repository·NearCache·API bean graph에는
등록하지 않는다. 기존 scope-list 경로와 candidate의 `findAll(where)`/per-ID
`get`을 같은 fixture에서 비교하고, scope-list/per-ID 불일치가 남으면 운영
전환을 보류한다.

**Tech Stack:** Kotlin 2.3, Spring Boot 4, Exposed v1 JDBC,
`bluetape4k-exposed-jdbc-lettuce`, Lettuce Redis, JUnit 5, bluetape4k assertions,
singleton Testcontainers launcher, Gradle version catalog.

---

## 파일 구조와 변경 경계

| 파일 | 책임 | 변경 유형 |
|---|---|---|
| `gradle/libs.versions.toml` | BOM 관리 jdbc-lettuce alias | 수정 |
| `appointment-api/build.gradle.kts` | test-only dependency 선언 | 수정 |
| `appointment-api/gradle.lockfile` | test configuration dependency lock 갱신 | Gradle로 생성·검토 |
| `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/JdbcLettuceMasterCachePilotIntegrationTest.kt` | fixture, SQL/Redis evidence, 세 타입 probe와 계약 테스트 | 생성 |
| `docs/superpowers/risk/2026-08-24-issue-314-jdbc-lettuce-risk.md` | caching/Redis/의존성/수명주기 위험과 rollback | 생성 |
| `docs/lessons/2026-08-24-issue-314-jdbc-lettuce.md` | 결과·보류 판정·재발 방지 guard | 생성, 검증 후 |

수정하지 않는 파일은 `appointment-core`의 세 production repository,
`appointment-api`의 `CacheConfig`, `NearCacheAdapter`, Flyway SQL, API DTO와
`clinic-*-v3` namespace이다. 새 production adapter, feature flag, metrics
abstraction, benchmark module은 만들지 않는다.

## Task 1: test-only dependency를 BOM 경계에 고정

**Files:**

- Modify: `gradle/libs.versions.toml`
- Modify: `appointment-api/build.gradle.kts`

- [x] **Step 1: version catalog alias를 추가한다.**

`[libraries]`의 Exposed 항목에 버전을 직접 쓰지 않고 다음 한 줄을 추가한다.

```toml
exposed-jdbc-lettuce = { module = "io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc-lettuce" }
```

- [x] **Step 2: API 모듈의 test classpath에만 연결한다.**

`appointment-api/build.gradle.kts`의 기존 Exposed test dependency 아래에
다음 선언을 추가한다.

```kotlin
testImplementation(libs.exposed.jdbc.lettuce)
```

`implementation`이나 `runtimeOnly`로 올리지 않는다.

- [x] **Step 3: dependency resolution을 확인한다.**

```bash
./gradlew :appointment-api:dependencies \
  --configuration testCompileClasspath \
  --write-locks \
  --no-build-cache --no-configuration-cache --console=plain
```

기대 결과: `io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc-lettuce`가
BOM이 결정한 버전으로 한 번 해석되고, `appointment-api/gradle.lockfile`에
test configuration 범위로만 기록되며 버전 충돌이나 lockfile 오류가 없다.

- [x] **Step 4: dependency 변경을 커밋한다.**

```bash
git add gradle/libs.versions.toml appointment-api/build.gradle.kts appointment-api/gradle.lockfile
git commit -m "테스트 전용 jdbc-lettuce 의존성 경계를 추가한다" -m "Constraint: 운영 runtime classpath와 production cache bean graph를 건드리지 않는다.\nRejected: 모듈 전역 implementation 승격은 파일럿 범위를 넓히므로 제외한다.\nConfidence: high\nScope-risk: narrow\nDirective: 테스트가 운영 전환 근거로 오해되지 않도록 testImplementation을 유지한다.\nTested: :appointment-api:dependencies --configuration testCompileClasspath\nNot-tested: probe 동작은 다음 task에서 검증한다."
```

## Task 2: RED — 첫 candidate 계약 테스트와 fixture 골격

**Files:**

- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/JdbcLettuceMasterCachePilotIntegrationTest.kt`

- [x] **Step 1: Spring 통합 테스트와 실패하는 doctor 계약을 작성한다.**

파일은 다음처럼 `AbstractApiIntegrationTest`와 실제 repository를 주입한다.
이 단계의 probe와 fixture helper는 `error("RED: ...")`를 반환해 테스트가
실제로 실패하도록 두며, production 코드는 수정하지 않는다.

```kotlin
class JdbcLettuceMasterCachePilotIntegrationTest @Autowired constructor(
    private val doctorRepository: DoctorRepository,
    private val cacheManager: CacheManager,
    private val redisClient: RedisClient,
) : AbstractApiIntegrationTest() {
    private lateinit var scopeA: TenantClinicScope
    private lateinit var doctorProbe: DoctorJdbcLettuceProbe

    @BeforeEach
    fun setUp() {
        cacheManager.getCache("clinic-doctors")?.clear()
        transaction {
            SchemaUtils.createMissingTablesAndColumns(TenantGroups, Clinics, Doctors)
            Doctors.deleteAll()
            Clinics.deleteAll()
            TenantGroups.deleteAll()
            insertTenantGroup(1L, "issue314-tenant-a")
            val clinicId = insertClinic(1L, "Issue 314 Clinic A")
            scopeA = TenantClinicScope(1L, clinicId)
            Doctors.insertAndGetId {
                it[Doctors.clinicId] = clinicId
                it[name] = "Issue 314 Doctor"
            }
        }
        doctorProbe = DoctorJdbcLettuceProbe(redisClient)
    }

    @Test
    fun `candidate doctor 조회 결과가 legacy scope 결과와 같다`() {
        val legacy = transaction { doctorRepository.findByScope(scopeA) }
        legacy.shouldNotBeEmpty()
        doctorProbe.findAll { Doctors.clinicId eq scopeA.clinicId } shouldBeEqualTo legacy
    }

    private fun insertTenantGroup(id: Long, code: String): Unit =
        error("RED: fixture helper is implemented with the green probe")

    private fun insertClinic(tenantGroupId: Long, name: String): Long =
        error("RED: fixture helper is implemented with the green probe")

    private class DoctorJdbcLettuceProbe(private val client: RedisClient) {
        fun findAll(where: () -> Op<Boolean>): List<DoctorRecord> =
            error("RED: AbstractJdbcLettuceRepository probe is not wired")
    }
}
```

- [x] **Step 2: RED를 확인한다.**

```bash
./gradlew :appointment-api:test \
  --tests "io.bluetape4k.clinic.appointment.api.config.JdbcLettuceMasterCachePilotIntegrationTest" \
  --no-build-cache --no-configuration-cache --console=plain
```

기대 결과: `RED: fixture helper is implemented with the green probe` 또는
`RED: AbstractJdbcLettuceRepository probe is not wired`가 발생한다. 이
실패는 candidate가 아직 연결되지 않았다는 증거이며, production 변경으로
우회하지 않는다.

## Task 3: GREEN — 실제 ecosystem probe와 공통 fixture를 연결

**Files:**

- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/JdbcLettuceMasterCachePilotIntegrationTest.kt`

- [x] **Step 1: fixture를 세 table과 두 tenant/세 clinic으로 확장한다.**

`setUp`에서 `TenantGroups, Clinics, Doctors, Equipments, TreatmentTypes`를
생성하고 자식부터 삭제한다. tenant A에는 데이터가 있는 clinic과 빈 clinic을,
tenant B에는 다른 clinic을 만든다. clinic 삽입은 다음 기존 Exposed 패턴을
그대로 사용한다.

```kotlin
private fun insertClinic(tenantGroupId: Long, name: String): Long =
    Clinics.insertAndGetId {
        it[Clinics.tenantGroupId] = EntityID(tenantGroupId, TenantGroups)
        it[Clinics.name] = name
        it[slotDurationMinutes] = 30
        it[timezone] = "Asia/Seoul"
        it[locale] = "ko-KR"
        it[maxConcurrentPatients] = 3
        it[openOnHolidays] = false
    }.value
```

`@AfterEach`는 legacy 세 cache를 clear하고 probe prefix의 raw key를 정리한 뒤
각 probe를 `close()`한다. cleanup은 `runCatching`으로 감싸며
`@Testcontainers`는 추가하지 않는다.

tenant seed는 고정 ID를 사용해 scope 재현성을 보장한다.

```kotlin
private fun insertTenantGroup(id: Long, code: String) {
    TenantGroups.insertAndGetId {
        it[TenantGroups.id] = EntityID(id, TenantGroups)
        it[TenantGroups.tenantCode] = code
        it[TenantGroups.displayName] = code
        it[TenantGroups.active] = true
    }
}
```

- [x] **Step 2: 세 record를 실제 `AbstractJdbcLettuceRepository`에 매핑한다.**

세 probe 모두 `LettuceCacheConfig.READ_ONLY.copy(keyPrefix =
"issue314:jdbc-lettuce:<type>", ttl = Duration.ofHours(1))`와 해당
`ExposedLettuceCodecs.jackson3(Record::class.java)`를 사용한다. doctor
probe의 전체 형태는 다음과 같다.

```kotlin
private class DoctorJdbcLettuceProbe(client: RedisClient) :
    AbstractJdbcLettuceRepository<Long, DoctorRecord>(
        client = client,
        config = LettuceCacheConfig.READ_ONLY.copy(
            keyPrefix = "issue314:jdbc-lettuce:doctors",
            ttl = Duration.ofHours(1),
        ),
        valueCodec = ExposedLettuceCodecs.jackson3(DoctorRecord::class.java),
    ) {
    override val table = Doctors
    override fun extractId(entity: DoctorRecord): Long = entity.id.requireNotNull("id")
    override fun ResultRow.toEntity(): DoctorRecord = toDoctorRecord()
    override fun UpdateStatement.updateEntity(entity: DoctorRecord) {
        this[Doctors.clinicId] = entity.clinicId
        this[Doctors.name] = entity.name
        this[Doctors.specialty] = entity.specialty
        this[Doctors.providerType] = entity.providerType
        this[Doctors.maxConcurrentPatients] = entity.maxConcurrentPatients
    }
    override fun BatchInsertStatement.insertEntity(entity: DoctorRecord) {
        this[Doctors.id] = entity.id.requireNotNull("id")
        this[Doctors.clinicId] = entity.clinicId
        this[Doctors.name] = entity.name
        this[Doctors.specialty] = entity.specialty
        this[Doctors.providerType] = entity.providerType
        this[Doctors.maxConcurrentPatients] = entity.maxConcurrentPatients
    }
}
```

equipment mapping은 `clinicId`, `name`, `usageDurationMinutes`, `quantity`를,
treatment mapping은 `clinicId`, `name`, `category`, `defaultDurationMinutes`,
`requiredProviderType`, `consultationMethod`, `requiresEquipment`,
`maxConcurrentPatients`를 사용한다. `ResultRow.toEntity`는 기존
`toEquipmentRecord()`/`toTreatmentTypeRecord()` mapper를 재사용한다.

두 probe의 실제 class 선언도 다음 field mapping을 고정한다.

```kotlin
private class EquipmentJdbcLettuceProbe(client: RedisClient) :
    AbstractJdbcLettuceRepository<Long, EquipmentRecord>(
        client,
        LettuceCacheConfig.READ_ONLY.copy(
            keyPrefix = "issue314:jdbc-lettuce:equipments",
            ttl = Duration.ofHours(1),
        ),
        ExposedLettuceCodecs.jackson3(EquipmentRecord::class.java),
    ) {
    override val table = Equipments
    override fun extractId(entity: EquipmentRecord): Long = entity.id.requireNotNull("id")
    override fun ResultRow.toEntity(): EquipmentRecord = toEquipmentRecord()
    override fun UpdateStatement.updateEntity(entity: EquipmentRecord) {
        this[Equipments.clinicId] = entity.clinicId
        this[Equipments.name] = entity.name
        this[Equipments.usageDurationMinutes] = entity.usageDurationMinutes
        this[Equipments.quantity] = entity.quantity
    }
    override fun BatchInsertStatement.insertEntity(entity: EquipmentRecord) {
        this[Equipments.id] = entity.id.requireNotNull("id")
        this[Equipments.clinicId] = entity.clinicId
        this[Equipments.name] = entity.name
        this[Equipments.usageDurationMinutes] = entity.usageDurationMinutes
        this[Equipments.quantity] = entity.quantity
    }
}

private class TreatmentTypeJdbcLettuceProbe(client: RedisClient) :
    AbstractJdbcLettuceRepository<Long, TreatmentTypeRecord>(
        client,
        LettuceCacheConfig.READ_ONLY.copy(
            keyPrefix = "issue314:jdbc-lettuce:treatment-types",
            ttl = Duration.ofHours(1),
        ),
        ExposedLettuceCodecs.jackson3(TreatmentTypeRecord::class.java),
    ) {
    override val table = TreatmentTypes
    override fun extractId(entity: TreatmentTypeRecord): Long = entity.id.requireNotNull("id")
    override fun ResultRow.toEntity(): TreatmentTypeRecord = toTreatmentTypeRecord()
    override fun UpdateStatement.updateEntity(entity: TreatmentTypeRecord) {
        this[TreatmentTypes.clinicId] = entity.clinicId
        this[TreatmentTypes.name] = entity.name
        this[TreatmentTypes.category] = entity.category
        this[TreatmentTypes.defaultDurationMinutes] = entity.defaultDurationMinutes
        this[TreatmentTypes.requiredProviderType] = entity.requiredProviderType
        this[TreatmentTypes.consultationMethod] = entity.consultationMethod
        this[TreatmentTypes.requiresEquipment] = entity.requiresEquipment
        this[TreatmentTypes.maxConcurrentPatients] = entity.maxConcurrentPatients
    }
    override fun BatchInsertStatement.insertEntity(entity: TreatmentTypeRecord) {
        this[TreatmentTypes.id] = entity.id.requireNotNull("id")
        this[TreatmentTypes.clinicId] = entity.clinicId
        this[TreatmentTypes.name] = entity.name
        this[TreatmentTypes.category] = entity.category
        this[TreatmentTypes.defaultDurationMinutes] = entity.defaultDurationMinutes
        this[TreatmentTypes.requiredProviderType] = entity.requiredProviderType
        this[TreatmentTypes.consultationMethod] = entity.consultationMethod
        this[TreatmentTypes.requiresEquipment] = entity.requiresEquipment
        this[TreatmentTypes.maxConcurrentPatients] = entity.maxConcurrentPatients
    }
}
```

- [x] **Step 3: tenant predicate helper를 실제 DB membership 조건으로 고정한다.**

```kotlin
private fun clinicIdsFor(scope: TenantClinicScope) =
    Clinics.select(Clinics.id).where {
        Clinics.tenantGroupId eq EntityID(scope.tenantGroupId, TenantGroups)
    }

private fun doctorPredicate(scope: TenantClinicScope): Op<Boolean> =
    (Doctors.clinicId eq scope.clinicId) and
        (Doctors.clinicId inSubQuery clinicIdsFor(scope))
```

equipment/treatment도 같은 `clinicIdsFor(scope)`를 각 table의 `clinicId`에
적용한다. 이 조건으로 다른 tenant가 같은 clinic ID를 요청한 교차 scope가
빈 결과인지 확인한다.

- [x] **Step 4: GREEN을 확인한다.**

동일 targeted command를 다시 실행한다. 기대 결과: legacy와 candidate record가
같고 테스트가 PASS한다. 실패하면 stack trace와 SQL을 읽고 probe mapping 또는
fixture만 수정한다.

## Task 4: 결과·tenant·empty-list·codec·TTL 계약을 확장

**Files:**

- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/JdbcLettuceMasterCachePilotIntegrationTest.kt`

- [x] **Step 1: 세 타입의 결과와 tenant 격리를 검증한다.**

각 legacy repository의 `findByScope(scopeA)`와 candidate의
`findAll(where = typePredicate(scopeA))`를 비교한다. tenant B clinic의 행은
tenant A scope 결과에 없고, `(tenantB, clinicA)` 교차 scope도 빈 결과여야 한다.
record 전체는 `shouldBeEqualTo`로 비교한다.

- [x] **Step 2: 빈 결과가 Redis key를 만들지 않고 이후 행을 읽는지 검증한다.**

tenant A의 빈 clinic을 각 predicate로 조회해 `shouldBeEmpty()`를 확인하고,
raw String connection의 `keys("issue314:jdbc-lettuce:<type>:*")`가 비어
있는지 확인한다. 같은 clinic에 row를 transaction으로 추가한 뒤 다시
`findAll`해 row가 반환되는지 확인한다.

- [x] **Step 3: key namespace와 TTL을 raw Redis로 확인한다.**

각 타입에서 한 건 이상 조회한 뒤 raw String connection으로 key를 읽고
다음을 assert한다.

```kotlin
keys.all { it.startsWith("issue314:jdbc-lettuce:doctors:") }
keys.none { it.startsWith("clinic-doctors-v3:") }
keys.map { commands.ttl(it) }.all { it in 1..3_600L }
```

equipment/treatment도 고유 prefix와 positive TTL을 확인한다. raw payload는
로그로 출력하지 않는다.

- [x] **Step 4: explicit codec round-trip을 확인한다.**

`findAll`로 warm한 각 record를 같은 ID의 `probe.get(id)`로 읽어
`shouldBeEqualTo`한다. 기존 v3 cache manager를 clear하는 것 외에 production
cache key를 직접 지우거나 쓰지 않는다.

## Task 5: SQL/cache hit·miss, invalidation, stale, transaction, close 계약

**Files:**

- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/JdbcLettuceMasterCachePilotIntegrationTest.kt`

- [x] **Step 1: 기존 `StatementInterceptor` 패턴으로 SQL 수를 기록한다.**

`ClinicSpringDataProjectionPilotTest`와 같은 `SqlStatementCapture`를
사용해 `transaction.registerInterceptor`/`unregisterInterceptor`를
`try/finally`로 감싼다. legacy 첫 호출은 해당 table SELECT 1건, 두 번째
`findByScope`는 0건, candidate `findAll`은 scope SELECT 1건이어야 한다.

- [x] **Step 2: per-ID warm hit과 key 삭제 후 read-through를 검증한다.**

candidate `findAll` 직후 `get(id)`를 capture해 SELECT 0건을 확인한다.
`probe.invalidate(id)` 뒤 같은 `get(id)`는 DB에서 다시 읽어 SELECT 1건을
만들고 같은 record를 반환해야 한다.

- [x] **Step 3: 외부 DB 변경·삭제와 invalidation을 검증한다.**

doctor row를 transaction에서 `Doctors.update`로 이름 변경한 뒤 invalidate
전에는 cached old value, invalidate 후에는 updated value를 확인한다. 이어
DB row를 `deleteWhere`로 삭제하고 다시 invalidate한 뒤 `get(id)`가 null이고
key가 없어지는지 확인한다. 이 테스트는 외부 writer가 자동 invalidation을
제공하지 않는 현재 경계를 문서화한다.

- [x] **Step 4: transaction 경계를 검증한다.**

`transaction { probe.findAll(where = predicate) }` 안에서 interceptor를
등록해 query가 같은 Exposed transaction 경계에서 완료되는지 확인한다.
transaction 밖으로 `Query`를 저장하지 않고 결과 record만 반환한다.

- [x] **Step 5: close와 cleanup을 검증한다.**

각 probe에 대해 `probe.clear(); probe.close(); probe.close()`를
`assertDoesNotThrow`로 실행한다. 내부 connection 상태를 reflection으로
읽지 않는다. `@AfterEach`의 cleanup 예외는 원래 assertion을 덮지 않도록
`runCatching`으로 분리한다.

## Task 6: Redis 장애 fallback과 dependency leakage를 검증

**Files:**

- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/JdbcLettuceMasterCachePilotIntegrationTest.kt`

- [x] **Step 1: 짧은 timeout의 실패 Redis client로 fallback을 검증한다.**

실제 singleton Redis를 닫지 않고 loopback의 사용하지 않는 포트와 짧은
timeout으로 warm 실패를 검증한다. `ExposedLettuceLoadedMap`이 cache 객체 생성
시점에 connection을 eager하게 열기 때문에, 이미 연결된 client를 별도로 종료해
`get` 명령 실패 fallback도 검증한다.

```kotlin
val unavailableClient = CacheConfig().redisClientWithTimeout(
    url = "redis://127.0.0.1:1",
    requireTls = false,
    commandTimeout = Duration.ofMillis(200),
)
val unavailableProbe = DoctorJdbcLettuceProbe(unavailableClient)
try {
    unavailableProbe.findAll { doctorPredicate(scopeA) } shouldBeEqualTo expected
} finally {
    runCatching { unavailableProbe.close() }
    unavailableClient.shutdown()
}

val failedClient = CacheConfig().redisClientWithTimeout(
    url = Containers.Redis.url,
    requireTls = false,
    commandTimeout = Duration.ofMillis(200),
)
val failedProbe = DoctorJdbcLettuceProbe(failedClient)
try {
    failedProbe.findAll { doctorPredicate(scopeA) } shouldBeEqualTo expected
    failedClient.shutdown()
    failedProbe.get(expected.single().id.requireNotNull("id")) shouldBeEqualTo expected.single()
} finally {
    runCatching { failedProbe.close() }
    runCatching { failedClient.shutdown() }
}
```

기대 결과: Redis GET/SET/warm 오류가 있어도 DB record가 반환되고 테스트가
멈추지 않는다. 출력에는 operation과 error type만 남기고 endpoint나 payload를
기록하지 않는다.

- [x] **Step 2: runtime classpath에 test-only artifact가 없는지 확인한다.**

```bash
./gradlew :appointment-api:dependencies \
  --configuration runtimeClasspath \
  --no-build-cache --no-configuration-cache --console=plain
```

기대 결과: 출력에 `bluetape4k-exposed-jdbc-lettuce`가 없다. 결과가 있으면
`testImplementation` scope를 고친 뒤 이 검사를 다시 실행한다.

- [x] **Step 3: bootJar에 test-only artifact가 유출되지 않는지 확인한다.**

```bash
./gradlew :appointment-api:bootJar \
  --no-build-cache --no-configuration-cache --console=plain
jar tf appointment-api/build/libs/appointment-api-*.jar | \
  rg 'bluetape4k-exposed-jdbc-lettuce'
```

기대 결과: 마지막 `rg`에 일치 항목이 없다. 일치하면 production scope
유출로 간주해 구현을 중단하고 dependency 경계를 복구한다.

## Task 7: 통합 검증·lesson·판정

**Files:**

- Create: `docs/lessons/2026-08-24-issue-314-jdbc-lettuce.md`

- [x] **Step 1: targeted test를 순차 실행한다.**

```bash
./gradlew :appointment-api:test \
  --tests "io.bluetape4k.clinic.appointment.api.config.JdbcLettuceMasterCachePilotIntegrationTest" \
  --no-build-cache --no-configuration-cache --console=plain
```

기대 결과: 모든 pilot test PASS, Redis failure fallback이 bounded하게 종료,
SQL/cache evidence가 로그에 남는다.

- [x] **Step 2: module regression과 build를 실행한다.**

```bash
./gradlew :appointment-api:test \
  --no-build-cache --no-configuration-cache --console=plain
```

기대 결과: baseline 851 tests, 3 skipped에서 pilot 8개가 추가된 859 tests,
3 skipped이며 기존 테스트 회귀가 없다. 이어 `:appointment-api:build`를 같은 옵션으로
실행하고 `git diff --check`를 통과시킨다.

- [x] **Step 3: 명세 acceptance trace를 lesson에 기록한다.**

lesson은 다음 표를 실제 수치와 함께 채운다.

| 항목 | 결과 기록 |
|---|---|
| 세 타입 결과/tenant/empty | PASS와 대표 assertion |
| key/codec/TTL/namespace | prefix, TTL 범위, v3 비혼입 |
| SQL/cache 동작 | legacy hit 0 SQL, candidate warm/get 수 |
| invalidate/stale/delete | PASS 또는 재현된 gap |
| Redis failure/close | fallback 결과와 cleanup 결과 |
| 운영 채택 판정 | scope-list/per-ID 불일치이면 `보류` |

실제 운영 SLO나 트래픽 수치를 추정하지 않는다. candidate가 scope-list
drop-in replacement가 아니라는 사실이 재현되면 production 변경 없이
`보류`로 결론 낸다.

- [x] **Step 4: lesson과 구현을 Lore commit으로 고정한다.**

```bash
git add gradle/libs.versions.toml appointment-api/build.gradle.kts appointment-api/gradle.lockfile \
  appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/JdbcLettuceMasterCachePilotIntegrationTest.kt \
  docs/lessons/2026-08-24-issue-314-jdbc-lettuce.md
git commit -m "jdbc-lettuce 파일럿 결과와 운영 보류 경계를 기록한다" -m "Constraint: 기존 scope-list production cache와 v3 namespace를 유지한다.\nRejected: per-ID warm을 scope-list drop-in replacement로 승격하지 않는다.\nConfidence: high\nScope-risk: narrow\nDirective: list-key 계약과 운영 관측 근거가 생기기 전에는 채택하지 않는다.\nTested: targeted pilot, :appointment-api:test, :appointment-api:build, runtimeClasspath, bootJar, git diff --check\nNot-tested: 운영 트래픽과 장기 TTL은 파일럿 범위를 벗어난다."
```

## Acceptance traceability와 stop condition

| 설계/Issue DoD | 계획 task | 완료 증거 |
|---|---:|---|
| 실제 jdbc-lettuce API 사용 | 1, 2, 3 | dependency resolution, lockfile, probe compile/test |
| production source/API/v3 불변 | 1, 6, 7 | diff, runtimeClasspath, bootJar |
| result/tenant/empty/codec/TTL | 4 | targeted assertions와 Redis metadata |
| invalidation/stale/failure/close | 5, 6 | SQL/log/fallback/cleanup evidence |
| scope-list와 per-ID 차이 | 5, 7 | interceptor count와 lesson 표 |
| 6관점 inline review | 설계 review, plan review | P0=0/P1=0 review artifact |
| rollback | risk doc, 1, 7 | dependency/test-only 제거로 복귀 가능 |

**Stop condition:** targeted/full verification과 dependency leakage 검사가
모두 PASS하고, candidate의 운영 채택 여부를 측정 결과로 `보류` 또는
명시적 조건부 채택으로 판정한 뒤 lesson commit까지 완료한다. P0/P1,
production runtime diff, 미확인 Redis 장애, 또는 baseline 회귀가 남으면
PR/merge 단계로 이동하지 않는다.

## Plan self-review

- 명세의 문제·범위·실패 모드·rollback·DoD가 Task 1~7과 traceability 표에
  연결되어 있다.
- 문서 내부에 미완성 placeholder가 없다.
- 모든 probe의 타입/codec/config/mapper 이름이 실제
  `AbstractJdbcLettuceRepository` 소스와 일치한다.
- fixture는 `transaction {}`, `SchemaUtils.createMissingTablesAndColumns`,
  `Table.deleteAll()`, singleton launcher 규칙을 따른다.
- plan 단계에서 승인 후 plan/review/risk 문서를 커밋하고, 그 전에는 Kotlin
  implementation을 시작하지 않는다.
