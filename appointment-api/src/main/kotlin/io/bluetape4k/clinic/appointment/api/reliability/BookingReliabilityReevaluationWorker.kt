package io.bluetape4k.clinic.appointment.api.reliability

import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityReevaluationCursor
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityReevaluationJobStatus
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityVerdict
import io.bluetape4k.clinic.appointment.repository.BookingReliabilityReevaluationJobRepository
import kotlinx.coroutines.CancellationException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Clock

/**
 * durable job row를 짧게 claim하고, DB transaction 밖에서 평가한 뒤 owner fencing으로
 * checkpoint/완료합니다. process 재시작은 같은 idempotency row를 다시 claim할 수 있지만
 * 이미 끝난 row를 중복 완료하지 않습니다.
 */
class BookingReliabilityReevaluationWorker(
    private val jobRepository: BookingReliabilityReevaluationJobRepository,
    private val applicationPort: BookingReliabilityApplicationPort,
    private val properties: BookingReliabilityProperties,
    private val metrics: BookingReliabilityMetrics? = null,
    private val operationalState: BookingReliabilityOperationalState? = null,
    private val retryPolicy: BookingReliabilityRetryPolicy = BookingReliabilityRetryPolicy(),
    private val schemaReadiness: DefaultBookingReliabilitySchemaReadiness? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val owner: String = "booking-reliability-worker",
) {
    fun runOnce(limit: Int = properties.maxHistoryRows): BookingReliabilityWorkerResult {
        require(limit in 1..properties.maxHistoryRows) { "limit is outside the bounded worker batch" }
        if (!properties.workerEnabled ||
            schemaReadiness?.canStartWorker(properties) == false
        ) return BookingReliabilityWorkerResult.disabled()

        val dueIds = transaction { jobRepository.findDueJobIds(limit) }
        var claimed = 0
        var completed = 0
        var retried = 0
        var failed = 0
        var leaseLost = 0

        dueIds.forEach { jobId ->
            val job = transaction { jobRepository.claimDue(jobId, owner) } ?: return@forEach
            claimed++
            metrics?.recordJob(BookingReliabilityMetrics.JobResult.CLAIMED)
            try {
                val decision = applicationPort.evaluate(
                    tenantGroupId = job.tenantGroupId,
                    clinicId = job.clinicId,
                    memberId = job.memberId,
                    at = clock.instant(),
                    requestedPolicySnapshotId = job.policyVersionId,
                )
                when (decision.verdict) {
                    BookingReliabilityVerdict.UNAVAILABLE ->
                        throw BookingReliabilityEvaluationFailure("EVALUATION_UNAVAILABLE")
                    BookingReliabilityVerdict.STALE -> {
                        val markedStale = transaction { jobRepository.markStale(jobId, owner) }
                        if (markedStale) {
                            failed++
                            metrics?.recordJob(BookingReliabilityMetrics.JobResult.DEAD_LETTER)
                        } else {
                            leaseLost++
                            operationalState?.recordLeaseLost()
                            metrics?.recordJob(BookingReliabilityMetrics.JobResult.LEASE_LOST)
                        }
                        return@forEach
                    }
                    else -> Unit
                }
                val completedByOwner = transaction {
                    val checkpointed = jobRepository.checkpoint(
                        jobId = jobId,
                        leaseOwner = owner,
                        cursor = BookingReliabilityReevaluationCursor(
                            cursorOccurredAt = job.cursorOccurredAt,
                            cursorEventId = job.cursorEventId,
                            scannedCount = job.scannedCount + 1,
                            decisionCount = job.decisionCount + 1,
                        ),
                    )
                    checkpointed && jobRepository.complete(jobId, owner)
                }
                if (completedByOwner) {
                    completed++
                    metrics?.recordJob(BookingReliabilityMetrics.JobResult.COMPLETED)
                } else {
                    leaseLost++
                    operationalState?.recordLeaseLost()
                    metrics?.recordJob(BookingReliabilityMetrics.JobResult.LEASE_LOST)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: BookingReliabilityEvaluationFailure) {
                val retry = retryPolicy.shouldRetry(job.attemptCount)
                val updated = transaction {
                    jobRepository.scheduleRetry(
                        jobId = jobId,
                        leaseOwner = owner,
                        failureCode = failure.code,
                        delay = retryPolicy.delayFor(job.attemptCount),
                    )
                }
                if (!updated) {
                    leaseLost++
                    operationalState?.recordLeaseLost()
                    metrics?.recordJob(BookingReliabilityMetrics.JobResult.LEASE_LOST)
                } else if (retry) {
                    retried++
                    metrics?.recordJob(BookingReliabilityMetrics.JobResult.RETRY)
                } else {
                    failed++
                    metrics?.recordJob(BookingReliabilityMetrics.JobResult.DEAD_LETTER)
                }
            } catch (_: Exception) {
                val retry = retryPolicy.shouldRetry(job.attemptCount)
                val updated = transaction {
                    jobRepository.scheduleRetry(
                        jobId = jobId,
                        leaseOwner = owner,
                        failureCode = "EVALUATION_FAILED",
                        delay = retryPolicy.delayFor(job.attemptCount),
                    )
                }
                if (!updated) {
                    leaseLost++
                    operationalState?.recordLeaseLost()
                    metrics?.recordJob(BookingReliabilityMetrics.JobResult.LEASE_LOST)
                } else if (retry) {
                    retried++
                    metrics?.recordJob(BookingReliabilityMetrics.JobResult.RETRY)
                } else {
                    failed++
                    metrics?.recordJob(BookingReliabilityMetrics.JobResult.DEAD_LETTER)
                }
            }
        }
        return BookingReliabilityWorkerResult(
            claimed = claimed,
            completed = completed,
            retried = retried,
            failed = failed,
            leaseLost = leaseLost,
        )
    }

    companion object {
        fun statusCounts(): Set<BookingReliabilityReevaluationJobStatus> =
            BookingReliabilityReevaluationJobStatus.entries.toSet()
    }
}

private class BookingReliabilityEvaluationFailure(
    val code: String,
) : RuntimeException(code)

data class BookingReliabilityWorkerResult(
    val claimed: Int,
    val completed: Int,
    val retried: Int,
    val failed: Int,
    val leaseLost: Int,
    val disabled: Boolean = false,
) {
    init {
        require(listOf(claimed, completed, retried, failed, leaseLost).all { it >= 0 })
    }

    companion object {
        fun disabled() = BookingReliabilityWorkerResult(0, 0, 0, 0, 0, disabled = true)
    }
}
