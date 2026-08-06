package io.bluetape4k.clinic.appointment.api.stats

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.messaging.AppointmentAggregateId
import io.bluetape4k.clinic.appointment.messaging.AppointmentConsumerContext
import io.bluetape4k.clinic.appointment.messaging.AppointmentConsumerIdentity
import io.bluetape4k.clinic.appointment.messaging.AppointmentConsumerProvenance
import io.bluetape4k.clinic.appointment.messaging.AppointmentEventEnvelope
import io.bluetape4k.clinic.appointment.messaging.AppointmentEventId
import io.bluetape4k.clinic.appointment.messaging.AppointmentEventType
import io.bluetape4k.clinic.appointment.messaging.AppointmentLogicalConsumerId
import io.bluetape4k.clinic.appointment.messaging.AppointmentLogicalStreamId
import io.bluetape4k.clinic.appointment.messaging.AppointmentTopic
import io.bluetape4k.clinic.appointment.messaging.AppointmentStatusChangedPayload
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import io.bluetape4k.clinic.appointment.service.AppointmentCausationId
import io.bluetape4k.clinic.appointment.service.AppointmentCorrelationId
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class AppointmentStatsProjectionConsumerTest {
    private val database = Database.connect(
        url = "jdbc:h2:mem:appointment-stats-projection;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        driver = "org.h2.Driver",
    )
    private val repository = AppointmentStatsProjectionRepository()
    private val consumer = AppointmentStatsProjectionConsumer(database, repository)

    @BeforeEach
    fun setUp() {
        transaction(database) {
            SchemaUtils.drop(AppointmentStatsProjectionTable)
            SchemaUtils.create(AppointmentStatsProjectionTable)
        }
    }

    @Test
    fun `projection is tenant scoped and ignores duplicate or lower versions`() {
        consumer.handle(envelope("event-2", version = 2, tenant = 11), context(tenant = 11))
        consumer.handle(envelope("event-2", version = 2, tenant = 11), context(tenant = 11))
        consumer.handle(envelope("event-1", version = 1, tenant = 11), context(tenant = 11))
        consumer.handle(envelope("event-3", version = 3, tenant = 11), context(tenant = 11))
        consumer.handle(envelope("event-other", version = 1, tenant = 12), context(tenant = 12))

        transaction(database) {
            repository.countByDateAndStatus(11, CLINIC_ID, DATE..DATE) shouldBeEqualTo
                listOf(AppointmentStatsProjectionRow(DATE, AppointmentState.CONFIRMED, 2L))
            repository.countByDateAndStatus(12, CLINIC_ID, DATE..DATE) shouldBeEqualTo
                listOf(AppointmentStatsProjectionRow(DATE, AppointmentState.CONFIRMED, 1L))
        }
    }

    private fun context(tenant: Long) = AppointmentConsumerContext(
        identity = AppointmentConsumerIdentity(
            AppointmentLogicalConsumerId("statistics"),
            AppointmentLogicalStreamId("appointment-events"),
        ),
        provenance = AppointmentConsumerProvenance(
            topic = AppointmentTopic("clinic.appointment.events"),
            partition = 0,
            offset = 1,
            schemaVersion = AppointmentEventEnvelope.CURRENT_SCHEMA_VERSION,
            tenantGroupId = tenant,
            clinicId = CLINIC_ID,
            payloadSha256 = "a".repeat(64),
        ),
    )

    private fun envelope(eventId: String, version: Long, tenant: Long) = AppointmentEventEnvelope(
        eventId = AppointmentEventId(eventId),
        eventType = AppointmentEventType.STATUS_CHANGED,
        schemaVersion = AppointmentEventEnvelope.CURRENT_SCHEMA_VERSION,
        occurredAt = Instant.parse("2026-08-06T12:00:00Z"),
        tenantGroupId = tenant,
        clinicId = CLINIC_ID,
        aggregateType = AppointmentEventEnvelope.AGGREGATE_TYPE,
        aggregateId = AppointmentAggregateId(42),
        correlationId = AppointmentCorrelationId("correlation-$eventId"),
        causationId = AppointmentCausationId("causation-$eventId"),
        payload = AppointmentStatusChangedPayload(
            appointmentId = AppointmentAggregateId(42),
            version = version,
            fromState = AppointmentState.REQUESTED,
            toState = AppointmentState.CONFIRMED,
        ),
    )

    companion object {
        private val DATE = java.time.LocalDate.of(2026, 8, 6)
        private const val CLINIC_ID = 31L
    }
}
