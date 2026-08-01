package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityReevaluationCursor
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityReevaluationJobRecord
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityReevaluationJobStatus
import io.bluetape4k.clinic.appointment.model.tables.BookingReliabilityReevaluationJobs
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Duration
import java.time.Instant

/**
 * 예약 신뢰성 재평가 job의 idempotent 생성, lease claim, checkpoint를 관리합니다.
 */
class BookingReliabilityReevaluationJobRepository(
    private val leaseDuration: Duration = Duration.ofSeconds(30),
    private val retryDelay: Duration = Duration.ofSeconds(5),
    private val maxAttempts: Int = 5,
) {
    init {
        require(!leaseDuration.isNegative && !leaseDuration.isZero) { "leaseDuration must be positive" }
        require(!retryDelay.isNegative) { "retryDelay must be non-negative" }
        require(maxAttempts > 0) { "maxAttempts must be positive" }
    }

    fun create(record: BookingReliabilityReevaluationJobRecord): BookingReliabilityReevaluationJobRecord {
        findCommandRow(record.tenantGroupId, record.clinicId, record.memberId, record.idempotencyKeyHash)
            ?.let { row ->
                require(row[BookingReliabilityReevaluationJobs.commandHash] == record.commandHash) {
                    "idempotency key is already bound to a different reevaluation command hash"
                }
                return row.toJobRecord()
            }

        val id =
            BookingReliabilityReevaluationJobs.insertAndGetId {
                it[tenantGroupId] = record.tenantGroupId
                it[clinicId] = record.clinicId
                it[memberId] = record.memberId.value
                it[policyVersionId] = record.policyVersionId
                it[idempotencyKeyHash] = record.idempotencyKeyHash
                it[commandHash] = record.commandHash
                it[status] = record.status
                it[nextAttemptAt] = record.nextAttemptAt
                it[leaseOwner] = record.leaseOwner
                it[leaseExpiresAt] = record.leaseExpiresAt
                it[attemptCount] = record.attemptCount
                it[cursorOccurredAt] = record.cursorOccurredAt
                it[cursorEventId] = record.cursorEventId
                it[scannedCount] = record.scannedCount
                it[decisionCount] = record.decisionCount
                it[lastFailureCode] = record.lastFailureCode
            }.value
        return requireNotNull(findJob(id))
    }

    fun findJob(jobId: Long): BookingReliabilityReevaluationJobRecord? =
        BookingReliabilityReevaluationJobs
            .selectAll()
            .where { BookingReliabilityReevaluationJobs.id eq jobId }
            .singleOrNull()
            ?.toJobRecord()

    fun findDueJobIds(limit: Int): List<Long> {
        require(limit > 0) { "limit must be positive" }
        val dbNow = currentDatabaseTimestamp()
        return BookingReliabilityReevaluationJobs
            .selectAll()
            .where { duePredicate(dbNow) }
            .orderBy(
                BookingReliabilityReevaluationJobs.nextAttemptAt to SortOrder.ASC,
                BookingReliabilityReevaluationJobs.id to SortOrder.ASC,
            )
            .limit(limit)
            .map { it[BookingReliabilityReevaluationJobs.id].value }
    }

    fun claimDue(jobId: Long, leaseOwner: String): BookingReliabilityReevaluationJobRecord? {
        require(jobId > 0) { "jobId must be positive" }
        require(leaseOwner.isNotBlank() && leaseOwner.length <= 160) {
            "leaseOwner must contain 1..160 characters"
        }
        val dbNow = currentDatabaseTimestamp()
        val row = findJobRow(jobId) ?: return null
        val canClaim =
            row[BookingReliabilityReevaluationJobs.status] in READY_STATES ||
                (
                    row[BookingReliabilityReevaluationJobs.status] == BookingReliabilityReevaluationJobStatus.RUNNING &&
                        row[BookingReliabilityReevaluationJobs.leaseExpiresAt]?.let { !it.isAfter(dbNow) } == true
                    )
        if (!canClaim || row[BookingReliabilityReevaluationJobs.nextAttemptAt].isAfter(dbNow)) {
            return null
        }
        val updated =
            BookingReliabilityReevaluationJobs.update({
                (BookingReliabilityReevaluationJobs.id eq jobId) and duePredicate(dbNow)
            }) {
                it[status] = BookingReliabilityReevaluationJobStatus.RUNNING
                it[BookingReliabilityReevaluationJobs.leaseOwner] = leaseOwner
                it[leaseExpiresAt] = dbNow.plus(leaseDuration)
                it[attemptCount] = row[BookingReliabilityReevaluationJobs.attemptCount] + 1
                it[updatedAt] = dbNow
            }
        if (updated != 1) return null
        return findJob(jobId)
    }

    fun checkpoint(
        jobId: Long,
        leaseOwner: String,
        cursor: BookingReliabilityReevaluationCursor,
    ): Boolean {
        val dbNow = currentDatabaseTimestamp()
        val row = findJobRow(jobId) ?: return false
        if (!hasActiveLease(row, leaseOwner, dbNow)) return false
        if (!isMonotonic(row, cursor)) return false
        return BookingReliabilityReevaluationJobs.update({
            activeLeasePredicate(jobId, leaseOwner, dbNow)
        }) {
            it[cursorOccurredAt] = cursor.cursorOccurredAt
            it[cursorEventId] = cursor.cursorEventId
            it[scannedCount] = cursor.scannedCount
            it[decisionCount] = cursor.decisionCount
            it[updatedAt] = dbNow
        } == 1
    }

    fun complete(jobId: Long, leaseOwner: String): Boolean =
        finish(jobId, leaseOwner, BookingReliabilityReevaluationJobStatus.COMPLETED, failureCode = null)

    fun markStale(jobId: Long, leaseOwner: String): Boolean =
        finish(jobId, leaseOwner, BookingReliabilityReevaluationJobStatus.STALE, failureCode = null)

    /** 운영자가 추가 재평가를 멈출 때 lease를 즉시 회수하고 PAUSED로 전환합니다. */
    fun pause(jobId: Long): Boolean {
        require(jobId > 0) { "jobId must be positive" }
        val dbNow = currentDatabaseTimestamp()
        return BookingReliabilityReevaluationJobs.update({
            (BookingReliabilityReevaluationJobs.id eq jobId) and
                (BookingReliabilityReevaluationJobs.status inList
                    listOf(
                        BookingReliabilityReevaluationJobStatus.PENDING,
                        BookingReliabilityReevaluationJobStatus.RETRY_WAIT,
                        BookingReliabilityReevaluationJobStatus.RUNNING,
                    ))
        }) {
            it[status] = BookingReliabilityReevaluationJobStatus.PAUSED
            it[leaseOwner] = null
            it[leaseExpiresAt] = null
            it[updatedAt] = dbNow
        } == 1
    }

    /** 승인된 재개 시각에 PAUSED job을 다시 due 상태로 돌립니다. */
    fun resume(jobId: Long): Boolean {
        require(jobId > 0) { "jobId must be positive" }
        val dbNow = currentDatabaseTimestamp()
        return BookingReliabilityReevaluationJobs.update({
            (BookingReliabilityReevaluationJobs.id eq jobId) and
                (BookingReliabilityReevaluationJobs.status eq BookingReliabilityReevaluationJobStatus.PAUSED)
        }) {
            it[status] = BookingReliabilityReevaluationJobStatus.PENDING
            it[nextAttemptAt] = dbNow
            it[leaseOwner] = null
            it[leaseExpiresAt] = null
            it[lastFailureCode] = null
            it[updatedAt] = dbNow
        } == 1
    }

    fun scheduleRetry(
        jobId: Long,
        leaseOwner: String,
        failureCode: String,
        delay: Duration = retryDelay,
    ): Boolean {
        require(failureCode.isNotBlank() && failureCode.length <= 96) {
            "failureCode must contain 1..96 characters"
        }
        require(!delay.isNegative) { "delay must be non-negative" }
        val dbNow = currentDatabaseTimestamp()
        val row = findJobRow(jobId) ?: return false
        if (!hasActiveLease(row, leaseOwner, dbNow)) return false
        val nextStatus =
            if (row[BookingReliabilityReevaluationJobs.attemptCount] >= maxAttempts) {
                BookingReliabilityReevaluationJobStatus.DEAD_LETTER
            } else {
                BookingReliabilityReevaluationJobStatus.RETRY_WAIT
            }
        return BookingReliabilityReevaluationJobs.update({
            activeLeasePredicate(jobId, leaseOwner, dbNow)
        }) {
            it[status] = nextStatus
            it[BookingReliabilityReevaluationJobs.leaseOwner] = null
            it[leaseExpiresAt] = null
            it[nextAttemptAt] = dbNow.plus(delay)
            it[lastFailureCode] = failureCode
            it[updatedAt] = dbNow
        } == 1
    }

    private fun finish(
        jobId: Long,
        leaseOwner: String,
        status: BookingReliabilityReevaluationJobStatus,
        failureCode: String?,
    ): Boolean {
        val dbNow = currentDatabaseTimestamp()
        return BookingReliabilityReevaluationJobs.update({
            activeLeasePredicate(jobId, leaseOwner, dbNow)
        }) {
            it[BookingReliabilityReevaluationJobs.status] = status
            it[BookingReliabilityReevaluationJobs.leaseOwner] = null
            it[leaseExpiresAt] = null
            it[lastFailureCode] = failureCode
            it[updatedAt] = dbNow
        } == 1
    }

    private fun findCommandRow(
        tenantGroupId: Long,
        clinicId: Long,
        memberId: MemberId,
        idempotencyKeyHash: String,
    ): ResultRow? =
        BookingReliabilityReevaluationJobs
            .selectAll()
            .where {
                (BookingReliabilityReevaluationJobs.tenantGroupId eq tenantGroupId) and
                    (BookingReliabilityReevaluationJobs.clinicId eq clinicId) and
                    (BookingReliabilityReevaluationJobs.memberId eq memberId.value) and
                    (BookingReliabilityReevaluationJobs.idempotencyKeyHash eq idempotencyKeyHash)
            }
            .singleOrNull()

    private fun findJobRow(jobId: Long): ResultRow? =
        BookingReliabilityReevaluationJobs
            .selectAll()
            .where { BookingReliabilityReevaluationJobs.id eq jobId }
            .singleOrNull()

    private fun duePredicate(dbNow: Instant): Op<Boolean> =
        (
            (BookingReliabilityReevaluationJobs.status inList READY_STATES) and
                (BookingReliabilityReevaluationJobs.nextAttemptAt lessEq dbNow)
            ) or
            (
                (BookingReliabilityReevaluationJobs.status eq BookingReliabilityReevaluationJobStatus.RUNNING) and
                    (BookingReliabilityReevaluationJobs.leaseExpiresAt lessEq dbNow)
                )

    private fun activeLeasePredicate(jobId: Long, leaseOwner: String, dbNow: Instant): Op<Boolean> =
        (BookingReliabilityReevaluationJobs.id eq jobId) and
            (BookingReliabilityReevaluationJobs.status eq BookingReliabilityReevaluationJobStatus.RUNNING) and
            (BookingReliabilityReevaluationJobs.leaseOwner eq leaseOwner) and
            (BookingReliabilityReevaluationJobs.leaseExpiresAt greater dbNow)

    private fun hasActiveLease(row: ResultRow, leaseOwner: String, dbNow: Instant): Boolean =
        row[BookingReliabilityReevaluationJobs.status] == BookingReliabilityReevaluationJobStatus.RUNNING &&
            row[BookingReliabilityReevaluationJobs.leaseOwner] == leaseOwner &&
            row[BookingReliabilityReevaluationJobs.leaseExpiresAt]?.isAfter(dbNow) == true

    private fun isMonotonic(
        row: ResultRow,
        cursor: BookingReliabilityReevaluationCursor,
    ): Boolean {
        val currentOccurredAt = row[BookingReliabilityReevaluationJobs.cursorOccurredAt]
        val currentEventId = row[BookingReliabilityReevaluationJobs.cursorEventId]
        if (cursor.scannedCount < row[BookingReliabilityReevaluationJobs.scannedCount]) return false
        if (cursor.decisionCount < row[BookingReliabilityReevaluationJobs.decisionCount]) return false
        if (currentOccurredAt == null || currentEventId == null) return true
        val nextOccurredAt = cursor.cursorOccurredAt ?: return false
        val nextEventId = cursor.cursorEventId ?: return false
        return nextOccurredAt.isAfter(currentOccurredAt) ||
            (nextOccurredAt == currentOccurredAt && nextEventId >= currentEventId)
    }

    companion object {
        private val READY_STATES =
            listOf(
                BookingReliabilityReevaluationJobStatus.PENDING,
                BookingReliabilityReevaluationJobStatus.RETRY_WAIT,
            )
    }
}

private fun ResultRow.toJobRecord(): BookingReliabilityReevaluationJobRecord =
    BookingReliabilityReevaluationJobRecord(
        tenantGroupId = this[BookingReliabilityReevaluationJobs.tenantGroupId],
        clinicId = this[BookingReliabilityReevaluationJobs.clinicId],
        memberId = MemberId(this[BookingReliabilityReevaluationJobs.memberId]),
        idempotencyKeyHash = this[BookingReliabilityReevaluationJobs.idempotencyKeyHash],
        commandHash = this[BookingReliabilityReevaluationJobs.commandHash],
        status = this[BookingReliabilityReevaluationJobs.status],
        nextAttemptAt = this[BookingReliabilityReevaluationJobs.nextAttemptAt],
        policyVersionId = this[BookingReliabilityReevaluationJobs.policyVersionId],
        leaseOwner = this[BookingReliabilityReevaluationJobs.leaseOwner],
        leaseExpiresAt = this[BookingReliabilityReevaluationJobs.leaseExpiresAt],
        attemptCount = this[BookingReliabilityReevaluationJobs.attemptCount],
        cursorOccurredAt = this[BookingReliabilityReevaluationJobs.cursorOccurredAt],
        cursorEventId = this[BookingReliabilityReevaluationJobs.cursorEventId],
        scannedCount = this[BookingReliabilityReevaluationJobs.scannedCount],
        decisionCount = this[BookingReliabilityReevaluationJobs.decisionCount],
        lastFailureCode = this[BookingReliabilityReevaluationJobs.lastFailureCode],
        jobId = this[BookingReliabilityReevaluationJobs.id].value,
    )
