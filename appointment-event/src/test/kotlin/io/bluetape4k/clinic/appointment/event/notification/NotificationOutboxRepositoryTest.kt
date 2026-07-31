package io.bluetape4k.clinic.appointment.event.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class NotificationOutboxRepositoryTest {

    private val database = Database.connect(
        "jdbc:h2:mem:notification_outbox_repository_${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        driver = "org.h2.Driver",
    )
    private val codec = NotificationOutboxCodec()
    private val repository = NotificationOutboxRepository(codec = codec, leaseDuration = Duration.ofMinutes(5))

    @BeforeEach
    fun setup() {
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(
                NotificationOutboxEvents,
                NotificationDeliveryAttempts,
            )
            NotificationDeliveryAttempts.deleteAll()
            NotificationOutboxEvents.deleteAll()
        }
    }

    @Test
    fun `같은 idempotency digest는 한 행으로 수렴한다`() {
        transaction(database) {
            val first = repository.enqueue(sendableDraft())
            val second = repository.enqueue(sendableDraft())

            first.id shouldBeEqualTo second.id
            NotificationOutboxEvents.selectAll().count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `legacy suppression은 raw appointment와 member를 저장하지 않는다`() {
        transaction(database) {
            val row = repository.suppressLegacy(legacySuppressionDraft())

            row.rowKind shouldBeEqualTo NotificationOutboxRowKind.LEGACY_SUPPRESSION
            row.status shouldBeEqualTo NotificationOutboxStatus.SUPPRESSED
            row.appointmentId.shouldBeNull()
            row.memberId.shouldBeNull()
            row.templateKey.shouldBeNull()
            row.parametersJson.shouldBeNull()
            row.providerKey.shouldBeNull()
            val stored = NotificationOutboxEvents.selectAll()
                .where { NotificationOutboxEvents.id eq row.id }
                .single()
            stored[NotificationOutboxEvents.channel].shouldBeNull()
            stored[NotificationOutboxEvents.eventType].shouldBeNull()
            stored[NotificationOutboxEvents.notificationSlot].shouldBeNull()
            stored[NotificationOutboxEvents.templateVersion].shouldBeNull()
            stored[NotificationOutboxEvents.parameterType].shouldBeNull()
        }
    }

    @Test
    fun `ready query contracts and indexes stay aligned`() {
        NotificationOutboxIndexes.names() shouldBeEqualTo listOf(
            "uk_notification_outbox_idempotency",
            "idx_notification_outbox_ready_clinic_cursor",
            "idx_notification_outbox_ready_within_clinic",
            "idx_notification_outbox_lease_recovery",
            "idx_notification_outbox_terminal_retention",
            "idx_notification_outbox_pending_oldest",
        )
        NotificationOutboxQueryContracts.readyClinicCursor.indexColumns shouldBeEqualTo
            NotificationOutboxIndexes.readyClinicCursor.columns
        NotificationOutboxQueryContracts.readyClinicCursor.filters shouldBeEqualTo
            NotificationOutboxIndexes.readyClinicCursor.columns
        NotificationOutboxQueryContracts.readyClinicCursor.orderBy shouldBeEqualTo listOf("tenant_group_id", "clinic_id")
        NotificationOutboxQueryContracts.readyWithinClinic.indexColumns shouldBeEqualTo
            NotificationOutboxIndexes.readyWithinClinic.columns
        NotificationOutboxQueryContracts.readyWithinClinic.filters shouldBeEqualTo
            NotificationOutboxIndexes.readyWithinClinic.columns
        NotificationOutboxQueryContracts.readyWithinClinic.orderBy shouldBeEqualTo listOf("id")
        NotificationOutboxQueryContracts.leaseRecovery.indexColumns shouldBeEqualTo
            NotificationOutboxIndexes.leaseRecovery.columns
        NotificationOutboxQueryContracts.leaseRecovery.filters shouldBeEqualTo
            NotificationOutboxIndexes.leaseRecovery.columns
        NotificationOutboxQueryContracts.leaseRecovery.orderBy shouldBeEqualTo listOf("lease_until", "id")
        NotificationOutboxQueryContracts.terminalRetention.indexColumns shouldBeEqualTo
            NotificationOutboxIndexes.terminalRetention.columns
        NotificationOutboxQueryContracts.terminalRetention.filters shouldBeEqualTo
            NotificationOutboxIndexes.terminalRetention.columns
        NotificationOutboxQueryContracts.terminalRetention.orderBy shouldBeEqualTo listOf("terminal_at", "id")
        NotificationOutboxQueryContracts.pendingOldest.indexColumns shouldBeEqualTo
            NotificationOutboxIndexes.pendingOldest.columns
        NotificationOutboxQueryContracts.pendingOldest.filters shouldBeEqualTo
            NotificationOutboxIndexes.pendingOldest.columns
        NotificationOutboxQueryContracts.pendingOldest.orderBy shouldBeEqualTo listOf("available_at", "created_at")
        NotificationDeliveryAttempts.columns.map { it.name }.none {
            it in setOf("recipient", "payload", "rendered", "error_message")
        }.shouldBeTrue()
    }

    @Test
    fun `ready 조회는 발송 가능한 clinic key와 candidate만 반환한다`() {
        transaction(database) {
            val readyOne = repository.enqueue(sendableDraft(eventId = "event-ready-1", digest = "digest-ready-1"))
            repository.enqueue(
                sendableDraft(
                    eventId = "event-future",
                    digest = "digest-future",
                    availableAt = Instant.parse("2999-01-01T00:00:00Z"),
                )
            )
            val retry = repository.enqueue(sendableDraft(eventId = "event-retry", digest = "digest-retry"))
            val claimed = repository.claim(retry.id, owner = "worker-a", token = "token-a")!!
            repository.scheduleRetry(
                RetryNotificationCommand(
                    outboxId = retry.id,
                    owner = claimed.owner,
                    token = claimed.token,
                    attemptNumber = claimed.attemptNumber,
                    failureCode = NotificationFailureCode.PROVIDER_UNAVAILABLE,
                    nextAttemptAt = Instant.parse("2020-01-01T00:01:00Z"),
                )
            ).shouldBeTrue()

            repository.findReadyClinicKeys(cursor = null, limit = 10) shouldBeEqualTo
                listOf(NotificationClinicKey(TenantGroupId(1L), ClinicId(2L)))
            repository.findReadyCandidates(NotificationClinicKey(TenantGroupId(1L), ClinicId(2L)), null, 10)
                .map { it.id } shouldBeEqualTo listOf(readyOne.id, retry.id)
        }
    }

    @Test
    fun `claim은 attempt를 하나 만들고 attempt number unique fence를 유지한다`() {
        transaction(database) {
            val candidate = repository.enqueue(sendableDraft())

            val claimed = repository.claim(candidate.id, owner = "worker-a", token = "token-a")!!

            claimed.attemptNumber shouldBeEqualTo 1
            NotificationDeliveryAttempts.selectAll().count() shouldBeEqualTo 1L
            NotificationDeliveryAttempts.selectAll().single()[NotificationDeliveryAttempts.outboxId].value shouldBeEqualTo
                candidate.id
        }
    }

    @Test
    fun `stale completion은 실패하고 현재 lease만 완료할 수 있다`() {
        transaction(database) {
            val candidate = repository.enqueue(sendableDraft())
            val stale = repository.claim(candidate.id, owner = "worker-a", token = "token-a")!!
            repository.scheduleRetry(
                RetryNotificationCommand(
                    outboxId = candidate.id,
                    owner = stale.owner,
                    token = stale.token,
                    attemptNumber = stale.attemptNumber,
                    failureCode = NotificationFailureCode.PROVIDER_UNAVAILABLE,
                    nextAttemptAt = Instant.parse("2026-07-31T00:01:00Z"),
                )
            ).shouldBeTrue()
            val current = repository.claim(candidate.id, owner = "worker-b", token = "token-b")!!

            repository.complete(
                CompleteNotificationCommand(
                    outboxId = candidate.id,
                    owner = stale.owner,
                    token = stale.token,
                    attemptNumber = stale.attemptNumber,
                )
            ).shouldBeFalse()
            repository.complete(
                CompleteNotificationCommand(
                    outboxId = candidate.id,
                    owner = current.owner,
                    token = current.token,
                    attemptNumber = current.attemptNumber,
                )
            ).shouldBeTrue()
        }
    }

    @Test
    fun `expired recovery closes previous attempt as lease lost and creates current attempt`() {
        transaction(database) {
            val candidate = repository.enqueue(sendableDraft())
            repository.claim(candidate.id, owner = "old-worker", token = "old-token")!!
            NotificationOutboxEvents.update({ NotificationOutboxEvents.id eq candidate.id }) {
                it[NotificationOutboxEvents.leaseUntil] = Instant.parse("2020-01-01T00:00:00Z")
            }

            val recovered = repository.recoverExpired(candidate.id, owner = "new-worker", token = "new-token")!!

            recovered.owner shouldBeEqualTo "new-worker"
            recovered.attemptNumber shouldBeEqualTo 2
            NotificationDeliveryAttempts.selectAll()
                .orderBy(NotificationDeliveryAttempts.attemptNumber)
                .map { it[NotificationDeliveryAttempts.failureCode] } shouldBeEqualTo
                listOf(NotificationFailureCode.LEASE_LOST.name, null)
        }
    }

    private fun sendableDraft(
        eventId: String = "event-1",
        digest: String = "digest-1",
        availableAt: Instant = Instant.parse("2020-01-01T00:00:00Z"),
    ): SendableNotificationDraft =
        SendableNotificationDraft(
            envelope = NotificationOutboxEnvelope(
                schemaVersion = NotificationOutboxEnvelope.CURRENT_SCHEMA_VERSION,
                eventId = NotificationEventId(eventId),
                idempotencyKey = NotificationIdempotencyKey("idem-$digest"),
                tenantGroupId = TenantGroupId(1L),
                clinicId = ClinicId(2L),
                appointmentId = AppointmentId(3L),
                memberId = MemberId("member-1"),
                channel = NotificationChannelType.DUMMY,
                eventType = NotificationEventType.CONFIRMED,
                notificationSlot = NotificationSlot.CONFIRMED,
                templateKey = NotificationTemplateKey("appointment-confirmed"),
                templateVersion = NotificationTemplateVersion(1),
                parameterType = NotificationParameterType.APPOINTMENT_CONFIRMED,
                parameters = AppointmentConfirmedParameters(
                    clinicDisplayName = "Clinic",
                    appointmentDate = LocalDate.parse("2026-08-01"),
                    startTime = LocalTime.parse("09:00"),
                ),
                occurredAt = Instant.parse("2026-07-31T00:00:00Z"),
                availableAt = availableAt,
            ),
            idempotencyDigest = NotificationIdempotencyDigest(
                keyId = "active-key",
                version = 1,
                value = digest,
            ),
            auditFingerprint = NotificationAuditFingerprint(
                keyId = "active-key",
                version = 1,
                value = "audit-1",
            ),
            providerKey = "dummy",
        )

    private fun legacySuppressionDraft(): LegacySuppressionDraft =
        LegacySuppressionDraft(
            idempotencyDigest = NotificationIdempotencyDigest(
                keyId = "active-key",
                version = 1,
                value = "legacy-digest-1",
            ),
            auditFingerprint = NotificationAuditFingerprint(
                keyId = "active-key",
                version = 1,
                value = "legacy-audit-1",
            ),
            tenantGroupId = TenantGroupId(1L),
            clinicId = ClinicId(2L),
            eventId = NotificationEventId("legacy-event-1"),
            suppressionReason = NotificationSuppressionReasonCode.MEMBER_ID_MISSING_LEGACY,
            availableAt = Instant.parse("2020-01-01T00:00:00Z"),
        )
}
