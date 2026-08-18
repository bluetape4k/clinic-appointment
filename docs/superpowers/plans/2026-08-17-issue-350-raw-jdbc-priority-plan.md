# Issue #350 raw JDBC 우선 경로 정렬 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: 구현을 시작하기 전에 이 계획을
> 읽고 각 작업을 순서대로 실행한다. 각 작업은 독립적으로 검증한 뒤 다음
> 작업으로 넘어간다.

**목표:** Exposed 트랜잭션 내부의 직접 JDBC 호출을 Exposed DSL 또는
`TransactionManager.current().exec(...)` 우선 경로로 전환하고, Flyway·readiness·
Gatling·benchmark·Testcontainers처럼 경계에 남아야 하는 호출은 기계적으로
검증 가능한 allowlist와 한국어 운영 문서로 고정한다.

**설계:** `MIGRATE`, `ADAPTER`, `ALLOWED-BOUNDARY` 세 분류를 호출 단위로
기록한다. `MIGRATE`는 enclosing Exposed transaction의 connection을 재사용하는
`exec`/DSL로 바꾸고, `ADAPTER`는 기존 standalone DataSource·query-plan·성능
보조 경계를 유지하되 정확한 호출과 대체 불가 사유를 기록한다.
`ALLOWED-BOUNDARY`는 framework lifecycle 또는 driver-level 책임으로 남기되
파일·심볼·값 바인딩·자원 소유자를 manifest에 고정한다. public API, repository
transaction ownership, Flyway schema 순서, PostgreSQL SQL semantics는 변경하지
않는다.

**기술 스택:** Kotlin 2.3, Java 25, Spring Boot 4, Exposed 1.4.0,
PostgreSQL Testcontainers(bluetape4k singleton launcher), H2 unit/wiring,
Node.js `node:test`, Gradle.

---

## 파일 구조와 책임

### 새로 추가할 파일

| 파일 | 책임 |
|---|---|
| `scripts/raw-jdbc-allowlist.json` | 38개 기준 파일의 호출·심볼·분류·소유자·유지 사유·검증 명령을 기계 판독 가능한 형태로 고정한다. 파일 하나에 여러 분류가 있으면 호출별 항목으로 나눈다. |
| `scripts/validate-appointment-raw-jdbc-inventory.mjs` | 지정된 Kotlin/Java source set을 스캔하고 direct JDBC marker를 찾는다. 새 direct JDBC, manifest에 없는 파일/심볼, 사라진 allowlist marker, Exposed transaction 내부의 허용되지 않은 connection 접근을 실패시킨다. `--json`과 `--self-test`를 지원한다. |
| `scripts/validate-appointment-raw-jdbc-inventory.test.mjs` | Node 표준 test runner로 direct JDBC 탐지, import/URL/예외 타입 false positive 제외, 바인딩 SQL 검사, allowlist 누락·추가·marker drift 실패를 고정한다. 외부 npm 의존성은 추가하지 않는다. |
| `docs/lessons/2026-08-17-issue-350-raw-jdbc-priority.md` | 기준 38파일/334 marker, 최종 분류표, allowlist 정책, resource lifecycle, PostgreSQL/H2 계약, benchmark 증거 명령과 실제 결과를 한국어로 남긴다. production SLO나 배포 증거는 주장하지 않는다. |
| `docs/superpowers/reviews/2026-08-17-issue-350-raw-jdbc-priority-plan-review.md` | 본 계획의 여섯 관점 검토와 통합 판정(P0~P3), spec-to-plan 추적, 남은 위험을 기록한다. |

### 변경 대상과 호출 분류

아래 표의 `MIGRATE`/`ADAPTER`/`ALLOWED-BOUNDARY`는 파일 전체가 아니라
해당 파일 안의 direct JDBC 호출 묶음에 적용한다. manifest는 표보다 세밀한
심볼 단위로 작성하고, 표의 38개 경로가 모두 나타나는지 검증한다.

| # | 파일 | 분류 | 실행 단위와 책임 |
|---:|---|---|---|
| 1 | `appointment-api/src/gatling/kotlin/io/bluetape4k/clinic/appointment/api/commitment/PatientAppointmentCancelPostgresFixture.kt` | `ALLOWED-BOUNDARY` | `sampleLockWaits`의 `pg_stat_activity` 계측은 Gatling PostgreSQL load-generation 진단 경계다. 값은 고정 query이고 결과 cursor/statement를 닫는다. |
| 2 | `appointment-api/src/gatling/kotlin/io/bluetape4k/clinic/appointment/api/commitment/VisitCommitmentGatlingFixture.kt` | `MIGRATE` + `ALLOWED-BOUNDARY` | `createSchedulingOutboxEventsTable`의 H2 DDL은 transaction connection 대신 `TransactionManager.current().exec(..., explicitStatementType = StatementType.CREATE)`로 전환한다. H2 URL과 Gatling source-set/driver setup은 경계로 기록한다. |
| 3 | `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ServiceConfig.kt` | `ALLOWED-BOUNDARY` | `bookingReliabilitySchemaReadiness`의 Spring-managed `DataSource` metadata/Flyway version probe는 readiness lifecycle이다. pool 소유권은 Spring에 두고 connection/statement/result를 현 scope에서 닫는다. |
| 4 | `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/DataSourceOwnershipContractTest.kt` | `ALLOWED-BOUNDARY` | 테스트 자체의 `DriverManager.getConnection`·`jdbc:` 문자열은 source guard의 pattern constant다. validator가 자기 manifest/guard 문자열을 직접 JDBC 호출로 오인하지 않도록 제외 규칙을 고정한다. |
| 5 | `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/commitment/VisitCommitmentCommandTestSupport.kt` | `MIGRATE` + `ALLOWED-BOUNDARY` | `tableReadable`의 `TransactionManager.current().connection` metadata lookup을 parameterized `TransactionManager.current().exec`로 전환한다. H2 fixture URL은 테스트 DB 경계로 남긴다. |
| 6 | `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/commitment/VisitCommitmentLoadIntegrationTest.kt` | `ALLOWED-BOUNDARY` | PostgreSQL singleton + Hikari pool은 실제 bounded load fixture 소유권이다. pool close와 shared schema cleanup을 보존하고 query path를 재설계하지 않는다. |
| 7 | `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/AppointmentCommitmentApplicationWiringTest.kt` | `ALLOWED-BOUNDARY` | Spring wiring 수명 검증용 H2 `HikariDataSource`와 seed DDL을 유지한다. `isClosed` assertion과 owner close 순서를 회귀로 보존한다. |
| 8 | `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/ExposedDatabaseFactoryTest.kt` | `ALLOWED-BOUNDARY` | H2 sentinel pool, `DelegatingDataSource`, `CountingDataSource`는 Exposed/Spring ownership contract를 시험하는 adapter fixture다. connection acquisition/lifecycle assertion을 유지한다. |
| 9 | `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/NotificationReminderRecoveryWiringTest.kt` | `ALLOWED-BOUNDARY` | Spring wiring용 H2 pool과 seed marker를 유지하고 test-owned pool을 닫는다. |
| 10 | `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/ProfileReevaluationWiringTest.kt` | `ALLOWED-BOUNDARY` | Spring wiring용 H2 pool과 seed marker를 유지하고 test-owned pool을 닫는다. |
| 11 | `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/integration/AppointmentConsumerMigrationContractTest.kt` | `ALLOWED-BOUNDARY` | migration contract용 `SimpleDriverDataSource`와 DDL은 Flyway/migration schema setup이다. application transaction으로 이동하지 않는다. |
| 12 | `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/integration/AppointmentPlanReadExplainIntegrationTest.kt` | `ADAPTER` | standalone DataSource에서 PostgreSQL `EXPLAIN`/seed query를 실행하는 query-plan adapter다. SQL semantics를 보존하고 값은 prepared binding으로 유지한다. |
| 13 | `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/integration/BookingReliabilityQueryPlanTest.kt` | `ADAPTER` | standalone H2/PostgreSQL query-plan fixture다. production repository transaction을 변경하지 않고 plan assertion과 statement close를 유지한다. |
| 14 | `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/integration/NotificationOutboxLoadIntegrationTest.kt` | `ADAPTER` | bounded load fixture의 standalone DataSource/pool을 유지한다. query execution과 pool lifecycle만 검증한다. |
| 15 | `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/integration/NotificationOutboxPerformanceTestSupport.kt` | `ADAPTER` | Flyway-backed performance seed/cleanup helper다. SQL 값을 binding하고 DataSource connection owner가 닫는다. |
| 16 | `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/integration/NotificationOutboxQueryPlanTest.kt` | `ADAPTER` | outbox `EXPLAIN`/query-plan adapter다. PostgreSQL SQL과 parameter binding, result cursor close를 보존한다. |
| 17 | `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/integration/PatientCancellationHistoryQueryPlanTest.kt` | `ADAPTER` | cancellation history schema/seed/`EXPLAIN` helper다. 동적 SQL fragment는 고정 상수로 유지하고 값은 binding한다. |
| 18 | `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/integration/ProfileReevaluationQueryPlanTest.kt` | `ADAPTER` | profile reevaluation query-plan fixture다. dialect별 고정 `EXPLAIN` branch와 connection lifecycle을 보존한다. |
| 19 | `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/integration/SchedulingPolicyPerformanceIntegrationTest.kt` | `ADAPTER` | 성능 seed/measurement/query-plan adapter다. 새 benchmark threshold를 만들지 않고 기존 assertions와 SQL semantics를 유지한다. |
| 20 | `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/integration/VisitCommitmentPerformanceIntegrationTest.kt` | `ADAPTER` | commitment 성능 seed/`EXPLAIN ANALYZE` adapter다. SQL 결과·pool 수명·resource cleanup을 보존한다. |
| 21 | `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/AppointmentCancellationMigrationTestSupport.kt` | `ALLOWED-BOUNDARY` | Flyway migration seed/metadata/검증 connection이다. |
| 22 | `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/AppointmentMessagingMigrationTestSupport.kt` | `ALLOWED-BOUNDARY` | Flyway migration과 legacy replay fixture connection이다. |
| 23 | `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/AppointmentPlanMigrationTestSupport.kt` | `ALLOWED-BOUNDARY` | migration schema/legacy data와 Exposed additive-drift 검증 connection이다. |
| 24 | `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/BookingReliabilityMigrationTestSupport.kt` | `ALLOWED-BOUNDARY` | Flyway v17 migration fixture다. |
| 25 | `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/LegacyAppointmentVersionMigrationTestSupport.kt` | `ALLOWED-BOUNDARY` | legacy migration DDL/data fixture다. |
| 26 | `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/MultitenancyMigrationTest.kt` | `ALLOWED-BOUNDARY` | 대상 DB에 schema를 만들고 삭제하는 migration contract의 `DriverManager` 경계다. schema identifier는 내부 고정 값이며 user input이 아니다. |
| 27 | `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/NotificationOutboxMigrationTestSupport.kt` | `ALLOWED-BOUNDARY` | outbox migration seed/metadata 검증 connection이다. |
| 28 | `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/PatientAuthenticationMigrationTestSupport.kt` | `ALLOWED-BOUNDARY` | Flyway authentication migration support다. |
| 29 | `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/ProfileReevaluationMigrationTestSupport.kt` | `ALLOWED-BOUNDARY` | profile migration/additive drift 검증 connection이다. |
| 30 | `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/ReminderRecoveryCheckpointMigrationTestSupport.kt` | `ALLOWED-BOUNDARY` | reminder checkpoint migration fixture다. |
| 31 | `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/TenantQueryIsolationMigrationTestSupport.kt` | `ALLOWED-BOUNDARY` | tenant isolation migration seed/query fixture다. |
| 32 | `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/VisitCommitmentMigrationTestSupport.kt` | `ALLOWED-BOUNDARY` | visit commitment migration seed/additive-drift fixture다. |
| 33 | `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/WaitlistCoreMigrationTestSupport.kt` | `ALLOWED-BOUNDARY` | waitlist core Flyway fixture다. |
| 34 | `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/WaitlistDeliveryMigrationTestSupport.kt` | `ALLOWED-BOUNDARY` | waitlist delivery migration/`EXPLAIN` fixture다. |
| 35 | `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/test/TestDB.kt` | `ALLOWED-BOUNDARY` | bluetape4k singleton PostgreSQL/H2 launcher와 Exposed `setupConnection` lifecycle을 제공한다. `@Testcontainers`는 추가하지 않는다. |
| 36 | `appointment-messaging/src/test/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentMessagingAutoConfigurationTest.kt` | `ALLOWED-BOUNDARY` | Spring auto-configuration 배선 검증용 H2 `JdbcDataSource`와 seed DDL이다. |
| 37 | `appointment-messaging/src/test/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentMessagingReadinessValidatorTest.kt` | `ALLOWED-BOUNDARY` | PostgreSQL/H2 readiness metadata setup과 schema create/drop을 직접 소유한다. singleton launcher·DataSource close·고정 schema identifier를 검증한다. |
| 38 | `benchmark/appointment-messaging-benchmark/src/main/kotlin/io/bluetape4k/clinic/appointment/benchmark/PostgreSqlBenchmarkFixture.kt` | `ALLOWED-BOUNDARY` | PostgreSQL schema/driver/Hikari/Flyway setup, seed, contention cleanup은 benchmark driver-level 책임이다. `close()`의 executor/pool 종료를 보존한다. |

## 실행 작업

### 작업 1 — RED: inventory contract와 machine allowlist를 먼저 고정

**변경 파일:**

- `scripts/raw-jdbc-allowlist.json`
- `scripts/validate-appointment-raw-jdbc-inventory.mjs`
- `scripts/validate-appointment-raw-jdbc-inventory.test.mjs`

**절차:**

1. 설계 문서의 38개 파일/334 marker를 manifest의 호출 단위 항목으로 옮긴다.
   각 항목은 `path`, `symbol`, `classification`, `owner`, `reason`,
   `rejectedAlternative`, `verification`, `marker`를 가진다.
2. Node 테스트에 다음 실패 우선 사례를 작성한다.
   - `TransactionManager.current().connection.prepareStatement`는 migrate
     항목으로 바꾸기 전 direct JDBC violation이다.
   - `DriverManager.getConnection`과 `jdbc:` 문자열은 각각 boundary와 URL
     marker를 구분한다.
   - `java.sql.Connection` import, `SQLException` 타입, validator 자신의
     pattern constant는 direct resource violation이 아니다.
   - `INSERT ... VALUES (?)`는 binding으로 통과하고 문자열 보간 value는
     실패한다.
   - manifest 파일이 삭제되거나 marker가 사라지거나 새 파일/심볼이 생기면
     실패한다.
3. 구현 전 `node --test scripts/validate-appointment-raw-jdbc-inventory.test.mjs`
   를 실행해 현재 기준의 두 transaction-owned direct connection이 RED임을
   기록한다. 예상 출력은 `VisitCommitmentCommandTestSupport.kt`와
   `VisitCommitmentGatlingFixture.kt`의 unallowlisted `connection` violation이다.
4. 이 단계에서는 production Kotlin/Java source를 변경하지 않는다.

**완료 기준:** 테스트가 의도한 RED를 내고, 38개 path가 manifest에 한 번씩
나타나며, `git diff --check`가 통과한다.

**커밋 경계:** `Issue #350 inventory 기준과 RED 검증을 고정한다`.

### 작업 2 — GREEN: Exposed transaction 내부 호출을 최소 전환

**변경 파일:**

- `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/commitment/VisitCommitmentCommandTestSupport.kt`
- `appointment-api/src/gatling/kotlin/io/bluetape4k/clinic/appointment/api/commitment/VisitCommitmentGatlingFixture.kt`
- 필요 시 두 파일의 KDoc만 한국어로 보강한다.

**절차:**

1. `tableReadable`를 `transaction(database)`가 제공하는 current transaction에
   참여하도록 유지한다. `TransactionManager.current().connection` cast와
   metadata cursor를 제거하고, `information_schema.tables`의 고정 SQL에
   `VarCharColumnType() to candidate`를 binding한
   `TransactionManager.current().exec(..., StatementType.SELECT)`로 바꾼다.
   `rows.next()` 결과와 대소문자 후보 순서는 보존한다.
2. `createSchedulingOutboxEventsTable`의 H2 DDL은 새 connection을 열지 않고
   `TransactionManager.current().exec(ddl, explicitStatementType = StatementType.CREATE)`
   로 실행한다. DDL identifier와 column fragment는 소스 고정 상수이며,
   request value를 삽입하지 않는다.
3. 두 변경 직후 다음 targeted 검증을 순차 실행한다.
   ```bash
   ./gradlew :appointment-api:compileTestKotlin :appointment-api:compileGatlingKotlin \
     --no-build-cache --no-daemon --console=plain
   ./gradlew :appointment-api:test \
     --tests '*VisitCommitmentLoadIntegrationTest' \
     --tests '*AppointmentCommitmentApplicationWiringTest' \
     --no-build-cache --no-daemon --console=plain
   node --test scripts/validate-appointment-raw-jdbc-inventory.test.mjs
   node scripts/validate-appointment-raw-jdbc-inventory.mjs
   ```
4. PostgreSQL `VisitCommitmentLoadIntegrationTest`가 실패하면 transaction
   owner와 schema initialization 순서를 먼저 확인한다. repository abstraction,
   retry, caller-owned transaction은 수정하지 않는다.

**완료 기준:** compile/test가 통과하고 validator가 두 MIGRATE 호출의 direct
connection violation을 0건으로 보고한다. `PatientAppointmentCancelPostgresFixture`
의 `sampleLockWaits`는 Gatling 진단 경계로 유지되어 allowlist에서만 통과한다.

**커밋 경계:** `Exposed transaction 내부 JDBC 호출을 exec 경로로 정렬한다`.

### 작업 3 — GREEN: validator와 allowlist를 최종 정책으로 연결

**변경 파일:**

- `scripts/validate-appointment-raw-jdbc-inventory.mjs`
- `scripts/raw-jdbc-allowlist.json`
- `scripts/validate-appointment-raw-jdbc-inventory.test.mjs`

**절차:**

1. scanner의 source root를 다음으로 고정한다.
   `appointment-api/src/{main,test,gatling}`, `appointment-core/src/{main,test}`,
   `appointment-event/src/{main,test}`, `appointment-messaging/src/{main,test}`,
   `appointment-notification/src/{main,test}`, `appointment-solver/src/{main,test}`,
   `benchmark/appointment-messaging-benchmark/src/main`.
2. direct resource marker(`DriverManager.getConnection`, receiver의
   `prepareStatement/createStatement`, `executeQuery/executeUpdate`,
   `TransactionManager.current().connection`, connection cast/use)를 탐지하되
   import, URL literal, exception type, validator/manifest fixture 문자열은
   제외한다. SQL value 문자열 보간은 실패시키고 `?` binding 또는 고정 SQL
   fragment만 통과시킨다.
3. manifest 항목의 실제 file/symbol/marker를 read-back한다. allowlist 파일이
   존재하지 않거나 marker가 현재 source에 없으면 실패한다. source marker가
   manifest와 불일치하면 `path`, line, symbol, classification을 출력한다.
4. `--json` 출력에는 기준 총 파일 수, direct marker 수, 각 분류 수,
   unallowlisted violation, stale entry, binding violation을 포함한다.
5. `node --test ...`와 validator를 실행해 GREEN 기대 결과를 기록한다.

**완료 기준:** baseline path 38개가 모두 분류되고, MIGRATE direct connection은
0건, ALLOWED/ADAPTER만 명시적 근거와 함께 남으며, 새 임의 `.kt` fixture를
추가하면 validator가 실패한다.

**커밋 경계:** `raw JDBC inventory allowlist 검증을 저장소 규칙으로 고정한다`.

### 작업 4 — appointment-api 경계 문서화와 회귀 검증

**변경 파일:**

- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ServiceConfig.kt`
  (필요한 readiness KDoc만)
- `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/DataSourceOwnershipContractTest.kt`
  (validator/allowlist 계약을 호출하는 최소 guard만)
- 38개 inventory 중 appointment-api 34개 파일은 호출 구현을 바꾸지 않고
  manifest 심볼·소유권·검증 명령 read-back 대상으로 삼는다.

**절차:**

1. `ServiceConfig` readiness KDoc에 Spring-managed DataSource가 owner이며
   migration version probe가 transaction migration이 아니라 startup
   readiness boundary임을 명시한다.
2. `DataSourceOwnershipContractTest`가 main runtime의 DataSource ownership와
   별도 inventory validator 실행 계약을 혼동하지 않도록 pattern constants와
   allowlist 경계를 명시한다. 이 테스트의 문자열을 direct JDBC evidence로
   세지 않는 negative assertion을 추가한다.
3. 다음 API wiring/contract 테스트를 실행한다.
   ```bash
   ./gradlew :appointment-api:test \
     --tests '*DataSourceOwnershipContractTest' \
     --tests '*ExposedDatabaseFactoryTest' \
     --tests '*AppointmentCommitmentApplicationWiringTest' \
     --tests '*ProfileReevaluationWiringTest' \
     --tests '*NotificationReminderRecoveryWiringTest' \
     --no-build-cache --no-daemon --console=plain
   ./gradlew :appointment-api:test \
     --tests '*AppointmentConsumerMigrationContractTest' \
     --tests '*BookingReliabilityQueryPlanTest' \
     --tests '*NotificationOutboxQueryPlanTest' \
     --no-build-cache --no-daemon --console=plain \
     -Dspring.profiles.active=test,test-postgresql
   node scripts/validate-appointment-raw-jdbc-inventory.mjs
   ```
4. migration/query-plan 테스트 실패 시 SQL semantics와 Testcontainers
   PostgreSQL contract를 기준으로 수정한다. H2만 통과하도록 dialect branch를
   약화시키지 않는다.

**완료 기준:** API compile/test와 inventory validator가 통과하고, readiness/
migration/query-plan 경계의 owner/close/parameter binding 근거가 manifest와
lesson에 일치한다.

**커밋 경계:** `appointment-api JDBC 경계와 resource ownership을 문서화한다`.

### 작업 5 — appointment-core와 appointment-messaging 경계 확인

**변경 파일:**

- `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/test/TestDB.kt`
  (필요한 singleton/lifecycle KDoc만)
- `appointment-messaging/src/test/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentMessagingAutoConfigurationTest.kt`
- `appointment-messaging/src/test/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentMessagingReadinessValidatorTest.kt`
  (필요한 boundary KDoc/assertion만)
- `appointment-messaging`의 두 inventory path 및 관련 existing outbox
  query-plan 테스트는 manifest read-back 대상으로 유지한다.

**절차:**

1. `TestDB`가 `bluetape4k.testcontainers.database.PostgreSQLServer` singleton을
   사용하고 `@Testcontainers`를 사용하지 않는다는 계약을 문서화한다.
2. messaging H2 wiring과 PostgreSQL readiness schema create/drop의 owner를
   명시하고, `DriverManager` schema identifier가 고정 내부 값임을 검증한다.
3. 다음 targeted 테스트를 순차 실행한다.
   ```bash
   ./gradlew :appointment-core:test \
     --tests '*PostgreSqlOnlyContractTest' \
     --tests '*TableSchemaTest' \
     --no-build-cache --no-daemon --console=plain
   ./gradlew :appointment-messaging:test \
     --tests '*AppointmentMessagingAutoConfigurationTest' \
     --tests '*AppointmentMessagingReadinessValidatorTest' \
     --no-build-cache --no-daemon --console=plain
   node scripts/validate-appointment-raw-jdbc-inventory.mjs
   ```
4. PostgreSQL singleton이 이미 실행 중이어야 하며, 정상 Colima를 재시작하지
   않는다. 실패하면 `colima status`, `docker context show`, `docker info`를
   확인하고 bind-mount/connection lifecycle 원인을 진단한다.

**완료 기준:** core/messaging targeted tests와 validator가 통과하고 container,
Hikari/DataSource close 로그 또는 테스트 assertion으로 자원 반납이 확인된다.

**커밋 경계:** `core와 messaging의 JDBC 경계 수명 검증을 고정한다`.

### 작업 6 — benchmark/Testcontainers 증거 수집

**변경 파일:**

- `benchmark/appointment-messaging-benchmark/src/main/kotlin/io/bluetape4k/clinic/appointment/benchmark/PostgreSqlBenchmarkFixture.kt`
  (소스 변경은 validator가 boundary drift를 발견하거나 close KDoc가 필요할
  때의 최소 변경으로 제한한다.)
- `docs/lessons/2026-08-17-issue-350-raw-jdbc-priority.md`

**절차:**

1. benchmark fixture가 schema creation, Hikari pool, Flyway, seed, contention
   cleanup을 driver-level boundary로 유지하는지 확인한다. `createSchema`의
   schema identifier가 `SCHEMA` 고정 상수이고, seed/cleanup 모든 값이 `?`
   binding인지 정적 검증한다.
2. fixture/measurement SQL을 수정하지 않았다면 다음 smoke만 실행한다.
   ```bash
   ./gradlew :appointment-messaging-benchmark:mainSmokeBenchmark \
     --no-daemon --console=plain
   ```
3. benchmark fixture 또는 측정 경로가 변경되었으면 smoke에 더해 full을 실행한다.
   ```bash
   ./gradlew :appointment-messaging-benchmark:mainBenchmark \
     --no-daemon --console=plain
   ```
4. 기존 collector와 validator를 실행해 report 경로를 lesson에 기록한다.
   ```bash
   node scripts/collect-appointment-messaging-benchmark.mjs \
     --input-dir benchmark/appointment-messaging-benchmark/build/reports/benchmarks \
     --output build/reports/appointment-messaging-postgresql/benchmark.json \
     --config smoke
   node scripts/validate-appointment-messaging-benchmark.mjs \
     --input build/reports/appointment-messaging-postgresql/benchmark.json
   ```
5. 결과는 task 성공, Testcontainers PostgreSQL schema/cleanup, 기존 validator
   통과로만 기술한다. production throughput/SLO로 일반화하지 않는다.

**완료 기준:** PostgreSQL Testcontainers smoke와 필요한 full/collector/
validator가 fresh output으로 통과하고 Hikari pool 및 contention executor가
`close()`에서 종료된다.

**커밋 경계:** `PostgreSQL benchmark 경계와 재현 가능한 증거를 기록한다`.

### 작업 7 — 문서·lesson·전체 정적 검증

**변경 파일:**

- `README.md`
- `docs/lessons/2026-08-17-issue-350-raw-jdbc-priority.md`
- 변경된 두 fixture의 KDoc

**절차:**

1. README의 DB/benchmark 안내에 Exposed transaction 우선 순서와 raw JDBC
   boundary allowlist 문서 링크를 추가한다. PostgreSQL이 production-schema
   simulation 기준이고 H2는 unit/wiring 보조라는 현재 정책을 유지한다.
2. lesson에 기준 38/334, 최종 MIGRATE/ADAPTER/ALLOWED 표, 실제 변경 파일,
   exact validator/Gradle commands, report path, resource lifecycle, rollback,
   known gap을 기록한다.
3. 전체 검증을 순차 실행한다.
   ```bash
   node --test scripts/validate-appointment-raw-jdbc-inventory.test.mjs
   node scripts/validate-appointment-raw-jdbc-inventory.mjs --json
   ./gradlew :appointment-api:build --no-build-cache --no-daemon --console=plain
   ./gradlew :appointment-core:test :appointment-messaging:test \
     --no-build-cache --no-daemon --console=plain
   git diff --check
   ```
4. `$bluetape-kotlin-patterns` testing/checklist 항목 KT-01..KT-05 및
   KT-FIN-01..KT-FIN-11을 changed file와 fresh output에 매핑한다.
5. receipt input JSON 등 작업 중 생성한 untracked 임시 파일은 `apply_patch`
   삭제로 정리하고, `.bluetape` workflow state와 승인된 설계/계획/review/
   lesson만 남긴다.

**완료 기준:** source set 전체에서 unallowlisted direct JDBC와 binding
violation이 0건이고 build/test/diff check와 문서 read-back이 통과한다.

**커밋 경계:** `Issue #350 정책과 검증 증거를 한국어 문서로 고정한다`.

### 작업 8 — PR, CI, rebase merge, local sync, cleanup

**절차:**

1. 각 커밋이 Lore protocol을 따르는지 확인한다. 첫 줄은 결정 이유를
   한국어로 쓰고 `Constraint`, `Rejected`, `Confidence`, `Scope-risk`,
   `Directive`, `Tested`, `Not-tested` trailers를 포함한다.
2. branch를 `origin`에 push하고 한국어 PR을 만든다. PR body에는 Issue #350,
   Issue #309 scope 분리, 38/334 inventory, 변경/허용 분류, exact tests,
   PostgreSQL Testcontainers/benchmark evidence, known gaps, `## DoD Status`를
   포함한다. `Closes #350`을 보존한다.
3. CI가 완료될 때까지 merge하지 않는다. PR head SHA, checks, review threads,
   mergeability, body/assignee/labels/milestone을 fresh read-back한다.
4. 사용자의 별도 최신 merge approval을 받은 뒤 `gh pr merge --rebase`로
   rebase merge한다. auto-merge는 사용하지 않는다.
5. remote `develop`을 fetch하고 local `develop`을 fast-forward sync한다.
   feature worktree는 merged 상태와 uncommitted diff가 없음을 확인한 뒤
   정리하되, remote feature branch는 다른 기기 작업을 위해 보존한다.

**완료 기준:** PR merged, CI green, local `develop == origin/develop`, root와
feature worktree의 임시 산출물이 정리되고 remote branch 보존 여부가 명시된다.

## 검증 매트릭스

| 설계 수용 기준 | 계획 작업 | Fresh evidence |
|---|---|---|
| 전체 marker 분류와 근거 | 1, 3, 7 | `raw-jdbc-allowlist.json`, validator JSON, lesson 표 |
| Exposed DSL/`exec` 우선 | 2 | compile, targeted tests, unallowlisted=0 |
| transaction 우회 raw connection 금지 | 2, 3 | scanner의 transaction violation=0 |
| boundary/adapter 문서화 | 3, 4, 5, 6, 7 | file/symbol/owner/reason/command read-back |
| Issue #309 분리 | 2, 4, 8 | diff path/API review, PR body |
| PostgreSQL/H2/Testcontainers 계약 | 4, 5, 6 | PostgreSQL targeted tests, H2 wiring tests, singleton lifecycle |
| benchmark evidence | 6 | smoke/full task, collector, validator report |
| 문서·KDoc·diff check | 7 | README/lesson/KDoc read-back, `git diff --check` |
| merge/sync/cleanup DoD | 8 | PR head/checks, rebase merge, local/remote SHA parity |

## 계획 자체의 중단 조건

- `exec` 전환으로 기존 transaction ownership, public API, repository signature,
  migration order 또는 PostgreSQL locking/query-plan semantics가 바뀌면 해당
  작업을 중단하고 원래 호출을 보존한 채 Issue #309 또는 별도 follow-up으로
  분리한다.
- validator가 migration/readiness/Gatling/benchmark boundary를 구분하지 못하면
  source를 더 바꾸지 말고 manifest/scanner 계약을 먼저 수정한다.
- PostgreSQL Testcontainers 증거가 실행 불가능하면 H2 결과를 대체 증거로
  주장하지 않고, Docker/Colima 원인을 진단한 뒤 남은 검증 gap을 lesson과 PR에
  명시한다.
- 사용자 merge approval 없이 merge, branch 삭제, remote feature branch
  삭제를 수행하지 않는다.

## 계획 완료 상태

- [x] 승인된 설계와 six-lens spec review를 작업 순서·파일·명령으로 분해했다.
- [x] 38개 inventory path를 분류하고 MIGRATE 대상 두 호출의 정확한 전환
      경계를 정했다.
- [x] RED→GREEN 테스트 순서, 커밋 경계, PostgreSQL evidence, PR/merge/sync
      절차를 고정했다.
- [ ] 계획 review artifact 작성과 사용자 계획 승인
- [ ] 구현·검증·PR·merge·local sync
