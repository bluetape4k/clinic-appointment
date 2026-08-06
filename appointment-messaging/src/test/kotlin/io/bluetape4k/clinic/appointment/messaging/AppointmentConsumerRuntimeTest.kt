package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.kafka.support.Acknowledgment
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class AppointmentConsumerRuntimeTest {
    private lateinit var database: Database
    private lateinit var runtime: AppointmentConsumerRuntime
    private val codec = AppointmentEventEnvelopeCodec()

    @BeforeEach
    fun setUp() {
        database = Database.connect(
            "jdbc:h2:mem:appointment_consumer_runtime_${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver",
        )
        transaction(database) {
            SchemaUtils.create(AppointmentConsumerInboxTable, AppointmentConsumerQuarantineTable)
        }
        runtime = AppointmentConsumerRuntime(
            codec = codec,
            inboxStore = JdbcAppointmentConsumerInboxStore(database, maxAttempts = 2),
            allowedTopics = setOf(AppointmentTopic("clinic.appointment.events")),
        )
    }

    @Test
    fun `processed record is deduplicated by logical consumer and event`() {
        val calls = AtomicInteger()
        val record = record()
        val acknowledgment = RecordingAcknowledgment()
        val handler = AppointmentConsumerHandler { _, _ -> calls.incrementAndGet() }

        runtime.consume(record, acknowledgment, identity(), handler) shouldBeEqualTo AppointmentConsumerOutcome.PROCESSED
        runtime.consume(record, acknowledgment, identity(), handler) shouldBeEqualTo AppointmentConsumerOutcome.DUPLICATE

        calls.get() shouldBeEqualTo 1
        acknowledgment.count shouldBeEqualTo 2
    }

    @Test
    fun `partition key mismatch is quarantined without invoking handler`() {
        val calls = AtomicInteger()
        val record = record(key = "tenant-99:CLINIC:clinic-9:APPOINTMENT:apt-9")

        val outcome = runtime.consume(record, identity()) { _, _ -> calls.incrementAndGet() }

        outcome shouldBeEqualTo AppointmentConsumerOutcome.QUARANTINED
        calls.get() shouldBeEqualTo 0
        transaction(database) {
            AppointmentConsumerInboxTable.selectAll().single()[AppointmentConsumerInboxTable.status]
                .shouldBeEqualTo(AppointmentConsumerStatus.QUARANTINED)
        }
    }

    @Test
    fun `retryable handler failure is redelivered and reclaimed`() {
        val calls = AtomicInteger()
        val record = record()
        val firstFailure = runCatching {
            runtime.consume(record, identity()) { _, _ ->
                if (calls.getAndIncrement() == 0) throw AppointmentConsumerRetryableException("provider unavailable")
            }
        }.exceptionOrNull()

        firstFailure.shouldBeInstanceOf<AppointmentConsumerRetryableException>()
        runtime.consume(record, identity()) { _, _ -> calls.incrementAndGet() }
            .shouldBeEqualTo(AppointmentConsumerOutcome.PROCESSED)
        calls.get() shouldBeEqualTo 2
    }

    @Test
    fun `invalid envelope fails without exposing raw payload`() {
        val invalid = record(value = "{\"eventId\":\"secret-patient-payload\"}")

        val failure = runCatching { runtime.consume(invalid, identity()) { _, _ -> } }.exceptionOrNull()

        failure.shouldBeInstanceOf<AppointmentConsumerInvalidEnvelopeException>()
        failure.message?.contains("secret-patient-payload") shouldBeEqualTo false
    }

    private fun identity() = AppointmentConsumerIdentity(
        consumerId = AppointmentLogicalConsumerId("notification"),
        streamId = AppointmentLogicalStreamId("appointment-events"),
    )

    private fun record(
        key: String = AppointmentPartitionKeyFactory.create(7, 31, 42).value,
        value: String = codec.encode(envelope()),
    ) = ConsumerRecord(
        "clinic.appointment.events",
        1,
        12L,
        key,
        value,
    )

    private fun envelope() = AppointmentEventEnvelope(
        eventId = AppointmentEventId("event-runtime-42"),
        eventType = AppointmentEventType.CREATED,
        schemaVersion = AppointmentEventEnvelope.CURRENT_SCHEMA_VERSION,
        occurredAt = Instant.parse("2026-08-06T00:00:00Z"),
        tenantGroupId = 7,
        clinicId = 31,
        aggregateType = AppointmentEventEnvelope.AGGREGATE_TYPE,
        aggregateId = AppointmentAggregateId(42),
        correlationId = io.bluetape4k.clinic.appointment.service.AppointmentCorrelationId("correlation-runtime-42"),
        causationId = io.bluetape4k.clinic.appointment.service.AppointmentCausationId("causation-runtime-42"),
        payload = AppointmentCreatedPayload(
            appointmentId = AppointmentAggregateId(42),
            version = 1,
            status = io.bluetape4k.clinic.appointment.statemachine.AppointmentState.CONFIRMED,
        ),
    )

    private class RecordingAcknowledgment : Acknowledgment {
        var count: Int = 0

        override fun acknowledge() {
            count += 1
        }
    }
}
