package io.bluetape4k.clinic.appointment.api.stats

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeEmpty
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
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger

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
            SchemaUtils.createMissingTablesAndColumns(
                AppointmentStatsProjectionAggregateLockTable,
                AppointmentStatsProjectionTable,
                AppointmentStatsProjectionEventTable,
            )
            AppointmentStatsProjectionAggregateLockTable.deleteAll()
            AppointmentStatsProjectionTable.deleteAll()
            AppointmentStatsProjectionEventTable.deleteAll()
        }
    }

    @Test
    fun `projection is tenant scoped and deduplicates by aggregate event`() {
        consumer.handle(envelope("event-2", version = 2, tenant = 11), context(tenant = 11))
        consumer.handle(envelope("event-2", version = 2, tenant = 11), context(tenant = 11))
        consumer.handle(envelope("event-1", version = 1, tenant = 11), context(tenant = 11))
        consumer.handle(envelope("event-3", version = 3, tenant = 11), context(tenant = 11))
        consumer.handle(envelope("event-other", version = 1, tenant = 12), context(tenant = 12))
        consumer.handle(envelope("event-other-aggregate", version = 1, tenant = 11, aggregateId = 43), context(tenant = 11))

        transaction(database) {
            repository.countByDateAndStatus(11, CLINIC_ID, DATE..DATE) shouldBeEqualTo
                listOf(AppointmentStatsProjectionRow(DATE, AppointmentState.CONFIRMED, 2L))
            repository.countByDateAndStatus(12, CLINIC_ID, DATE..DATE) shouldBeEqualTo
                listOf(AppointmentStatsProjectionRow(DATE, AppointmentState.CONFIRMED, 1L))
        }
    }

    @Test
    fun `projection moves an aggregate to its latest state and event date`() {
        consumer.handle(
            envelope(
                eventId = "event-confirmed",
                version = 1,
                tenant = 11,
                status = AppointmentState.CONFIRMED,
                occurredAt = Instant.parse("2026-08-06T12:00:00Z"),
            ),
            context(tenant = 11),
        )
        consumer.handle(
            envelope(
                eventId = "event-cancelled",
                version = 2,
                tenant = 11,
                status = AppointmentState.CANCELLED,
                occurredAt = Instant.parse("2026-08-07T12:00:00Z"),
            ),
            context(tenant = 11),
        )

        transaction(database) {
            repository.countByDateAndStatus(11, CLINIC_ID, DATE..DATE).shouldBeEmpty()
            repository.countByDateAndStatus(11, CLINIC_ID, NEXT_DATE..NEXT_DATE) shouldBeEqualTo
                listOf(AppointmentStatsProjectionRow(NEXT_DATE, AppointmentState.CANCELLED, 1L))
        }
    }

    @Test
    fun `projection serializes concurrent events for the same aggregate`() {
        val rounds = 32
        val round = AtomicInteger()
        val barrier = CyclicBarrier(2) { round.incrementAndGet() }

        MultithreadingTester()
            .workers(2)
            .rounds(rounds)
            .addAll(
                {
                    barrier.await()
                    val aggregateId = 1_000L + round.get()
                    consumer.handle(
                        envelope(
                            eventId = "concurrent-confirmed-$aggregateId",
                            version = 1,
                            tenant = 11,
                            aggregateId = aggregateId,
                            status = AppointmentState.CONFIRMED,
                            occurredAt = Instant.parse("2026-08-06T12:00:00Z"),
                        ),
                        context(tenant = 11),
                    )
                },
                {
                    barrier.await()
                    val aggregateId = 1_000L + round.get()
                    consumer.handle(
                        envelope(
                            eventId = "concurrent-cancelled-$aggregateId",
                            version = 2,
                            tenant = 11,
                            aggregateId = aggregateId,
                            status = AppointmentState.CANCELLED,
                            occurredAt = Instant.parse("2026-08-07T12:00:00Z"),
                        ),
                        context(tenant = 11),
                    )
                },
            )
            .run()

        transaction(database) {
            repository.countByDateAndStatus(11, CLINIC_ID, DATE..DATE).shouldBeEmpty()
            repository.countByDateAndStatus(11, CLINIC_ID, NEXT_DATE..NEXT_DATE) shouldBeEqualTo
                listOf(AppointmentStatsProjectionRow(NEXT_DATE, AppointmentState.CANCELLED, rounds.toLong()))
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

    private fun envelope(
        eventId: String,
        version: Long,
        tenant: Long,
        aggregateId: Long = 42,
        status: AppointmentState = AppointmentState.CONFIRMED,
        occurredAt: Instant = Instant.parse("2026-08-06T12:00:00Z"),
    ) = AppointmentEventEnvelope(
        eventId = AppointmentEventId(eventId),
        eventType = AppointmentEventType.STATUS_CHANGED,
        schemaVersion = AppointmentEventEnvelope.CURRENT_SCHEMA_VERSION,
        occurredAt = occurredAt,
        tenantGroupId = tenant,
        clinicId = CLINIC_ID,
        aggregateType = AppointmentEventEnvelope.AGGREGATE_TYPE,
        aggregateId = AppointmentAggregateId(aggregateId),
        correlationId = AppointmentCorrelationId("correlation-$eventId"),
        causationId = AppointmentCausationId("causation-$eventId"),
        payload = AppointmentStatusChangedPayload(
            appointmentId = AppointmentAggregateId(aggregateId),
            version = version,
            fromState = AppointmentState.REQUESTED,
            toState = status,
        ),
    )

    companion object {
        private val DATE = java.time.LocalDate.of(2026, 8, 6)
        private val NEXT_DATE = java.time.LocalDate.of(2026, 8, 7)
        private const val CLINIC_ID = 31L
    }
}
