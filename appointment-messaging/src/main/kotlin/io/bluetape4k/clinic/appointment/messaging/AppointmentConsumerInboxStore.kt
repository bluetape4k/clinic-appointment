package io.bluetape4k.clinic.appointment.messaging

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.Database
import java.time.Instant

sealed interface AppointmentConsumerBeginResult {
    data class Acquired(val attemptCount: Int) : AppointmentConsumerBeginResult
    data class Duplicate(val status: AppointmentConsumerStatus) : AppointmentConsumerBeginResult
}

interface AppointmentConsumerInboxStore {
    fun begin(
        identity: AppointmentConsumerIdentity,
        eventId: AppointmentEventId,
        provenance: AppointmentConsumerProvenance,
    ): AppointmentConsumerBeginResult

    fun markProcessed(identity: AppointmentConsumerIdentity, eventId: AppointmentEventId): Boolean

    fun markFailure(
        identity: AppointmentConsumerIdentity,
        eventId: AppointmentEventId,
        failureCode: AppointmentConsumerFailureCode,
    ): AppointmentConsumerStatus

    fun quarantine(
        identity: AppointmentConsumerIdentity,
        eventId: AppointmentEventId,
        failureCode: AppointmentConsumerFailureCode,
    ): Boolean

    fun cleanupProcessed(cutoff: Instant, batchSize: Int): Int
}

/** Spring DataSource로 연결된 Exposed Database를 통해 inbox lifecycle을 저장합니다. */
class JdbcAppointmentConsumerInboxStore(
    private val database: Database,
    private val maxAttempts: Int = 8,
    private val clock: AppointmentDatabaseClock = AppointmentDatabaseClock.current,
) : AppointmentConsumerInboxStore {

    init {
        require(maxAttempts in 1..100) { "maxAttempts must be bounded" }
    }

    override fun begin(
        identity: AppointmentConsumerIdentity,
        eventId: AppointmentEventId,
        provenance: AppointmentConsumerProvenance,
    ): AppointmentConsumerBeginResult = transaction(database) {
        val inserted = AppointmentConsumerInboxTable.insertIgnore {
            it[logicalConsumerId] = identity.consumerId.value
            it[logicalStreamId] = identity.streamId.value
            it[this.eventId] = eventId.value
            it[topic] = provenance.topic.value
            it[partition] = provenance.partition
            it[offset] = provenance.offset
            it[schemaVersion] = provenance.schemaVersion
            it[tenantGroupId] = provenance.tenantGroupId
            it[clinicId] = provenance.clinicId
            it[payloadSha256] = provenance.payloadSha256
            it[status] = AppointmentConsumerStatus.PROCESSING
            it[attemptCount] = 1
            it[receivedAt] = clock.now()
        }.insertedCount == 1
        if (inserted) return@transaction AppointmentConsumerBeginResult.Acquired(attemptCount = 1)

        val row = AppointmentConsumerInboxTable
            .selectAll()
            .where { keyPredicate(identity, eventId) }
            .single()
        val status = row[AppointmentConsumerInboxTable.status]
        if (status == AppointmentConsumerStatus.RETRYABLE &&
            row[AppointmentConsumerInboxTable.attemptCount] <= this@JdbcAppointmentConsumerInboxStore.maxAttempts
        ) {
            val reclaimed = AppointmentConsumerInboxTable.update({
                keyPredicate(identity, eventId) and
                    (AppointmentConsumerInboxTable.status eq AppointmentConsumerStatus.RETRYABLE)
            }) {
                it[AppointmentConsumerInboxTable.status] = AppointmentConsumerStatus.PROCESSING
                it[AppointmentConsumerInboxTable.failureCode] = null
            }
            if (reclaimed == 1) {
                return@transaction AppointmentConsumerBeginResult.Acquired(
                    attemptCount = row[AppointmentConsumerInboxTable.attemptCount],
                )
            }
        }
        AppointmentConsumerBeginResult.Duplicate(status)
    }

    override fun markProcessed(identity: AppointmentConsumerIdentity, eventId: AppointmentEventId): Boolean =
        transaction(database) {
            AppointmentConsumerInboxTable.update({
                keyPredicate(identity, eventId) and
                    (AppointmentConsumerInboxTable.status eq AppointmentConsumerStatus.PROCESSING)
            }) {
                it[status] = AppointmentConsumerStatus.PROCESSED
                it[processedAt] = clock.now()
            } == 1
        }

    override fun markFailure(
        identity: AppointmentConsumerIdentity,
        eventId: AppointmentEventId,
        failureCode: AppointmentConsumerFailureCode,
    ): AppointmentConsumerStatus = transaction(database) {
        val row = AppointmentConsumerInboxTable
            .selectAll()
            .where { keyPredicate(identity, eventId) }
            .singleOrNull()
            ?: return@transaction AppointmentConsumerStatus.QUARANTINED
        val currentStatus = row[AppointmentConsumerInboxTable.status]
        if (currentStatus == AppointmentConsumerStatus.PROCESSED || currentStatus == AppointmentConsumerStatus.QUARANTINED) {
            return@transaction currentStatus
        }
        val currentAttempt = row[AppointmentConsumerInboxTable.attemptCount]
        if (currentAttempt >= this@JdbcAppointmentConsumerInboxStore.maxAttempts) {
            quarantineInTransaction(identity, eventId, failureCode = AppointmentConsumerFailureCode.ATTEMPT_EXHAUSTED, row = row)
            AppointmentConsumerStatus.QUARANTINED
        } else {
            AppointmentConsumerInboxTable.update({ keyPredicate(identity, eventId) }) {
                it[status] = AppointmentConsumerStatus.RETRYABLE
                it[attemptCount] = currentAttempt + 1
                it[this.failureCode] = failureCode.name
            }
            AppointmentConsumerStatus.RETRYABLE
        }
    }

    override fun quarantine(
        identity: AppointmentConsumerIdentity,
        eventId: AppointmentEventId,
        failureCode: AppointmentConsumerFailureCode,
    ): Boolean = transaction(database) {
        val row = AppointmentConsumerInboxTable
            .selectAll()
            .where { keyPredicate(identity, eventId) }
            .singleOrNull()
            ?: return@transaction false
        if (row[AppointmentConsumerInboxTable.status] == AppointmentConsumerStatus.PROCESSED) return@transaction false
        quarantineInTransaction(identity, eventId, failureCode, row)
        true
    }

    override fun cleanupProcessed(cutoff: Instant, batchSize: Int): Int = transaction(database) {
        require(batchSize in 1..1_000) { "batchSize must be bounded" }
        val candidates = AppointmentConsumerInboxTable
            .selectAll()
            .where {
                (AppointmentConsumerInboxTable.status inList listOf(
                    AppointmentConsumerStatus.PROCESSED,
                    AppointmentConsumerStatus.QUARANTINED,
                )) and (AppointmentConsumerInboxTable.processedAt lessEq cutoff)
            }
            .limit(batchSize)
            .map {
                Triple(
                    it[AppointmentConsumerInboxTable.logicalConsumerId],
                    it[AppointmentConsumerInboxTable.logicalStreamId],
                    it[AppointmentConsumerInboxTable.eventId],
                )
            }
        candidates.count { (consumerId, streamId, eventId) ->
            AppointmentConsumerInboxTable.deleteWhere {
                (logicalConsumerId eq consumerId) and
                    (logicalStreamId eq streamId) and
                    (this.eventId eq eventId)
            } == 1
        }
    }

    private fun quarantineInTransaction(
        identity: AppointmentConsumerIdentity,
        eventId: AppointmentEventId,
        failureCode: AppointmentConsumerFailureCode,
        row: org.jetbrains.exposed.v1.core.ResultRow,
    ) {
        AppointmentConsumerInboxTable.update({ keyPredicate(identity, eventId) }) {
            it[status] = AppointmentConsumerStatus.QUARANTINED
            it[this.failureCode] = failureCode.name
            it[processedAt] = clock.now()
        }
        AppointmentConsumerQuarantineTable.insertIgnore {
            it[logicalConsumerId] = row[AppointmentConsumerInboxTable.logicalConsumerId]
            it[logicalStreamId] = row[AppointmentConsumerInboxTable.logicalStreamId]
            it[this.eventId] = row[AppointmentConsumerInboxTable.eventId]
            it[this.failureCode] = failureCode.name
            it[topic] = row[AppointmentConsumerInboxTable.topic]
            it[partition] = row[AppointmentConsumerInboxTable.partition]
            it[offset] = row[AppointmentConsumerInboxTable.offset]
            it[schemaVersion] = row[AppointmentConsumerInboxTable.schemaVersion]
            it[tenantGroupId] = row[AppointmentConsumerInboxTable.tenantGroupId]
            it[clinicId] = row[AppointmentConsumerInboxTable.clinicId]
            it[payloadSha256] = row[AppointmentConsumerInboxTable.payloadSha256]
        }
    }

    private fun keyPredicate(
        identity: AppointmentConsumerIdentity,
        eventId: AppointmentEventId,
    ) = (AppointmentConsumerInboxTable.logicalConsumerId eq identity.consumerId.value) and
        (AppointmentConsumerInboxTable.logicalStreamId eq identity.streamId.value) and
        (AppointmentConsumerInboxTable.eventId eq eventId.value)
}
