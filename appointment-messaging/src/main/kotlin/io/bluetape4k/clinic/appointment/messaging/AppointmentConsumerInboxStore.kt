package io.bluetape4k.clinic.appointment.messaging

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.core.SortOrder
import org.apache.kafka.clients.consumer.ConsumerRecord
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

sealed interface AppointmentConsumerBeginResult {
    data class Acquired(
        val attemptCount: Int,
        /** 이 lease timestamp가 terminal update의 compare-and-set fencing token입니다. */
        val leaseUntil: Instant,
    ) : AppointmentConsumerBeginResult
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

    fun markProcessed(
        identity: AppointmentConsumerIdentity,
        eventId: AppointmentEventId,
        leaseUntil: Instant?,
    ): Boolean

    fun markFailure(
        identity: AppointmentConsumerIdentity,
        eventId: AppointmentEventId,
        failureCode: AppointmentConsumerFailureCode,
        leaseUntil: Instant?,
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

    /** PROCESSED와 QUARANTINED terminal inbox를 bounded batch로 제거합니다. */
    fun cleanupTerminal(cutoff: Instant, batchSize: Int): Int = cleanupProcessed(cutoff, batchSize)

    /** 현재 처리 중인 inbox 중 가장 오래된 row의 age를 조회합니다. */
    fun oldestProcessingAge(now: Instant): Duration? = null
}

/** Spring DataSource로 연결된 Exposed Database를 통해 inbox lifecycle을 저장합니다. */
class JdbcAppointmentConsumerInboxStore(
    private val database: Database,
    private val maxAttempts: Int = 8,
    private val clock: AppointmentDatabaseClock = AppointmentDatabaseClock.current,
    private val processingLease: Duration = Duration.ofMinutes(5),
    private val metrics: AppointmentConsumerMetrics = NoopAppointmentConsumerMetrics,
) : AppointmentConsumerInboxStore {

    init {
        require(maxAttempts in 1..100) { "maxAttempts must be bounded" }
        require(!processingLease.isNegative && !processingLease.isZero) { "processingLease must be positive" }
    }

    override fun begin(
        identity: AppointmentConsumerIdentity,
        eventId: AppointmentEventId,
        provenance: AppointmentConsumerProvenance,
    ): AppointmentConsumerBeginResult = measured("begin") { transaction(database) {
        val now = clock.now()
        val leaseUntil = now.plus(processingLease)
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
            it[processingLeaseUntil] = leaseUntil
        }.insertedCount == 1
        if (inserted) return@transaction AppointmentConsumerBeginResult.Acquired(
            attemptCount = 1,
            leaseUntil = leaseUntil,
        )

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
                    (AppointmentConsumerInboxTable.status eq AppointmentConsumerStatus.RETRYABLE) and
                    (AppointmentConsumerInboxTable.attemptCount eq row[AppointmentConsumerInboxTable.attemptCount])
            }) {
                it[AppointmentConsumerInboxTable.status] = AppointmentConsumerStatus.PROCESSING
                it[AppointmentConsumerInboxTable.failureCode] = null
                it[AppointmentConsumerInboxTable.processingLeaseUntil] = leaseUntil
            }
            if (reclaimed == 1) {
                return@transaction AppointmentConsumerBeginResult.Acquired(
                    attemptCount = row[AppointmentConsumerInboxTable.attemptCount],
                    leaseUntil = leaseUntil,
                )
            }
        }
        if (status == AppointmentConsumerStatus.PROCESSING) {
            val leaseUntil = row[AppointmentConsumerInboxTable.processingLeaseUntil]
            if (leaseUntil == null || !leaseUntil.isAfter(now)) {
                val currentAttempt = row[AppointmentConsumerInboxTable.attemptCount]
                if (currentAttempt >= this@JdbcAppointmentConsumerInboxStore.maxAttempts) {
                    val quarantined = quarantineInTransaction(
                        identity = identity,
                        eventId = eventId,
                        failureCode = AppointmentConsumerFailureCode.ATTEMPT_EXHAUSTED,
                        row = row,
                        predicate = keyPredicate(identity, eventId) and
                            (AppointmentConsumerInboxTable.status eq AppointmentConsumerStatus.PROCESSING) and
                            leaseCasPredicate(leaseUntil),
                    )
                    return@transaction AppointmentConsumerBeginResult.Duplicate(
                        if (quarantined) AppointmentConsumerStatus.QUARANTINED else currentStatus(identity, eventId),
                        provenanceMatches,
                    )
                }
                val reclaimedLease = now.plus(processingLease)
                val reclaimed = AppointmentConsumerInboxTable.update({
                    keyPredicate(identity, eventId) and
                        (AppointmentConsumerInboxTable.status eq AppointmentConsumerStatus.PROCESSING) and
                        (AppointmentConsumerInboxTable.attemptCount eq currentAttempt) and
                        leaseCasPredicate(leaseUntil)
                }) {
                    it[AppointmentConsumerInboxTable.processingLeaseUntil] = reclaimedLease
                    it[AppointmentConsumerInboxTable.attemptCount] = currentAttempt + 1
                    it[AppointmentConsumerInboxTable.failureCode] = AppointmentConsumerFailureCode.LEASE_EXPIRED.name
                }
                if (reclaimed == 1) {
                    metrics.retry(AppointmentConsumerFailureCode.LEASE_EXPIRED)
                    return@transaction AppointmentConsumerBeginResult.Acquired(
                        attemptCount = currentAttempt + 1,
                        leaseUntil = reclaimedLease,
                    )
                }
            }
        }
        AppointmentConsumerBeginResult.Duplicate(status, provenanceMatches)
    } }

    override fun markProcessed(
        identity: AppointmentConsumerIdentity,
        eventId: AppointmentEventId,
        leaseUntil: Instant?,
    ): Boolean =
        measured("processed") { transaction(database) {
            AppointmentConsumerInboxTable.update({
                keyPredicate(identity, eventId) and
                    (AppointmentConsumerInboxTable.status eq AppointmentConsumerStatus.PROCESSING) and
                    leasePredicate(leaseUntil)
            }) {
                it[status] = AppointmentConsumerStatus.PROCESSED
                it[processedAt] = clock.now()
                it[processingLeaseUntil] = null
            } == 1
        } }

    override fun markFailure(
        identity: AppointmentConsumerIdentity,
        eventId: AppointmentEventId,
        failureCode: AppointmentConsumerFailureCode,
        leaseUntil: Instant?,
    ): AppointmentConsumerStatus = measured("failure") { transaction(database) {
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
        val predicate = keyPredicate(identity, eventId) and
            (AppointmentConsumerInboxTable.status eq AppointmentConsumerStatus.PROCESSING) and
            (AppointmentConsumerInboxTable.attemptCount eq currentAttempt) and
            leasePredicate(leaseUntil)
        if (currentAttempt >= this@JdbcAppointmentConsumerInboxStore.maxAttempts) {
            if (quarantineInTransaction(
                    identity,
                    eventId,
                    failureCode = AppointmentConsumerFailureCode.ATTEMPT_EXHAUSTED,
                    row = row,
                    predicate = predicate,
                )
            ) AppointmentConsumerStatus.QUARANTINED else currentStatus(identity, eventId)
        } else {
            val updated = AppointmentConsumerInboxTable.update({ predicate }) {
                it[status] = AppointmentConsumerStatus.RETRYABLE
                it[attemptCount] = currentAttempt + 1
                it[this.failureCode] = failureCode.name
                it[processingLeaseUntil] = null
            }
            if (updated == 1) AppointmentConsumerStatus.RETRYABLE else currentStatus(identity, eventId)
        }
    } }

    fun markProcessed(identity: AppointmentConsumerIdentity, eventId: AppointmentEventId): Boolean =
        markProcessed(identity, eventId, null)

    fun markFailure(
        identity: AppointmentConsumerIdentity,
        eventId: AppointmentEventId,
        failureCode: AppointmentConsumerFailureCode,
    ): AppointmentConsumerStatus = markFailure(identity, eventId, failureCode, null)

    override fun quarantine(
        identity: AppointmentConsumerIdentity,
        eventId: AppointmentEventId,
        failureCode: AppointmentConsumerFailureCode,
    ): Boolean = measured("quarantine") { transaction(database) {
        val row = AppointmentConsumerInboxTable
            .selectAll()
            .where { keyPredicate(identity, eventId) }
            .singleOrNull()
            ?: return@transaction false
        if (row[AppointmentConsumerInboxTable.status] == AppointmentConsumerStatus.PROCESSED) return@transaction false
        quarantineInTransaction(identity, eventId, failureCode, row, keyPredicate(identity, eventId))
    } }

    override fun quarantineRejected(
        identity: AppointmentConsumerIdentity,
        record: ConsumerRecord<*, *>,
        failureCode: AppointmentConsumerFailureCode,
    ): Boolean = measured("rejected") { transaction(database) {
        AppointmentConsumerRejectedRecordTable.insertIgnore {
            it[logicalConsumerId] = identity.consumerId.value
            it[logicalStreamId] = identity.streamId.value
            it[this.failureCode] = failureCode.name
            it[topic] = record.topic().take(249)
            it[partition] = record.partition()
            it[offset] = record.offset()
            it[payloadSha256] = sha256(record.value()?.toString().orEmpty())
        }.insertedCount == 1
    } }

    override fun cleanupProcessed(cutoff: Instant, batchSize: Int): Int = measured("cleanup") { transaction(database) {
        cleanupTerminalInternal(cutoff, batchSize, AppointmentConsumerStatus.PROCESSED)
    } }

    override fun cleanupTerminal(cutoff: Instant, batchSize: Int): Int = measured("cleanup") { transaction(database) {
        val statuses = listOf(AppointmentConsumerStatus.PROCESSED, AppointmentConsumerStatus.QUARANTINED)
        val candidates = AppointmentConsumerInboxTable
            .selectAll()
            .where {
                (AppointmentConsumerInboxTable.status inList statuses) and
                    (AppointmentConsumerInboxTable.processedAt lessEq cutoff)
            }
            .orderBy(AppointmentConsumerInboxTable.processedAt to SortOrder.ASC)
            .limit(batchSize)
            .map {
                Triple(
                    it[AppointmentConsumerInboxTable.logicalConsumerId],
                    it[AppointmentConsumerInboxTable.logicalStreamId],
                    it[AppointmentConsumerInboxTable.eventId],
                )
            }
        if (candidates.isEmpty()) {
            0
        } else {
            val predicate = candidates
                .map { (consumerId, streamId, eventId) ->
                    (AppointmentConsumerInboxTable.logicalConsumerId eq consumerId) and
                        (AppointmentConsumerInboxTable.logicalStreamId eq streamId) and
                        (AppointmentConsumerInboxTable.eventId eq eventId)
                }
                .reduce { left, right -> left or right }
            AppointmentConsumerInboxTable.deleteWhere { predicate }
        }
    } }

    private fun cleanupTerminalInternal(cutoff: Instant, batchSize: Int, status: AppointmentConsumerStatus): Int {
        require(batchSize in 1..1_000) { "batchSize must be bounded" }
        val candidates = AppointmentConsumerInboxTable
            .selectAll()
            .where {
                (AppointmentConsumerInboxTable.status eq status) and
                    (AppointmentConsumerInboxTable.processedAt lessEq cutoff)
            }
            .orderBy(AppointmentConsumerInboxTable.processedAt to SortOrder.ASC)
            .limit(batchSize)
            .map {
                Triple(
                    it[AppointmentConsumerInboxTable.logicalConsumerId],
                    it[AppointmentConsumerInboxTable.logicalStreamId],
                    it[AppointmentConsumerInboxTable.eventId],
                )
            }
        if (candidates.isEmpty()) return 0
        val predicate = candidates
            .map { (consumerId, streamId, eventId) ->
                (AppointmentConsumerInboxTable.logicalConsumerId eq consumerId) and
                    (AppointmentConsumerInboxTable.logicalStreamId eq streamId) and
                    (AppointmentConsumerInboxTable.eventId eq eventId)
            }
            .reduce { left, right -> left or right }
        return AppointmentConsumerInboxTable.deleteWhere { predicate }
    }

    override fun oldestProcessingAge(now: Instant): Duration? = measured("oldest_age") {
        transaction(database) {
            AppointmentConsumerInboxTable
                .selectAll()
                .where { AppointmentConsumerInboxTable.status eq AppointmentConsumerStatus.PROCESSING }
                .orderBy(AppointmentConsumerInboxTable.receivedAt to SortOrder.ASC)
                .limit(1)
                .singleOrNull()
                ?.let { Duration.between(it[AppointmentConsumerInboxTable.receivedAt], now).coerceAtLeast(Duration.ZERO) }
        }
    }

    private fun <T> measured(operation: String, block: () -> T): T {
        val startedAt = System.nanoTime()
        return try {
            block()
        } finally {
            metrics.recordInboxTransaction(
                operation = operation,
                duration = Duration.ofNanos((System.nanoTime() - startedAt).coerceAtLeast(0L)),
            )
        }
    }

    private fun quarantineInTransaction(
        identity: AppointmentConsumerIdentity,
        eventId: AppointmentEventId,
        failureCode: AppointmentConsumerFailureCode,
        row: org.jetbrains.exposed.v1.core.ResultRow,
        predicate: Op<Boolean> = keyPredicate(identity, eventId),
    ): Boolean {
        val updated = AppointmentConsumerInboxTable.update({ predicate }) {
            it[status] = AppointmentConsumerStatus.QUARANTINED
            it[this.failureCode] = failureCode.name
            it[processedAt] = clock.now()
            it[processingLeaseUntil] = null
        }
        if (updated == 0) return false
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
        return true
    }

    private fun currentStatus(identity: AppointmentConsumerIdentity, eventId: AppointmentEventId): AppointmentConsumerStatus =
        AppointmentConsumerInboxTable
            .selectAll()
            .where { keyPredicate(identity, eventId) }
            .singleOrNull()
            ?.get(AppointmentConsumerInboxTable.status)
            ?: AppointmentConsumerStatus.QUARANTINED

    private fun leasePredicate(leaseUntil: Instant?): Op<Boolean> =
        leaseUntil?.let { AppointmentConsumerInboxTable.processingLeaseUntil eq it }
            ?: (AppointmentConsumerInboxTable.processingLeaseUntil.isNull() or
                AppointmentConsumerInboxTable.processingLeaseUntil.isNotNull())

    private fun leaseCasPredicate(leaseUntil: Instant?): Op<Boolean> =
        leaseUntil?.let { AppointmentConsumerInboxTable.processingLeaseUntil eq it }
            ?: AppointmentConsumerInboxTable.processingLeaseUntil.isNull()

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
