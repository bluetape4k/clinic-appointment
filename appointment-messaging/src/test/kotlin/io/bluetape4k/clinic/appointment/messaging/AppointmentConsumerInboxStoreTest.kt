package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.clinic.appointment.service.AppointmentCommandContext
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class AppointmentConsumerInboxStoreTest {
    private lateinit var database: Database
    private lateinit var store: JdbcAppointmentConsumerInboxStore

    @BeforeEach
    fun setUp() {
        database = Database.connect(
            "jdbc:h2:mem:appointment_consumer_inbox_${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
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
        store = JdbcAppointmentConsumerInboxStore(database, maxAttempts = 2)
    }

    @Test
    fun `begin is acquired once and duplicate after processed never reclaims handler`() {
        val first = store.begin(identity(), eventId(), provenance())
        first::class shouldBeEqualTo AppointmentConsumerBeginResult.Acquired::class
        store.markProcessed(identity(), eventId()).shouldBeTrue()

        val duplicate = store.begin(identity(), eventId(), provenance())
        (duplicate as AppointmentConsumerBeginResult.Duplicate).status shouldBeEqualTo AppointmentConsumerStatus.PROCESSED
    }

    @Test
    fun `same event id is independent for another logical consumer`() {
        store.begin(identity("notification"), eventId(), provenance())
        val statistics = store.begin(identity("statistics"), eventId(), provenance())

        statistics::class shouldBeEqualTo AppointmentConsumerBeginResult.Acquired::class
    }

    @Test
    fun `retryable row can be acquired again with a bounded next attempt`() {
        store.begin(identity(), eventId(), provenance())
        store.markFailure(identity(), eventId(), AppointmentConsumerFailureCode.HANDLER_RETRYABLE)

        val retry = store.begin(identity(), eventId(), provenance())

        retry.shouldBeInstanceOf<AppointmentConsumerBeginResult.Acquired>().attemptCount
            .shouldBeEqualTo(2)
    }

    @Test
    fun `retry is bounded and exhausted attempt becomes quarantine`() {
        store.begin(identity(), eventId(), provenance())

        store.markFailure(identity(), eventId(), AppointmentConsumerFailureCode.HANDLER_RETRYABLE)
            .shouldBeEqualTo(AppointmentConsumerStatus.RETRYABLE)
        store.begin(identity(), eventId(), provenance())
            .shouldBeInstanceOf<AppointmentConsumerBeginResult.Acquired>().attemptCount
            .shouldBeEqualTo(2)
        store.markFailure(identity(), eventId(), AppointmentConsumerFailureCode.HANDLER_RETRYABLE)
            .shouldBeEqualTo(AppointmentConsumerStatus.QUARANTINED)

        transaction(database) {
            val row = AppointmentConsumerInboxTable.selectAll().single()
            row[AppointmentConsumerInboxTable.status] shouldBeEqualTo AppointmentConsumerStatus.QUARANTINED
            row[AppointmentConsumerInboxTable.payloadSha256] shouldBeEqualTo provenance().payloadSha256
            row[AppointmentConsumerInboxTable.failureCode] shouldBeEqualTo AppointmentConsumerFailureCode.ATTEMPT_EXHAUSTED.name
        }
    }

    @Test
    fun `quarantined row is only reclaimed when replay is explicitly allowed`() {
        store.begin(identity(), eventId(), provenance())
        store.markFailure(identity(), eventId(), AppointmentConsumerFailureCode.HANDLER_RETRYABLE)
        store.begin(identity(), eventId(), provenance())
        store.markFailure(identity(), eventId(), AppointmentConsumerFailureCode.HANDLER_RETRYABLE)
            .shouldBeEqualTo(AppointmentConsumerStatus.QUARANTINED)

        store.begin(identity(), eventId(), provenance())
            .shouldBeEqualTo(AppointmentConsumerBeginResult.Duplicate(AppointmentConsumerStatus.QUARANTINED))
        store.begin(identity(), eventId(), provenance(), allowQuarantinedReplay = true)
            .shouldBeInstanceOf<AppointmentConsumerBeginResult.Acquired>().attemptCount
            .shouldBeEqualTo(1)
    }

    @Test
    fun `quarantine stores metadata hash and cleanup retains quarantine and processing`() {
        store.begin(identity(), eventId(), provenance())
        store.quarantine(identity(), eventId(), AppointmentConsumerFailureCode.INVALID_ENVELOPE)
            .shouldBeTrue()

        store.begin(identity("second"), AppointmentEventId("event-processing"), provenance())
        store.cleanupProcessed(Instant.now().plusSeconds(1), 10) shouldBeEqualTo 0
        val expectedHash = provenance().payloadSha256

        transaction(database) {
            AppointmentConsumerInboxTable.selectAll().count() shouldBeEqualTo 2L
            val quarantineHash = AppointmentConsumerQuarantineTable
                .selectAll()
                .single()[AppointmentConsumerQuarantineTable.payloadSha256]
            quarantineHash shouldBeEqualTo expectedHash
        }
    }

    @Test
    fun `expired processing lease is reclaimed instead of acknowledged as duplicate`() {
        val leaseClock = MutableAppointmentDatabaseClock(Instant.parse("2026-08-06T00:00:00Z"))
        val leasedStore = JdbcAppointmentConsumerInboxStore(
            database = database,
            maxAttempts = 2,
            clock = leaseClock,
            processingLease = java.time.Duration.ofSeconds(10),
        )
        leasedStore.begin(identity(), eventId(), provenance())
        leasedStore.begin(identity(), eventId(), provenance())
            .shouldBeEqualTo(AppointmentConsumerBeginResult.Duplicate(AppointmentConsumerStatus.PROCESSING))

        leaseClock.advance(java.time.Duration.ofSeconds(11))
        leasedStore.begin(identity(), eventId(), provenance())
            .shouldBeInstanceOf<AppointmentConsumerBeginResult.Acquired>().attemptCount
            .shouldBeEqualTo(2)
    }

    @Test
    fun `stale handler cannot complete after lease reclaim`() {
        val leaseClock = MutableAppointmentDatabaseClock(Instant.parse("2026-08-06T00:00:00Z"))
        val leasedStore = JdbcAppointmentConsumerInboxStore(
            database = database,
            maxAttempts = 3,
            clock = leaseClock,
            processingLease = java.time.Duration.ofSeconds(10),
        )
        val first = leasedStore.begin(identity(), eventId(), provenance())
            .shouldBeInstanceOf<AppointmentConsumerBeginResult.Acquired>()
        leaseClock.advance(java.time.Duration.ofSeconds(11))
        val reclaimed = leasedStore.begin(identity(), eventId(), provenance())
            .shouldBeInstanceOf<AppointmentConsumerBeginResult.Acquired>()

        leasedStore.markProcessed(identity(), eventId(), first.leaseUntil).shouldBeFalse()
        leasedStore.markProcessed(identity(), eventId(), reclaimed.leaseUntil).shouldBeTrue()
    }

    @Test
    fun `repeated crash lease expiry reaches the attempt bound`() {
        val leaseClock = MutableAppointmentDatabaseClock(Instant.parse("2026-08-06T00:00:00Z"))
        val leasedStore = JdbcAppointmentConsumerInboxStore(
            database = database,
            maxAttempts = 2,
            clock = leaseClock,
            processingLease = java.time.Duration.ofSeconds(10),
        )
        leasedStore.begin(identity(), eventId(), provenance())
        leaseClock.advance(java.time.Duration.ofSeconds(11))
        leasedStore.begin(identity(), eventId(), provenance())
            .shouldBeInstanceOf<AppointmentConsumerBeginResult.Acquired>().attemptCount
            .shouldBeEqualTo(2)
        leaseClock.advance(java.time.Duration.ofSeconds(11))

        leasedStore.begin(identity(), eventId(), provenance())
            .shouldBeEqualTo(AppointmentConsumerBeginResult.Duplicate(AppointmentConsumerStatus.QUARANTINED))
    }

    @Test
    fun `rejected records retain broker metadata and payload hash only`() {
        val record = org.apache.kafka.clients.consumer.ConsumerRecord<String, String>(
            "clinic.appointment.events",
            2,
            91L,
            "key",
            "secret-payload",
        )
        store.quarantineRejected(identity(), record, AppointmentConsumerFailureCode.INVALID_ENVELOPE).shouldBeTrue()

        transaction(database) {
            AppointmentConsumerRejectedRecordTable.selectAll().single().let { row ->
                row[AppointmentConsumerRejectedRecordTable.payloadSha256].length shouldBeEqualTo 64
                row[AppointmentConsumerRejectedRecordTable.topic] shouldBeEqualTo record.topic()
            }
        }
    }

    private fun identity(name: String = "notification") = AppointmentConsumerIdentity(
        consumerId = AppointmentLogicalConsumerId(name),
        streamId = AppointmentLogicalStreamId("appointment-events"),
    )

    private fun eventId(value: String = "event-inbox-42") = AppointmentEventId(value)

    private fun provenance() = AppointmentConsumerProvenance(
        topic = AppointmentTopic("scheduling.appointment-events"),
        partition = 1,
        offset = 12,
        schemaVersion = AppointmentEventEnvelope.CURRENT_SCHEMA_VERSION,
        tenantGroupId = 7,
        clinicId = 31,
        payloadSha256 = "a".repeat(64),
    )

    private class MutableAppointmentDatabaseClock(private var current: Instant) : AppointmentDatabaseClock {
        override fun now(): Instant = current

        fun advance(duration: java.time.Duration) {
            current = current.plus(duration)
        }
    }
}
