# Issue #393 outbox 소유권 구현 계획

> **에이전트 작업자 필수 하위 skill:** `$subagent-driven-development`(권장) 또는 `$executing-plans`를 사용해 이 계획을 task 단위로 실행한다. 단계는 checkbox(`- [ ]`)로 추적한다.

**Goal:** `appointment-messaging`가 event persistence 구현을 소비자 API classpath로 누출하지 않도록 `appointment-core` public contract와 Gradle scope를 정렬하고, `appointment-notification` readiness가 V19 waitlist outbox table/index를 실제 preflight하도록 만든다. 현재 물리 table/repository 이동은 하지 않으며 ADR·README·compile fixture가 소유권과 예외를 정확히 설명하게 한다.

**Architecture:** messaging은 `appointment-event`를 implementation으로 소비하고 `appointment-core`를 직접 API로 노출한다. public reason-code source import는 core로 이동하지만 scheduling outbox table은 event의 내부 구현 의존성으로 남긴다. notification은 public event repository/DTO를 아직 노출하므로 event `api`를 transitional exception으로 유지하고, V19 waitlist table/index를 readiness 검증 목록에 추가한다. 물리 contract 분리는 별도 후속 Issue #409의 범위다.

**Tech Stack:** Kotlin 2.3, Java 25, Spring Boot 4, Gradle Kotlin DSL, Exposed 1.x, H2 test database, Flyway V19 migration, JUnit 5, `bluetape4k-assertions`, Gradle API consumer fixture, Korean terminology audit.

---

## 사전 조건과 기준선

- [x] worktree가 `refactor/issue-393-outbox-ownership`이고 기준 develop commit이 `28e38915cc153fc01275a2c6acad632d99340b93`인지 확인한다.
- [x] root의 기존 변경(`frontend/appointment-frontend/angular.json`, `.superpowers/`, `.workflow-inputs/`)과 다른 worktree를 되돌리지 않는다.
- [x] 승인된 설계 `docs/superpowers/specs/2026-08-25-issue-393-outbox-ownership-design.ko.md`와 7-Tier 검토 `docs/superpowers/reviews/2026-08-25-issue-393-spec-review.ko.md`의 범위 밖 dependency·module·schema 변경을 추가하지 않는다.
- [x] 다음 source 좌표를 구현 전 inventory로 고정한다.
  - `appointment-messaging/build.gradle.kts`의 현재 `api(project(":appointment-event"))`.
  - `build.gradle.kts`의 messaging API expected scope와 `src/consumerFixture/messaging/kotlin/io/bluetape4k/clinic/appointment/consumer/MessagingApiConsumerFixture.kt`.
  - `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentMessagingContracts.kt`, `AppointmentEventEnvelopeCodec.kt`, `AppointmentOutboxWriter.kt`의 event package `CancellationReasonCode` import.
  - `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationSchemaReadiness.kt`의 `requiredTables`, `missingRequiredIndexes`, `REQUIRED_INDEXES`.
  - `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationSchemaReadinessTest.kt`의 모든 `SchemaUtils.create` fixture.
  - `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/waitlist/WaitlistNotificationOutboxAdapter.kt`의 `WaitlistNotificationOutboxEvents`와 세 index 이름.
  - API의 `appointment-api/src/main/resources/db/migration/{h2,mysql,postgresql}/V19__add_waitlist_delivery.sql` 및 `FlywayMigrationTest` 계열.

## 실행 게이트

- [x] 이 계획은 승인된 spec과 live Issue #393/#409 readback을 전제로 한다. Step 3-R(6-lane/7-Tier 계획 검토)에서 P0/P1이 0이 되고 사용자가 계획을 승인하기 전에는 Task 1~8의 코드·문서 구현을 시작하지 않는다.
- [x] 계획 승인 후 Task 1~8은 이 worktree의 1인 개발 lane에서 순차 실행한다. 해당 child의 branch/base가 정렬되면 child PR은 생성할 수 있지만, merge와 최종 승인 요청은 #392~#402 전체가 완료된 뒤까지 보류한다.

## Task 1 — messaging API 경계를 먼저 red로 고정한다

**Files:**

- Modify `build.gradle.kts`.
- Modify `src/consumerFixture/messaging/kotlin/io/bluetape4k/clinic/appointment/consumer/MessagingApiConsumerFixture.kt`.

**Steps:**

- [x] `build.gradle.kts`의 messaging expected scope에서 다음 한 줄을 교체한다.

```kotlin
// before
"project::appointment-event",
// after
"project::appointment-core",
```

- [x] `MessagingApiConsumerFixture.kt`에 다음 core/messaging imports와 KClass anchors를 추가한다. 기존 event package import는 추가하지 않는다.

```kotlin
import io.bluetape4k.clinic.appointment.commitment.CancellationReasonCode
import io.bluetape4k.clinic.appointment.model.dto.AppointmentRecord
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import io.bluetape4k.clinic.appointment.messaging.AppointmentMessagingContext
import io.bluetape4k.clinic.appointment.messaging.AppointmentOutboxWriter

private val outboxWriterType: KClass<out AppointmentOutboxWriter> = AppointmentOutboxWriter::class
private val messagingContextType: KClass<out AppointmentMessagingContext> = AppointmentMessagingContext::class
private val appointmentRecordType: KClass<out AppointmentRecord> = AppointmentRecord::class
private val tenantClinicScopeType: KClass<out TenantClinicScope> = TenantClinicScope::class
private val cancellationReasonCodeType: KClass<out CancellationReasonCode> = CancellationReasonCode::class
```

- [x] `verifyMessagingApiConsumerSurface` 반환 목록에 다섯 anchor를 추가한다. `AppointmentOutboxWriter`의 event table/status 구현 타입은 anchor로 추가하지 않고, fixture 주석에 “Gradle API scope/leakage만 증명하며 물리 소유권은 ADR/source-path가 증명한다”고 명시한다.
- [x] fixture inventory가 실제 method signature까지 검사하도록 `build.gradle.kts`의 messaging inventory에 `AppointmentOutboxWriter`, `AppointmentMessagingContext`, `AppointmentRecord`, `TenantClinicScope`, `CancellationReasonCode`를 추가하고, fixture에 다음 compile-only typed invocation을 넣는다.

```kotlin
private fun verifyOutboxWriterMethods(
    writer: AppointmentOutboxWriter,
    scope: TenantClinicScope,
    appointment: AppointmentRecord,
    replacement: AppointmentRecord,
    context: AppointmentMessagingContext,
    fromState: AppointmentState,
    reasonCode: CancellationReasonCode?,
) {
    writer.created(scope, appointment, context)
    writer.statusChanged(scope, appointment, fromState, context, reasonCode)
    writer.cancelled(scope, appointment, context, reasonCode)
    writer.rescheduled(scope, appointment, replacement, context)
}
```

`AppointmentState` import도 fixture에 추가하고, 함수는 호출하지 않아 compile-only surface만 검증한다. 이 typed invocation은 단순 `KClass` 존재 확인보다 `AppointmentOutboxWriter.kt:16-44`의 public parameter 순서·nullable reason-code·core record/scope 타입을 직접 고정한다.
- [x] fixture를 event dependency 없이 compile하는 계약으로 고정하고, 변경 전 expected scope mismatch가 실제로 발생하는지 아래 red 명령으로 읽는다. 기존 public anchors는 삭제하지 않는다.

**Verification:**

```bash
./gradlew --no-configuration-cache assertModuleConsumerFixtureApiVariants
```

Expected red result: messaging expected API scope가 현재 `project::appointment-event`와 달라 assertion이 실패한다. 외부 dependency나 runtime test를 이 단계에서 추가하지 않는다.

## Task 2 — messaging dependency와 public source import를 최소 수정한다

**Files:**

- Modify `appointment-messaging/build.gradle.kts`.
- Modify `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentMessagingContracts.kt`.
- Modify `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentEventEnvelopeCodec.kt`.
- Modify `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentOutboxWriter.kt`.
- Modify `build.gradle.kts` only for the expected messaging API scope from Task 1.
- Regenerate the root `gradle.lockfile` entry for the messaging consumer fixture configuration.
- Regenerate `benchmark/appointment-messaging-benchmark/gradle.lockfile` for the benchmark compile configuration.

**Steps:**

- [x] `appointment-messaging/build.gradle.kts`의 dependency block을 다음처럼 바꾼다. Kafka, Exposed, Spring, test dependency의 scope와 순서는 유지한다.

```kotlin
dependencies {
    api(project(":appointment-core"))
    implementation(project(":appointment-event"))
    // 이후 기존 dependency는 변경하지 않는다.
}
```

- [x] 세 Kotlin 파일에서 아래 import를 정확히 교체한다. `SchedulingOutboxEvents`와 `SchedulingOutboxStatus`는 writer 구현이 transaction 안에서 사용하는 event table/type이므로 남긴다.

```kotlin
// before
import io.bluetape4k.clinic.appointment.event.notification.CancellationReasonCode
// after (all three public source consumers)
import io.bluetape4k.clinic.appointment.commitment.CancellationReasonCode
```

- [x] `AppointmentMessagingContracts.kt`의 `AppointmentMessagingContext`가 messaging module 정의 타입이고, `CancellationReasonCode`·`AppointmentRecord`·`TenantClinicScope`가 core 타입이라는 구분을 README/ADR에 기록한다. `AppointmentEventEnvelopeCodec.kt`의 decode/encode signature와 `AppointmentOutboxWriter.kt`의 public method parameter 이름·nullable 여부는 그대로 둔다.
- [x] typealias의 underlying JVM ABI와 public method signature는 유지하되, event package typealias를 직접 import하던 source consumer가 core import 또는 명시적 event dependency로 이동해야 한다는 사실을 KDoc/README에 과장 없이 기록한다.
- [x] notification의 `api(project(":appointment-event"))`와 root notification expected scope는 이번 Task에서 변경하지 않는다. 이것은 #409로 추적하는 명시적 transitional exception이다.
- [x] source diff에서 새 abstraction, new module, dependency version, Kafka/Redis 설정, migration SQL 변경이 없는지 확인한다.
- [x] strict dependency locking이 API scope 변경을 stale lock으로 거부하지 않도록 `--write-locks`로 root와 benchmark module `gradle.lockfile`을 갱신한다. root는 `appointmentMessagingConsumerFixtureClasspath`에서 제거되는 R2DBC 좌표 4개(`r2dbc-spi`, `exposed-r2dbc`, `kotlinx-coroutines-reactive`, `reactive-streams`)의 suffix만 제거하고, benchmark는 `compileClasspath`의 같은 좌표 suffix만 제거하며 버전·다른 configuration은 변경하지 않는다.

**Verification:**

```bash
./gradlew :appointment-messaging:compileKotlin
./gradlew --no-configuration-cache assertModuleConsumerFixtureApiVariants compileModuleConsumerFixtures
```

Expected result: `BUILD SUCCESSFUL`; messaging consumer fixture가 core를 직접 해석하고 event persistence 구현은 API scope에 나타나지 않는다.

## Task 3 — readiness regression을 먼저 작성한다

**Files:**

- Modify `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationSchemaReadinessTest.kt`.
- Modify `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationAutoConfigurationTest.kt`.

**Steps:**

- [x] `WaitlistNotificationOutboxEvents`를 import하고 기존 H2 fixture의 `SchemaUtils.create(...)` 호출을 `SchemaUtils.createMissingTablesAndColumns(...)`로 바꾼다. 정상 fixture 호출은 아래처럼 `transaction(database)` 안에 둔다. `NotificationAutoConfigurationTest`의 readiness fixture도 waitlist table을 포함하고 동일한 Base58 suffix invariant를 유지한다.

```kotlin
transaction(database) {
    SchemaUtils.createMissingTablesAndColumns(
        TenantGroups,
        Clinics,
        AppointmentEventLogs,
        NotificationOutboxEvents,
        NotificationDeliveryAttempts,
        WaitlistNotificationOutboxEvents,
        FlywaySchemaHistory,
    )
}
```

테스트마다 아래 `System.nanoTime()`과 `Base58.randomString(8)` suffix가 포함된 고유 H2 URL을 사용하므로 공유 row cleanup이 필요하지 않다는 invariant를 source와 review artifact에 기록한다. 공유 fixture를 도입할 경우에는 repository 규칙대로 `@BeforeEach`에서 `Table.deleteAll()`을 호출한다.

```kotlin
private fun connect(name: String): Database =
    Database.connect(
        "jdbc:h2:mem:${name}_${System.nanoTime()}_${Base58.randomString(8)};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        driver = "org.h2.Driver",
    )
```
- [x] V19 table 누락 회귀를 아래 형태로 추가한다. notification의 나머지 필수 table과 Flyway history는 만들고 waitlist table만 생략해 reason이 다른 누락에 가려지지 않게 한다.

```kotlin
val result = NotificationSchemaReadiness(database, NotificationCryptoProperties(active = key())).check()
result.available shouldBeEqualTo false
result.reason shouldBeEqualTo "missing tables: clinic_waitlist_notification_outbox"
```

- [x] `uk_waitlist_notification_outbox_idempotency`, `idx_waitlist_notification_outbox_ready`, `idx_waitlist_notification_outbox_lease` 각각을 아래처럼 실제 이름의 `DROP INDEX`로 제거하는 테스트를 추가하고, 각 단일 누락 fixture에서 exact reason을 검증한다. 기존 notification index 회귀는 유지한다.

```kotlin
exec("DROP INDEX uk_waitlist_notification_outbox_idempotency")
result.reason shouldBeEqualTo "missing indexes: uk_waitlist_notification_outbox_idempotency"

exec("DROP INDEX idx_waitlist_notification_outbox_ready")
result.reason shouldBeEqualTo "missing indexes: idx_waitlist_notification_outbox_ready"

exec("DROP INDEX idx_waitlist_notification_outbox_lease")
result.reason shouldBeEqualTo "missing indexes: idx_waitlist_notification_outbox_lease"
```
- [x] waitlist table과 세 index, V21 Flyway history, event-log tenant column, tenant backfill, active key가 모두 준비된 fixture에서 `result.available shouldBeEqualTo true`를 검증한다.
- [x] 모든 새 equality assertion은 `io.bluetape4k.assertions.shouldBeEqualTo`를 사용한다. `assertThrows`, AssertJ, Kluent, `kotlin.test` assertion을 새로 도입하지 않는다.
- [x] 각 test name/KDoc는 한국어로 작성하고, readiness reason은 운영자가 바로 조치할 수 있는 table/index 식별자를 보존한다.

**Verification (red before production change):**

```bash
./gradlew :appointment-notification:test --tests '*NotificationSchemaReadinessTest*'
```

Expected red result: readiness production code가 아직 waitlist table/index를 검사하지 않아 missing waitlist/index 회귀가 `available == true` 또는 waitlist와 무관한 reason으로 실패한다. 실패 로그를 읽고 assertion이 실제 계약을 검증하는지 확인한 뒤 Task 4로 이동한다.

## Task 4 — readiness 구현을 최소 범위로 보강한다

**Files:**

- Modify `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationSchemaReadiness.kt`.

**Steps:**

- [x] `WaitlistNotificationOutboxEvents`를 import하고 다음 세 좌표를 정확히 추가한다.

```kotlin
private fun requiredTables(): List<String> =
    listOf(
        NotificationOutboxEvents.tableName,
        WaitlistNotificationOutboxEvents.tableName,
        NotificationDeliveryAttempts.tableName,
        AppointmentEventLogs.tableName,
        Clinics.tableName,
    )

val migrationStatements = MigrationUtils.statementsRequiredForDatabaseMigration(
    NotificationOutboxEvents,
    NotificationDeliveryAttempts,
    WaitlistNotificationOutboxEvents,
)

val REQUIRED_INDEXES = listOf(
    "idx_notification_outbox_ready_clinic_cursor",
    "idx_notification_outbox_ready_within_clinic",
    "idx_notification_outbox_direct_lookup",
    "idx_notification_outbox_tenant_direct_lookup",
    "idx_notification_outbox_reminder_suppression",
    "idx_notification_outbox_lease_recovery",
    "idx_notification_outbox_terminal_retention",
    "idx_notification_outbox_pending_oldest",
    "uk_waitlist_notification_outbox_idempotency",
    "idx_waitlist_notification_outbox_ready",
    "idx_waitlist_notification_outbox_lease",
)
```

- [x] 위 snippet의 `REQUIRED_INDEXES`에는 실제 기존 notification index 목록을 그대로 보존하고 waitlist 세 이름만 append한다. `requiredTables()`의 순서는 missing table reason 테스트가 기대하는 순서와 일치하도록 유지한다.
- [x] `check()`의 transaction, Flyway version gate, event-log tenant preflight, crypto validation, 예외 reason 형식을 변경하지 않는다.
- [x] dispatcher/worker 호출 지점은 유지한다. readiness가 호출될 때 V19 고정 probe가 추가될 뿐이며 dispatch당 추가 table probe 상한 `1 + globalConcurrency`를 초과하는 loop/query를 만들지 않는다.
- [x] Exposed table name과 migration/index 문자열을 여러 곳에 복제하지 않는다. readiness 내부에서는 `requiredTables()`의 `WaitlistNotificationOutboxEvents.tableName`과 `REQUIRED_INDEXES` 한 곳에만 waitlist 좌표를 추가하고, migration statement 재사용 경로를 따른다.

**Verification:**

```bash
./gradlew :appointment-notification:test --tests '*NotificationSchemaReadinessTest*'
```

Expected result: Task 3의 missing table/index, existing notification index, tenant preflight, active-key, UP 테스트가 모두 통과한다.

## Task 5 — 소유권 문서와 한국어 용어를 실제 구현에 맞춘다

**Files:**

- Modify `appointment-event/README.md`.
- Modify `appointment-event/README.ko.md`.
- Modify `appointment-messaging/README.md`.
- Modify `appointment-messaging/README.ko.md`.
- Modify `appointment-notification/README.md`.
- Modify `appointment-notification/README.ko.md`.
- Modify `docs/requirements/architecture.md`.

**Steps:**

- [x] event README 두 locale에서 실제 `appointment-core`/Exposed API 의존성과 event contract 책임을 설명하고 `예약서비스`를 `예약 서비스`로 고친다.
- [x] messaging README 두 locale에서 direct `appointment-core` API와 event implementation dependency의 migration note, public API와 내부 scheduling table의 경계를 기록한다.
- [x] notification README 두 locale에서 실제 Gradle dependency 목록을 반영하고 event `api`를 public event repository/DTO 때문에 유지하는 transitional exception으로 명시한다. `snapshot`은 독자가 조치할 수 있는 `관측 기준 데이터` 등 구체적인 한국어로 바꾼다.
- [x] `docs/requirements/architecture.md`에 ADR-15 ownership matrix를 추가한다. 표에는 다음 row family와 책임 좌표를 그대로 포함한다.

```markdown
| row family | table declaration | repository/write | claim·relay·worker | readiness | migration |
| scheduling | `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/SchedulingOutboxEvents.kt` | `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentOutboxWriter.kt` + `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/SchedulingEventRepository.kt` + `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/policy/SchedulingPolicyEventRepository.kt` (row family별) | `appointment-messaging` | `appointment-messaging` | `appointment-api` V22 |
| notification | `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationOutboxEvents.kt` | `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationOutboxRepository.kt` | `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationOutboxWorkStore.kt` | `NotificationSchemaReadiness` | `appointment-api` V14/V21 |
| waitlist notification | `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/waitlist/WaitlistNotificationOutboxAdapter.kt` | `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/waitlist/WaitlistNotificationOutboxAdapter.kt` (`WaitlistNotificationOutboxRepository`) | `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/WaitlistOfferNotificationStore.kt` | `NotificationSchemaReadiness` V19 table/index preflight | `appointment-api` V19 |
```

표 아래에 “ADR/source-path review는 물리 소유권을, compile fixture는 Gradle API scope/leakage만 증명한다”는 한계를 명시한다. 기존 `snapshot` 다섯 표현은 각 문맥의 `정책 기준 데이터`, `권위 기준 데이터`, `재현 기준 데이터` 등 구체 용어로 교체한다.
- [x] ADR-15에 다음 rollback 표를 추가한다.

```markdown
| 변경 family | rollback 절차 | schema rollback |
|---|---|---|
| messaging dependency/API fixture | `appointment-messaging/build.gradle.kts`, root expected scope, consumer fixture, 세 public import를 동일 commit에서 함께 되돌린다. | 불필요 |
| notification V19 readiness | `NotificationSchemaReadiness.kt`의 waitlist table/index 목록과 `NotificationSchemaReadinessTest.kt` fixture/assertion을 동일 commit에서 함께 되돌린다. | 불필요 |
| README/ADR 문서 | event/messaging/notification 6개 README와 `docs/requirements/architecture.md` ADR-15 변경을 동일 commit에서 함께 되돌리고, Issue body의 evidence link를 원래 source path로 복구한다. | 불필요 |
| migration/runtime | 이번 plan은 V19 SQL과 runtime schema를 변경하지 않으므로 migration rollback을 수행하지 않는다. | 불필요 |
```
- [x] README 두 locale의 기술 identifier, commands, URLs, package names는 보존하고 임의의 English README를 새로 만들지 않는다.
- [x] 문서 변경 후 broad audit이 0 findings가 되는지 확인한다.

**Verification:**

```bash
node ~/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs \
  appointment-event/README.md appointment-event/README.ko.md \
  appointment-messaging/README.md appointment-messaging/README.ko.md \
  appointment-notification/README.md appointment-notification/README.ko.md \
  docs/requirements/architecture.md
```

Expected result: `Terminology audit passed: 7 file(s), series=clinic-appointment, findings=0.`

## Task 6 — 모듈·migration·API 계약을 순서대로 검증한다

- [x] 먼저 changed behavior의 targeted test를 실행한다.

```bash
./gradlew :appointment-notification:test --tests '*NotificationSchemaReadinessTest*'
```

- [x] 그 다음 event/messaging/notification 모듈 단독 테스트를 실행한다. `gradle.properties`의 `org.gradle.parallel=true`가 PostgreSQL/Redis fixture를 겹치게 하지 않도록 `--no-parallel`을 고정한다.

```bash
./gradlew --no-parallel :appointment-event:test :appointment-messaging:test :appointment-notification:test
```

- [x] Gradle API fixture를 configuration cache 없이 다시 검증한다.

```bash
./gradlew --no-configuration-cache assertModuleConsumerFixtureApiVariants compileModuleConsumerFixtures
```

- [x] API Flyway V19 clean/upgrade migration 계약을 실행한다.

```bash
./gradlew --no-parallel :appointment-api:test \
  --tests 'io.bluetape4k.clinic.appointment.api.migration.FlywayMigrationTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.migration.FlywayPostgreSQLMigrationTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.migration.FlywayMySQLMigrationTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.migration.WaitlistDeliveryMigrationContractTest'
```

승인 spec의 wildcard `*FlywayMigrationTest*`는 최소 H2 gate이고, 이 계획은 현재 source inventory의 PostgreSQL/MySQL migration contract까지 보장하도록 네 test class를 명시적으로 확장한다. 이는 새 동작 범위가 아니라 동일 V19 acceptance의 dialect별 증거 확장이다.

- [x] messaging benchmark가 production module compile graph에서 깨지지 않는지 확인한다.

```bash
./gradlew :appointment-messaging-benchmark:compileKotlin
```

- [x] readiness 비용 상한을 source path와 기존 concurrency test로 증명한다. `NotificationOutboxDispatcher.kt:86`의 pre-claim `readiness?.check()` 1회, `NotificationOutboxDispatcher.kt:109-117`의 claim 상한 `claimed.size <= globalConcurrency`, `NotificationOutboxWorker.kt:73-75`의 row별 `readiness?.check()`, `NotificationAutoConfiguration.kt:379`의 동일 readiness wiring을 함께 읽어 dispatch당 추가 table probe가 `1 + globalConcurrency` 이하임을 review artifact에 기록한다.

```bash
rg -n 'readiness\?\.check\(\)|globalConcurrency|claimWorkBatch|process\(' \
  appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationOutboxDispatcher.kt \
  appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationOutboxWorker.kt \
  appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationAutoConfiguration.kt
sed -n '80,120p' appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationOutboxDispatcher.kt
sed -n '66,80p' appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationOutboxWorker.kt
sed -n '370,386p' appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationAutoConfiguration.kt
./gradlew :appointment-notification:test --tests '*NotificationOutboxDispatcherTest*'
```

- [x] source-level diff, terminology, accidental event API scope를 마지막으로 확인한다. `develop` baseline과의 range diff에서 구현 허용 변경 파일만 남아야 하며, API migration SQL V14/V19/V21/V22는 모두 무변경이어야 한다.

```bash
base=28e38915cc153fc01275a2c6acad632d99340b93
git diff --check "$base"
git diff "$base" --name-only | sort
git diff --exit-code "$base" -- \
  appointment-api/src/main/resources/db/migration/h2/V14__add_notification_outbox.sql \
  appointment-api/src/main/resources/db/migration/h2/V19__add_waitlist_delivery.sql \
  appointment-api/src/main/resources/db/migration/h2/V21__add_tenant_query_isolation.sql \
  appointment-api/src/main/resources/db/migration/h2/V22__add_appointment_messaging_outbox_lease.sql \
  appointment-api/src/main/resources/db/migration/mysql/V14__add_notification_outbox.sql \
  appointment-api/src/main/resources/db/migration/mysql/V19__add_waitlist_delivery.sql \
  appointment-api/src/main/resources/db/migration/mysql/V21__add_tenant_query_isolation.sql \
  appointment-api/src/main/resources/db/migration/mysql/V22__add_appointment_messaging_outbox_lease.sql \
  appointment-api/src/main/resources/db/migration/postgresql/V14__add_notification_outbox.sql \
  appointment-api/src/main/resources/db/migration/postgresql/V19__add_waitlist_delivery.sql \
  appointment-api/src/main/resources/db/migration/postgresql/V21__add_tenant_query_isolation.sql \
  appointment-api/src/main/resources/db/migration/postgresql/V22__add_appointment_messaging_outbox_lease.sql
git ls-files --others --exclude-standard
git status --short
```

working-tree diff의 파일 목록은 다음 22개 implementation/documentation 경로와 계획·review 문서만 허용한다: root `build.gradle.kts`와 `gradle.lockfile`, `benchmark/appointment-messaging-benchmark/gradle.lockfile`, messaging Gradle·세 public Kotlin source·messaging fixture, notification readiness source와 두 test, event/messaging/notification README 6개, `docs/requirements/architecture.md`, `docs/superpowers/specs/2026-08-25-issue-393-outbox-ownership-design.ko.md`, `docs/superpowers/reviews/2026-08-25-issue-393-spec-review.ko.md`, `docs/superpowers/reviews/2026-08-25-issue-393-implementation-review.ko.md`, `docs/superpowers/plans/2026-08-25-issue-393-outbox-ownership-plan.ko.md`. `git ls-files --others --exclude-standard`가 비어 있고 허용 목록 밖 파일이 나오지 않아야 한다.

- [x] 실패 시 원인을 해당 Task로 되돌려 수정하고, 실패한 명령과 재실행 결과를 리뷰 artifact에 기록한다. skip/실패를 성공으로 간주하지 않는다.

## Task 7 — 구현 diff의 module별 6-lane/7-Tier review를 고정한다

**Review artifact:** `docs/superpowers/reviews/2026-08-25-issue-393-implementation-review.ko.md`

- [x] Task 1~6의 현재 `HEAD`와 승인 spec `c59124cf757d3fe95220f61311cbdb5b93e37a4b`를 기준으로 architect, code reviewer, spec verifier, performance, security/data-boundary, operations/integration의 6개 독립 관점을 재실행한다. 각 관점은 현재 diff와 fresh command output만 사용한다.
- [x] review artifact에 아래 3개 module별 7-Tier 표를 작성한다. 각 행은 `성능·안정성·보안/데이터 경계·운영·개발자/API·사용자/호출자·통합/테스트`를 모두 판정하고 file/line 근거를 적는다.

```markdown
| module | 성능 | 안정성 | 보안/데이터 경계 | 운영 | 개발자/API | 사용자/호출자 | 통합/테스트 |
|---|---|---|---|---|---|---|---|
| appointment-event | 판정 + file:line 근거 | 판정 + file:line 근거 | 판정 + file:line 근거 | 판정 + file:line 근거 | 판정 + file:line 근거 | 판정 + file:line 근거 | 판정 + file:line 근거 |
| appointment-messaging | 판정 + file:line 근거 | 판정 + file:line 근거 | 판정 + file:line 근거 | 판정 + file:line 근거 | 판정 + file:line 근거 | 판정 + file:line 근거 | 판정 + file:line 근거 |
| appointment-notification | 판정 + file:line 근거 | 판정 + file:line 근거 | 판정 + file:line 근거 | 판정 + file:line 근거 | 판정 + file:line 근거 | 판정 + file:line 근거 | 판정 + file:line 근거 |
```

- [x] artifact의 결론에 fresh review 결과의 `P0`, `P1`, `P2`, `P3` 실제 개수를 기록하고, P2/P3는 이번 PR에서 수정했는지 또는 #409/후속 이슈로 명시했는지 각각 링크한다. P0/P1이 0이 아니면 구현을 멈추고 해당 Task를 수정한 뒤 6-lane review를 다시 실행한다.
- [x] review artifact의 기준 SHA, 실행 명령, module별 7-Tier 표, finding disposition, terminology audit 및 migration SQL no-diff 결과가 한 문서에서 추적되는지 `git diff --check`로 확인한다.

```bash
git add docs/superpowers/reviews/2026-08-25-issue-393-implementation-review.ko.md
git diff --cached --check
```

Review artifact가 staged 상태에서 whitespace 검사를 통과한 뒤 Task 8의 Lore commit에 포함한다.

## Task 8 — Issue/후속 이슈 readback과 Lore commit을 고정한다

- [x] `gh issue view 393 --json number,title,body,state,labels,milestone,assignees,parent,subIssues,url`로 현재 baseline SHA, messaging scope, notification transitional exception, V19 table/index, evidence boundary, #409 link가 live body에 모두 있는지 확인한다.
- [x] `gh issue view 409 --json number,title,body,state,labels,milestone,assignees,parent,subIssues,url`로 후속 이슈가 #392~#402 train의 native child가 아니며 `debop` assignee와 Korean body를 유지하는지 확인한다.
- [x] `gh issue view 407 --json subIssues --jq '{total: (.subIssues.nodes | length), membership: (.subIssues.nodes | map(.number) | sort | join(","))}'` 결과가 `total=11`, membership `392,393,394,395,396,397,398,399,400,401,402`이고 #409가 포함되지 않는지 확인한다. native sub-issue의 정렬 목록과 별개로, `gh issue view 407 --json body --jq '.body'`에서 PR 실행 순서가 `393,402,399,395,400,396,392,394,397,398,401`으로 기록되어 있는지 별도 readback한다.
- [x] implementation diff와 plan/spec/review 문서를 함께 검토해 #409 범위가 이번 PR에 섞이지 않았는지 확인한다.
- [x] 계획 승인 후 생성한 plan commit과 spec/review commit을 readback해 다음 provenance를 기록한다: spec `c59124cf757d3fe95220f61311cbdb5b93e37a4b`, review artifact의 해당 spec 기준 SHA, plan commit의 실제 SHA. 계획 파일이 `git status --short`에서 untracked가 아니어야 한다.
- [x] 다음 Lore 형식의 Korean commit message로 구현을 커밋한다. 구현 commit은 `2e7b48ad9c79f62b0bbc79d35535b423575b54e8`이며 PR은 #410이다.

```text
messaging event 의존성을 public contract 경계에 맞춘다

messaging은 core를 직접 API로 소비하고 notification V19 readiness는 waitlist schema를 선행 검증한다.

Constraint: #393은 physical contract split을 수행하지 않고 #409 transitional exception을 유지한다.
Rejected: notification event API를 이번 변경에서 implementation으로 낮춤 | public DTO/repository source compatibility를 침범한다.
Confidence: high
Scope-risk: moderate
Directive: 후속 contract 분리는 #409 설계·API fixture gate를 먼저 통과시킨다.
Tested: targeted readiness, module tests, API consumer fixtures, FlywayMigrationTest, Korean terminology audit
Not-tested: external Maven publication consumer; 별도 #409 범위
```

## 계획 자체 검토

- [x] spec의 contract-first Gradle/import 변경은 Task 1~2, V19 table/index readiness와 회귀는 Task 3~4, README/ADR ownership·rollback·용어는 Task 5, 모듈/Flyway/benchmark/performance 검증은 Task 6, module별 7-Tier review artifact는 Task 7, live issue/provenance/Lore commit은 Task 8에서 각각 추적된다.
- [x] 다음 미완성 표현 scan에서 계획 문서에 실행 미정 표현이 남지 않는지 확인한다.

```bash
bad_token_1=TO"DO"; bad_token_2=TB"D"
! rg -n "\\b(${bad_token_1}|${bad_token_2})\\b" \
  docs/superpowers/plans/2026-08-25-issue-393-outbox-ownership-plan.ko.md
```

- [x] type consistency를 재확인한다: `CancellationReasonCode`는 `io.bluetape4k.clinic.appointment.commitment`에서 import하고, `AppointmentMessagingContext`는 messaging package에 남기며, `WaitlistNotificationOutboxEvents`는 event waitlist package에서 readiness로 import한다. index names는 adapter와 V19 SQL의 exact names와 일치한다.
- [x] `git diff --check`와 Korean terminology audit command의 target list가 Task 5/6과 동일한 7개 문서를 가리키는지 확인한다.

## 최종 DoD

- [x] #393의 messaging event persistence API leakage가 compile fixture와 Gradle expected scope에서 제거된다.
- [x] V19 waitlist table/index readiness가 missing/UP 상태를 정확히 판정하고 관련 테스트가 통과한다.
- [x] event/messaging/notification 6개 README와 ADR-15가 실제 source/Gradle ownership을 설명한다.
- [x] ADR-15에 dependency/fixture rollback, readiness/test-fixture rollback, SQL/runtime 무변경으로 schema rollback이 불필요하다는 운영 경로가 기록된다.
- [x] Korean terminology audit `findings=0`과 `git diff --check` 증거가 있다.
- [x] event/messaging/notification 모듈 테스트, API Flyway migration, API consumer fixture가 fresh run에서 `BUILD SUCCESSFUL`이다.
- [x] strict dependency locking readback에서 root와 benchmark module `gradle.lockfile`이 실제 API/compile graph와 일치하고, 변경 범위가 R2DBC 좌표 4개의 해당 configuration suffix 제거로 한정된다.
- [x] `:appointment-messaging-benchmark:compileKotlin`과 readiness source-path/performance review가 통과하고 `1 + globalConcurrency` 상한 근거가 review artifact에 기록된다.
- [x] 구현 review artifact의 module별 7-Tier 표에서 P0/P1이 0이고, P2/P3 disposition과 후속 Issue 링크가 모두 기록된다.
- [x] Issue #393/#409 live metadata readback이 완료되고 #407의 11개 native child/train 순서는 변하지 않는다.
- [x] 이 계획의 구현·검증은 #393 child에만 적용한다. PR 생성은 train 순서가 실제 branch/base에 정렬된 뒤 수행할 수 있지만, merge와 최종 승인 요청은 #392~#402 전체 child 완료 후 한 번만 수행한다.
