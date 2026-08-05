# Issue #41 transactional outbox 메시징 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use `test-driven-development` and execute each task with a red-green-refactor checkpoint. 모든 단계는 이 계획의 순서와 파일 범위를 따른다.

**목표:** 예약 aggregate mutation과 Kafka4 appointment domain event intent를 하나의 DB transaction으로 원자화하고, bounded lease-fenced relay로 at-least-once 발행한다.

**아키텍처:** 기존 `scheduling_outbox_events`를 재사용한다. `appointment-messaging`은 envelope/allow-list/partition key, writer, DB lease store, Kafka4 relay, readiness를 소유하고 `appointment-api`는 mutation transaction 안에서 writer를 호출한다. Kafka I/O는 DB transaction 밖에서만 실행하며, stale owner는 terminal write를 할 수 없다.

**기술 스택:** Kotlin 2.3, Spring Boot 4, Exposed ORM, Flyway H2/PostgreSQL/MySQL, Kafka 4 client, Spring Kafka 4, Jackson 3, JUnit 5/Kotest assertions, MockK, Micrometer.

---

## 작업 전역 규칙

- 모든 Exposed 호출은 caller 소유 `transaction {}` 안에서 실행한다.
- 구현 코드를 쓰기 전에 해당 동작의 failing test를 작성하고, 기대한 이유로 실패하는 것을 확인한다.
- `@Testcontainers`를 추가하지 않는다. relay는 fake publisher와 H2로 검증하고 dialect 검증은 기존 singleton launcher/migration harness를 재사용한다.
- 기존 `appointment-event`의 plan/policy row를 새 relay가 임의로 claim하지 않는다. 새 appointment writer가 채운 `topic`과 `partition_key`가 있는 allow-list row만 relay 대상이다.
- 내부 문서와 KDoc은 한국어로 작성하고, public README/PR/issue metadata는 영어로 유지한다.
- 커밋은 Lore protocol을 따른다. 각 논리 단위의 커밋은 다음 trailer를 포함한다.

  ```text
  <why intent line>

  Constraint: transactional outbox and Kafka4-only decision
  Rejected: broker call inside DB transaction | it breaks commit atomicity
  Confidence: high
  Scope-risk: moderate
  Directive: preserve owner/token fencing on every terminal update
  Tested: <exact command and result>
  Not-tested: <explicit remaining gap>
  ```

## 파일 변경 지도

### 생성

- `appointment-messaging/build.gradle.kts` — 새 모듈의 Exposed/Jackson3/Kafka4/Spring Kafka 의존성과 test 설정.
- `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentMessagingContracts.kt` — 이벤트 type, payload, envelope, bounded metadata contract.
- `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentEventEnvelopeCodec.kt` — strict Jackson3 envelope serialization/validation.
- `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentPartitionKeyFactory.kt` — tenant/clinic/aggregate partition key 생성.
- `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentOutboxWriter.kt` — caller transaction에서 outbox row를 insert하는 port와 구현.
- `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentOutboxStore.kt` — claim/retry/published/failed의 Exposed CAS 저장소.
- `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentOutboxRelay.kt` — bounded claim → Kafka send → fenced terminal transition orchestration.
- `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentMessagingProperties.kt` — topic/lease/retry/claim/size 설정 및 fail-closed 검증.
- `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentMessagingAutoConfiguration.kt` — Spring bean wiring, relay scheduler, readiness/metrics.
- `appointment-messaging/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` — Boot 4 auto-configuration 등록.
- `appointment-messaging/src/test/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentEventEnvelopeCodecTest.kt` — envelope allow-list, unknown type/version, cap 검증.
- `appointment-messaging/src/test/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentPartitionKeyFactoryTest.kt` — tenant/clinic/aggregate key 안정성 검증.
- `appointment-messaging/src/test/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentOutboxWriterTest.kt` — same transaction insert/rollback 및 네 이벤트 row 검증.
- `appointment-messaging/src/test/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentOutboxStoreTest.kt` — claim 경쟁, lease 만료, owner/token CAS 검증.
- `appointment-messaging/src/test/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentOutboxRelayTest.kt` — fake Kafka publisher의 success/retry/ack-after-DB-failure 시나리오.
- `appointment-messaging/src/test/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentMessagingAutoConfigurationTest.kt` — properties/readiness/scheduler wiring 검증.
- `appointment-messaging/src/test/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentMessagingKafkaServerLauncher.kt` — `bluetape4k-testcontainers` singleton KRaft broker fixture와 순차 통합 테스트 경계.
- `appointment-messaging/src/test/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentOutboxPerformanceTestSupport.kt` — 고정 seed backlog, 2-relay contention, outage/recovery, raw-payload-free benchmark report.
- `appointment-messaging/src/test/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentOutboxQueryPlanTest.kt` — H2/PostgreSQL/MySQL claim SQL EXPLAIN·index·lock-wait 계약.
- `appointment-messaging/src/test/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentMessagingKafkaIntegrationTest.kt` — singleton Kafka4/Embedded KRaft ACK, partition, ACL/readiness self-check.
- `appointment-messaging/src/test/resources/junit-platform.properties`, `appointment-messaging/src/test/resources/logback-test.xml` — 새 module의 표준 테스트 runtime resource.
- `appointment-messaging/README.md`, `appointment-messaging/README.ko.md` — 모듈 설치/트랜잭션 경계/relay 운영 문서의 English/Korean parity.
- `docs/superpowers/reviews/2026-08-05-issue-41-transactional-outbox-messaging-spec-review.ko.md` — Step 2-R six-lens finding/convergence ledger.
- `docs/superpowers/reviews/2026-08-05-issue-41-transactional-outbox-messaging-plan-review.ko.md` — Step 3-R plan lens findings, required edits, and closure verdict.
- `docs/superpowers/risk/2026-08-05-issue-41-transactional-outbox-risk-register.ko.md` — performance, security, migration, shutdown rollback points.
- `docs/runbooks/appointment-messaging-operations.md` — English operator runbook for hold, pause, redrive, rollback, and escalation.
- `docs/alerts/appointment-messaging-rules.yml` — concrete Prometheus alert/clear expressions mapped to the runbook.
- `appointment-api/src/main/resources/db/migration/h2/V22__add_appointment_messaging_outbox_lease.sql` — H2 additive schema.
- `appointment-api/src/main/resources/db/migration/postgresql/V22__add_appointment_messaging_outbox_lease.sql` — PostgreSQL additive schema.
- `appointment-api/src/main/resources/db/migration/mysql/V22__add_appointment_messaging_outbox_lease.sql` — MySQL additive schema.

### 수정

- `gradle/libs.versions.toml` — BOM가 관리하는 `bluetape4k-kafka4`, `kafka-clients`, `spring-kafka`, `spring-kafka-test`, `jackson3-databind`, `micrometer-core` aliases.
- `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/SchedulingOutboxEvents.kt` — `occurred_at`, `topic`, `partition_key`, lease/failure columns 및 Exposed indexes.
- `appointment-api/build.gradle.kts` — `implementation(project(":appointment-messaging"))` 추가.
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/service/AppointmentService.kt` — create/status/cancel transaction 안 writer 호출과 bounded correlation ID 전달.
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/AppointmentController.kt` — `X-Correlation-Id` filter 값의 service 전달.
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/RescheduleController.kt` — reschedule HTTP correlation/causation context 전달.
- `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/NotificationOutboxContracts.kt` — bounded correlation/event ID value type 재사용 또는 공통 wrapper 확인.
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ServiceConfig.kt` — reschedule lambda에서 notification writer와 messaging writer를 같은 transaction에 조립.
- `settings.gradle.kts` — `includeModules()` 자동 등록을 검증하고 `appointment-messaging` discovery 회귀를 고정한다.
- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/AppointmentCommandContext.kt` — core가 event/messaging 모듈에 의존하지 않고 전달할 server-produced correlation/causation context.
- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/ClosureRescheduleService.kt` — 실제 경로에서 command context를 기존 caller transaction port에 전달.
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/RescheduleController.kt` — confirm/auto endpoint의 correlation/causation 전달.
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/service/AppointmentService.kt`, `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/AppointmentController.kt`, `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/service/AppointmentEventLogger.kt` — blocking Exposed 경계와 로그/이력의 raw ID/reason redaction.
- `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/service/AppointmentNotificationAtomicityTest.kt` — fake outbox writer와 rollback/commit assertions.
- `appointment-messaging/src/test/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentOutboxWriterScopeTest.kt` — H2 및 available dialect harness의 forged tenant/clinic/replacement scope negative matrix.
- `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/AppointmentControllerTest.kt` — correlation header가 writer contract에 도달하는지 검증.
- `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/RescheduleControllerTest.kt` — reschedule correlation/causation 연속성 검증.
- `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/FlywayMigrationTest.kt` — V22 column/index/readiness contract 포함.
- `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/FlywayPostgreSQLMigrationTest.kt`, `FlywayMySQLMigrationTest.kt` — 세 dialect additive migration 검증.
- `.github/workflows/ci.yml`, `.github/workflows/nightly.yml`, `README.md`, `README.ko.md` — module path filter/job/Kover/nightly와 root catalog parity.

---

## Task 1: Gradle catalog와 모듈 skeleton을 먼저 고정

**Files:**

- Modify: `gradle/libs.versions.toml`
- Verify: `settings.gradle.kts` — `includeModules()` discovers the new directory; add a `./gradlew projects --quiet` assertion so manual include drift is detected.
- Create: `appointment-messaging/build.gradle.kts`
- Create: `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/ModuleMarker.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/AppointmentCommandContext.kt`
- Create: `appointment-messaging/src/test/resources/junit-platform.properties`
- Create: `appointment-messaging/src/test/resources/logback-test.xml`
- Create: `appointment-messaging/src/test/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentMessagingKafkaServerLauncher.kt`
- Test: `./gradlew projects`

- [ ] **Step 1: catalog aliases를 추가하는 failing configuration check 작성**

  `appointment-messaging/build.gradle.kts`가 참조할 alias를 먼저 선언하고 아직 module directory가 없을 때 `./gradlew projects`가 실패하는 상태를 기록한다. aliases에는 version을 넣지 않고 기존 BOM resolution을 사용한다.

  ```toml
  # gradle/libs.versions.toml
  bluetape4k-kafka4 = { module = "io.github.bluetape4k:bluetape4k-kafka4" }
  kafka4-clients = { module = "org.apache.kafka:kafka-clients" }
  spring-kafka4 = { module = "org.springframework.kafka:spring-kafka" }
  spring-kafka4-test = { module = "org.springframework.kafka:spring-kafka-test" }
  jackson3-databind = { module = "tools.jackson.core:jackson-databind" }
  micrometer-core = { module = "io.micrometer:micrometer-core" }
  ```

  Run: `./gradlew projects`

  Expected: 새 module이 아직 없어 `:appointment-messaging` project를 찾지 못하거나 build script가 unresolved 상태로 실패한다.

- [ ] **Step 2: module build script와 marker를 추가해 project discovery를 green으로 만든다**

  ```kotlin
  // appointment-messaging/build.gradle.kts
  plugins {
      alias(libs.plugins.exposed)
      kotlin("plugin.spring")
  }

  dependencies {
      api(project(":appointment-event"))
      implementation(libs.bluetape4k.kafka4)
      implementation(libs.kafka4.clients)
      implementation(libs.spring.kafka4)
      implementation(libs.jackson3.module.kotlin)
      implementation(libs.jackson3.databind)
      implementation(libs.micrometer.core)
      compileOnly("org.springframework.boot:spring-boot-autoconfigure")
      compileOnly("org.springframework:spring-context")
      testImplementation("org.springframework.boot:spring-boot-starter-test")
      testImplementation(libs.bluetape4k.junit5)
      testImplementation(libs.h2.v2)
  testImplementation(libs.spring.kafka4.test)
  testImplementation(libs.bluetape4k.testcontainers)
  }
  ```

  ```kotlin
  // ModuleMarker.kt
  package io.bluetape4k.clinic.appointment.messaging

  /** appointment-messaging module이 Gradle/Spring classpath에 등록됐음을 나타내는 marker다. */
  internal object ModuleMarker
  ```

  `AppointmentCommandContext`는 `appointment-core`가 소유하는 dependency-neutral 타입이다. HTTP/filter와 내부 workflow만 이 타입을 만들 수 있도록 server-produced correlation/causation 값의 bounded grammar를 검증하고, caller가 보낸 causation header/body는 무시하거나 거부한다. `appointment-messaging`은 이 타입을 `AppointmentMessagingContext`로 명시적으로 변환한다. 따라서 `appointment-core -> appointment-event -> appointment-messaging` 순환 의존성을 만들지 않는다.

  Run: `./gradlew projects :appointment-messaging:compileKotlin`

  Expected: `settings.gradle.kts`의 `includeModules()`가 `:appointment-messaging`를 표시하고 marker compilation이 성공한다. Kafka 통합 테스트는 `AppointmentMessagingKafkaServerLauncher`의 singleton/serialized resource lock 아래에서만 실행한다.

- [ ] **Step 3: build graph를 기록하고 Lore commit을 만든다**

  Run: `./gradlew projects --quiet :appointment-messaging:dependencies --configuration testRuntimeClasspath` and `git diff --check`

  Expected: catalog aliases resolve to the governed Kafka4/Spring Kafka4/Jackson3/Micrometer artifacts and test runtime resources are discovered.

  Commit intent: `Keep Kafka4 messaging dependency and module discovery explicit`

## Task 2: V22 schema와 Exposed table metadata를 red 단계부터 고정

**Files:**

- Modify: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/SchedulingOutboxEvents.kt`
- Create: `appointment-api/src/main/resources/db/migration/h2/V22__add_appointment_messaging_outbox_lease.sql`
- Create: `appointment-api/src/main/resources/db/migration/postgresql/V22__add_appointment_messaging_outbox_lease.sql`
- Create: `appointment-api/src/main/resources/db/migration/mysql/V22__add_appointment_messaging_outbox_lease.sql`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/AppointmentMessagingMigrationTestSupport.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/FlywayMigrationTest.kt`

- [ ] **Step 1: V22 column/index를 읽는 failing migration test를 추가한다**

  `TenantQueryIsolationMigrationTestSupport` 패턴을 따라 `AppointmentMessagingMigrationTestSupport.verifyV22Migration`을 먼저 추가한다. 이 helper는 Flyway `target("21")`로 V21 schema를 만들고, prepared statement로 legacy PENDING row와 appointment fixture를 넣은 뒤 target V22를 실행한다. `DatabaseMetaData`로 column/index 순서와 nullable 보존을 검사하며 현재 harness에 없는 임의 helper를 호출하지 않는다.

  ```kotlin
  private val appointmentMessagingColumns = setOf(
      "occurred_at", "topic", "partition_key",
      "lease_owner", "lease_token", "lease_until",
      "last_failure_code", "last_failure_at",
  )

  @Test
  fun `V22 appointment messaging outbox lease contract exists`() {
      AppointmentMessagingMigrationTestSupport.verifyV22Migration(
          dataSource = h2DataSource("appointment-messaging-v22"),
          location = "classpath:db/migration/h2",
      )
  }
  ```

  Run: `./gradlew :appointment-api:test --tests '*FlywayMigrationTest*V22*'`

  Expected: FAIL because V22 files/columns do not exist.

- [ ] **Step 2: 세 dialect에 additive SQL을 작성한다**

  H2/PostgreSQL은 `TIMESTAMP`, MySQL은 `DATETIME(6)`을 사용한다. 모든 dialect에서 기존 row를 backfill하지 않고 nullable로 추가하며, 새 row의 bounded length와 discriminator-leading ready/lease indexes를 만든다. topic/partition key의 allow-list 길이와 index column 순서는 Exposed metadata 및 `DatabaseMetaData` assertion과 일치해야 한다. PostgreSQL은 appointment ready predicate와 동일한 partial index를 사용하고, H2/MySQL은 partial index가 없으므로 같은 discriminator와 due/lease 순서를 가진 full composite index를 사용한다. index contract는 실제 claim SQL의 `status=PENDING`, `aggregate_type=APPOINTMENT`, 네 event allow-list, `next_attempt_at <= databaseNow`, lease 만료 predicate를 반복한다.

  ```sql
  ALTER TABLE scheduling_outbox_events ADD COLUMN occurred_at TIMESTAMP NULL;
  ALTER TABLE scheduling_outbox_events ADD COLUMN topic VARCHAR(249) NULL;
  ALTER TABLE scheduling_outbox_events ADD COLUMN partition_key VARCHAR(512) NULL;
  ALTER TABLE scheduling_outbox_events ADD COLUMN lease_owner VARCHAR(160) NULL;
  ALTER TABLE scheduling_outbox_events ADD COLUMN lease_token VARCHAR(128) NULL;
  ALTER TABLE scheduling_outbox_events ADD COLUMN lease_until TIMESTAMP NULL;
  ALTER TABLE scheduling_outbox_events ADD COLUMN last_failure_code VARCHAR(64) NULL;
  ALTER TABLE scheduling_outbox_events ADD COLUMN last_failure_at TIMESTAMP NULL;
  -- PostgreSQL only: WHERE predicate is part of the contract.
  CREATE INDEX idx_outbox_appointment_ready
      ON scheduling_outbox_events(next_attempt_at, lease_until, created_at, id)
      WHERE status = 'PENDING'
        AND aggregate_type = 'APPOINTMENT'
        AND event_type IN ('AppointmentCreated', 'AppointmentStatusChanged', 'AppointmentCancelled', 'AppointmentRescheduled');
  CREATE INDEX idx_outbox_appointment_lease_recovery
      ON scheduling_outbox_events(status, aggregate_type, event_type, lease_until, id);

  -- H2/MySQL use the equivalent full composite form (same column order after the discriminator).
  ```

  MySQL의 `TIMESTAMP`는 migration dialect 규칙에 맞춰 `DATETIME(6)`으로 치환한다.

- [ ] **Step 3: Exposed columns를 추가하고 migration test를 green으로 만든다**

  ```kotlin
  val occurredAt = timestamp("occurred_at").nullable()
  val topic = varchar("topic", 249).nullable()
  val partitionKey = varchar("partition_key", 512).nullable()
  val leaseOwner = varchar("lease_owner", 160).nullable()
  val leaseToken = varchar("lease_token", 128).nullable()
  val leaseUntil = timestamp("lease_until").nullable()
  val lastFailureCode = varchar("last_failure_code", 64).nullable()
  val lastFailureAt = timestamp("last_failure_at").nullable()
  ```

  Run: `./gradlew :appointment-api:test --tests '*FlywayMigrationTest*V22*'`

  Expected: PASS and existing appointment-event tests remain green. Update `SchedulingOutboxEvents` KDoc to distinguish legacy command-driven rows (nullable `causation_event_id`) from new Issue #41 appointment rows, whose root envelope causation follows the approved Issue #40 rule and whose non-root rows carry the upstream event ID.

- [ ] **Step 4: migration dialect tests와 Lore commit**

  Run: `./gradlew :appointment-api:test --tests '*AppointmentMessagingMigrationTestSupport*' --tests '*FlywayPostgreSQLMigrationTest*' --tests '*FlywayMySQLMigrationTest*'`

  `AppointmentOutboxQueryPlanTest` must run the exact ready/lease claim SQL on every available dialect, assert the chosen V22 index (no full scan), capture lock-wait p95 under two-relay contention, and verify the conditional UPDATE repeats `next_attempt_at <= databaseNow` and the candidate row/version predicate. The migration fixture also exercises V21 writer/read compatibility, V22 new writer, partial-DDL failure, and rollback-to-old-writer while retaining nullable legacy columns.

  Commit intent: `Make the existing scheduling outbox leaseable without breaking legacy rows`

## Task 3: envelope, payload allow-list, and partition key를 TDD로 구현

**Files:**

- Create: `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentMessagingContracts.kt`
- Create: `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentEventEnvelopeCodec.kt`
- Create: `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentPartitionKeyFactory.kt`
- Test: `appointment-messaging/src/test/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentEventEnvelopeCodecTest.kt`
- Test: `appointment-messaging/src/test/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentPartitionKeyFactoryTest.kt`

- [ ] **Step 1: envelope contract의 red tests를 작성한다**

  ```kotlin
  @Test
  fun `status changed envelope retains bounded identity and payload`() {
      val envelope = codec.encode(
          AppointmentEventEnvelope(
              eventId = AppointmentEventId(UUID.fromString("00000000-0000-0000-0000-000000000041")),
              eventType = AppointmentEventType.STATUS_CHANGED,
              schemaVersion = 1,
              occurredAt = Instant.parse("2026-08-05T08:30:00Z"),
              tenantGroupId = 7,
              clinicId = 31,
              aggregateType = AppointmentAggregateType.APPOINTMENT,
              aggregateId = AppointmentAggregateId(924),
              correlationId = CorrelationId("request-41"),
              causation = CausationRef.Root(CorrelationId("request-41")),
              payload = AppointmentStatusChangedPayload(924, 3, "REQUESTED", "CONFIRMED", null),
          ),
      )
      envelope shouldContain "\"eventType\":\"AppointmentStatusChanged\""
      envelope shouldNotContain "patientName"
  }

  @Test
  fun `unknown event type and schema fail closed`() {
      shouldThrow<AppointmentMessagingContractException> { codec.decode("{\"eventType\":\"Unknown\"}") }
  }
  ```

  Run: `./gradlew :appointment-messaging:test --tests '*AppointmentEventEnvelopeCodecTest*'`

  Expected: FAIL because contracts/codec are not implemented.

- [ ] **Step 2: immutable contract types and strict codec를 최소 구현한다**

  ```kotlin
  @JvmInline value class AppointmentEventId(val value: UUID)
  @JvmInline value class CorrelationId(val value: String)
  @JvmInline value class AppointmentAggregateId(val value: Long)

  sealed interface CausationRef {
      data class Root(val correlationId: CorrelationId) : CausationRef
      data class UpstreamEvent(val eventId: AppointmentEventId) : CausationRef
  }

  enum class AppointmentAggregateType { APPOINTMENT }
  sealed interface AppointmentPayload

  enum class AppointmentEventType(val wireName: String) {
      CREATED("AppointmentCreated"),
      STATUS_CHANGED("AppointmentStatusChanged"),
      CANCELLED("AppointmentCancelled"),
      RESCHEDULED("AppointmentRescheduled"),
  }

  data class AppointmentEventEnvelope<T : AppointmentPayload>(
      val eventId: AppointmentEventId,
      val eventType: AppointmentEventType,
      val schemaVersion: Int,
      val occurredAt: Instant,
      val tenantGroupId: Long,
      val clinicId: Long,
      val aggregateType: AppointmentAggregateType,
      val aggregateId: AppointmentAggregateId,
      val correlationId: CorrelationId,
      val causation: CausationRef,
      val payload: T,
  )
  ```

  `AppointmentPayload`는 event type별 sealed DTO이고 constructor가 event type↔payload 조합, positive IDs/version, scope/record consistency, closed status/reason code를 검증한다. Jackson 3 codec는 `eventType`/`schemaVersion` allow-list를 먼저 확인하고, identifier/JSON size/nesting/collection cap을 검증한다. 자유 입력 reason은 payload constructor에서 받지 않고 등록된 code만 받는다. codec은 deterministic field ordering으로 canonical JSON을 생성한다. production relay API는 caller clock이나 raw `String` event ID를 받지 않는다.

- [ ] **Step 3: partition key red-green을 완료한다**

  ```kotlin
  fun appointmentPartitionKey(tenantGroupId: Long, clinicId: Long, aggregateId: Long): String {
      require(tenantGroupId > 0 && clinicId > 0 && aggregateId > 0)
      return "tenant-$tenantGroupId:CLINIC:clinic-$clinicId:APPOINTMENT:apt-$aggregateId"
  }
  ```

  Test: 같은 입력은 같은 key를 반환하고 tenant/clinic/aggregate 중 하나라도 0 이하면 `IllegalArgumentException`을 반환하는지 검증한다.

  Run: `./gradlew :appointment-messaging:test --tests '*AppointmentPartitionKeyFactoryTest*' --tests '*AppointmentEventEnvelopeCodecTest*'`

- [ ] **Step 4: Lore commit**

  Commit intent: `Freeze the appointment event wire contract before persistence wiring`

## Task 4: transactional writer를 네 mutation contract에 연결

**Files:**

- Create: `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentOutboxWriter.kt`
- Test: `appointment-messaging/src/test/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentOutboxWriterTest.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/AppointmentCommandContext.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/service/AppointmentService.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/AppointmentController.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/RescheduleController.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ServiceConfig.kt`
- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/ClosureRescheduleService.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/service/AppointmentEventLogger.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/service/AppointmentNotificationAtomicityTest.kt`
- Create: `appointment-messaging/src/test/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentOutboxWriterScopeTest.kt`
- Modify: `appointment-api/build.gradle.kts`

- [ ] **Step 1: writer insert/rollback의 red tests를 작성한다**

  ```kotlin
  @Test
  fun `appointment insert and outbox intent commit together`() {
      transaction {
          val saved = appointmentRepository.save(record)
          writer.created(scope, saved, AppointmentMessagingContext.root(CorrelationId("request-41")))
      }
      transaction {
          SchedulingOutboxEvents.selectAll().single()[SchedulingOutboxEvents.eventType] shouldBe
              "AppointmentCreated"
      }
  }

  @Test
  fun `writer failure rolls back caller transaction`() {
      shouldThrow<AppointmentMessagingContractException> {
          transaction {
              appointmentRepository.save(record)
              writer.statusChanged(
                  scope,
                  record,
                  AppointmentState.REQUESTED,
                  AppointmentState.CONFIRMED,
                  AppointmentReasonCode.ADMIN_CANCELLED,
                  AppointmentMessagingContext.root(CorrelationId("request-41")),
              )
          }
      }
      transaction { appointmentRepository.findByIdOrNull(record.id) shouldBe null }
  }
  ```

  Run: `./gradlew :appointment-messaging:test --tests '*AppointmentOutboxWriterTest*'`

  Expected: FAIL because writer and appointment event row insertion are absent.

- [ ] **Step 2: writer API와 payload builders를 구현한다**

  ```kotlin
  interface AppointmentOutboxWriter {
      fun created(scope: TenantClinicScope, record: AppointmentRecord, context: AppointmentMessagingContext): AppointmentEventId
      fun statusChanged(
          scope: TenantClinicScope,
          record: AppointmentRecord,
          from: AppointmentState,
          to: AppointmentState,
          reason: AppointmentReasonCode?,
          context: AppointmentMessagingContext,
      ): AppointmentEventId
      fun cancelled(
          scope: TenantClinicScope,
          record: AppointmentRecord,
          reason: AppointmentReasonCode?,
          context: AppointmentMessagingContext,
      ): AppointmentEventId
      fun rescheduled(
          scope: TenantClinicScope,
          original: AppointmentRecord,
          replacement: AppointmentRecord,
          context: AppointmentMessagingContext,
      ): AppointmentEventId
  }
  ```

  `AppointmentMessagingContext`는 non-empty bounded `CorrelationId`와 root/non-root `CausationRef`를 함께 가진다. 구현은 caller transaction을 열거나 commit하지 않는다. production에서는 DB clock abstraction과 UUID generator를 주입하고, canonical payload/partition key/topic/occurredAt를 같은 insert에 기록한다. writer는 record/scope ID·version/status, `record.clinicId == scope.clinicId`, clinic의 tenant 소속, reschedule original/replacement scope·lineage를 같은 transaction에서 검증한다. DB unique `event_id` conflict는 같은 transaction에서 실패시켜 aggregate와 함께 rollback한다.

- [ ] **Step 3: AppointmentService의 create/status/cancel을 transaction 안 writer 호출로 바꾼다**

  `create`, `updateStatus`, `cancel`에 core-owned `AppointmentCommandContext`를 추가하고, 기존 JVM descriptor는 명시적 overload가 같은 transaction 구현으로 delegation하도록 보존한다. Kotlin default UUID fallback은 사용하지 않는다. `AppointmentController`는 validated `X-Correlation-Id`에서 root context를 명시적으로 만들고 caller-supplied causation을 사용하지 않는다. `AppointmentService`의 suspend API는 Exposed 전체 transaction을 `withContext(Dispatchers.IO)` 경계 안에서 실행하거나 MVC blocking boundary를 명시하고, cancellation 시 transaction rollback과 writer terminal update 부재를 검증한다. 각 transaction 내부에서 notification writer 다음에 messaging writer를 호출하고, transaction 반환 후의 `ApplicationEventPublisher` 호출은 local compatibility 용도로만 남긴다. listener 예외는 이미 commit된 2xx 결과를 5xx로 바꾸지 않도록 격리한다.

- [ ] **Step 4: reschedule port lambda에서 두 outbox를 함께 기록한다**

  `appointment-core`의 `AppointmentRescheduleNotificationWriter`는 event/messaging 타입을 직접 참조하지 않고 `AppointmentCommandContext`를 전달한다. `ServiceConfig`의 lambda에서 이를 `AppointmentMessagingContext`로 명시적으로 변환한 뒤 `AppointmentOutboxWriter`를 주입한다.

  ```kotlin
  AppointmentRescheduleNotificationWriter { tenantGroupId, original, replacement, version, commandContext ->
      appointmentNotificationWriter.rescheduled(tenantGroupId, original, replacement, version)
      appointmentOutboxWriter.rescheduled(
          scope = TenantClinicScope(tenantGroupId, original.clinicId),
          original = original,
          replacement = replacement,
          context = commandContext.toMessagingContext(),
      )
  }
  ```

  `appointment-api/.../RescheduleController.kt`의 confirm/auto endpoint부터 `appointment-core/.../service/ClosureRescheduleService.kt` port까지 검증된 correlation/causation context를 전달한다. 실제 HTTP correlation이 없으면 식별자를 포함하지 않는 bounded workflow ID를 내부 job이 명시적으로 만든다. `ClosureRescheduleService`의 transaction 경계를 열거나 분리하지 않는다. `AppointmentOutboxWriterScopeTest`는 forged tenant/clinic scope, original/replacement mismatch, zero side effects를 H2와 사용 가능한 PostgreSQL/MySQL harness에서 검증하고, 실패 시 aggregate·notification·appointment outbox를 모두 rollback한다.

- [ ] **Step 5: API atomicity/controller tests를 green으로 만든다**

  Run: `./gradlew :appointment-messaging:test :appointment-api:test --tests '*AppointmentNotificationAtomicityTest*' --tests '*AppointmentControllerTest*'`

  Expected: mutation 성공 시 aggregate와 appointment outbox row가 함께 존재하고, writer 예외/aggregate 예외/scope mismatch/schema-config fail-fast 시 둘 다 rollback되며, idempotency replay에는 새 `AppointmentCreated` row가 추가되지 않는다. 2xx는 durable outbox commit만 의미하고 Kafka ack를 의미하지 않으며, broker outage 중에는 PENDING row를 보존한다. pre-commit 실패는 stable error code와 `Retry-After` 정책으로 반환하고 local listener failure는 2xx를 변경하지 않는다. writer와 controller/service log, `AppointmentEventLogger`, state-history/audit payload에는 raw reason·tenant/clinic/appointment ID를 남기지 않는 redaction/typed-code assertion을 추가한다.

  `AdminAppointmentController`/`CustomerAppointmentController` commitment-v2와 closure의 중간 `PENDING_RESCHEDULE` transition은 이 issue의 partial stream 범위 밖이며, 해당 경로에는 writer를 연결하지 않고 exclusion regression test와 README 경고를 추가한다.

- [ ] **Step 6: Lore commit**

  Commit intent: `Make appointment mutations durable before any broker delivery`

## Task 5: lease-fenced Exposed store를 구현

**Files:**

- Create: `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentOutboxStore.kt`
- Test: `appointment-messaging/src/test/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentOutboxStoreTest.kt`

- [ ] **Step 1: claim/terminal CAS red tests를 작성한다**

  ```kotlin
  @Test
  fun `two relays atomically claim disjoint batch rows`() {
      val first = store.claimBatch(owner = WorkerOwner("worker-a"), limit = 32)
      val second = store.claimBatch(owner = WorkerOwner("worker-b"), limit = 32)
      (first.map { it.id }.toSet() intersect second.map { it.id }.toSet()) shouldBe emptySet()
  }

  @Test
  fun `stale owner cannot mark published after database lease expiry`() {
      val claim = store.claimBatch(owner = WorkerOwner("worker-a"), limit = 1).single()
      advanceDatabaseClockBeyond(claim.leaseUntil)
      store.markPublished(claim) shouldBe false
  }
  ```

  Run: `./gradlew :appointment-messaging:test --tests '*AppointmentOutboxStoreTest*'`

  Expected: FAIL because claim and fencing operations are not implemented.

- [ ] **Step 2: record/claim types와 DB-clock contract를 구현한다**

  ```kotlin
  data class AppointmentOutboxClaim(
      val id: Long,
      val eventId: AppointmentEventId,
      val owner: WorkerOwner,
      val token: LeaseToken,
      val leaseUntil: Instant,
      val attemptNumber: Int,
      val topic: String,
      val partitionKey: String,
      val envelopeJson: String,
  )
  ```

  `claimBatch(owner, limit)`만 production API로 공개한다. 짧은 `transaction {}`에서 `CURRENT_TIMESTAMP`/DB dialect clock을 읽고 ready predicate와 `topic IS NOT NULL`, `partition_key IS NOT NULL`, persisted topic/key allow-list, 네 event allow-list를 적용한다. candidate query는 `next_attempt_at <= databaseNow`를 선택과 CAS UPDATE 양쪽에서 반복하고, 같은 aggregate의 predecessor가 non-terminal이면 후속 row를 제외하며 batch당 aggregate 하나만 선택한다. PostgreSQL은 `FOR UPDATE SKIP LOCKED`와 row별 token을 사용하고, H2/MySQL은 bounded candidate IDs + dialect-supported per-row token assignment 또는 명시적으로 bounded locked update를 사용한다. 단일 batch의 statement/update count와 token uniqueness를 계측해 N+1 row-by-row fallback을 허용하지 않는다. claim은 `attempt_count`를 원자적으로 증가시키고 `attemptNumber`를 반환하며 `maxAttempts` 초과 row는 `FAILED`로 terminal 전환한다. caller-supplied `now`는 production API에 존재하지 않으며 fake clock은 DB-clock test fixture 내부에서만 사용한다.

- [ ] **Step 3: retry/failed/published terminal update를 fenced CAS로 구현한다**

  모든 update는 `(id, status=PENDING, lease_owner=owner, lease_token=token, lease_until > databaseNow, next_attempt_at <= databaseNow, candidate row/version)`를 predicate로 사용한다. success는 `PUBLISHED`/`published_at`; retry는 `PENDING`/`next_attempt_at`/`last_failure_code`; permanent invalid 또는 attempt exhaustion은 `FAILED`를 기록하고 lease를 clear한다. affected row가 0이면 `LeaseLost`를 반환하고 늦은 worker의 결과를 폐기한다. retry-vs-claim race에서 due predicate가 우회되지 않는 barrier test와 same-aggregate delayed publisher race를 추가한다. startup validation은 lease/send/retry/terminal DB budget inequality를 확인한다.

- [ ] **Step 4: H2 setup에서 existing legacy row exclusion과 expiry recovery를 green으로 검증한다**

  `SchemaUtils.createMissingTablesAndColumns(SchedulingOutboxEvents)`와 `Table.deleteAll()`을 `@BeforeEach`에 사용한다. `topic=null` legacy row는 claim되지 않고, expired lease row는 다시 claim되는지 확인한다. two-relay concurrent batch race는 barrier와 statement counter로 disjoint IDs, unique row tokens, bounded update count, lock-wait p95를 검증한다. mixed legacy backlog, DB-clock expiry boundary, attempt exhaustion, same-aggregate predecessor guard와 `Dispatchers.IO` execution evidence를 함께 검증한다. dialect-specific EXPLAIN은 Task 2의 `AppointmentOutboxQueryPlanTest`에서 수행한다.

- [ ] **Step 5: Lore commit**

  Commit intent: `Fence every outbox terminal transition with the database lease`

## Task 6: Kafka4 relay를 transport 밖 orchestration으로 구현

**Files:**

- Create: `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentOutboxRelay.kt`
- Create: `appointment-messaging/src/test/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentOutboxRelayTest.kt`

- [ ] **Step 1: fake publisher를 사용하는 relay red tests를 작성한다**

  ```kotlin
  @Test
  fun `Kafka ack followed by terminal db failure republishes same event id`() {
      val publisher = RecordingPublisher(success = true)
      val first = relay.tick()
      store.simulateTerminalUpdateFailureOnce()
      val second = relay.tick()
      publisher.eventIds shouldBe listOf(first.eventId, first.eventId)
  }

  @Test
  fun `invalid envelope never calls Kafka and becomes failed`() {
      val publisher = RecordingPublisher()
      insertInvalidAppointmentOutbox()
      relay.tick()
      publisher.records shouldBe emptyList()
      store.findOnly().status shouldBe SchedulingOutboxStatus.FAILED
  }
  ```

  Run: `./gradlew :appointment-messaging:test --tests '*AppointmentOutboxRelayTest*'`

  Expected: FAIL because relay/publisher orchestration is absent.

- [ ] **Step 2: narrow publisher interface와 `ProducerRecord` 생성 구현**

  ```kotlin
  fun interface AppointmentKafkaPublisher {
      fun publish(record: ProducerRecord<String, String>): CompletionStage<*>
  }
  ```

  production adapter는 `KafkaTemplate<String, String>` 또는 bluetape4k Kafka4 suspend extension을 사용하되 relay transaction과 분리한다. `CompletionStage`를 scheduler/default dispatcher에서 `join/get`하지 않고 injected IO dispatcher의 bounded suspend adapter로 await하며 cancellation을 전파한다. record에는 persisted topic/key/value만 넣고 unsafe FQN header/tombstone은 넣지 않는다. test double은 즉시 completed/failed future를 반환하고 dispatcher starvation/cancellation을 검증한다.

- [ ] **Step 3: relay tick을 bounded 순서로 구현한다**

  `claimLimit`만큼 batch claim하고 각 claim은 (1) strict decode 및 persisted metadata consistency/allow-list 확인, (2) `ProducerRecord`, (3) bounded send timeout, (4) fenced terminal transition 순서로 처리한다. 한 row 실패가 다음 row claim을 막지 않지만 한 tick 전체의 in-flight queue(`<=32`), clinic fairness(`<=4`), IO/concurrency budget은 설정 상한을 넘지 않는다. same-aggregate predecessor guard와 per-aggregate single in-flight rule이 이전 row가 terminal이 되기 전 후속 claim을 막아 Kafka key order를 보존한다. broker outage 연속 3회 후 30초 pause와 jittered catch-up을 적용한다. disallowed persisted topic/key는 Kafka 호출 전에 bounded `FAILED`로 전환한다.

- [ ] **Step 4: retry/backoff/metrics를 연결하고 green으로 만든다**

  `attemptCount`와 retry limit에 따라 retryable failure와 permanent failure를 구분하고, claim이 반환한 attempt number가 max-attempts 초과 시 영구 실패로 terminal 전환되는지 검증한다. metric tag는 `eventType`, `outcome` 등 low-cardinality만 사용한다. deterministic publisher/barrier로 max in-flight, queue depth, per-clinic fairness, no scheduler overlap, `Dispatchers.IO` execution, cancellation 시 terminal DB write 부재와 lease recovery를 계측한다. 같은 event ID 재전송, stale owner ignore, Kafka exception retry, retry-vs-claim race, ACK-before-DB-update, broker outage pause/fairness를 테스트한다. malformed event는 bounded failure code/payload hash만 남기고 승인된 redrive runbook을 기록한다.

- [ ] **Step 5: Lore commit**

  Commit intent: `Publish committed appointment intents with bounded Kafka4 at-least-once delivery`

## Task 7: Spring auto-configuration, readiness, scheduler를 연결

**Files:**

- Create: `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentMessagingProperties.kt`
- Create: `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentMessagingAutoConfiguration.kt`
- Create: `appointment-messaging/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Create: `appointment-messaging/src/test/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentMessagingAutoConfigurationTest.kt`

- [ ] **Step 1: properties/readiness red tests를 작성한다**

  ```kotlin
  @Test
  fun `missing topic fails startup before writer construction`() {
      val properties = AppointmentMessagingProperties(topic = "")
      shouldThrow<AppointmentMessagingConfigurationException> {
          properties.validateForStartup()
      }
  }

  @Test
  fun `temporary broker outage degrades relay while writer remains available`() {
      val properties = validProperties()
      val readiness = readinessProbe.withBrokerFailure()
      readiness.state shouldBe AppointmentMessagingStartupState.DEGRADED
      writer shouldNotBe null
  }
  ```

  Run: `./gradlew :appointment-messaging:test --tests '*AppointmentMessagingAutoConfigurationTest*'`

  Expected: FAIL because properties/auto config/readiness are absent.

- [ ] **Step 2: bounded properties와 conditional beans를 구현한다**

  Properties keys:

  ```text
  clinic.appointment.messaging.enabled=true
  clinic.appointment.messaging.topic=clinic.appointment.events
  clinic.appointment.messaging.claim-limit=32
  clinic.appointment.messaging.lease=PT30S
  clinic.appointment.messaging.send-timeout=PT5S
  clinic.appointment.messaging.max-attempts=8
  clinic.appointment.messaging.poll-interval=PT1S
  clinic.appointment.messaging.max-in-flight=32
  clinic.appointment.messaging.max-per-clinic-batch=4
  clinic.appointment.messaging.broker-pause=PT30S
  clinic.appointment.messaging.shutdown-drain=PT10S
  clinic.appointment.messaging.allowed-topic=clinic.appointment.events
  ```

  `claim-limit`, in-flight/fairness caps, durations, topic/key/string caps, lease/send/retry budget inequality는 positive/bounded validation을 적용한다. `KafkaSecretReference`는 secret manager key만 허용하고 inline password/token·resolved secret logging을 거부한다. topic/producer syntax, TLS/authn/authz reference, allow-list, serializer self-check가 실패하면 application startup을 fail-fast하고 writer bean도 만들지 않는다. valid config 이후 broker metadata/ACL outage만 `DEGRADED`/`OUT_OF_SERVICE`로 분리하며 writer는 durable PENDING row를 기록할 수 있다. `ApplicationContextRunner`로 invalid config가 writer construction 전에 실패하고, broker outage context가 기동한 채 writer를 유지하며 liveness는 UP/readiness만 DEGRADED가 되는지 검증한다. auto-configuration imports file은 완전한 class name 하나만 포함한다.

- [ ] **Step 3: readiness와 scheduler shutdown을 green으로 만든다**

  readiness는 V22 required columns/indexes, broker metadata/authz, required topic existence, serializer self-check, relay `enabled|paused|held` sub-state를 각각 관찰하고, broker 연결 실패를 API transaction failure로 전파하지 않는다. `SmartLifecycle`/`@PreDestroy` shutdown hook은 acceptingWork를 false로 바꾼 뒤 active send를 최대 10초 drain/cancel하고, cancellation 시 terminal DB write를 하지 않으며 lease expiry/reclaim evidence를 남긴다. `docs/alerts/appointment-messaging-rules.yml`은 pending/oldest-age/lease-lost/retry/failed/pause/partition-skew에 대해 수치 trigger, clear window, severity, owner, escalation, hold/rollback action을 정의하고 runbook 링크 validator로 검증한다.

- [ ] **Step 4: Lore commit**

  Commit intent: `Expose bounded outbox relay readiness without coupling API commits to Kafka`

## Task 8: migration/transaction/API 회귀와 문서 parity를 마무리

**Files:**

- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/FlywayMigrationTest.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/FlywayPostgreSQLMigrationTest.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/FlywayMySQLMigrationTest.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/service/AppointmentNotificationAtomicityTest.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/AppointmentControllerTest.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/RescheduleControllerTest.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/GlobalExceptionHandler.kt`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/messaging/AppointmentMessagingCallerOutcomeTest.kt`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/messaging/AppointmentMessagingPrivacyRedactionTest.kt`
- Create: `appointment-messaging/README.md`
- Create: `appointment-messaging/README.ko.md`
- Create: `docs/runbooks/appointment-messaging-operations.md`
- Create: `docs/alerts/appointment-messaging-rules.yml`

- [ ] **Step 1: API transaction regression tests를 red/green으로 완료한다**

  다음 세 조건을 각각 독립 test로 둔다.

  ```kotlin
  @Test
  fun `create success writes exactly one AppointmentCreated outbox row`() {
      val result = fixture.createAppointment(idempotencyKey = null, correlationId = "request-41")
      result.replayed shouldBe false
      transaction {
          SchedulingOutboxEvents.selectAll()
              .where { SchedulingOutboxEvents.eventType eq "AppointmentCreated" }
              .count() shouldBe 1
      }
  }

  @Test
  fun `idempotency replay does not append another appointment event`() {
      fixture.createAppointment(idempotencyKey = "same-key", correlationId = "request-41")
      val replay = fixture.createAppointment(idempotencyKey = "same-key", correlationId = "request-42")
      replay.replayed shouldBe true
      transaction {
          SchedulingOutboxEvents.selectAll()
              .where { SchedulingOutboxEvents.eventType eq "AppointmentCreated" }
              .count() shouldBe 1
      }
  }

  @Test
  fun `status CAS failure leaves no status changed event`() {
      fixture.forceConcurrentStatusUpdate()
      shouldThrow<IllegalStateException> {
          fixture.updateStatus(targetStatus = "CONFIRMED", correlationId = "request-41")
      }
      transaction {
          SchedulingOutboxEvents.selectAll()
              .where { SchedulingOutboxEvents.eventType eq "AppointmentStatusChanged" }
              .count() shouldBe 0
      }
  }
  ```

  `fixture`는 기존 API test harness가 제공하는 실제 setup helper로 구성하며, 각 test는 먼저 기대한 failure를 확인한 뒤 writer wiring을 적용해 통과시킨다.

  caller outcome matrix: durable aggregate+outbox commit은 `2xx`이고 Kafka ack 완료를 의미하지 않는다. valid-config broker outage에서는 동일 correlation으로 `2xx`/PENDING을 반환한다. pre-commit scope/schema/config failure는 aggregate까지 rollback하고 privacy-safe stable error code 및 retryable `503`/`Retry-After` 정책을 반환한다. duplicate idempotency replay는 최초 eventId/correlation을 보존하고 현재 HTTP attempt correlation만 response header에 사용한다. post-commit local listener failure는 response를 `5xx`로 바꾸지 않는다.

- [ ] **Step 2: dialect migration test를 실행한다**

  Run: `./gradlew :appointment-api:test --tests '*FlywayMigrationTest*' --tests '*FlywayPostgreSQLMigrationTest*' --tests '*FlywayMySQLMigrationTest*'`

  Expected: V22 columns/indexes exist in all three dialects, legacy rows migrate without backfill failure, readiness probe sees new contract. Target-21→22, old writer against V22, new writer against V22, rollback-to-old-writer, partial-DDL and index/EXPLAIN cases are all asserted with actual migration support APIs.

- [ ] **Step 3: README parity를 작성하고 검증한다**

  English/Korean README에 동일한 module purpose, dependency graph, transaction boundary, typed context/caller outcome (2xx means durable intent), topic/key example, fail-fast/readiness states, rollout/rollback/runbook, partial-stream scope, local test commands를 각각 자연스러운 언어로 작성한다. English operator runbook에는 hold/drain/redrive/rollback, alert threshold/clear window, owner/escalation, attempt exhaustion, same-aggregate ordering, lease recovery를 concrete command와 함께 기록하고 alert YAML은 runbook anchor를 검증한다. `AppointmentMessagingPrivacyRedactionTest`와 static scan은 logs, event logger, state history/audit payload, Kafka value/headers에 raw reason·tenant/clinic/appointment ID가 없는지 확인한다. `git diff --check`와 heading/table parity scan으로 누락을 확인한다. root README catalogs and OpenAPI error examples are updated in the same parity pass.

  Add `appointment-messaging/**`, its core/event/API integration paths, runbook/alerts, and V22 migrations to CI path filters, module test/Kover aggregation, nightly matrix, and Flyway-trigger dependencies. Give Kafka/KRaft integration a separate serialized job with the singleton launcher; keep unit jobs free of broker startup. Run a messaging-only path simulation or workflow YAML assertion proving a messaging-only change schedules the module test, coverage, migration, and required `ci-status` dependencies.

- [ ] **Step 4: Lore commit**

  Commit intent: `Document and regression-test the durable appointment messaging boundary`

## Task 9: 통합 검증과 Type-A review handoff

**Files:**

- Review: 모든 변경 파일과 `docs/superpowers/specs/2026-08-05-issue-41-transactional-outbox-messaging-design.md`
- Review artifact: `docs/superpowers/reviews/2026-08-05-issue-41-transactional-outbox-messaging-*.ko.md`

- [ ] **Step 1: module-scoped verification**

  ```bash
  ./gradlew :appointment-messaging:test
  ./gradlew :appointment-api:test --tests '*AppointmentNotificationAtomicityTest*' --tests '*AppointmentControllerTest*'
  ./gradlew :appointment-api:test --tests '*FlywayMigrationTest*' --tests '*FlywayPostgreSQLMigrationTest*' --tests '*FlywayMySQLMigrationTest*'
  ./gradlew :appointment-event:test
  ./gradlew :appointment-messaging:test --tests '*AppointmentMessagingKafkaIntegrationTest*'
  ./gradlew :appointment-messaging:test --tests '*AppointmentOutboxQueryPlanTest*'
  ```

  Read every result; no `BUILD SUCCESSFUL` claim is made from exit code alone if a test report contains skipped/failing cases.

- [ ] **Step 2: static/build verification**

  ```bash
  ./gradlew :appointment-messaging:build :appointment-api:build
  ./gradlew detekt ktlintCheck koverVerify
  ./gradlew projects --quiet
  git diff --check
  ```

  Run `AppointmentOutboxPerformanceTestSupport` with fixed seed `41`, 20,000 mixed legacy/appointment backlog rows, explicit warmup 3×30s and measured 5×60s samples, baseline/candidate comparison, sustained 200 events/s, broker outage/recovery, two concurrent relays, fairness/partition skew, lock wait, heap/thread/queue and serializer allocation evidence. Write a raw-payload-free report to `build/reports/appointment-messaging/benchmark.json`; assert publish-to-ack p95<=500ms, p99<=2s, catch-up<=180s, lock-wait p95<=50ms, skew<=2.0x, heap<=256MiB, relay threads<=32, and retain the exact JVM/DB/Kafka configuration in report metadata. `AppointmentOutboxQueryPlanTest` emits per-dialect EXPLAIN and asserts the V22 chosen index/no full scan.

- [ ] **Step 3: independent review lanes**

  Request read-only Type-A reviews for performance, stability/SRE, security, operator readiness, library/API usability, caller/user behavior, testing, and code quality. Each review must report P0/P1/P2/P3 counts, exact file evidence, and no speculative blocker. The Step 3-R plan review artifact must record the three independent plan lanes, every P1 repair (core context boundary, claim attempt/due/order fencing, dialect EXPLAIN/benchmark, readiness context proof, Kafka launcher/CI, scope/privacy/ops artifacts), and the final verdict. Reconcile all P0/P1 before PR creation; document accepted P2/P3 follow-ups in the review artifact. The review artifact records the earlier six-lens Step 2-R findings, spec edits, reviewer divergence on root causation (resolved against Issue #40), and final P0/P1 counts.

- [ ] **Step 4: workflow DoD evidence**

  Update bluetape workflow topology only through `bluetape-flow.py`; verify mutation receipts, plan/spec/risk checksums, review artifacts, fresh tests, and final worktree cleanliness. Keep `## DoD Status` as the final PR-body section when delivery begins.

## Task 10: delivery gate (implementation 완료 후에만)

- [ ] Create English PR linked to Issue #41 with matching assignee/milestone/labels.
- [ ] Re-read exact PR head/body and required CI checks; do not enable auto-merge.
- [ ] Obtain fresh user approval tied to the exact verified PR head before merge.
- [ ] Merge, sync local `develop`, remove only the dedicated issue worktree/branch after confirming merged state, and verify root/worktree parity.
- [ ] Run final DoD report with plan statuses, changed files, tests/CI evidence, P0/P1 counts, risks, `Required checks: X/Y; N/A: N; Blocked: N`, and final `DONE`/`PENDING`/`BLOCKED` state.

## Plan self-review

- **Spec coverage:** transaction atomicity is Task 4; envelope/routing is Task 3; schema/lease is Tasks 2 and 5; relay/failure model is Task 6; readiness/rollout is Task 7; tests/dialect/docs/review/delivery are Tasks 8–10. No spec section is intentionally unassigned.
- **Placeholder scan:** 모든 단계가 구체적인 파일, 명령, assertion을 포함한다. Task 8 test examples도 concrete fixture calls와 assertions를 사용한다.
- **Type consistency:** `AppointmentOutboxWriter`, `AppointmentOutboxClaim`, `AppointmentEventEnvelope`, `AppointmentEventType`, `AppointmentMessagingContext`, typed ID/reason values, and V22 column names are used consistently across tasks. The core-owned `AppointmentCommandContext` crosses the reschedule port and is explicitly mapped at the API/messaging boundary; no core→event/messaging dependency cycle is introduced. The `rescheduled` port preserves the existing caller-transaction contract.
- **Contract reconciliation:** root causation follows the approved Issue #40 envelope rule (`correlationId` as root causation); legacy generic plan/policy rows retain their existing nullable causation semantics. The V22 writer KDoc/update records this boundary explicitly.
- **Operational coverage:** Task 2/5/6/7/8/9 include dialect-specific partial/composite indexes and EXPLAIN, atomic batch claim with due/version/order/attempt fencing, DB-clock lease budget, broker auth/readiness context proof, pause/backpressure/fairness, bounded shutdown lifecycle, real singleton Kafka integration, concrete alert/runbook ownership, and quantitative benchmark thresholds/artifacts.
- **Step 3-R repair closure:** The first plan review found P1 gaps in executable benchmark/query-plan evidence, per-row claim tokens and races, resource/dispatcher assertions, Kafka launcher/CI isolation, core context ownership, attempt exhaustion, same-aggregate ordering, retry backoff CAS, readiness context behavior, scope/privacy/allow-list enforcement, reschedule file ownership, and operational artifacts. Each is mapped to a named file, task, assertion, or command above; P0/P1 must be re-counted as zero after implementation review.
- **Known dependency risk:** exact published artifact names for `bluetape4k-kafka4` and Spring Kafka 4 are validated by `./gradlew dependencies` in Task 1; no hard-coded version is introduced. If the catalog alias resolves to a different existing name, update only the alias reference and record the evidence in the commit trailer.
