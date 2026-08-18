package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock
import java.time.Duration
import java.time.Instant

/** PostgreSQL lease reclaim이 handler event-id idempotency 계약과 함께 동작하는지 검증합니다. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ResourceLock(
    value = "appointment-messaging-postgresql-consumer-runtime",
    mode = ResourceAccessMode.READ_WRITE,
)
class AppointmentConsumerRuntimePostgreSqlTest {
    private lateinit var database: Database
    private val codec = AppointmentEventEnvelopeCodec()
    private val scopeAuthority = AppointmentConsumerScopeAuthority { tenantGroupId, clinicId ->
        tenantGroupId == 7L && clinicId == 31L
    }

    @BeforeAll
    fun connectPostgreSQL() {
        val postgres = PostgreSQLServer.Launcher.postgres
        database = Database.connect(
            postgres.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = postgres.username ?: PostgreSQLServer.USERNAME,
            password = postgres.password ?: PostgreSQLServer.PASSWORD,
        )
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(
                AppointmentConsumerInboxTable,
                AppointmentConsumerQuarantineTable,
                AppointmentConsumerRejectedRecordTable,
            )
        }
    }

    @BeforeEach
    fun resetInbox() {
        transaction(database) {
            AppointmentConsumerRejectedRecordTable.deleteAll()
            AppointmentConsumerQuarantineTable.deleteAll()
            AppointmentConsumerInboxTable.deleteAll()
        }
    }

    @Test
    fun `crash after side effect commit replays through idempotent handler contract without duplicate side effect`() {
        val leaseClock = MutableAppointmentDatabaseClock(Instant.parse("2026-08-06T00:00:00Z"))
        val delegate = JdbcAppointmentConsumerInboxStore(
            database = database,
            maxAttempts = 2,
            clock = leaseClock,
            processingLease = Duration.ofSeconds(10),
        )
        val crashStore = object : AppointmentConsumerInboxStore by delegate {
            override fun markProcessed(
                identity: AppointmentConsumerIdentity,
                eventId: AppointmentEventId,
                leaseUntil: Instant?,
            ): Boolean = throw SimulatedConsumerCrash()
        }
        val firstRuntime = runtime(crashStore)
        val restartRuntime = runtime(delegate)
        val sideEffects = mutableSetOf<AppointmentEventId>()
        val handler = AppointmentConsumerHandler { envelope, _ ->
            if (sideEffects.add(envelope.eventId)) {
                AppointmentConsumerHandlerResult.APPLIED
            } else {
                AppointmentConsumerHandlerResult.ALREADY_APPLIED
            }
        }

        assertFailsWith<SimulatedConsumerCrash> {
            firstRuntime.consume(record(), identity(), handler)
        }
        leaseClock.advance(Duration.ofSeconds(11))

        restartRuntime.consume(record(), identity(), handler)
            .shouldBeEqualTo(AppointmentConsumerOutcome.PROCESSED)
        sideEffects.size shouldBeEqualTo 1
    }

    private fun runtime(inboxStore: AppointmentConsumerInboxStore) = AppointmentConsumerRuntime(
        codec = codec,
        inboxStore = inboxStore,
        allowedTopics = setOf(AppointmentTopic("clinic.appointment.events")),
        scopeAuthority = scopeAuthority,
    )

    private fun identity() = AppointmentConsumerIdentity(
        consumerId = AppointmentLogicalConsumerId("notification"),
        streamId = AppointmentLogicalStreamId("appointment-events"),
    )

    private fun record() = ConsumerRecord(
        "clinic.appointment.events",
        1,
        12L,
        AppointmentPartitionKeyFactory.create(7, 31, 42).value,
        codec.encode(
            AppointmentEventEnvelope(
                eventId = AppointmentEventId("event-runtime-postgresql-42"),
                eventType = AppointmentEventType.CREATED,
                schemaVersion = AppointmentEventEnvelope.CURRENT_SCHEMA_VERSION,
                occurredAt = Instant.parse("2026-08-06T00:00:00Z"),
                tenantGroupId = 7,
                clinicId = 31,
                aggregateType = AppointmentEventEnvelope.AGGREGATE_TYPE,
                aggregateId = AppointmentAggregateId(42),
                correlationId = io.bluetape4k.clinic.appointment.service.AppointmentCorrelationId(
                    "correlation-runtime-postgresql-42",
                ),
                causationId = io.bluetape4k.clinic.appointment.service.AppointmentCausationId(
                    "causation-runtime-postgresql-42",
                ),
                payload = AppointmentCreatedPayload(
                    appointmentId = AppointmentAggregateId(42),
                    version = 1,
                    status = io.bluetape4k.clinic.appointment.statemachine.AppointmentState.CONFIRMED,
                ),
            ),
        ),
    )

    private class MutableAppointmentDatabaseClock(private var current: Instant) : AppointmentDatabaseClock {
        override fun now(): Instant = current

        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }

    private class SimulatedConsumerCrash : Error("simulated process termination after side effect commit")
}
