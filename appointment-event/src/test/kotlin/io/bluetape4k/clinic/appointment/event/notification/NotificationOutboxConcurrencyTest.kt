package io.bluetape4k.clinic.appointment.event.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class NotificationOutboxConcurrencyTest {

    private val database = Database.connect(
        "jdbc:h2:mem:notification_outbox_concurrency;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;LOCK_TIMEOUT=10000",
        driver = "org.h2.Driver",
    )
    private val repository = NotificationOutboxRepository(
        codec = NotificationOutboxCodec(),
        leaseDuration = Duration.ofMinutes(5),
    )

    @BeforeEach
    fun setup() {
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(
                NotificationOutboxEvents,
                NotificationDeliveryAttempts,
            )
            NotificationDeliveryAttempts.deleteAll()
            NotificationOutboxEvents.deleteAll()
            repository.enqueue(sendableDraft())
        }
    }

    @Test
    fun `같은 candidate를 20개 worker가 경쟁해도 하나만 claim한다`() {
        val successes = ConcurrentLinkedQueue<ClaimedNotification>()
        val workerId = AtomicInteger()
        val candidateId = transaction(database) {
            repository.findReadyCandidates(NotificationClinicKey(TenantGroupId(1L), ClinicId(2L)), null, 10)
                .single()
                .id
        }

        MultithreadingTester()
            .workers(20)
            .rounds(1)
            .add {
                val worker = workerId.incrementAndGet()
                transaction(database) {
                    repository.claim(candidateId, owner = "worker-$worker", token = "token-$worker")
                }?.let(successes::add)
            }
            .run()

        successes.size shouldBeEqualTo 1
        transaction(database) {
            NotificationDeliveryAttempts.selectAll().count() shouldBeEqualTo 1L
        }
    }

    private fun sendableDraft(): SendableNotificationDraft =
        SendableNotificationDraft(
            envelope = NotificationOutboxEnvelope(
                schemaVersion = NotificationOutboxEnvelope.CURRENT_SCHEMA_VERSION,
                eventId = NotificationEventId("event-1"),
                idempotencyKey = NotificationIdempotencyKey("idem-1"),
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
                availableAt = Instant.parse("2020-01-01T00:00:00Z"),
            ),
            idempotencyDigest = NotificationIdempotencyDigest(
                keyId = "active-key",
                version = 1,
                value = "digest-1",
            ),
            auditFingerprint = NotificationAuditFingerprint(
                keyId = "active-key",
                version = 1,
                value = "audit-1",
            ),
            providerKey = "dummy",
        )
}
