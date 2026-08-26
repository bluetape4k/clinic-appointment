package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.assertions.shouldBeEqualTo
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class AppointmentConsumerRetentionServiceTest {
    private lateinit var database: Database
    private lateinit var store: JdbcAppointmentConsumerInboxStore
    private val now = Instant.parse("2026-08-07T00:00:00Z")

    @BeforeEach
    fun setUp() {
        database = Database.connect(
            "jdbc:h2:mem:appointment_consumer_retention_${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver",
        )
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(
                AppointmentConsumerInboxTable,
                AppointmentConsumerRejectedRecordTable,
                AppointmentConsumerQuarantineTable,
                AppointmentConsumerReplayAuditTable,
            )
            AppointmentConsumerReplayAuditTable.deleteAll()
            AppointmentConsumerQuarantineTable.deleteAll()
            AppointmentConsumerRejectedRecordTable.deleteAll()
            AppointmentConsumerInboxTable.deleteAll()
        }
        store = JdbcAppointmentConsumerInboxStore(database)
    }

    @Test
    fun `cleanup deletes terminal metadata in bounded tables but keeps processing rows`() {
        val old = now.minus(Duration.ofDays(2))
        store.begin(identity("processed"), eventId("processed"), provenance())
        store.markProcessed(identity("processed"), eventId("processed"))
        store.begin(identity("processing"), eventId("processing"), provenance())
        store.begin(identity("quarantine-inbox"), eventId("quarantine-inbox"), provenance())
        store.quarantine(identity("quarantine-inbox"), eventId("quarantine-inbox"), AppointmentConsumerFailureCode.HANDLER_FAILED)
        transaction(database) {
            AppointmentConsumerInboxTable.update({
                AppointmentConsumerInboxTable.logicalConsumerId eq "processed"
            }) {
                it[AppointmentConsumerInboxTable.processedAt] = old
            }
            AppointmentConsumerInboxTable.update({
                AppointmentConsumerInboxTable.logicalConsumerId eq "quarantine-inbox"
            }) {
                it[AppointmentConsumerInboxTable.processedAt] = old
            }
            AppointmentConsumerQuarantineTable.update({
                AppointmentConsumerQuarantineTable.eventId eq "retention-quarantine-inbox"
            }) {
                it[AppointmentConsumerQuarantineTable.createdAt] = old
            }
            AppointmentConsumerRejectedRecordTable.insert {
                it[logicalConsumerId] = "notification"
                it[logicalStreamId] = "appointment-events"
                it[failureCode] = AppointmentConsumerFailureCode.INVALID_ENVELOPE.name
                it[topic] = "scheduling.appointment-events"
                it[partition] = 0
                it[offset] = 11
                it[payloadSha256] = "b".repeat(64)
                it[createdAt] = old
            }
            AppointmentConsumerQuarantineTable.insert {
                it[logicalConsumerId] = "notification"
                it[logicalStreamId] = "appointment-events"
                it[eventId] = "quarantine-1"
                it[failureCode] = AppointmentConsumerFailureCode.HANDLER_FAILED.name
                it[topic] = "scheduling.appointment-events"
                it[partition] = 0
                it[offset] = 12
                it[schemaVersion] = AppointmentEventEnvelope.CURRENT_SCHEMA_VERSION
                it[tenantGroupId] = 7
                it[clinicId] = 31
                it[payloadSha256] = "c".repeat(64)
                it[createdAt] = old
            }
            AppointmentConsumerReplayAuditTable.insert {
                it[requestId] = "retention-request-1"
                it[logicalConsumerId] = "notification"
                it[logicalStreamId] = "appointment-events"
                it[tenantGroupId] = 7
                it[clinicId] = 31
                it[fromOffset] = 1
                it[toOffset] = 1
                it[requestHash] = "d".repeat(64)
                it[dryRun] = true
                it[approvedBy] = "operator-1"
                it[status] = AppointmentReplayAuditStatus.DRY_RUN
                it[createdAt] = old
            }
            AppointmentConsumerReplayAuditTable.insert {
                it[requestId] = "retention-request-pending"
                it[logicalConsumerId] = "notification"
                it[logicalStreamId] = "appointment-events"
                it[tenantGroupId] = 7
                it[clinicId] = 31
                it[fromOffset] = 2
                it[toOffset] = 2
                it[requestHash] = "e".repeat(64)
                it[dryRun] = false
                it[approvedBy] = "operator-1"
                it[status] = AppointmentReplayAuditStatus.REQUESTED
                it[createdAt] = old
            }
        }

        val service = AppointmentConsumerRetentionService(
            database = database,
            inboxStore = store,
            properties = AppointmentConsumerRetentionProperties(
                enabled = true,
                processedAge = Duration.ofDays(1),
                rejectedAge = Duration.ofDays(1),
                quarantineAge = Duration.ofDays(1),
                replayAuditAge = Duration.ofDays(1),
                batchSize = 10,
            ),
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

        service.cleanup() shouldBeEqualTo AppointmentConsumerRetentionResult(2, 1, 2, 1)
        transaction(database) {
            AppointmentConsumerInboxTable.selectAll().count() shouldBeEqualTo 1L
            AppointmentConsumerInboxTable.selectAll().single()[AppointmentConsumerInboxTable.logicalConsumerId]
                .shouldBeEqualTo("processing")
            AppointmentConsumerRejectedRecordTable.selectAll().count() shouldBeEqualTo 0L
            AppointmentConsumerQuarantineTable.selectAll().count() shouldBeEqualTo 0L
            AppointmentConsumerReplayAuditTable.selectAll().count() shouldBeEqualTo 1L
            AppointmentConsumerReplayAuditTable.selectAll().single()[AppointmentConsumerReplayAuditTable.status]
                .shouldBeEqualTo(AppointmentReplayAuditStatus.REQUESTED)
        }
    }

    private fun identity(consumerId: String) = AppointmentConsumerIdentity(
        AppointmentLogicalConsumerId(consumerId),
        AppointmentLogicalStreamId("appointment-events"),
    )

    private fun eventId(value: String) = AppointmentEventId("retention-$value")

    private fun provenance() = AppointmentConsumerProvenance(
        topic = AppointmentTopic("scheduling.appointment-events"),
        partition = 0,
        offset = 10,
        schemaVersion = AppointmentEventEnvelope.CURRENT_SCHEMA_VERSION,
        tenantGroupId = 7,
        clinicId = 31,
        payloadSha256 = "a".repeat(64),
    )
}
