package io.bluetape4k.clinic.appointment.messaging

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.Database
import org.apache.kafka.clients.consumer.ConsumerRecord
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

sealed interface AppointmentConsumerBeginResult {
    data class Acquired(val attemptCount: Int) : AppointmentConsumerBeginResult
    data class Duplicate(
        val status: AppointmentConsumerStatus,
        val provenanceMatches: Boolean = true,
    ) : AppointmentConsumerBeginResult
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

    fun quarantineRejected(
        identity: AppointmentConsumerIdentity,
        record: ConsumerRecord<*, *>,
        failureCode: AppointmentConsumerFailureCode,
    ): Boolean

    fun cleanupProcessed(cutoff: Instant, batchSize: Int): Int
}

/** Spring DataSource로 연결된 Exposed Database를 통해 inbox lifecycle을 저장합니다. */
class JdbcAppointmentConsumerInboxStore(
    private val database: Database,
    private val maxAttempts: Int = 8,
    private val clock: AppointmentDatabaseClock = AppointmentDatabaseClock.current,
    private val processingLease: Duration = Duration.ofMinutes(5),
) : AppointmentConsumerInboxStore {

    init {
        require(maxAttempts in 1..100) { "maxAttempts must be bounded" }
        require(!processingLease.isNegative && !processingLease.isZero) { "processingLease must be positive" }
    }

    override fun begin(
        identity: AppointmentConsumerIdentity,
        eventId: AppointmentEventId,
        provenance: AppointmentConsumerProvenance,
    ): AppointmentConsumerBeginResult = transaction(database) {
        val now = clock.now()
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
            it[receivedAt] = now
            it[processingLeaseUntil] = now.plus(processingLease)
        }.insertedCount == 1
        if (inserted) return@transaction AppointmentConsumerBeginResult.Acquired(attemptCount = 1)

        val row = AppointmentConsumerInboxTable
            .selectAll()
            .where { keyPredicate(identity, eventId) }
            .single()
        val status = row[AppointmentConsumerInboxTable.status]
        val provenanceMatches = row[AppointmentConsumerInboxTable.topic] == provenance.topic.value &&
            row[AppointmentConsumerInboxTable.partition] == provenance.partition &&
            row[AppointmentConsumerInboxTable.offset] == provenance.offset &&
            row[AppointmentConsumerInboxTable.schemaVersion] == provenance.schemaVersion &&
            row[AppointmentConsumerInboxTable.tenantGroupId] == provenance.tenantGroupId &&
            row[AppointmentConsumerInboxTable.clinicId] == provenance.clinicId &&
            row[AppointmentConsumerInboxTable.payloadSha256] == provenance.payloadSha256
        if (status == AppointmentConsumerStatus.RETRYABLE &&
            row[AppointmentConsumerInboxTable.attemptCount] <= this@JdbcAppointmentConsumerInboxStore.maxAttempts
        ) {
            val reclaimed = AppointmentConsumerInboxTable.update({
                keyPredicate(identity, eventId) and
                    (AppointmentConsumerInboxTable.status eq AppointmentConsumerStatus.RETRYABLE)
            }) {
                it[AppointmentConsumerInboxTable.status] = AppointmentConsumerStatus.PROCESSING
                it[AppointmentConsumerInboxTable.failureCode] = null
                it[AppointmentConsumerInboxTable.processingLeaseUntil] = now.plus(processingLease)
            }
            if (reclaimed == 1) {
                return@transaction AppointmentConsumerBeginResult.Acquired(
                    attemptCount = row[AppointmentConsumerInboxTable.attemptCount],
                )
            }
        }
        if (status == AppointmentConsumerStatus.PROCESSING) {
            val leaseUntil = row[AppointmentConsumerInboxTable.processingLeaseUntil]
            if (leaseUntil == null || !leaseUntil.isAfter(now)) {
                val reclaimed = AppointmentConsumerInboxTable.update({
                    keyPredicate(identity, eventId) and
                        (AppointmentConsumerInboxTable.status eq AppointmentConsumerStatus.PROCESSING)
                }) {
                    it[AppointmentConsumerInboxTable.processingLeaseUntil] = now.plus(processingLease)
                    it[AppointmentConsumerInboxTable.failureCode] = null
                }
                if (reclaimed == 1) {
                    return@transaction AppointmentConsumerBeginResult.Acquired(
                        attemptCount = row[AppointmentConsumerInboxTable.attemptCount],
                    )
                }
            }
        }
        AppointmentConsumerBeginResult.Duplicate(status, provenanceMatches)
    }

    override fun markProcessed(identity: AppointmentConsumerIdentity, eventId: AppointmentEventId): Boolean =
        transaction(database) {
            AppointmentConsumerInboxTable.update({
                keyPredicate(identity, eventId) and
                    (AppointmentConsumerInboxTable.status eq AppointmentConsumerStatus.PROCESSING)
            }) {
                it[status] = AppointmentConsumerStatus.PROCESSED
                it[processedAt] = clock.now()
                it[processingLeaseUntil] = null
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
        if (currentStatus != AppointmentConsumerStatus.PROCESSING) return@transaction currentStatus
        val currentAttempt = row[AppointmentConsumerInboxTable.attemptCount]
        if (currentAttempt >= this@JdbcAppointmentConsumerInboxStore.maxAttempts) {
            quarantineInTransaction(identity, eventId, failureCode = AppointmentConsumerFailureCode.ATTEMPT_EXHAUSTED, row = row)
            AppointmentConsumerStatus.QUARANTINED
        } else {
            AppointmentConsumerInboxTable.update({ keyPredicate(identity, eventId) }) {
                it[status] = AppointmentConsumerStatus.RETRYABLE
                it[attemptCount] = currentAttempt + 1
                it[this.failureCode] = failureCode.name
                it[processingLeaseUntil] = null
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

    override fun quarantineRejected(
        identity: AppointmentConsumerIdentity,
        record: ConsumerRecord<*, *>,
        failureCode: AppointmentConsumerFailureCode,
    ): Boolean = transaction(database) {
        AppointmentConsumerRejectedRecordTable.insertIgnore {
            it[logicalConsumerId] = identity.consumerId.value
            it[logicalStreamId] = identity.streamId.value
            it[this.failureCode] = failureCode.name
            it[topic] = record.topic().take(249)
            it[partition] = record.partition()
            it[offset] = record.offset()
            it[payloadSha256] = sha256(record.value()?.toString().orEmpty())
        }.insertedCount == 1
    }

    override fun cleanupProcessed(cutoff: Instant, batchSize: Int): Int = transaction(database) {
        require(batchSize in 1..1_000) { "batchSize must be bounded" }
        val candidates = AppointmentConsumerInboxTable
            .selectAll()
            .where {
                (AppointmentConsumerInboxTable.status eq AppointmentConsumerStatus.PROCESSED) and
                    (AppointmentConsumerInboxTable.processedAt lessEq cutoff)
            }
            .limit(batchSize)
            .map {
                Triple(
                    it[AppointmentConsumerInboxTable.logicalConsumerId],
                    it[AppointmentConsumerInboxTable.logicalStreamId],
                    it[AppointmentConsumerInboxTable.eventId],
                )
            }
        if (candidates.isEmpty()) return@transaction 0
        val predicate = candidates
            .map { (consumerId, streamId, eventId) ->
                (AppointmentConsumerInboxTable.logicalConsumerId eq consumerId) and
                    (AppointmentConsumerInboxTable.logicalStreamId eq streamId) and
                    (AppointmentConsumerInboxTable.eventId eq eventId)
            }
            .reduce { left, right -> left or right }
        AppointmentConsumerInboxTable.deleteWhere { predicate }
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
            it[processingLeaseUntil] = null
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

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
