# Issue #409 알림 event contract와 persistence contract 분리 구현 계획

> **에이전트 실행자:** 이 계획은 승인된 명세를 기준으로 작업 단위마다 테스트를 먼저 작성하고, 각 단위를 검증한 뒤 Lore commit protocol에 따라 커밋한다. production code를 수정하기 전에 `$test-driven-development`와 `$bluetape-kotlin-patterns`를 다시 읽는다.

**목표:** `appointment-event`에는 순수 notification event write contract만 남기고, `appointment-notification.persistence`가 notification table·JDBC repository·claim lifecycle을 소유하도록 바꾸면서 기존 schema·transaction·lease semantics와 호출자 호환성 migration을 증명한다.

**구조:** event module은 `NotificationOutboxWriter`, opaque write receipt, envelope/codec/hasher와 draft/value object만 제공한다. notification module은 JDBC persistence와 worker lifecycle을 같은 module 안에서 조립하고 `NotificationOutboxWriter`를 구현한다. API는 concrete repository가 아닌 port를 주입받으며, root Gradle event/notification consumer fixture와 jar/source guard가 양방향 경계를 고정한다.

**기술 스택:** Kotlin 2.3, Java 25, Spring Boot 4, Exposed 1 JDBC, Gradle Kotlin DSL, JUnit 5, MockK, `io.bluetape4k.assertions`, `io.bluetape4k.junit5` singleton/concurrency launcher, `Base58`, Flyway, Kafka4, Resilience4j, Redis 8.8.

---

## 승인 기준과 작업 경계

- 승인 명세: [2026-08-26-issue-409-notification-contract-boundary-design.ko.md](../specs/2026-08-26-issue-409-notification-contract-boundary-design.ko.md)
- 명세 2-R: [2026-08-26-issue-409-notification-contract-boundary-spec-review.ko.md](../reviews/2026-08-26-issue-409-notification-contract-boundary-spec-review.ko.md)
- 기준 `develop`: `8d68b1e3bc8c944bc1ba1f9e6e8233417d23cff8`
- 계획 작성 기준 HEAD: 현재 `refactor/issue-409-contract-boundary` worktree의 `85fea437`
- 대상 Issue: [#409](https://github.com/bluetape4k/clinic-appointment/issues/409)
- 새 module·외부 dependency·Flyway migration SQL 변경은 허용하지 않는다.
- root worktree의 기존 `frontend/appointment-frontend/angular.json`, `.superpowers/`, `.workflow-inputs/` 변경은 건드리지 않는다.
- 보안 lease token과 durable recovery checkpoint UUID는 유지한다. 새 H2/topic/test suffix에만 `Base58.randomString(8)`을 사용한다.
- 모든 Exposed 접근은 호출자 `transaction {}` 안에서 실행하고, `@Testcontainers` 대신 저장소의 singleton launcher를 사용한다.

## 파일 책임 지도

| 책임 | 생성·이동·수정 파일 | 계획상 보장 |
|---|---|---|
| 순수 event write contract | `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationOutboxWriter.kt` 생성 | `NotificationOutboxWriter`, `NotificationOutboxWriteReceipt`, `SendableNotificationDraft`, `LegacySuppressionDraft`를 Exposed 없이 제공한다. |
| 순수 event 공통 타입 | `appointment-event/.../notification/NotificationOutboxContracts.kt` 수정 | row/status enum과 DB timestamp helper를 제거하고 channel/event/failure/value object만 유지한다. |
| notification persistence 타입 | `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/persistence/NotificationOutboxPersistenceTypes.kt` 생성 | row kind/status/attempt outcome와 JDBC timestamp 정규화 helper를 소유한다. |
| notification outbox table/query | `appointment-event/.../notification/NotificationOutboxEvents.kt` → `appointment-notification/.../notification/persistence/NotificationOutboxEvents.kt` 이동 | table·column·index·query contract 이름과 순서를 그대로 보존한다. |
| delivery audit table | `appointment-event/.../notification/NotificationDeliveryAttempts.kt` → `appointment-notification/.../notification/persistence/NotificationDeliveryAttempts.kt` 이동 | V14의 audit column과 FK를 변경하지 않는다. |
| JDBC repository | `appointment-event/.../notification/NotificationOutboxRepository.kt` → `appointment-notification/.../notification/persistence/JdbcNotificationOutboxRepository.kt` 이동·분리 | concrete class 이름과 package에 persistence 소유권을 표시하고, 기존 claim/retry/retention SQL을 그대로 유지한다. |
| waitlist event contract | `appointment-event/.../waitlist/WaitlistNotificationOutboxContracts.kt` 생성 | envelope·codec·deterministic key·contract exception만 Exposed 없이 제공한다. |
| waitlist persistence | `appointment-notification/.../notification/persistence/WaitlistNotificationOutboxPersistence.kt` 생성 | status·row·record·sink·table·adapter·repository를 소유한다. |
| notification worker wiring | `appointment-notification/.../notification/NotificationAutoConfiguration.kt`, `NotificationOutboxWorkStore.kt`, `NotificationOutboxWorker.kt`, `NotificationOutboxDispatcher.kt`, `NotificationOutboxConcurrencyCoordinator.kt`, `NotificationDirectOutboxDelivery.kt`, `NotificationOutboxAlertPolicy.kt`, `NotificationOutboxMetrics.kt`, `NotificationRetentionRunner.kt`, `NotificationSchemaReadiness.kt`, `NotificationStatusQueryService.kt`, `WaitlistOfferNotificationStore.kt` 수정 | persistence import를 새 package로 통일하고 worker runtime semantics를 보존한다. |
| API contract wiring | `appointment-api/.../notification/AppointmentNotificationWriter.kt`, `JdbcAppointmentReminderRecoveryStore.kt`, `ServiceConfig.kt`, `DatabaseConfig.kt` 수정 | public constructor는 event port만 참조하고, auto-configuration이 concrete implementation을 조립한다. |
| API/test migration | `appointment-api/src/test/kotlin/.../notification/*`, `.../migration/NotificationOutboxMigrationTestSupport.kt`, `.../config/SchemaInitConfigTest.kt`, `.../KotlinProductionPatternComplianceTest.kt` 수정 | fake writer, moved table import, source path guard, checkpoint DB clock를 검증한다. |
| persistence tests | `appointment-event/src/test/.../NotificationOutboxRepositoryTest.kt`, `NotificationOutboxConcurrencyTest.kt`, `NotificationCodecBacklogBenchmarkTest.kt`, `waitlist/WaitlistNotificationOutboxAdapterTest.kt` → notification persistence test tree 이동 | repository/adapter 동작과 negative/동시성/benchmark가 event artifact를 필요로 하지 않게 한다. |
| event fixture | `src/consumerFixture/event/kotlin/io/bluetape4k/clinic/appointment/consumer/EventNotificationContractConsumerFixture.kt` 생성 | event jar에서 순수 contract만 compile한다. |
| root fixture graph | `build.gradle.kts` 수정 | event fixture configuration/target/scope/inventory와 event jar/source guard를 등록한다. |
| public 문서 | `docs/requirements/architecture.md`, `appointment-event/README.md`, `appointment-event/README.ko.md`, `appointment-notification/README.md`, `appointment-notification/README.ko.md`, `appointment-api/README.md`, `appointment-api/README.ko.md` 수정 | ADR-15, source path, API migration과 persistence 소유권을 실제 코드와 맞춘다. |
| delivery artifact | `docs/superpowers/lessons/2026-08-26-issue-409-notification-contract-boundary.ko.md`, Issue #409, PR body 생성·갱신 | 7-Tier review, 후속 Issue, Korean `## DoD Status`를 남긴다. |

## 의존성과 실행 순서

```text
Task 0 baseline
  -> Task 1 RED contract/fixture
  -> Task 2 event pure contract
  -> Task 3 notification persistence move
  -> Task 4 notification worker/auto-configuration
  -> Task 5 API port wiring
  -> Task 6 tests and ecosystem reuse
  -> Task 7 Gradle API/source/jar guards
  -> Task 8 schema/migration and documentation
  -> Task 9 module verification and 7-Tier/performance review
  -> Task 10 lesson, issue, PR and exact-head delivery
```

각 화살표의 앞 단계가 compile 가능한 상태를 만들기 전에는 뒤 단계의 implementation
test를 시작하지 않는다. 파일 이동은 `git mv` 후 package/import를 즉시 고쳐 rename
중간 상태를 commit하지 않는다.

## Task 0: baseline과 불변 증적 고정

**Files:** 수정 없음. 검증 결과만 `.omx` 상태와 작업 로그에 기록한다.

- [ ] **Step 1: worktree와 기준 SHA 확인**

```bash
git status --short --branch
git rev-parse HEAD origin/develop
git worktree list --porcelain
```

예상: 현재 worktree가 `refactor/issue-409-contract-boundary`이고, root의 무관한
dirty path를 제외하면 이 worktree는 clean이며 `origin/develop`은
`8d68b1e3bc8c944bc1ba1f9e6e8233417d23cff8`이다.

- [ ] **Step 2: baseline compile/test 실행**

```bash
./gradlew :appointment-event:test :appointment-notification:test --no-daemon --console=plain
```

예상: `BUILD SUCCESSFUL`. 실패하면 implementation을 시작하지 않고 실패한 test와
현재 SHA를 계획 실행 로그에 기록한 뒤 원인별로 복구한다.

- [ ] **Step 3: schema SQL과 source inventory fingerprint 저장**

```bash
sha256sum appointment-api/src/main/resources/db/migration/{h2,mysql,postgresql}/V14__add_notification_outbox.sql \
  appointment-api/src/main/resources/db/migration/{h2,mysql,postgresql}/V19__add_waitlist_delivery.sql \
  appointment-api/src/main/resources/db/migration/{h2,mysql,postgresql}/V21__add_tenant_query_isolation.sql \
  appointment-api/src/main/resources/db/migration/{h2,mysql,postgresql}/V22__add_appointment_messaging_outbox_lease.sql
git diff --check
```

예상: migration 파일은 이후에도 diff가 없고, checksum은 Task 8에서 동일하게
재확인한다.

- [ ] **Step 4: Lore commit**

```bash
git add docs/superpowers/plans/2026-08-26-issue-409-notification-contract-boundary-plan.ko.md
git commit -m "알림 contract 분리 구현 계획을 고정한다" -m "승인된 명세를 파일별 TDD 순서와 검증 증적으로 분해한다.

Constraint: schema·runtime semantics와 Korean-only artifact 정책을 유지해야 한다
Rejected: 전체 repository를 한 번에 재구성하는 방식 | API 경계와 회귀 원인을 분리할 수 없어 배제
Confidence: high
Scope-risk: broad
Directive: 각 Task는 RED 증적과 module-scoped verification을 남긴다
Tested: baseline source inventory와 계획 문서 diff check
Not-tested: production source는 후속 Task에서 수정·검증한다"
```

## Task 1: RED event contract와 consumer fixture 작성

**Files:**

- Create: `appointment-event/src/test/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationOutboxWriterContractTest.kt`
- Create: `src/consumerFixture/event/kotlin/io/bluetape4k/clinic/appointment/consumer/EventNotificationContractConsumerFixture.kt`
- Modify: `build.gradle.kts` — event fixture 등록을 위한 최소 RED wiring

- [ ] **Step 1: contract compile test 작성**

```kotlin
package io.bluetape4k.clinic.appointment.event.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test

class NotificationOutboxWriterContractTest {
    @Test
    fun `writer receipt는 opaque id만 노출한다`() {
        val receipt = NotificationOutboxWriteReceipt(7L)

        receipt.id shouldBeEqualTo 7L
        NotificationOutboxWriteReceipt::class.java.declaredFields
            .map { it.name }
            .none { it in setOf("status", "rowKind", "leaseToken", "attemptNumber", "resultRow") }
            .shouldBeTrue()
    }

    private class RecordingWriter : NotificationOutboxWriter {
        override fun enqueue(draft: SendableNotificationDraft): NotificationOutboxWriteReceipt =
            NotificationOutboxWriteReceipt(1L)

        override fun suppressLegacy(draft: LegacySuppressionDraft): NotificationOutboxWriteReceipt =
            NotificationOutboxWriteReceipt(2L)

        override fun containsIdempotency(digest: NotificationIdempotencyDigest): Boolean = false

        override fun suppressOutstandingReminders(
            tenantGroupId: TenantGroupId,
            clinicId: ClinicId,
            appointmentId: AppointmentId,
            suppressionReason: NotificationSuppressionReasonCode,
        ): Int = 0
    }
}
```

이 테스트는 새 port와 receipt가 아직 없는 상태를 의도적으로 먼저 참조한다. generic
JUnit assertion은 허용하지 않고 io.bluetape4k.assertions vocabulary를 사용한다.

- [ ] **Step 2: event consumer fixture 작성**

```kotlin
package io.bluetape4k.clinic.appointment.consumer

import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxCodec
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxEnvelope
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxHasher
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxWriteReceipt
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxWriter
import io.bluetape4k.clinic.appointment.event.notification.SendableNotificationDraft
import kotlin.reflect.KClass

private val pureEventTypes: List<KClass<*>> = listOf(
    NotificationOutboxWriter::class,
    NotificationOutboxWriteReceipt::class,
    SendableNotificationDraft::class,
    NotificationOutboxEnvelope::class,
    NotificationOutboxCodec::class,
    NotificationOutboxHasher::class,
)

private fun eventContractType(writer: NotificationOutboxWriter): KClass<*> =
    NotificationOutboxWriter::class

private val noPersistenceAnchor: List<KClass<*>> = pureEventTypes
```

fixture는 NotificationOutboxEvents, NotificationDeliveryAttempts,
JdbcNotificationOutboxRepository, ClaimedNotification,
CompleteNotificationCommand를 import하지 않는다.

- [ ] **Step 3: RED 검증 실행**

```bash
./gradlew :appointment-event:test --tests \
  "io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxWriterContractTest" \
  --no-daemon --console=plain
```

예상: new type/port가 아직 없어 compile failure가 발생한다. 이 failure가 확인되면
Task 2로 진행하고, unrelated baseline failure이면 수정하지 않고 분리 기록한다.

- [ ] **Step 4: Lore commit**

```bash
git add appointment-event/src/test/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationOutboxWriterContractTest.kt \
  src/consumerFixture/event/kotlin/io/bluetape4k/clinic/appointment/consumer/EventNotificationContractConsumerFixture.kt \
  build.gradle.kts
git commit -m "알림 event write contract의 RED 증적을 추가한다" -m "concrete persistence 타입을 참조하지 않는 event consumer와 opaque receipt 계약을 먼저 고정한다.

Constraint: TDD RED를 production move보다 먼저 남긴다
Rejected: 기존 repository를 fake port 이름으로만 alias하는 방식 | event persistence leakage를 계속 허용하므로 배제
Confidence: high
Scope-risk: moderate
Directive: 다음 Task에서 이 RED를 최소 contract 구현으로 녹색화한다
Tested: targeted compile failure가 새 contract 부재를 가리키는지 확인
Not-tested: persistence move와 worker compile은 아직 실행하지 않는다"
```

## Task 2: event module을 순수 contract로 정리

**Files:**

- Create: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationOutboxWriter.kt`
- Create: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/waitlist/WaitlistNotificationOutboxContracts.kt`
- Modify: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationOutboxContracts.kt`
- Modify: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/waitlist/WaitlistNotificationOutboxAdapter.kt`

- [ ] **Step 1: 최소 writer port와 receipt 구현**

```kotlin
package io.bluetape4k.clinic.appointment.event.notification

import java.io.Serializable

/** caller transaction에서 notification persistence에 기록을 요청하는 최소 event port다. */
interface NotificationOutboxWriter {
    fun enqueue(draft: SendableNotificationDraft): NotificationOutboxWriteReceipt

    fun suppressLegacy(draft: LegacySuppressionDraft): NotificationOutboxWriteReceipt

    fun containsIdempotency(digest: NotificationIdempotencyDigest): Boolean

    fun suppressOutstandingReminders(
        tenantGroupId: TenantGroupId,
        clinicId: ClinicId,
        appointmentId: AppointmentId,
        suppressionReason: NotificationSuppressionReasonCode,
    ): Int
}

/** persistence 상태를 event caller에 전달하지 않는 opaque write result다. */
open class NotificationOutboxWriteReceipt(
    open val id: Long,
) : Serializable {
    init {
        require(id > 0L) { "id must be positive" }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** 새로 발송 가능한 알림을 기록하기 위한 event draft다. */
data class SendableNotificationDraft(
    val envelope: NotificationOutboxEnvelope,
    val idempotencyDigest: NotificationIdempotencyDigest,
    val auditFingerprint: NotificationAuditFingerprint,
    val providerKey: String,
) : Serializable {
    init {
        validateDurableOpaqueString(providerKey, "providerKey", 128)
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** legacy 예약의 회원 ID 누락을 발송하지 않는 terminal row로 기록하는 event draft다. */
data class LegacySuppressionDraft(
    val idempotencyDigest: NotificationIdempotencyDigest,
    val auditFingerprint: NotificationAuditFingerprint,
    val tenantGroupId: TenantGroupId,
    val clinicId: ClinicId,
    val eventId: NotificationEventId,
    val suppressionReason: NotificationSuppressionReasonCode,
    val availableAt: java.time.Instant,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
```

`NotificationOutboxWriteReceipt`는 data class로 만들지 않아 concrete persistence
`NotificationOutboxRecord`가 `: NotificationOutboxWriteReceipt(id)`로 covariant return을
구현할 수 있게 한다. `NotificationOutboxRecord`의 상태·row kind 필드는 persistence
package에 남긴다.

- [ ] **Step 2: event 공통 파일의 최종 제거를 Task 3에 예약**

이 단계에서는 NotificationOutboxRowKind, NotificationOutboxStatus,
toNotificationDbInstant를 기존 위치에 임시 유지해 event compile을 깨뜨리지 않는다.
channel/event/failure/suppression enum, value object, NotificationContractException,
low-risk metadata validation은 그대로 둔다. SendableNotificationDraft와
LegacySuppressionDraft는 기존 repository 파일에서 삭제하고 Step 1 파일의 동일한
constructor/property/UID로 이동한다. 임시 persistence 선언과 helper는 Task 3의 물리
이동과 동일한 변경에서 제거하며, 그 전에는 consumer fixture가 이 타입을 import하지
않는다는 source guard만 먼저 적용한다.

- [ ] **Step 3: waitlist event/persistence split의 RED-safe skeleton 작성**

`WaitlistNotificationOutboxAdapter.kt`에서 다음 순수 선언을
`WaitlistNotificationOutboxContracts.kt`로 이동한다.

- `WaitlistNotificationOutboxEnvelope`와 `CURRENT_SCHEMA_VERSION`
- `WaitlistNotificationOutboxCodec`와 private JSON projection
- `WaitlistNotificationOutboxKeys`의 deterministic SHA-256 key 생성
- `WaitlistNotificationOutboxContractException`

기존 package와 public 이름, canonical JSON field 순서, UTF-8 size/duplicate key/trailing
token/schema validation을 그대로 유지한다. 새 event 파일의 import에는
`org.jetbrains.exposed`가 없어야 한다.

- [ ] **Step 4: event module compile 확인**

```bash
./gradlew :appointment-event:compileKotlin --no-daemon --console=plain
```

예상: 기존 persistence 선언이 임시로 남아 있으므로 event module compile이 성공한다.
실패하면 pure waitlist codec/writer 변경과 unrelated baseline을 분리하고, Task 3의
물리 이동 전에는 다음 단계로 진행하지 않는다.

- [ ] **Step 5: Lore commit**

```bash
git add appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationOutboxWriter.kt \
  appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationOutboxContracts.kt \
  appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/waitlist/WaitlistNotificationOutboxContracts.kt \
  appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/waitlist/WaitlistNotificationOutboxAdapter.kt
git commit -m "알림 event write contract의 skeleton을 고정한다" -m "persistence 물리 이동 전에도 port와 waitlist payload 경계를 먼저 검증할 수 있게 한다.

Constraint: 기존 payload schema와 source compatibility migration을 보존해야 한다
Rejected: event package에 concrete repository facade를 남기는 방식 | 역의존과 가짜 구현을 만들므로 배제
Confidence: high
Scope-risk: broad
Directive: persistence 선언의 최종 제거와 lifecycle 이동은 Task 3에서 함께 수행한다
Tested: writer RED contract source와 waitlist codec compile boundary
Not-tested: moved repository compile과 최종 event jar boundary는 후속 Task에서 검증한다"
```

## Task 3: notification persistence package로 table/repository 이동

**Files:**

- Create: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/persistence/NotificationOutboxPersistenceTypes.kt`
- Move/modify: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationOutboxEvents.kt` → `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/persistence/NotificationOutboxEvents.kt`
- Move/modify: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationDeliveryAttempts.kt` → `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/persistence/NotificationDeliveryAttempts.kt`
- Move/modify: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationOutboxRepository.kt` → `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/persistence/JdbcNotificationOutboxRepository.kt`
- Create/modify: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/persistence/WaitlistNotificationOutboxPersistence.kt`

- [ ] **Step 1: physical package 생성과 파일 이동**

```bash
mkdir -p appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/persistence
git mv appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationOutboxEvents.kt \
  appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/persistence/NotificationOutboxEvents.kt
git mv appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationDeliveryAttempts.kt \
  appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/persistence/NotificationDeliveryAttempts.kt
git mv appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationOutboxRepository.kt \
  appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/persistence/JdbcNotificationOutboxRepository.kt
```

각 이동 파일의 package 선언을
`io.bluetape4k.clinic.appointment.notification.persistence`로 바꾼다. repository class
이름은 `NotificationOutboxRepository`에서 `JdbcNotificationOutboxRepository`로
바꾸고, public 메서드 이름·parameter·SQL predicate·index order는 바꾸지 않는다.

- [ ] **Step 2: persistence type 파일 정리**

`NotificationOutboxPersistenceTypes.kt`에 다음 선언을 이동한다.

```kotlin
package io.bluetape4k.clinic.appointment.notification.persistence

import java.time.Instant

enum class NotificationOutboxRowKind { SENDABLE, LEGACY_SUPPRESSION }

enum class NotificationOutboxStatus { PENDING, PROCESSING, RETRY_WAIT, SENT, SUPPRESSED, EXHAUSTED }

enum class NotificationDeliveryAttemptOutcome { SUCCESS, RETRY_SCHEDULED, SUPPRESSED, EXHAUSTED, LEASE_LOST }

internal fun Any?.toNotificationDbInstant(): Instant = when (this) {
    is Instant -> this
    is java.sql.Timestamp -> toInstant()
    is java.time.OffsetDateTime -> toInstant()
    is java.time.ZonedDateTime -> toInstant()
    is java.time.LocalDateTime -> toInstant(java.time.ZoneOffset.UTC)
    else -> error("Unsupported CURRENT_TIMESTAMP type: ${this?.javaClass?.name}")
}
```

기존 enum의 순서와 serialization UID를 보존하고, repository의
`private fun JdbcTransaction.dbCurrentTimestamp()`가 이 helper를 사용하게 한다.
`NotificationOutboxRecord`는 event의 `NotificationOutboxWriteReceipt`를 상속한다.

- [ ] **Step 3: repository pure draft 제거와 persistence DTO 유지**

JdbcNotificationOutboxRepository.kt에서 SendableNotificationDraft와
LegacySuppressionDraft 선언만 삭제하고 Task 2의 event file에서 import한다.
NotificationClinicKey, NotificationFairCursor, NotificationCandidate,
NotificationOutboxObservation, ClaimedNotification, NotificationOutboxRecord,
CompleteNotificationCommand, RetryNotificationCommand는 concrete persistence DTO/command로
유지하되 package를 persistence로 고정한다.
`NotificationOutboxRecord` 선언은 다음 형태로 바꾼다.

```kotlin
data class NotificationOutboxRecord(
    override val id: Long,
    val rowKind: NotificationOutboxRowKind,
    val status: NotificationOutboxStatus,
    val tenantGroupId: TenantGroupId,
    val clinicId: ClinicId,
    val eventId: NotificationEventId,
    val appointmentId: AppointmentId?,
    val memberId: MemberId?,
    val templateKey: NotificationTemplateKey?,
    val parametersJson: String?,
    val providerKey: String?,
    val attemptNumber: Int,
) : NotificationOutboxWriteReceipt(id), Serializable
```

`JdbcNotificationOutboxRepository`는 `NotificationOutboxWriter`를 구현한다. `enqueue`,
`suppressLegacy`, `containsIdempotency`, `suppressOutstandingReminders`의 기존 SQL과
caller transaction 조건을 보존하고, `currentDatabaseTime()`은 worker 내부 persistence
API로만 남긴다.

- [ ] **Step 4: waitlist persistence file 구성**

`WaitlistNotificationOutboxPersistence.kt`에는 기존 adapter file에서 다음 선언을
옮기고 package를 persistence로 바꾼다.

- `WaitlistNotificationOutboxStatus`
- `WaitlistNotificationOutboxSink`
- `WaitlistNotificationOutboxRow`, `WaitlistNotificationOutboxRecord`
- `WaitlistNotificationOutboxAdapter`
- `WaitlistNotificationOutboxEvents`
- `WaitlistNotificationOutboxRepository`

adapter의 constructor가 event package의 `WaitlistNotificationOutboxCodec`를 받고,
row/table/repository만 persistence import를 사용하게 한다. repository 시작부의
`TransactionManager.current()` guard와 `upsert` key `(tenantGroupId, clinicId,
idempotencyKey)`를 보존한다. 모든 row field/column/index 이름은 V19 SQL과 동일해야 한다.

- [ ] **Step 5: persistence package transaction negative test를 RED→GREEN으로 고정**

새 테스트 `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/persistence/NotificationPersistenceTransactionContractTest.kt`를 만든다.

```kotlin
package io.bluetape4k.clinic.appointment.notification.persistence

import io.bluetape4k.assertions.assertFailsWith
import org.junit.jupiter.api.Test

class NotificationPersistenceTransactionContractTest {
    @Test
    fun `repository는 caller transaction이 없으면 실패한다`() {
        val repository = JdbcNotificationOutboxRepository(
            codec = io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxCodec(),
            leaseDuration = java.time.Duration.ofMinutes(5),
        )

        assertFailsWith<IllegalStateException> {
            repository.containsIdempotency(
                io.bluetape4k.clinic.appointment.event.notification.NotificationIdempotencyDigest(
                    keyId = "active-key", version = 1, value = "digest-1",
                ),
            )
        }
    }
}
```

실제 persistence helper가 `TransactionManager.current()`를 호출하도록 구현하고,
실패 예외가 바뀌면 assertion vocabulary만 유지하면서 원인을 기록한다.

- [ ] **Step 6: module compile**

```bash
./gradlew :appointment-event:compileKotlin :appointment-notification:compileKotlin \
  --no-daemon --console=plain
```

예상: 새 package와 persistence DTO가 서로 compile되고, 아직 이전 package를
가리키는 worker/API import만 남은 경우에는 다음 Task의 caller migration error로
한정된다.

- [ ] **Step 7: Lore commit**

```bash
git add appointment-event/src/main/kotlin appointment-notification/src/main/kotlin appointment-notification/src/test/kotlin
git commit -m "알림 persistence를 notification module로 이동한다" -m "table·JDBC repository·claim lifecycle과 waitlist row를 notification persistence가 소유하게 한다.

Constraint: V14/V19 schema, query plan, transaction과 lease fence를 바꾸지 않는다
Rejected: event에 persistence DTO를 호환 alias로 남기는 방식 | event jar 경계를 다시 오염시키므로 배제
Confidence: high
Scope-risk: broad
Directive: 모든 worker/API import는 새 persistence package를 통해서만 접근한다
Tested: persistence transaction negative test; event/notification compile boundary
Not-tested: Spring auto-configuration과 API wiring은 후속 Task에서 검증한다"
```

## Task 4: notification worker와 auto-configuration 이관

**Files:**

- appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationAutoConfiguration.kt
- NotificationOutboxWorkStore.kt, NotificationOutboxWorker.kt, NotificationOutboxDispatcher.kt
- NotificationOutboxConcurrencyCoordinator.kt, NotificationDirectOutboxDelivery.kt
- NotificationOutboxAlertPolicy.kt, NotificationOutboxMetrics.kt, NotificationRetentionRunner.kt
- NotificationSchemaReadiness.kt, NotificationStatusQueryService.kt, WaitlistOfferNotificationStore.kt
- 위 파일을 참조하는 notification 테스트

- [ ] **Step 1: import와 공개 생성자 사용처를 행렬로 고정**

```bash
rg -n "event\\.(notification|waitlist)|NotificationOutboxRepository|NotificationOutboxEvents|NotificationDeliveryAttempts|ClaimedNotification|CompleteNotificationCommand|RetryNotificationCommand" \
  appointment-notification/src/main appointment-notification/src/test
```

순수 event 타입(NotificationOutboxCodec, envelope, hasher, draft)은 event package에서
계속 가져오고, table·row·claim·retry·retention 타입은
io.bluetape4k.clinic.appointment.notification.persistence로 일괄 변경한다.

- [ ] **Step 2: auto-configuration에서 concrete repository와 port를 조립**

기존 class-level 조건, Database 조건, bean 누락 조건과 destroy lifecycle을 보존하면서
다음 bean을 추가한다.

```kotlin
@Bean
@ConditionalOnMissingBean(JdbcNotificationOutboxRepository::class)
fun notificationOutboxRepository(
    codec: NotificationOutboxCodec,
    properties: NotificationProperties,
): JdbcNotificationOutboxRepository = JdbcNotificationOutboxRepository(
    codec = codec,
    leaseDuration = properties.worker.validate().leaseDuration,
)

@Bean
@ConditionalOnMissingBean(NotificationOutboxWriter::class)
fun notificationOutboxWriter(
    repository: JdbcNotificationOutboxRepository,
): NotificationOutboxWriter = repository
```

기존 NotificationOutboxCodec bean은 event contract 조립용으로 유지한다. worker 내부
store와 dispatcher는 concrete persistence repository를 사용하되, API 조립 지점은 port
alias를 사용한다. ConditionalOnMissingBean 검색 대상이 port와 구현체에서 서로
충돌하지 않는지 Spring context test로 확인한다.

- [ ] **Step 3: readiness와 retention 경계 확인**

NotificationSchemaReadiness의 bounded probe와 fail-closed 동작을 보존하고 table 목록은
새 persistence package의 NotificationOutboxEvents와 NotificationDeliveryAttempts를
참조한다. retention runner와 metrics는 claim 상태 enum을 persistence package에서
가져오며, polling/backoff/lease fence를 새 round trip 없이 유지한다.

- [ ] **Step 4: notification module 검증**

```bash
./gradlew :appointment-notification:compileKotlin :appointment-notification:test \
  --no-daemon --console=plain
```

예상: auto-configuration context, worker claim/retry/retention, waitlist store가
통과하고, 실패 시 이전 package import·bean 조건·lifecycle 중 어느 경계인지 로그로
분류한다.

- [ ] **Step 5: Lore commit**

```bash
git add appointment-notification/src/main appointment-notification/src/test
git commit -m "알림 worker가 notification persistence를 조립하게 한다" -m "worker lifecycle은 concrete persistence에 남기고 공개 조립 경계는 event port로 연결한다.

Constraint: Spring 조건부 bean, lease fence, polling과 retry semantics를 유지해야 한다
Rejected: worker마다 repository를 직접 생성하는 방식 | bean lifecycle과 테스트 대체 지점을 잃으므로 배제
Confidence: high
Scope-risk: broad
Directive: API는 concrete persistence 생성자를 다시 노출하지 않는다
Tested: notification compile, auto-configuration context, worker lifecycle tests
Not-tested: API source/ABI fixture는 다음 Task에서 검증한다"
```

## Task 5: API public constructor와 recovery checkpoint 이관

**Files:**

- appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/notification/AppointmentNotificationWriter.kt
- JdbcAppointmentReminderRecoveryStore.kt, ServiceConfig.kt, DatabaseConfig.kt
- API notification writer/recovery/wiring 테스트

- [ ] **Step 1: public writer를 event port에 고정**

DefaultAppointmentNotificationWriter 생성자를 다음 경계로 변경한다.

```kotlin
class DefaultAppointmentNotificationWriter(
    private val writer: NotificationOutboxWriter,
    private val hasher: NotificationOutboxHasher,
    private val clinicRepository: ClinicRepository,
    private val clock: Clock,
    private val sameDayReminderLeadTime: Duration,
    private val cancellationSchemaVersion: Int = NotificationOutboxEnvelope.LEGACY_SCHEMA_VERSION,
) : AppointmentNotificationWriter
```

기존 repository 호출을 writer.enqueue, writer.suppressLegacy, writer.containsIdempotency,
writer.suppressOutstandingReminders로 바꾸고 public return type에 persistence projection을
노출하지 않는다. draft 생성, idempotency hash, same-day reminder와 cancellation schema
version은 기존 semantics를 유지한다.

- [ ] **Step 2: recovery store의 DB clock와 port 주입**

JdbcAppointmentReminderRecoveryStore는 concrete repository 대신 NotificationOutboxWriter를
받고, checkpoint를 생성할 때 쓰던 UUID는 durable recovery 식별자이므로 유지한다.
currentDatabaseTime()은 API 내부 helper로 이동해 다음 순서로 정규화한다.

```kotlin
private fun currentDatabaseTime(): Instant =
    TransactionManager.current().exec("SELECT CURRENT_TIMESTAMP") { result ->
        result.next()
        when (val value = result.getObject(1)) {
            is Instant -> value
            is java.sql.Timestamp -> value.toInstant()
            is java.time.OffsetDateTime -> value.toInstant()
            is java.time.ZonedDateTime -> value.toInstant()
            is java.time.LocalDateTime -> value.toInstant(ZoneOffset.UTC)
            else -> error("Unsupported CURRENT_TIMESTAMP type: ${value?.javaClass?.name}")
        }
    } ?: error("CURRENT_TIMESTAMP returned no row")
```

이 helper는 API transaction 내부에서만 실행하고, 기존 checkpoint lease/recovery 순서와
취소 시 cleanup을 바꾸지 않는다.

- [ ] **Step 3: ServiceConfig bean 조건과 fail-closed fallback 변경**

codec와 concrete repository bean 생성을 API에서 제거하고 ObjectProvider<NotificationOutboxWriter>와
hasher를 주입한다. writer bean이 없으면 기존 UnavailableAppointmentNotificationWriter
fallback을 사용하되, 실제 persistence를 우회해 성공을 보고하지 않는다. recovery bean
조건은 다음을 포함한다.

```kotlin
@ConditionalOnBean(
    NotificationOutboxWriter::class,
    Database::class,
    NotificationOutboxHasher::class,
)
```

Spring context test에서 custom NotificationOutboxWriter 대체, missing writer,
database missing, hasher missing의 네 가지 경로를 검증한다.

- [ ] **Step 4: schema bootstrap과 API 테스트 이관**

DatabaseConfig와 SchemaInitConfig의 SchemaUtils.createMissingTablesAndColumns 순서는
새 persistence table을 사용하도록 바꾸되 scheduling table과 Flyway 대상은 건드리지
않는다. API 테스트는 concrete repository 대신 RecordingNotificationOutboxWriter를
사용하고, KotlinProductionPatternComplianceTest의 기존 event repository source path
guard를 새 port/persistence 경로로 갱신한다.

- [ ] **Step 5: API targeted verification**

```bash
./gradlew :appointment-api:test --no-daemon --console=plain
```

예상: writer behavior, recovery checkpoint, Spring fallback/condition, schema bootstrap
test가 통과한다. UUID는 durable/security 식별자로 남고 새 test suffix만 Base58로 바뀌었는지
source scan으로 확인한다.

- [ ] **Step 6: Lore commit**

```bash
git add appointment-api/src/main appointment-api/src/test
git commit -m "API 알림 조립 경계를 event port로 고정한다" -m "public writer와 recovery store가 concrete persistence를 참조하지 않고 조건부 port bean을 사용하게 한다.

Constraint: API ABI migration과 fail-closed 동작, durable checkpoint 의미를 보존해야 한다
Rejected: API에서 repository facade를 계속 노출하는 방식 | persistence contract 누출을 해결하지 못하므로 배제
Confidence: high
Scope-risk: moderate
Directive: 이후 caller는 NotificationOutboxWriter만 의존하고 persistence DTO를 확장하지 않는다
Tested: API writer/recovery/context/schema tests
Not-tested: root source/jar boundary는 다음 Task에서 검증한다"
```

## Task 6: persistence 테스트 이동과 bluetape4k 재활용 증명

**Files:**

```text
appointment-event/src/test/.../NotificationOutboxRepositoryTest.kt
  -> appointment-notification/src/test/.../persistence/JdbcNotificationOutboxRepositoryTest.kt
NotificationOutboxConcurrencyTest.kt
  -> JdbcNotificationOutboxConcurrencyTest.kt
NotificationCodecBacklogBenchmarkTest.kt
  -> notification persistence/NotificationCodecBacklogBenchmarkTest.kt
WaitlistNotificationOutboxAdapterTest.kt
  -> notification persistence/WaitlistNotificationOutboxAdapterTest.kt
```

- [ ] **Step 1: 테스트를 소유 module로 이동**

```bash
mkdir -p appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/persistence
git mv appointment-event/src/test/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationOutboxRepositoryTest.kt \
  appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/persistence/JdbcNotificationOutboxRepositoryTest.kt
git mv appointment-event/src/test/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationOutboxConcurrencyTest.kt \
  appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/persistence/JdbcNotificationOutboxConcurrencyTest.kt
git mv appointment-event/src/test/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationCodecBacklogBenchmarkTest.kt \
  appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/persistence/NotificationCodecBacklogBenchmarkTest.kt
git mv appointment-event/src/test/kotlin/io/bluetape4k/clinic/appointment/event/waitlist/WaitlistNotificationOutboxAdapterTest.kt \
  appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/persistence/WaitlistNotificationOutboxAdapterTest.kt
```

package 선언과 concrete constructor 이름을 즉시 변경한다. event test tree에는 순수
codec/hasher/contract test만 남긴다.

- [ ] **Step 2: Base58과 singleton launcher를 실제 fixture에 적용**

새 H2 database/topic/test suffix는 다음처럼 io.bluetape4k.codec.Base58을 사용한다.

```kotlin
private val database = Database.connect(
    "jdbc:h2:mem:notification_outbox_repository_${Base58.randomString(8)};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    driver = "org.h2.Driver",
)
```

System.nanoTime() 기반 suffix는 제거하고, durable/security UUID는 의미 보존을 위해
유지한다. BeforeEach에서 SchemaUtils.createMissingTablesAndColumns(Table)와
Table.deleteAll()을 사용한다. Testcontainers는 추가하지 않고 기존
io.bluetape4k.junit5 singleton launcher, MultithreadingTester, Dispatchers.IO를
재사용한다.

- [ ] **Step 3: transaction·동시성·취소 semantics를 고정**

각 Exposed 호출은 caller transaction 안에서 실행하고, 20 worker contention,
lease-token fence, retry/terminal transition, coroutine cancellation 이후 cleanup을
기존 assertions로 보존한다. assertion은 io.bluetape4k.assertions와 Kluent vocabulary를
사용하며 generic JUnit assertion을 새로 도입하지 않는다.

- [ ] **Step 4: moved persistence tests 실행**

```bash
./gradlew :appointment-notification:test \
  --tests "io.bluetape4k.clinic.appointment.notification.persistence.JdbcNotificationOutboxRepositoryTest" \
  --tests "io.bluetape4k.clinic.appointment.notification.persistence.JdbcNotificationOutboxConcurrencyTest" \
  --tests "io.bluetape4k.clinic.appointment.notification.persistence.WaitlistNotificationOutboxAdapterTest" \
  --no-daemon --console=plain
```

예상: repository CRUD/claim/retry/retention, waitlist upsert/codec, concurrency와
cleanup이 통과한다. 실패하면 package move로 인한 차이와 실제 semantic regression을
분리해 수정한다.

- [ ] **Step 5: Lore commit**

```bash
git add appointment-event/src/test appointment-notification/src/test
git commit -m "알림 persistence 테스트를 소유 module로 옮긴다" -m "repository와 waitlist 회귀·동시성 검증이 event artifact에 의존하지 않게 하고 bluetape4k fixture 재사용을 고정한다.

Constraint: transaction, lease, coroutine cleanup과 테스트 격리 semantics를 유지해야 한다
Rejected: 새 Testcontainers harness를 추가하는 방식 | 저장소 singleton launcher와 실행 비용 정책을 위반하므로 배제
Confidence: high
Scope-risk: moderate
Directive: 새 식별자 fixture는 Base58, durable checkpoint는 UUID라는 경계를 유지한다
Tested: moved persistence tests, concurrency, waitlist adapter, codec backlog benchmark
Not-tested: root API/source/jar boundary는 다음 Task에서 검증한다"
```

## Task 7: Gradle API fixture와 source/jar boundary 고정

**Files:**

- build.gradle.kts
- src/consumerFixture/event/kotlin/io/bluetape4k/clinic/appointment/consumer/EventNotificationContractConsumerFixture.kt
- src/consumerFixture/notification/kotlin/io/bluetape4k/clinic/appointment/consumer/NotificationApiConsumerFixture.kt
- event/notification build.gradle.kts는 scanner 설정이 실제로 부족할 때만 최소 수정
- appointment-api의 ApiDependencyBoundaryContractTest.kt

- [ ] **Step 1: event fixture configuration을 등록**

root Gradle의 기존 core/messaging/notification fixture 패턴을 복제하지 말고, event
module의 직접 API와 live resolution report에서 관찰한 compile scope를 기준으로
event fixture configuration, dependency, compile target, expected scope, inventory와
task graph를 등록한다. fixture에는 NotificationOutboxWriter, receipt, draft, envelope,
codec, hasher만 import하고 persistence class는 언급하지 않는다. expected API scope는
Task 실행 시 출력된 좌표를 고정해 이후 drift를 실패로 만든다.

- [ ] **Step 2: notification fixture의 API anchor를 변경**

NotificationApiConsumerFixture는 API writer가 NotificationOutboxWriter를 통해 조립되는
경로를 compile하고, worker constructor가 공개한 persistence 타입은 실제 transitional
public surface로만 명시한다. event concrete repository import는 제거한다.

- [ ] **Step 3: event jar와 source forbidden entry를 추가**

root에 assertAppointmentEventNotificationBoundary task를 추가한다. event jar에는 다음
entry가 없어야 한다.

```text
io/bluetape4k/clinic/appointment/event/notification/NotificationOutboxEvents.class
io/bluetape4k/clinic/appointment/event/notification/NotificationDeliveryAttempts.class
io/bluetape4k/clinic/appointment/event/notification/NotificationOutboxRepository.class
io/bluetape4k/clinic/appointment/event/notification/JdbcNotificationOutboxRepository.class
io/bluetape4k/clinic/appointment/event/notification/ClaimedNotification.class
io/bluetape4k/clinic/appointment/event/notification/CompleteNotificationCommand.class
io/bluetape4k/clinic/appointment/event/notification/RetryNotificationCommand.class
io/bluetape4k/clinic/appointment/event/notification/NotificationOutboxRecord.class
io/bluetape4k/clinic/appointment/event/notification/NotificationOutboxStatus.class
io/bluetape4k/clinic/appointment/event/notification/NotificationOutboxRowKind.class
io/bluetape4k/clinic/appointment/event/notification/NotificationDeliveryAttemptOutcome.class
io/bluetape4k/clinic/appointment/event/waitlist/WaitlistNotificationOutboxEvents.class
io/bluetape4k/clinic/appointment/event/waitlist/WaitlistNotificationOutboxAdapter.class
io/bluetape4k/clinic/appointment/event/waitlist/WaitlistNotificationOutboxRepository.class
io/bluetape4k/clinic/appointment/event/waitlist/WaitlistNotificationOutboxRow.class
io/bluetape4k/clinic/appointment/event/waitlist/WaitlistNotificationOutboxRecord.class
io/bluetape4k/clinic/appointment/event/waitlist/WaitlistNotificationOutboxStatus.class
```

source scan은 event notification package와 waitlist persistence 선언을 검사하되 scheduling
event integration과 policy 파일은 제외한다. persistence DTO가 이름 변경으로 다시
event artifact에 들어오는 경우도 실패해야 한다.

- [ ] **Step 4: API source dependency contract를 추가**

ApiDependencyBoundaryContractTest에 다음 assertion을 추가한다.

```kotlin
@Test
fun publicAppointmentNotificationWriterUsesEventPortOnly() {
    val source = read(
        "appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/notification/AppointmentNotificationWriter.kt",
    )

    source.contains("event.notification.NotificationOutboxRepository") shouldBeEqualTo false
    source.contains("NotificationOutboxWriter") shouldBeEqualTo true
}
```

assertion은 bluetape4k assertions vocabulary를 사용하고, public source에 persistence
package 경로가 재유입되는 경우를 별도 실패 메시지로 남긴다.

- [ ] **Step 5: fixture와 boundary 검증**

```bash
./gradlew --no-configuration-cache compileModuleConsumerFixtures \
  assertModuleConsumerFixtureApiVariants \
  assertAppointmentEventNotificationBoundary \
  --no-daemon --console=plain
```

예상: event fixture가 persistence 없이 compile되고 notification/API fixture와 jar/source
guard가 통과한다. scope drift나 forbidden entry가 발견되면 implementation commit을
진행하지 않고 Gradle contract를 먼저 고친다.

- [ ] **Step 6: Lore commit**

```bash
git add build.gradle.kts src/consumerFixture appointment-api/src/test
git commit -m "event와 notification Gradle 경계를 fixture로 고정한다" -m "source·jar·consumer fixture가 event pure contract와 notification persistence 소유권을 동시에 검증하게 한다.

Constraint: 실제 public ABI와 live dependency resolution을 기준으로 drift를 차단해야 한다
Rejected: package 이름만 검사하는 방식 | jar 재포장과 DTO 재유입을 놓치므로 배제
Confidence: high
Scope-risk: moderate
Directive: 새 public type은 fixture와 forbidden inventory를 함께 갱신한다
Tested: consumer fixture compile, API variant, event jar/source boundary
Not-tested: migration scanner와 README 반영은 다음 Task에서 검증한다"
```

## Task 8: migration scanner·schema bootstrap·문서 동기화

**Files:**

- appointment-notification/build.gradle.kts
- appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/DatabaseConfig.kt
- appointment-api/src/test/.../migration/NotificationOutboxMigrationTestSupport.kt
- docs/requirements/architecture.md
- appointment-event/README.md, README.ko.md
- appointment-notification/README.md, README.ko.md
- appointment-api/README.md, README.ko.md

- [ ] **Step 1: migration scanner의 재귀 package 탐색 확인**

```bash
./gradlew :appointment-notification:generateMigrations --no-daemon --console=plain
```

현재 tablesPackage가 io.bluetape4k.clinic.appointment.notification을 재귀적으로
탐색해 notification.persistence table을 발견하는지 생성 로그와 결과로 확인한다. 재귀가
보장되지 않는 경우에만 package를 .notification.persistence로 최소 조정하며 생성 SQL과
Flyway migration은 커밋하지 않는다.

- [ ] **Step 2: schema bootstrap과 Flyway regression 검증**

NotificationOutboxMigrationTestSupport와 SchemaInitConfig의 moved table import를 갱신하고
다음 테스트를 실행한다. V14, V19, V21, V22 SQL은 diff가 없어야 한다.

```bash
./gradlew :appointment-api:test --tests \
  "io.bluetape4k.clinic.appointment.api.config.SchemaInitConfigTest" \
  --tests "io.bluetape4k.clinic.appointment.api.migration.FlywayMigrationTest" \
  --tests "io.bluetape4k.clinic.appointment.api.migration.FlywayMySQLMigrationTest" \
  --tests "io.bluetape4k.clinic.appointment.api.migration.FlywayPostgreSQLMigrationTest" \
  --no-daemon --console=plain
```

실패하면 schema name, column/index order, SQL predicate, table discovery 중 원인을
분리한다. scheduling_* schema 이름은 변경하지 않는다.

- [ ] **Step 3: ADR-15와 README를 실제 ownership으로 동기화**

ADR-15에서 durable notification physical/table/write/claim/readiness owner를 notification
persistence로 갱신하고 event는 port/payload만 소유한다고 명시한다. API는 port를 조립하고,
worker의 일부 persistence public constructor는 transitional exception으로 기록하며
후속 API 축소 issue를 링크한다. 세 모듈의 README.md와 README.ko.md 모두 Korean 정책에
맞춰 source path, dependency direction, migration recipe, fail-closed 동작을 설명한다.

- [ ] **Step 4: 문서 품질과 migration checksum 확인**

```bash
sha256sum appointment-api/src/main/resources/db/migration/{h2,mysql,postgresql}/V14__add_notification_outbox.sql \
  appointment-api/src/main/resources/db/migration/{h2,mysql,postgresql}/V19__add_waitlist_delivery.sql \
  appointment-api/src/main/resources/db/migration/{h2,mysql,postgresql}/V21__add_tenant_query_isolation.sql \
  appointment-api/src/main/resources/db/migration/{h2,mysql,postgresql}/V22__add_appointment_messaging_outbox_lease.sql
git diff --check
node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs \
  docs/requirements/architecture.md \
  appointment-event/README.md appointment-event/README.ko.md \
  appointment-notification/README.md appointment-notification/README.ko.md \
  appointment-api/README.md appointment-api/README.ko.md
```

예상: checksum이 Task 0과 같고 diff check 및 Korean terminology audit에서 오류가
없다.

- [ ] **Step 5: Lore commit**

```bash
git add appointment-notification/build.gradle.kts appointment-api/src/main appointment-api/src/test \
  docs/requirements/architecture.md appointment-event/README.md appointment-event/README.ko.md \
  appointment-notification/README.md appointment-notification/README.ko.md \
  appointment-api/README.md appointment-api/README.ko.md
git commit -m "알림 contract ownership과 migration 문서를 동기화한다" -m "실제 table discovery, schema bootstrap과 세 module의 Korean 사용 설명을 구현 경계에 맞춘다.

Constraint: Flyway SQL, scheduling schema와 기존 migration checksum을 보존해야 한다
Rejected: generated migration을 재생성해 SQL을 바꾸는 방식 | schema 계약과 rollback을 불필요하게 흔드므로 배제
Confidence: high
Scope-risk: moderate
Directive: physical table ownership 변경은 ADR·README·scanner evidence와 함께 유지한다
Tested: migration generation, schema/Flyway tests, checksum, Korean terminology audit
Not-tested: full module build와 7-Tier review는 다음 Task에서 검증한다"
```

## Task 9: module-wide verification, 4-P와 7-Tier 구현 검토

**Files:**

- docs/superpowers/reviews/2026-08-26-issue-409-notification-contract-boundary-implementation-review.ko.md
- concrete failure가 확인된 source/test 파일만 수정

- [ ] **Step 1: 전체 module과 fixture 검증**

```bash
./gradlew :appointment-event:build :appointment-notification:build :appointment-api:test \
  --no-daemon --console=plain
./gradlew --no-configuration-cache compileModuleConsumerFixtures \
  assertModuleConsumerFixtureApiVariants assertAppointmentEventNotificationBoundary \
  --no-daemon --console=plain
```

모든 명령의 출력과 exact HEAD를 review artifact에 남긴다. 실패 시 실패한 module/task를
재현한 뒤 원인을 수정하고 같은 명령을 다시 실행한다.

- [ ] **Step 2: 4-P performance/stability 표 작성**

다음 행을 파일·line·명령·결과로 기록한다.

1. DB hot path: SQL/index/lock/round trip, V14/V19 checksum과 repository tests
2. coroutine: IO dispatcher, cancellation, claim 뒤 provider 호출, worker/dispatcher source
3. contention: MultithreadingTester 20 worker와 Redis integration
4. cleanup: Exposed transaction, singleton launcher, Redis/Lettuce close
5. retry/poll: bounded limit, backoff, retention, readiness fail-closed
6. benchmark: moved codec backlog와 Redis benchmark smoke

package move 자체로 performance improvement를 주장하지 않는다. 새 round trip, allocation,
lock 범위, polling/backpressure 회귀가 확인되면 P1로 분류해 구현을 멈춘다.

- [ ] **Step 3: 7-Tier review 결과를 독립 표로 작성**

다음 일곱 관점마다 file:line, 검증 명령, evidence, P0/P1 상태를 기록한다.

1. Performance
2. Stability
3. Security/Data boundary
4. Operator/Ops
5. Developer/API
6. User/caller
7. Integration/Tests

각 관점의 P0/P1은 0이어야 하며, transitional public persistence API는 P2 follow-up으로
중복 확인한 Issue link와 함께 기록한다.

- [ ] **Step 4: 구현 review artifact 품질 검증**

review 문서는 Korean-only 정책, SPW-01..05, KO-01..07을 따르고 다음을 포함한다.

- 기준 SHA, 변경 commit 목록, 실행 명령과 exit evidence
- spec/DoD 항목별 pass/fail 표
- 4-P와 7-Tier 표, P0/P1/P2 분류
- 남은 위험·rollback·후속 Issue 링크

```bash
git diff --check
node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs \
  docs/superpowers/reviews/2026-08-26-issue-409-notification-contract-boundary-implementation-review.ko.md
```

- [ ] **Step 5: Lore commit**

```bash
git add docs/superpowers/reviews/2026-08-26-issue-409-notification-contract-boundary-implementation-review.ko.md
git commit -m "알림 contract 분리 구현의 7-Tier 검토를 기록한다" -m "module build, boundary fixture와 4-P 안정성 증적을 함께 검토해 merge 전 위험을 고정한다.

Constraint: P0/P1 blocker 없이 exact-head evidence를 남겨야 한다
Rejected: 단순 compile 성공만으로 완료를 선언하는 방식 | DB·coroutine·운영 회귀를 놓치므로 배제
Confidence: high
Scope-risk: broad
Directive: P2 follow-up은 Issue link 없이는 완료 상태로 기록하지 않는다
Tested: full module build, consumer fixtures, boundary guard, 4-P, 7-Tier review
Not-tested: GitHub PR CI와 exact-head readback은 다음 Task에서 검증한다"
```

## Task 10: lesson·Issue·PR delivery와 merge-ready 상태

**Files:**

- docs/superpowers/lessons/2026-08-26-issue-409-notification-contract-boundary.ko.md
- Issue #409 body와 중복 확인된 follow-up Issue
- refactor/issue-409-contract-boundary의 Korean PR body

- [ ] **Step 1: lesson을 작성**

lesson에는 reuse-first 판단, event/persistence 경계, Base58 fixture와 UUID 보존 이유,
schema/rollback, 실패했던 가정, 재발 방지 검증을 SPW-01..05와 KO-01..07 형식으로
기록한다.

- [ ] **Step 2: transitional public API follow-up Issue를 중복 확인**

```bash
gh issue list --repo bluetape4k/clinic-appointment --state all --search \
  "notification persistence public API worker contract in:title,body"
```

동일 목적 Issue가 없을 때만 좁은 범위의 Korean Issue를 생성한다. goal/current evidence,
scope/non-scope/completion을 적고 debop assignee와 maintenance/refactor labels를
설정한 뒤 title/body/metadata를 live read-back한다. 기존 Issue가 있으면 링크만 기록한다.

- [ ] **Step 3: Issue #409 completion evidence를 갱신**

Issue body checklist에 spec/plan/review/lesson commit, source·jar·fixture boundary,
module build/test, migration checksum, 7-Tier 결과와 PR 번호를 추가한다. non-goal과
rollback 경계를 유지하고 native parent/subissue hierarchy를 변경하지 않는다.

- [ ] **Step 4: PR 생성 전 검증과 Korean body 준비**

```bash
git status --short --branch
git diff origin/develop...HEAD --stat
git diff --check
gh issue view 409 --repo bluetape4k/clinic-appointment \
  --json number,title,state,assignees,labels,body,url
```

PR body는 Korean으로 작성하고 마지막에 정확히 ## DoD Status heading과 checklist를
둔다. base는 develop, head는 refactor/issue-409-contract-boundary로 고정한다.

- [ ] **Step 5: PR publish와 exact-head CI readback**

```bash
git push -u origin refactor/issue-409-contract-boundary
gh pr create --repo bluetape4k/clinic-appointment --base develop \
  --head refactor/issue-409-contract-boundary \
  --title "아키텍처(notification): event와 persistence contract를 분리한다" \
  --body-file /tmp/issue-409-pr-body.ko.md
gh pr checks <PR> --watch
gh pr view <PR> --json statusCheckRollup,reviews,comments,latestReviews,mergeStateStatus,headRefOid
```

실제 PR 번호와 head SHA를 read-back하고, exact-head CI가 모두 통과하며 unresolved review
thread가 0인지 확인한다. merge는 별도 fresh 승인 없이는 수행하지 않는다.

- [ ] **Step 6: Lore commit와 merge-ready handoff**

```bash
git add docs/superpowers/lessons docs/superpowers/reviews
git commit -m "알림 contract 분리 lesson과 delivery 증적을 남긴다" -m "Issue·PR·7-Tier 결과를 Korean delivery artifact로 묶고 merge 전 상태를 명확히 한다.

Constraint: PR merge는 exact-head CI와 별도 fresh 승인 뒤에만 가능하다
Rejected: CI 이전 자동 merge 또는 auto-merge | 사용자 승인 경계를 침범하므로 배제
Confidence: high
Scope-risk: broad
Directive: merge 시점에는 head·CI·review·thread를 다시 읽는다
Tested: lesson, Issue/PR live readback, exact-head CI and mergeability
Not-tested: merge와 worktree cleanup은 별도 승인 이후에만 수행한다"
```

---

## 계획 자체의 3-R self-review

| 승인 명세/DoD 요구 | 계획 task와 증적 | 판정 |
|---|---|---|
| event에는 순수 write contract만 남김 | Task 1·2 RED/port, Task 7 event fixture·jar/source guard | 통과 |
| persistence가 table/repository/claim lifecycle 소유 | Task 3·4 package move/worker wiring, Task 8 scanner/ADR | 통과 |
| API ABI/source migration | Task 5 port constructor/recovery, Task 7 source fixture, Task 8 README/ADR | 통과 |
| waitlist contract와 persistence 분리 | Task 2·3·6 codec/row 이동, Task 7 forbidden inventory | 통과 |
| transaction/lease/retry/retention semantics | Task 3·4·6·9 negative/concurrency/4-P tests | 통과 |
| bluetape4k ecosystem 재활용 | Task 6 Base58, assertions, singleton launcher, MultithreadingTester, Task 9 evidence | 통과 |
| schema/migration 보존 | Task 0 checksum, Task 8 scanner/Flyway regression | 통과 |
| 문서·KDoc·README·lesson·Issue·PR | Task 2 KDoc, Task 8 ADR/README, Task 9 review, Task 10 lesson/delivery | 통과 |
| 7-Tier와 DoD | Task 9 seven-tier artifact, Task 10 Korean PR DoD | 통과 |

### 구현 순서와 리스크 점검

- Task 1의 RED는 새 port/receipt 부재만 원인으로 확인하고 unrelated baseline failure와
  분리한다.
- Task 2에서 event source를 줄인 뒤 Task 3에서 moved repository와 persistence enum을
  동시에 복구하므로 중간 compile failure를 다음 caller migration으로 넘기지 않는다.
- Task 7의 forbidden inventory는 concrete repository뿐 아니라 claim/retry/row/status와
  waitlist persistence class까지 포함해 이름 변경 우회를 막는다.
- Spring conditional bean, Exposed transaction receiver, coroutine cancellation,
  lock/round-trip, cleanup/backpressure, migration recursion은 각각 Task 4·5·6·8·9의
  명시적 gate다.
- 새로운 module/dependency/schema migration은 만들지 않으며, rollback은 package/import와
  bean wiring을 이전 commit 단위로 되돌리는 것으로 제한한다.
- 구현 중 새 public persistence surface가 남으면 Task 10의 duplicate-check Issue를
  생성하기 전에는 완료로 표시하지 않는다.

### 계획 품질 gate

- 모든 파일 경로와 책임이 책임 지도에 있다.
- 모든 task에 TDD 순서, 성공/실패 조건, 정확한 명령, Lore commit이 있다.
- Kotlin production/test, Spring auto-configuration, Exposed, coroutine, migration,
  performance/stability, README 변형, Korean artifact가 각각 범위에 있다.
- 새 dependency와 생성 SQL을 추가하지 않는 제약이 반복 확인된다.
- 계획은 승인 명세와 2-R 결과의 P2 follow-up을 보존한다.
