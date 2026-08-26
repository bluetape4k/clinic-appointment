package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
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
    private val scopeAuthority = AppointmentConsumerScopeAuthority { tenantGroupId, clinicId ->
        tenantGroupId == 7L && clinicId == 31L
    }

    @BeforeEach
    fun setUp() {
        database = Database.connect(
            "jdbc:h2:mem:appointment_consumer_runtime_${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver",
        )
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(
                AppointmentConsumerInboxTable,
                AppointmentConsumerQuarantineTable,
                AppointmentConsumerRejectedRecordTable,
            )
            AppointmentConsumerQuarantineTable.deleteAll()
            AppointmentConsumerRejectedRecordTable.deleteAll()
            AppointmentConsumerInboxTable.deleteAll()
        }
        runtime = AppointmentConsumerRuntime(
            codec = codec,
            inboxStore = JdbcAppointmentConsumerInboxStore(database, maxAttempts = 2),
            allowedTopics = setOf(AppointmentTopic("clinic.appointment.events")),
            scopeAuthority = scopeAuthority,
        )
    }

    @Test
    fun `processed record is deduplicated by logical consumer and event`() {
        val calls = AtomicInteger()
        val record = record()
        val acknowledgment = RecordingAcknowledgment()
        val handler = AppointmentConsumerHandler { _, _ ->
            calls.incrementAndGet()
            AppointmentConsumerHandlerResult.APPLIED
        }

        runtime.consume(record, acknowledgment, identity(), handler) shouldBeEqualTo AppointmentConsumerOutcome.PROCESSED
        runtime.consume(record, acknowledgment, identity(), handler) shouldBeEqualTo AppointmentConsumerOutcome.DUPLICATE

        calls.get() shouldBeEqualTo 1
        acknowledgment.count shouldBeEqualTo 2
    }

    @Test
    fun `partition key mismatch is quarantined without invoking handler`() {
        val calls = AtomicInteger()
        val record = record(key = "tenant-99:CLINIC:clinic-9:APPOINTMENT:apt-9")

        val outcome = runtime.consume(record, identity()) { _, _ ->
            calls.incrementAndGet()
            AppointmentConsumerHandlerResult.APPLIED
        }

        outcome shouldBeEqualTo AppointmentConsumerOutcome.QUARANTINED
        calls.get() shouldBeEqualTo 0
        transaction(database) {
            AppointmentConsumerInboxTable.selectAll().single()[AppointmentConsumerInboxTable.status]
                .shouldBeEqualTo(AppointmentConsumerStatus.QUARANTINED)
        }
    }

    @Test
    fun `live record for an unknown clinic is quarantined before invoking handler`() {
        val calls = AtomicInteger()
        val acknowledgment = RecordingAcknowledgment()
        val record = record(
            key = AppointmentPartitionKeyFactory.create(7, 999, 42).value,
            value = codec.encode(envelope(clinicId = 999)),
        )

        runtime.consume(record, acknowledgment, identity()) { _, _ ->
            calls.incrementAndGet()
            AppointmentConsumerHandlerResult.APPLIED
        }
            .shouldBeEqualTo(AppointmentConsumerOutcome.QUARANTINED)

        calls.get() shouldBeEqualTo 0
        acknowledgment.count shouldBeEqualTo 1
        transaction(database) {
            AppointmentConsumerRejectedRecordTable.selectAll().single()[AppointmentConsumerRejectedRecordTable.failureCode]
                .shouldBeEqualTo(AppointmentConsumerFailureCode.SCOPE_MISMATCH.name)
        }
    }

    @Test
    fun `live record for a clinic owned by another tenant is quarantined before invoking handler`() {
        val calls = AtomicInteger()
        val acknowledgment = RecordingAcknowledgment()
        val record = record(
            key = AppointmentPartitionKeyFactory.create(8, 31, 42).value,
            value = codec.encode(envelope(tenantGroupId = 8)),
        )

        runtime.consume(record, acknowledgment, identity()) { _, _ ->
            calls.incrementAndGet()
            AppointmentConsumerHandlerResult.APPLIED
        }
            .shouldBeEqualTo(AppointmentConsumerOutcome.QUARANTINED)

        calls.get() shouldBeEqualTo 0
        acknowledgment.count shouldBeEqualTo 1
        transaction(database) {
            AppointmentConsumerRejectedRecordTable.selectAll().single()[AppointmentConsumerRejectedRecordTable.failureCode]
                .shouldBeEqualTo(AppointmentConsumerFailureCode.SCOPE_MISMATCH.name)
        }
    }

    @Test
    fun `live record with a forged tenant and clinic scope is quarantined before invoking handler`() {
        val calls = AtomicInteger()
        val acknowledgment = RecordingAcknowledgment()
        val record = record(
            key = AppointmentPartitionKeyFactory.create(999, 998, 42).value,
            value = codec.encode(envelope(tenantGroupId = 999, clinicId = 998)),
        )

        runtime.consume(record, acknowledgment, identity()) { _, _ ->
            calls.incrementAndGet()
            AppointmentConsumerHandlerResult.APPLIED
        }
            .shouldBeEqualTo(AppointmentConsumerOutcome.QUARANTINED)

        calls.get() shouldBeEqualTo 0
        acknowledgment.count shouldBeEqualTo 1
        transaction(database) {
            AppointmentConsumerRejectedRecordTable.selectAll().single()[AppointmentConsumerRejectedRecordTable.failureCode]
                .shouldBeEqualTo(AppointmentConsumerFailureCode.SCOPE_MISMATCH.name)
        }
    }

    @Test
    fun `live scope authority failure is retried without quarantine or acknowledgement`() {
        val authorityFailure = IllegalStateException("scope authority unavailable")
        val unavailableRuntime = AppointmentConsumerRuntime(
            codec = codec,
            inboxStore = JdbcAppointmentConsumerInboxStore(database),
            allowedTopics = setOf(AppointmentTopic("clinic.appointment.events")),
            scopeAuthority = AppointmentConsumerScopeAuthority { _, _ -> throw authorityFailure },
        )
        val acknowledgment = RecordingAcknowledgment()

        val failure = assertFailsWith<AppointmentConsumerRetryableException> {
            unavailableRuntime.consume(record(), acknowledgment, identity()) { _, _ ->
                error("handler must not be reached")
            }
        }
        failure.cause.shouldBeEqualTo(authorityFailure)

        acknowledgment.count shouldBeEqualTo 0
        transaction(database) {
            AppointmentConsumerRejectedRecordTable.selectAll().count() shouldBeEqualTo 0L
        }
    }

    @Test
    fun `retryable handler failure is redelivered and reclaimed`() {
        val calls = AtomicInteger()
        val record = record()
        val firstFailure = runCatching {
            runtime.consume(record, identity()) { _, _ ->
                if (calls.getAndIncrement() == 0) throw AppointmentConsumerRetryableException("provider unavailable")
                AppointmentConsumerHandlerResult.APPLIED
            }
        }.exceptionOrNull()

        firstFailure.shouldBeInstanceOf<AppointmentConsumerRetryableException>()
        runtime.consume(record, identity()) { _, _ ->
            calls.incrementAndGet()
            AppointmentConsumerHandlerResult.APPLIED
        }
            .shouldBeEqualTo(AppointmentConsumerOutcome.PROCESSED)
        calls.get() shouldBeEqualTo 2
    }

    @Test
    fun `terminal handler failure keeps the bounded quarantine contract`() {
        val terminalRuntime = AppointmentConsumerRuntime(
            codec = codec,
            inboxStore = JdbcAppointmentConsumerInboxStore(database, maxAttempts = 1),
            allowedTopics = setOf(AppointmentTopic("clinic.appointment.events")),
            scopeAuthority = scopeAuthority,
        )

        terminalRuntime.consume(record(), identity()) { _, _ ->
            throw IllegalStateException("terminal handler failure")
        }.shouldBeEqualTo(AppointmentConsumerOutcome.QUARANTINED)

        transaction(database) {
            AppointmentConsumerInboxTable.selectAll().single()[AppointmentConsumerInboxTable.status]
                .shouldBeEqualTo(AppointmentConsumerStatus.QUARANTINED)
        }
    }

    @Test
    fun `invalid envelope is quarantined and acknowledged without exposing raw payload`() {
        val invalid = record(value = "{\"eventId\":\"secret-patient-payload\"}")
        val acknowledgment = RecordingAcknowledgment()

        runtime.consume(invalid, acknowledgment, identity()) { _, _ -> AppointmentConsumerHandlerResult.ALREADY_APPLIED }
            .shouldBeEqualTo(AppointmentConsumerOutcome.QUARANTINED)

        acknowledgment.count shouldBeEqualTo 1
        transaction(database) {
            AppointmentConsumerRejectedRecordTable.selectAll().single()[AppointmentConsumerRejectedRecordTable.payloadSha256]
                .length shouldBeEqualTo 64
        }
    }

    @Test
    fun `schema registry outage is retried without quarantine or acknowledgement`() {
        val unavailableRuntime = AppointmentConsumerRuntime(
            codec = codec,
            inboxStore = JdbcAppointmentConsumerInboxStore(database),
            allowedTopics = setOf(AppointmentTopic("clinic.appointment.events")),
            scopeAuthority = scopeAuthority,
            schemaRegistry = object : AppointmentSchemaRegistry {
                override val subject: String = "appointment-events-value"
                override fun validate(schemaVersion: Int) {
                    throw AppointmentSchemaRegistryUnavailableException("registry unavailable")
                }
                override fun readiness(): AppointmentSchemaReadiness = AppointmentSchemaReadiness(
                    subject = subject,
                    localSchemaValid = true,
                    registryReachable = false,
                    compatibilityLevel = "UNAVAILABLE",
                )
            },
        )
        val acknowledgment = RecordingAcknowledgment()

        assertFailsWith<AppointmentConsumerRetryableException> {
            unavailableRuntime.consume(record(), acknowledgment, identity()) { _, _ ->
                AppointmentConsumerHandlerResult.ALREADY_APPLIED
            }
        }

        acknowledgment.count shouldBeEqualTo 0
        transaction(database) {
            AppointmentConsumerRejectedRecordTable.selectAll().count() shouldBeEqualTo 0L
        }
    }

    @Test
    fun `replay scope mismatch is quarantined before handler side effect`() {
        var calls = 0

        runtime.consume(
            record = record(),
            acknowledgment = null,
            identity = identity(),
            handler = { _, _ ->
                calls++
                AppointmentConsumerHandlerResult.APPLIED
            },
            expectedScope = AppointmentReplayScope(tenantGroupId = 99, clinicId = 31),
        ).shouldBeEqualTo(AppointmentConsumerOutcome.QUARANTINED)

        calls shouldBeEqualTo 0
        transaction(database) {
            AppointmentConsumerRejectedRecordTable.selectAll().single()[AppointmentConsumerRejectedRecordTable.failureCode]
                .shouldBeEqualTo(AppointmentConsumerFailureCode.SCOPE_MISMATCH.name)
        }
    }

    @Test
    fun `replay scope reclaims quarantined inbox and invokes handler again`() {
        val calls = AtomicInteger()
        val record = record()
        val failingHandler = AppointmentConsumerHandler { _, _ ->
            calls.incrementAndGet()
            throw AppointmentConsumerRetryableException("provider unavailable")
        }

        assertFailsWith<AppointmentConsumerRetryableException> {
            runtime.consume(record, identity(), failingHandler)
        }
        runtime.consume(record, identity(), failingHandler)
            .shouldBeEqualTo(AppointmentConsumerOutcome.QUARANTINED)

        val replayOutcome = runtime.consume(
            record = record,
            acknowledgment = null,
            identity = identity(),
            handler = { _, _ ->
                calls.incrementAndGet()
                AppointmentConsumerHandlerResult.APPLIED
            },
            expectedScope = AppointmentReplayScope(tenantGroupId = 7, clinicId = 31),
        )

        replayOutcome shouldBeEqualTo AppointmentConsumerOutcome.PROCESSED
        calls.get() shouldBeEqualTo 3
        transaction(database) {
            AppointmentConsumerInboxTable.selectAll().single()[AppointmentConsumerInboxTable.status]
                .shouldBeEqualTo(AppointmentConsumerStatus.PROCESSED)
        }
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

    private fun envelope(
        tenantGroupId: Long = 7,
        clinicId: Long = 31,
        appointmentId: Long = 42,
    ) = AppointmentEventEnvelope(
        eventId = AppointmentEventId("event-runtime-$clinicId"),
        eventType = AppointmentEventType.CREATED,
        schemaVersion = AppointmentEventEnvelope.CURRENT_SCHEMA_VERSION,
        occurredAt = Instant.parse("2026-08-06T00:00:00Z"),
        tenantGroupId = tenantGroupId,
        clinicId = clinicId,
        aggregateType = AppointmentEventEnvelope.AGGREGATE_TYPE,
        aggregateId = AppointmentAggregateId(appointmentId),
        correlationId = io.bluetape4k.clinic.appointment.service.AppointmentCorrelationId("correlation-runtime-42"),
        causationId = io.bluetape4k.clinic.appointment.service.AppointmentCausationId("causation-runtime-42"),
        payload = AppointmentCreatedPayload(
            appointmentId = AppointmentAggregateId(appointmentId),
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
