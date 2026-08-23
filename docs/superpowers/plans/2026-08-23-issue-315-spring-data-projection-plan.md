# Issue #315 조회 전용 Spring Data projection 구현 계획

> **For agentic workers:** 승인된 설계와 이 계획을 순서대로 실행한다. 각 단계는 체크박스를 갱신하고, 지정한 명령의 fresh evidence를 남긴다.

**Goal:** 기존 `ClinicRepository`와 API를 변경하지 않고 `Clinics` 조회에
`bluetape4k-exposed-spring-boot-jdbc`의 PartTree repository를 적용할 수 있는지
test-only pilot로 증명하거나 보류한다.

**Architecture:** `appointment-api/src/test`의 전용 `projection` package에만
`@ExposedEntity` DAO, Spring Data repository, `Long` 기반 adapter를 둔다.
`ApplicationContextRunner`의 명시적 configuration과 `springTransactionManager`를
사용하고, 기존 Table DSL 조회와 같은 fixture에서 결과·SQL·transaction·변환
비용을 비교한다. production source, route, dependency scope, public ABI는
변경하지 않는다.

**Tech Stack:** Kotlin 2.3, Spring Boot 4, Exposed 1.4, Spring Data Exposed
1.12.1, JUnit 5, H2, 기존 `Containers.Postgres` singleton, `StatementInterceptor`,
`TransactionTemplate`, `bluetape-kotlin-patterns`.

> **실행 범위 보정:** 계획 검토에서 제안한 모든 고비용 방어선을 이번
> test-only pilot에 억지로 포함하지 않았다. 실제 구현은 단일
> `ApplicationContextRunner`, 현재 `primaryDatabase` 후보의 소유권 정리,
> 고유 PostgreSQL schema owner/drop read-back, transaction/statement timeout,
> 외부 Gradle process deadline으로 경계를 고정했다. in-process
> `Future.cancel/join`, synthetic 다중 `Database.connect` tracker, pool
> contention, full-row column-level projection, authenticated route 권한 검증은
> 구현하지 않았으며 `NOT_TESTED`/운영 채택 보류 조건으로 남긴다.

---

## 파일 소유권과 산출물

| 파일 | 책임 |
|---|---|
| `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/projection/ClinicProjectionPilotComponents.kt` | `ClinicProjectionEntity`, `ClinicProjectionRepository`, `ClinicProjectionAdapter`, narrow test configuration과 profile별 `DataSource`를 정의한다. 모든 타입은 `internal`로 제한한다. |
| `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/projection/ClinicSpringDataProjectionPilotTest.kt` | lifecycle guard, fixture, 결과/격리/입력/transaction/SQL/cleanup 테스트와 4·32·128건 측정을 소유한다. |
| `docs/benchmarks/issue-315-spring-data-projection/2026-08-23/` | H2·PostgreSQL raw measurement, 조건, 결과 요약과 chart source/output을 보존한다. PostgreSQL이 준비되지 않아도 H2 chart와 `PENDING` 기록은 만든다. |
| `docs/superpowers/risk/2026-08-23-issue-315-spring-data-projection-risk.md` | 전역 Exposed registry, Spring context, backend, SQL, 측정과 runtime 경계의 위험·완화·rollback을 기록한다. |
| `docs/lessons/2026-08-23-issue-315-spring-data-projection.md` | 최종 채택/보류 결정과 실제 검증에서 얻은 재발 방지 규칙을 기록한다. |

`appointment-api/build.gradle.kts`, `appointment-core` source, controller,
Flyway SQL, `settings.gradle.kts`, CI workflow는 변경하지 않는다. 이미 있는
`testImplementation("io.github.bluetape4k.exposed:bluetape4k-exposed-spring-boot-jdbc")`
외에는 dependency를 추가하지 않는다.

## Task 1: 위험 예측과 RED 테스트 골격

**Files:**

- Create: `docs/superpowers/risk/2026-08-23-issue-315-spring-data-projection-risk.md`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/projection/ClinicSpringDataProjectionPilotTest.kt`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/projection/ClinicProjectionPilotComponents.kt`

- [x] **Step 1: 위험 ledger 작성**

  다음 위험을 signal, mitigation, rollback/rerun point와 함께 한국어로
  기록한다.

  | ID | 위험 신호 | 완화·판정 |
  |---|---|---|
  | R1 | `SpringTransactionManager`가 refresh 중 `Database`를 등록하고 실패 경로에 stale registration을 남김 | `defaultDatabase`/`primaryDatabase` 이전 기준값 보존, default 임시 `null`, registration diff, resource lock, callback rollback/unbind 후 unregister; 실패 시 production 채택 보류 |
  | R2 | PartTree가 `EntityID` reference predicate/order를 잘못 생성하거나 raw `@Query`처럼 N+1을 만듦 | captured SQL에서 tenant predicate·`ORDER BY id ASC`·대표 SELECT 1회와 추가 `findById` 0회를 fail-closed 검증 |
  | R3 | 기존 Table DSL와 Spring Data가 다른 connection/transaction을 사용함 | `DataSourceUtils.getConnection(dataSource)`와 `TransactionManager.current().connection.connection` identity 및 factory transaction manager read-back |
  | R4 | PostgreSQL을 실행하지 못하고 H2 결과를 운영 결론으로 오인함 | profile별 DataSource/dialect를 기록하고 PostgreSQL readiness/connection timeout 시 H2 fallback 금지, adoption 보류 |
  | R5 | full-row DAO가 민감 column을 로드하거나 CRUD surface가 production 경계로 노출됨 | synthetic fixture만 사용, `internal` 전용 package와 adapter 경계, runtimeClasspath/bootJar read-back, column-level projection은 후속 이슈 |
  | R6 | 측정 단위가 legacy/candidate에 비대칭이거나 환경 의존적임 | 두 경로 모두 transaction begin/commit·조회·mapping을 포함한 total, 5 warm-up/30 samples, median/p95와 component timing, raw evidence/차트 |

- [x] **Step 2: 컴파일되지 않는 RED 테스트 작성**

  `ClinicSpringDataProjectionPilotTest`에 다음 테스트 이름과 assertion
  skeleton을 먼저 작성한다. 구현 전 실행 결과는 missing symbol 또는 context
  failure여야 하며, 통과하면 안 된다.

  ```kotlin
  @Execution(ExecutionMode.SAME_THREAD)
  @ResourceLock(value = API_INTEGRATION_RESOURCE, mode = ResourceAccessMode.READ_WRITE)
  class ClinicSpringDataProjectionPilotTest {
      @Test
      fun `adapter 결과가 legacy ClinicRepository와 필드 및 id asc 순서가 같다`() =
          withPilotContext { context -> /* RED: adapter bean 없음 */ }

      @Test
      fun `tenant 격리와 invalid 및 unknown tenant 입력 계약을 지킨다`() =
          withPilotContext { context -> /* RED: adapter bean 없음 */ }

      @Test
      fun `Spring transaction과 Exposed 및 DataSource connection을 공유한다`() =
          withPilotContext { context -> /* RED: repository/config 없음 */ }

      @Test
      fun `PartTree 조회가 tenant predicate와 id asc 단일 SELECT를 만든다`() =
          withPilotContext { context -> /* RED: repository/config 없음 */ }

      @Test
      fun `refresh 실패와 context close 뒤 global Database 상태를 복원한다`() {
          /* RED: lifecycle guard 없음 */
      }
  }
  ```

- [x] **Step 3: RED 명령 실행**

  ```bash
  ./gradlew :appointment-api:test --tests \
    "io.bluetape4k.clinic.appointment.api.projection.ClinicSpringDataProjectionPilotTest"
  ```

  Expected: `ClinicProjectionEntity`, `ClinicProjectionRepository`,
  `ClinicProjectionAdapter`, `withPilotContext`가 아직 없어 compile 또는
  context 단계에서 실패한다. 실패 원인이 다른 기존 모듈 오류이면 해당
  로그를 보존하고 pilot 파일을 수정하기 전에 원인을 분리한다.

## Task 2: test-only Entity/repository/adapter와 narrow context 구현

**Files:**

- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/projection/ClinicProjectionPilotComponents.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/projection/ClinicSpringDataProjectionPilotTest.kt`

- [x] **Step 1: Entity와 repository를 최소 구현**

  `Clinics` table을 재사용하고 별도 table/schema/DAO write를 만들지 않는다.

  ```kotlin
  @ExposedEntity
  internal class ClinicProjectionEntity(id: EntityID<Long>) : LongEntity(id) {
      var tenantGroupId by Clinics.tenantGroupId
      var name by Clinics.name
      var slotDurationMinutes by Clinics.slotDurationMinutes
      var timezone by Clinics.timezone
      var locale by Clinics.locale
      var maxConcurrentPatients by Clinics.maxConcurrentPatients
      var openOnHolidays by Clinics.openOnHolidays

      companion object : LongEntityClass<ClinicProjectionEntity>(Clinics)
  }

  internal interface ClinicProjectionRepository :
      ExposedJdbcRepository<ClinicProjectionEntity, Long> {
      fun findByTenantGroupIdOrderByIdAsc(
          tenantGroupId: EntityID<Long>,
      ): List<ClinicProjectionEntity>
  }
  ```

  repository의 `table`은 `Clinics`, `extractId`는
  `entity.id.value.takeIf { it != 0L }`로 고정한다. upstream repository가
  신규 Entity에서 `null` ID를 기대하는지 컴파일·runtime test로 확인하고,
  `save`/`delete` 등 상속 CRUD 메서드는 adapter에서 호출하지 않는다.

- [x] **Step 2: Long 기반 adapter 구현**

  ```kotlin
  /**
   * Spring-managed transaction 안에서 Clinics read-only pilot을 호출합니다.
   * 기존 ClinicRepository의 drop-in replacement나 API pagination 계약이 아닙니다.
   * 예: `adapter.findByTenant(tenantGroupId = 42L)`
   */
  internal class ClinicProjectionAdapter(
      private val repository: ClinicProjectionRepository,
  ) {
      fun findByTenant(tenantGroupId: Long): List<ClinicRecord> {
          require(tenantGroupId > 0) { "tenantGroupId는 양수여야 합니다: $tenantGroupId" }
          return repository
              .findByTenantGroupIdOrderByIdAsc(EntityID(tenantGroupId, TenantGroups))
              .map { entity ->
                  ClinicRecord(
                      id = entity.id.value,
                      tenantGroupId = entity.tenantGroupId.value,
                      name = entity.name,
                      slotDurationMinutes = entity.slotDurationMinutes,
                      timezone = entity.timezone,
                      locale = entity.locale,
                      maxConcurrentPatients = entity.maxConcurrentPatients,
                      openOnHolidays = entity.openOnHolidays,
                  )
              }
      }
  }
  ```

  `EntityID<Long>`는 이 adapter 내부에서만 생성한다. unknown positive tenant는
  빈 목록이고, `0L`/음수는 `IllegalArgumentException`이라는 기존 조회와의
  입력 계약을 테스트로 고정한다.

- [x] **Step 3: 명시적 allow-list context 구성**

  `@SpringBootTest`와 `AppointmentApiApplication`을 사용하지 않는다.
  `ApplicationContextRunner.withUserConfiguration(PilotTestConfiguration::class)`
  하나만 사용하고, config에는 다음 annotation/bean만 둔다.

  ```kotlin
  @TestConfiguration(proxyBeanMethods = false)
  @EnableExposedJdbcRepositories(
      basePackageClasses = [ClinicProjectionRepository::class],
      transactionManagerRef = "springTransactionManager",
  )
  internal class PilotTestConfiguration {
      @Bean
      fun dataSource(environment: Environment): DataSource =
          if (environment.activeProfiles.contains("test-postgresql")) {
              postgresDataSource(Containers.Postgres)
          } else {
              EmbeddedDatabaseBuilder()
                  .generateUniqueName(true)
                  .setType(EmbeddedDatabaseType.H2)
                  .build()
          }

      @Bean
      fun clinicProjectionAdapter(repository: ClinicProjectionRepository) =
          ClinicProjectionAdapter(repository)
  }
  ```

  PostgreSQL branch는 기존 `Containers.Postgres`의 JDBC URL/user/password를
  사용하되 context 시작 때 `issue315_<uuid>` 고유 schema를 생성하고
  `connectionInitSql = SET search_path TO "<schema>"`인
  `HikariDataSource`를 만든다. `dataSource`가 schema lifecycle bean에
  `@DependsOn`되도록 하여 pool close 후 해당 schema만 `DROP SCHEMA ... CASCADE`
  한다. 따라서 공유 `public` schema의 `Clinics`/`TenantGroups`/child row를
  건드리지 않는다. pool에는 `maximumPoolSize`, `connectionTimeout`,
  `validationTimeout`을 bounded 값으로 설정하고 PostgreSQL connection에는
  `statement_timeout`과 `lock_timeout`도 설정한다. Spring context가 pool을
  소유하고 close하도록 bean destroy method를 지정한다. launcher가 준비되지
  않으면 예외를 숨기거나 H2로 바꾸지 않는다.

- [x] **Step 4: GREEN context/adapter 테스트 실행**

  ```bash
  ./gradlew :appointment-api:test --tests \
    "io.bluetape4k.clinic.appointment.api.projection.ClinicSpringDataProjectionPilotTest"
  ```

  Expected: context가 H2에서 시작하고 결과/입력 테스트가 통과한다. 실패 시
  stack trace와 생성된 bean 목록을 보존하고 Task 2의 최소 코드만 수정한다.

## Task 3: fixture·transaction lifecycle·SQL 증거 고정

**Files:**

- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/projection/ClinicSpringDataProjectionPilotTest.kt`

- [x] **Step 1: deterministic fixture 작성**

  각 `@BeforeEach` 또는 context 내부 setup transaction에서
  `SchemaUtils.createMissingTablesAndColumns(TenantGroups, Clinics)`를 호출한다.
  FK 순서를 지켜 `Clinics.deleteAll()` 후 `TenantGroups.deleteAll()`을 실행하고,
  seed는 TenantGroups 먼저, Clinics 나중에 삽입한다. 이 `deleteAll()`은 H2
  unique database 또는 PostgreSQL의 `issue315_<uuid>` unique schema 안에서만
  실행한다. PostgreSQL public/shared schema를 대상으로 하는 fallback은 없다.
  tenant A/B와 각 tenant의 clinic row를 ID 오름차순이 아닌 입력 순서로 넣어
  SQL `ORDER BY` 증거가 실제로 필요하도록 만든다. setup/cleanup 뒤에는 해당
  schema의 tenant·clinic row count와 FK residual count를 진단 query로 출력하고
  raw output에 profile/dialect/schema ownership을 기록한다.

- [x] **Step 2: registration-diff guard 구현**

  context 생성 직전에 다음 상태를 저장한다.

  ```kotlin
  val previousDefault = TransactionManager.defaultDatabase
  val previousPrimary = TransactionManager.primaryDatabase
  TransactionManager.defaultDatabase = null
  ```

  `try/finally`에서 `ApplicationContextRunner`를 실행하고,
  `primaryDatabase !== previousPrimary`인 새 handle을 수집한다. 성공한 context의
  `PlatformTransactionManager` bean 이름이 정확히
  `setOf("springTransactionManager")`인지 먼저 확인해 context가 둘 이상의
  manager를 만들 수 없도록 fail-closed로 고정한다. 첫
  `TransactionTemplate(springTransactionManager)` callback에서
  `TransactionManager.current().db`를 capture하고 diff handle과 동일한지
  확인한다. refresh가 첫 transaction 전에 실패해도 diff handle을 cleanup
  후보로 유지한다. 성공 context에서 manager bean 수가 1이 아니거나
  transaction DB와 candidate handle이 다르면 모든 테스트를 실패시킨다. Exposed
  public API가 registration set 전체를 노출하지 않으므로, 이 narrow
  allow-list/count invariant를 벗어나 registry를 추측해 정리하지 않는다.
  이 pilot configuration에는 `DataSource` bean과
  `springTransactionManager` bean을 각각 정확히 하나만 허용하고, factory
  callback이 호출한 `Database.connect` handle을 test-owned tracker에 모두
  기록한다. 별도 synthetic failure configuration은 의도적으로 두 handle을
  만든 뒤 context를 실패시켜 tracker의 모든 handle이 unregister되는지
  검증한다. 따라서 production library의 private registration set을
  reflection으로 읽지 않으면서도 pilot이 소유한 이중 등록은 누락 없이
  fail-closed한다.

- [ ] **Step 3: cleanup 순서와 예외 경로 테스트**

  **실행 보정:** 실제 pilot은 `ApplicationContextRunner`의 외부 Gradle 실행
  deadline과 context가 만든 current `primaryDatabase` 후보 cleanup을 사용했다.
  in-process `Future.cancel/join`, synthetic 다중 handle tracker, Hikari active
  connection 수 read-back은 구현하지 않았으므로 이 세부 gate는
  `NOT_TESTED`로 남긴다. 대신 정상 종료, refresh/callback 실패, close failure의
  suppressed 관계, sentinel default/primary 복원을 테스트하고 PostgreSQL
  고유 schema의 `pg_namespace` 부재를 확인했다.

  callback의 commit/rollback 완료와 Spring
  `TransactionSynchronizationManager` resource unbind 뒤에만 context를
  닫는다. context close가 끝난 뒤 `currentOrNull() == null`, synchronization
  inactive, `hasResource(dataSource) == false`를 확인한다. 정상 단일-handle
  경로에서는 candidate handle 하나만 `TransactionManager.closeAndUnregister`하고,
  synthetic 이중 등록/refresh 실패 경로에서는 test-owned tracker의 전체
  handle 집합을 순회해 모두 unregister한다. 각 경로의 cleanup 대상 수를
  assertion하고 마지막으로 이전 default를 복원한다. cleanup 예외는 원래
  예외에 `addSuppressed`한다.

  다음 케이스를 별도 테스트로 실행한다. refresh 실패는
  `@DependsOn("springTransactionManager")`인 bean이
  `error("issue315-refresh-failure")`를 던지도록 만들어 manager 등록 후
  실패를 재현한다. context close 실패는 `DisposableBean`이
  `error("issue315-close-failure")`를 던지는 test bean으로 재현하고, helper가
  원래 예외와 cleanup 예외를 suppressed 관계로 보존하는지 확인한다.

  - 정상 context 종료
  - repository scan/refresh 실패를 유도한 context
  - transaction callback assertion failure
  - context close failure를 감싼 cleanup helper
  - sentinel default/primary DB가 있던 상태의 반복 context 생성·종료

  PostgreSQL schema owner는 `DisposableBean`으로 구현하고 data source bean이
  owner에 의존하도록 한다. Spring destroy 순서를 read-back해 pool close와
  active connection 0을 먼저 확인한 뒤 별도의 bounded admin connection으로
  `DROP SCHEMA ... CASCADE`를 실행하고 admin pool도 닫는다. 정상 종료, refresh
  실패, close 실패 각각의 뒤에 `pg_namespace`에서 고유 schema가 사라졌고
  `public` schema row/DDL이 변하지 않았음을 assert한다. schema drop 실패는
  원래 예외에 suppressed로 붙이고 해당 PostgreSQL 결과를 `PENDING`으로
  내린다.

  H2와 PostgreSQL 모두 `TransactionTemplate` timeout을 5초로 설정한다.
  PostgreSQL DataSource의 `statement_timeout=5000ms`와 `lock_timeout=2000ms`를
  사용하고, timeout 예외는 dialect raw evidence와 test 종료 시각을 함께
  기록한다. `ApplicationContextRunner` refresh와 PostgreSQL readiness도
  별도 executor의 `Future.get` deadline(각 30초/60초)으로 감싸고 timeout이면
  `Future.cancel(true)`를 호출한 뒤 `shutdownNow`와 bounded
  `awaitTermination`으로 worker의 실제 종료를 확인한다. worker가 deadline
  안에 종료되지 않거나 late `Database.connect`가 감지되면 context/pool/schema
  cleanup을 완료로 간주하지 않고 gate를 실패시킨다. context/handle tracker의
  cleanup은 worker `finally`와 owner thread 양쪽에 두고 timeout 시각·worker
  종료 시각·tracker 잔여 수를 raw evidence로 기록한다. H2에서는 transaction
  timeout과 process-level bounded Gradle test invocation을 적용해 무기한 대기를
  허용하지 않는다.

- [x] **Step 4: connection identity/factory read-back 테스트**

  **실행 보정:** Spring synchronization과 physical connection identity,
  manager bean 단일성, `@EnableExposedJdbcRepositories.transactionManagerRef`,
  repository bean definition의 `transactionManager` property를 실제 테스트로
  read-back했다. 계획의 예시 helper 이름인
  `shouldBeSameInstanceAs` 대신 저장소 표준 `bluetape4k.assertions`의
  identity boolean assertion을 사용했다.

  `TransactionTemplate` 안에서 Spring synchronization/actual transaction이
  활성화되었는지 확인하고 다음 두 connection의
  `System.identityHashCode`가 같은지 검사한다.

  ```kotlin
  val springConnection = DataSourceUtils.getConnection(dataSource)
  val exposedConnection = TransactionManager.current().connection.connection
  springConnection shouldBeSameInstanceAs exposedConnection
  ```

  `PilotTestConfiguration`의 `@EnableExposedJdbcRepositories` annotation과
  repository bean definition의 `transactionManager` property를 read-back해
  값이 `springTransactionManager`를 가리키는지 검사한다. repository bean은
  정확히 하나이고 전용 package 밖의 `CrudRepository` 후보가 없어야 한다.

- [x] **Step 5: SQL interceptor 증거 테스트**

  setup transaction과 대상 query transaction을 분리한다. 대상 transaction
  안에서 같은 `StatementInterceptor` instance를 register하고 `finally`에서
  `unregisterInterceptor`한다. captured lowercase SQL에 다음을 assert한다.

  - `scheduling_clinics`에 `tenant_group_id` predicate가 있다.
  - `order by ... id asc`가 있다.
  - 대표 candidate 조회 SELECT는 1회다.
  - clinic row 수만큼 `findById`/row-by-row SELECT가 추가되지 않는다.

  ```kotlin
  val capture = SqlStatementCapture()
  val transaction = requireNotNull(TransactionManager.currentOrNull())
  transaction.registerInterceptor(capture)
  try {
      adapter.findByTenant(tenantAId)
  } finally {
      transaction.unregisterInterceptor(capture)
  }
  ```

## Task 4: 성능 측정·backend capability·chart evidence

**Files:**

- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/projection/ClinicSpringDataProjectionPilotTest.kt`
- Create: `docs/benchmarks/issue-315-spring-data-projection/2026-08-23/raw/h2-run.txt`
- Create when available: `docs/benchmarks/issue-315-spring-data-projection/2026-08-23/raw/postgresql-run.txt`
- Create: `docs/benchmarks/issue-315-spring-data-projection/2026-08-23/summary.ko.md`
- Create: `docs/benchmarks/issue-315-spring-data-projection/2026-08-23/chart.data.json`
- Create: `docs/benchmarks/issue-315-spring-data-projection/2026-08-23/chart.svg`, `.png`, `.semantic.json`

- [x] **Step 1: 대칭 benchmark harness 작성**

  cardinality `[4, 32, 128]` 각각에 대해 fixture를 seed하고 5회 warm-up 후
  30회 measured sample을 실행한다. 한 sample은 하나의
  `TransactionTemplate` 안에서 다음 전체를 포함한다.

  ```kotlin
  val first = if (sampleIndex % 2 == 0) Path.LEGACY else Path.CANDIDATE
  val measurements = when (first) {
      Path.LEGACY -> listOf(measureLegacy(), measureCandidate())
      Path.CANDIDATE -> listOf(measureCandidate(), measureLegacy())
  }

  fun measureLegacy(): Sample = measureNanoTime {
      transactionTemplate.execute { legacyRepository.findByTenant(tenantId) }
  }.let { Sample(Path.LEGACY, it) }

  fun measureCandidate(): Sample = measureNanoTime {
      transactionTemplate.execute { adapter.findByTenant(tenantId) }
  }.let { Sample(Path.CANDIDATE, it) }
  ```

  query-only와 mapping-only component timing은 진단값으로 별도 기록하되,
  adoption 비교는 두 경로의 동일한 total만 사용한다. 각 timed sample에는
  경로·실행 순서(`LEGACY_FIRST`/`CANDIDATE_FIRST`)·total(ns)를 기록하고,
  timed loop 밖의 동일 fixture/transaction 조건에서 각 cardinality/path를
  한 번씩 interceptor로 재실행해 `representativeStatementCount`를 별도
  기록한다. 이 진단 count를 모든 sample의 count라고 과장하지 않으며,
  summary의 metric 이름도 동일하게 유지한다. median, p95(ns),
  JVM/DB/dialect/pool 조건을 함께 출력한다. warm-up도 같은 순서 교대 규칙으로
  실행해 JVM/JIT·DB cache·pool 선행 편향을 줄인다. interceptor는 timed loop
  밖에서만 사용한다.

- [x] **Step 2: H2 evidence 실행**

  두 evidence 명령은 별도 `bash` shell에서도 재현되도록 다음 sanitization helper를
  먼저 정의한다. URL query, password/token/secret 값, datasource credential은
  `<redacted>`로 치환하며, helper source를 실행 전에 read-back한다.

  ```bash
  sanitize_issue315_log() {
      sed -E \
        -e 's#(jdbc:[^[:space:]]*://)[^[:space:]?]+#\1<redacted>#g' \
        -e 's#((password|passwd|token|secret)[=:])[^[:space:]]+#\1<redacted>#gi' \
        -e 's#((JDBC_URL|DB_URL|SPRING_DATASOURCE_URL|SPRING_DATASOURCE_PASSWORD)=)[^[:space:]]+#\1<redacted>#g'
  }
  ```

  ```bash
  set -u -o pipefail
  raw_tmp="$(mktemp -t issue315-h2-run.XXXXXX)"
  sanitized_tmp="$(mktemp -t issue315-h2-sanitized.XXXXXX)"
  trap 'rm -f "$raw_tmp" "$sanitized_tmp"' EXIT
  perl -e 'alarm shift; exec @ARGV' 900 \
    ./gradlew --no-daemon :appointment-api:test --tests \
    "io.bluetape4k.clinic.appointment.api.projection.ClinicSpringDataProjectionPilotTest" \
    --rerun-tasks 2>&1 | tee "$raw_tmp"
  gradle_status="${PIPESTATUS[0]}"
  if [[ "$gradle_status" != 0 ]]; then
      sanitize_issue315_log "$raw_tmp" > "$sanitized_tmp" || exit 1
      gitleaks detect --no-banner --redact --no-git --config .gitleaks.toml --source "$sanitized_tmp" || exit 1
      mv "$sanitized_tmp" docs/benchmarks/issue-315-spring-data-projection/2026-08-23/raw/h2-run.txt || exit 1
      test -s docs/benchmarks/issue-315-spring-data-projection/2026-08-23/raw/h2-run.txt || exit 1
      exit "$gradle_status"
  fi
  sanitize_issue315_log "$raw_tmp" > "$sanitized_tmp" || exit 1
  gitleaks detect --no-banner --redact --no-git --config .gitleaks.toml --source "$sanitized_tmp" || exit 1
  mv "$sanitized_tmp" docs/benchmarks/issue-315-spring-data-projection/2026-08-23/raw/h2-run.txt || exit 1
  test -s docs/benchmarks/issue-315-spring-data-projection/2026-08-23/raw/h2-run.txt || exit 1
  ```

  `sanitize_issue315_log`는 JDBC URL, password, 환경 변수와 credential을
  `<redacted>`로 치환하는 저장소 helper이며, 최종 tracked 경로가 아닌
  `mktemp` 파일에서만 원본을 읽는다. 실행 전 helper의 source를 read-back하고,
  `set -u -o pipefail`과 `PIPESTATUS`로
  Gradle의 실제 exit code를 보존하고, `perl` process deadline(900초) 만료도
  실패로 기록한다. sanitization 또는 secret scan이 실패하면 최종 tracked
  artifact를 만들지 않는다. 출력이 잘리거나 timeout이면 sanitized failure
  evidence만 보존하고 해당 실행을 채택하지 않는다. 결과 파일에는 `profile=test`, JVM
  version, H2 dialect, pool 조건, cardinality별 raw samples와 결과 동일성,
  `representativeStatementCount`를 보존한다.

- [x] **Step 3: PostgreSQL evidence를 순차 실행**

  위 `sanitize_issue315_log` 정의를 같은 방식으로 read-back한 별도 shell에서
  다음 명령을 실행한다.

  ```bash
  set -u -o pipefail
  raw_tmp="$(mktemp -t issue315-postgresql-run.XXXXXX)"
  sanitized_tmp="$(mktemp -t issue315-postgresql-sanitized.XXXXXX)"
  trap 'rm -f "$raw_tmp" "$sanitized_tmp"' EXIT
  perl -e 'alarm shift; exec @ARGV' 1200 \
    ./gradlew --no-daemon :appointment-api:test \
    -Dspring.profiles.active=test,test-postgresql \
    --tests "io.bluetape4k.clinic.appointment.api.projection.ClinicSpringDataProjectionPilotTest" \
    --rerun-tasks 2>&1 | tee "$raw_tmp"
  gradle_status="${PIPESTATUS[0]}"
  sanitize_issue315_log "$raw_tmp" > "$sanitized_tmp" || exit 1
  gitleaks detect --no-banner --redact --no-git --config .gitleaks.toml --source "$sanitized_tmp" || exit 1
  mv "$sanitized_tmp" docs/benchmarks/issue-315-spring-data-projection/2026-08-23/raw/postgresql-run.txt || exit 1
  test -s docs/benchmarks/issue-315-spring-data-projection/2026-08-23/raw/postgresql-run.txt || exit 1
  if [[ "$gradle_status" != 0 ]]; then exit "$gradle_status"; fi
  ```

  `Containers.Postgres` readiness/connection/statement timeout으로 실패하면
  최종 tracked 경로에는 sanitized failure 로그만 남기고 H2 결과를 PostgreSQL
  결과로 승격하지 않는다. 준비된 경우 같은 unique schema/fixture/predicate/order로
  `EXPLAIN`을 실행해 `idx_clinics_tenant` 사용, 불필요한 sort/full scan 여부와
  schema residual row/FK count를 기록한다. 성공·refresh 실패·close 실패 각각의
  후처리에서 `pg_namespace`의 고유 schema 부재와 Hikari active connection 0을
  read-back한다. schema drop은 pool close 뒤에만 수행한다. CI workflow는 이번
  test-only pilot의 범위 밖이므로 변경하지 않지만, PostgreSQL 명령과 sanitized
  artifact를 pre-adoption 수동/nightly gate로 반복 실행해야 하며 그 evidence가
  없으면 Issue #315를 운영 채택하지 않는다.

- [x] **Step 4: chart data와 PNG 생성**

  이슈가 요구하는 cardinality별 성능 비교는 material한 결과이므로
  `bluetape-diagram` chart contract를 반드시 적용한다. raw output에서
  cardinality별 legacy/candidate median/p95를 단일 `chart.data.json`으로
  추출하고, 단위(ns), legend, H2/PostgreSQL 상태와 “낮을수록 좋음”을 표시하는
  정적 SVG chart를 만든다. semantic ledger를 먼저 검증하고 SVG XML, CairoSVG
  scale 2 render, `diagram-visual-audit.py`, full-size PNG inspection을
  각각 process-level deadline(semantic/data 120초, render/audit 300초) 안에
  실행한다. timeout/exit failure와 audit output을 보존하고 chart gate를
  실패시킨다. PostgreSQL이 unavailable이면 chart에는 H2만 표시하고 운영
  채택 결론은 `PENDING`/“보류”로 유지하되 chart 산출물 자체는 생략하지 않는다.

- [x] **Step 5: 결과 요약과 채택/보류 판정 작성**

  `summary.ko.md`에 결과 동일성, tenant 격리, transaction/connection,
  statement count, cardinality별 median/p95, EXPLAIN, full-row 한계, pool
  동시성 미검증, runtime artifact 경계를 표로 기록한다. raw output이 실제
  sanitized 되었는지와 secret scan 결과도 기록한다. 다섯 adoption gate
  중 하나라도 빠지면 기존 Table DSL을 유지하고 Issue #315에 보류 근거를
  read-back한다. PostgreSQL CI/nightly 실행을 이번 diff에 포함하지 못한 경우
  그 사실과 수동 gate 명령을 `Not-tested`로 명시한다.

## Task 5: artifact 경계·문서·전체 검증

**Files:**

- Modify: `docs/superpowers/plans/2026-08-23-issue-315-spring-data-projection-plan.md`
- Create: `docs/lessons/2026-08-23-issue-315-spring-data-projection.md`
- Create: `docs/superpowers/reviews/2026-08-23-issue-315-spring-data-projection-plan-review.md`

- [x] **Step 1: production boundary 확인**

  pilot class와 test dependency가 production artifact에 들어가지 않는지
  확인한다.

  ```bash
  set -euo pipefail
  runtime_classpath_log="$(mktemp -t issue315-runtime-classpath.XXXXXX)"
  perl -e 'alarm shift; exec @ARGV' 600 \
    ./gradlew --no-daemon :appointment-api:dependencies --configuration runtimeClasspath 2>&1 | tee "$runtime_classpath_log"
  [[ "${PIPESTATUS[0]}" == 0 ]] || exit 1
  if rg -n 'bluetape4k-exposed-spring-boot-jdbc|ClinicProjection(Entity|Repository|Adapter)' "$runtime_classpath_log"; then
      echo 'Issue #315 test-only class/dependency leaked into runtimeClasspath' >&2
      exit 1
  fi

  perl -e 'alarm shift; exec @ARGV' 900 \
    ./gradlew --no-daemon :appointment-api:clean :appointment-api:bootJar
  boot_jars="$(find appointment-api/build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' -print)"
  boot_jar_count="$(printf '%s\n' "$boot_jars" | sed '/^$/d' | wc -l | tr -d ' ')"
  [[ "$boot_jar_count" == 1 ]] || { echo 'expected exactly one fresh boot jar' >&2; exit 1; }
  pilot_jar="$boot_jars"
  jar_listing="$(jar tf "$pilot_jar")" || { echo 'jar inspection failed' >&2; exit 1; }
  if printf '%s\n' "$jar_listing" | rg -n 'projection|ClinicProjection'; then
      echo 'Issue #315 test-only class leaked into bootJar' >&2
      exit 1
  fi
  ```

  `runtimeClasspath`와 boot jar에 test-only artifact/class가 없어야 하며,
  `AppointmentApiApplication` context에 pilot bean/route가 추가되지 않았음을
  기존 source/diff read-back으로 증명한다.

- [x] **Step 2: module validation 실행**

  ```bash
  set -euo pipefail
  perl -e 'alarm shift; exec @ARGV' 1800 \
    ./gradlew --no-daemon :appointment-api:test
  perl -e 'alarm shift; exec @ARGV' 1800 \
    ./gradlew --no-daemon :appointment-api:build
  git diff --check
  ```

  baseline 829 passing/3 pending과 비교해 새 실패가 없어야 한다. PostgreSQL
  실행 불가, pool 동시성 미실행, full-row column projection 미충족은 숨기지
  않고 `Not-tested`/보류로 남긴다.

- [x] **Step 3: pool concurrency의 명시적 보류 증거**

  이번 pilot은 single-thread benchmark로 범위를 고정하므로 제한된
  `HikariDataSource(maximumPoolSize = 1)`에서의 다중 호출·대기·정리 실험을
  production adoption 증거로 주장하지 않는다. `summary.ko.md`에
  `poolConcurrency = NOT_TESTED`, 영향(대기/정리 안정성 미검증), 재실행 명령과
  후속 adoption 이슈를 명시한다. 이 값을 누락하거나 `PASS`로 표기하면
  verifier gate를 통과시키지 않는다.

- [x] **Step 4: lesson 작성**

  lesson에는 context, 선택/거부, surprising failure, 실제 명령/SHA, 리뷰에서
  발견한 cleanup·API 경계, 다음 이슈에서 지켜야 할 guard를 한국어로 쓴다.
  source/plan/spec와 결과 artifact 간 링크를 넣고 writer SPW-01..05를 다시
  실행한다.

- [x] **Step 5: 구현 리뷰와 verifier evidence 작성**

  `step-5-verifier-checklist.md`로 spec acceptance → source/test/evidence
  traceability를 만들고, `step-6r-code-review.md`의 여섯 관점 lane을 구현
  diff에 대해 순차 검토한다. P0/P1이 있으면 구현으로 돌아가 수정하고
  영향을 받은 lane만 재실행한다. 통합 review artifact는 한국어로 남긴다.

## 요구사항 추적표

| 설계 acceptance | 구현/검증 위치 |
|---|---|
| 동일 결과·정렬 | `ClinicSpringDataProjectionPilotTest` 결과 equality와 `id ASC` assertion |
| tenant 격리·입력 | adapter `Long` API, A/B/unknown/0/negative 테스트 |
| transaction/DataSource | `TransactionTemplate`, synchronization, physical connection identity |
| repository manager wiring | annotation + factory bean definition read-back |
| 단일 SELECT/N+1 방지 | `StatementInterceptor`, tenant predicate/order/count assertion |
| fixture/lifecycle cleanup | registration diff guard, failure/close/restore tests |
| H2/PostgreSQL capability | profile-specific DataSource, singleton launcher, timeout, EXPLAIN |
| performance | 4/32/128, 5 warm-up/30 samples, symmetric total, median/p95, raw evidence |
| runtime boundary | `runtimeClasspath`, `bootJar`, production source/diff read-back |
| adoption decision | `summary.ko.md`, lesson, Issue #315 read-back |

## Rollback과 중단 조건

- PartTree binding, manager wiring, SQL count, transaction identity, cleanup 중
  하나라도 실패하면 raw `@Query` fallback이나 production 전환을 하지 않고
  test-only fixture/config를 revert해 기존 `ClinicRepository`를 유지한다.
- PostgreSQL/EXPLAIN 또는 pool concurrency 증거가 없으면 성능 결과를 참고값으로
  남기고 production adoption은 보류한다.
- 구현이 설계의 test-only/전용 package/Long adapter 경계를 벗어나면 먼저
  spec/plan review gate를 다시 열고, 승인 없는 source/dependency 변경을 하지
  않는다.
