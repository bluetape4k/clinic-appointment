package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.event.notification.NotificationDeliveryAttemptOutcome
import io.bluetape4k.clinic.appointment.event.notification.NotificationDeliveryAttempts
import io.bluetape4k.clinic.appointment.event.notification.AppointmentConfirmedParameters
import io.bluetape4k.clinic.appointment.event.notification.AppointmentId
import io.bluetape4k.clinic.appointment.event.notification.NotificationAuditFingerprint
import io.bluetape4k.clinic.appointment.event.notification.NotificationChannelType
import io.bluetape4k.clinic.appointment.event.notification.NotificationEventId
import io.bluetape4k.clinic.appointment.event.notification.NotificationEventType
import io.bluetape4k.clinic.appointment.event.notification.NotificationIdempotencyDigest
import io.bluetape4k.clinic.appointment.event.notification.NotificationIdempotencyKey
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxEnvelope
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxEvents
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxRepository
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxCodec
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxStatus
import io.bluetape4k.clinic.appointment.event.notification.NotificationParameterType
import io.bluetape4k.clinic.appointment.event.notification.NotificationSlot
import io.bluetape4k.clinic.appointment.event.notification.NotificationTemplateKey
import io.bluetape4k.clinic.appointment.event.notification.NotificationTemplateVersion
import io.bluetape4k.clinic.appointment.event.notification.SendableNotificationDraft
import io.bluetape4k.clinic.appointment.event.notification.TenantGroupId
import io.bluetape4k.clinic.appointment.event.notification.ClinicId
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test

internal class NotificationOutboxWorkerLeaseTest {

    @Test
    fun `만료 lease 복구는 DB 시간 기준으로 이전 attempt를 닫고 새 attempt를 claim한다`() {
        runBlocking {
            val database = Database.connect(
                "jdbc:h2:mem:notification_lease_${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver",
            )
            transaction(database) {
                SchemaUtils.createMissingTablesAndColumns(NotificationOutboxEvents, NotificationDeliveryAttempts)
            }
            val repository = NotificationOutboxRepository(NotificationOutboxCodec(), Duration.ofMinutes(5))
            val enqueued = transaction(database) { repository.enqueue(notificationDraft("lease-recovery")) }
            val firstClaim = JdbcNotificationOutboxWorkStore(database, repository).claim(enqueued.id, "owner-a")
            checkNotNull(firstClaim)
            transaction(database) {
                exec(
                    "UPDATE clinic_notification_outbox " +
                        "SET lease_until = DATEADD('SECOND', -1, CURRENT_TIMESTAMP) " +
                        "WHERE id = ${enqueued.id}",
                )
            }

            val recovered = NotificationOutboxWorker(
                workStore = JdbcNotificationOutboxWorkStore(database, repository),
                leaseOwner = "owner-b",
            ).recoverExpiredOnce(limit = 5)

            recovered.single().attemptNumber shouldBeEqualTo 2
            recovered.single().owner shouldBeEqualTo "owner-b"
            transaction(database) {
                NotificationOutboxEvents.selectAll().single()[NotificationOutboxEvents.status] shouldBeEqualTo
                    NotificationOutboxStatus.PROCESSING
                NotificationDeliveryAttempts
                    .selectAll()
                    .map { it[NotificationDeliveryAttempts.outcome] } shouldBeEqualTo
                    listOf(NotificationDeliveryAttemptOutcome.LEASE_LOST, null)
            }
        }
    }

    private fun notificationDraft(key: String): SendableNotificationDraft =
        SendableNotificationDraft(
            envelope = NotificationOutboxEnvelope(
                schemaVersion = NotificationOutboxEnvelope.CURRENT_SCHEMA_VERSION,
                eventId = NotificationEventId("event-$key"),
                idempotencyKey = NotificationIdempotencyKey("idempotency-$key"),
                tenantGroupId = TenantGroupId(1L),
                clinicId = ClinicId(1L),
                appointmentId = AppointmentId(1L),
                memberId = MemberId("member-$key"),
                channel = NotificationChannelType.DUMMY,
                eventType = NotificationEventType.CONFIRMED,
                notificationSlot = NotificationSlot.CONFIRMED,
                templateKey = NotificationTemplateKey("appointment.confirmed"),
                templateVersion = NotificationTemplateVersion(1),
                parameterType = NotificationParameterType.APPOINTMENT_CONFIRMED,
                parameters = AppointmentConfirmedParameters(
                    clinicDisplayName = "테스트 클리닉",
                    appointmentDate = LocalDate.parse("2026-08-01"),
                    startTime = LocalTime.parse("09:00:00"),
                ),
                occurredAt = Instant.parse("2026-07-31T00:00:00Z"),
                availableAt = Instant.parse("2026-07-31T00:00:00Z"),
            ),
            idempotencyDigest = NotificationIdempotencyDigest(
                keyId = "test-key",
                version = 1,
                value = "idem-$key",
            ),
            auditFingerprint = NotificationAuditFingerprint(
                keyId = "test-key",
                version = 1,
                value = "audit-$key",
            ),
            providerKey = "dummy",
        )
}
