package io.bluetape4k.clinic.appointment.api.profile

import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitmentStatus
import io.bluetape4k.clinic.appointment.model.dto.ClaimProfileReevaluationJobs
import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationCursor
import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationJobRecord
import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationOutcomeCounts
import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationPriorityClass
import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationScope
import io.bluetape4k.clinic.appointment.model.dto.RedriveProfileReevaluationJob
import io.bluetape4k.clinic.appointment.model.profile.ProfileReevaluationOutcomeType
import io.bluetape4k.clinic.appointment.model.profile.ProfileReevaluationJobStatus
import io.bluetape4k.clinic.appointment.repository.AppointmentRepository
import io.bluetape4k.clinic.appointment.repository.ProfileReevaluationAppointmentCandidate
import io.bluetape4k.clinic.appointment.repository.ProfileReevaluationRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.error
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * 한 번의 tick에서 제한된 수의 예약을 상태별 keyset cursor로 재평가합니다.
 *
 * CRM assessment는 transaction 밖에서 한 번만 조회합니다. 각 예약을 처리하기 직전에
 * runtime gate를 다시 읽고, lease 또는 최신 revision을 잃으면 추가 변경을 중단합니다.
 */
class ProfileReevaluationWorker(
    private val store: ProfileReevaluationWorkStore,
    private val assessmentClient: ProfileAssessmentClient,
    private val appointmentProcessor: ProfileReevaluationAppointmentProcessor,
    private val runtimeGate: ProfileReevaluationRuntimeGate,
    private val failureObserver: ProfileReevaluationFailureObserver =
        LoggingProfileReevaluationFailureObserver,
    private val retryPolicy: ProfileReevaluationRetryPolicy = ProfileReevaluationRetryPolicy(),
    private val maxAppointmentsPerTick: Int = 100,
    private val pageSize: Int = 50,
    private val leaseRenewInterval: Duration = Duration.ofSeconds(10),
    private val clock: Clock = Clock.systemUTC(),
) : ProfileReevaluationJobWorker {
    init {
        require(maxAppointmentsPerTick > 0) { "maxAppointmentsPerTick must be positive" }
        require(pageSize in 1..100) { "pageSize must be between 1 and 100" }
        require(!leaseRenewInterval.isNegative && !leaseRenewInterval.isZero) {
            "leaseRenewInterval must be positive"
        }
    }

    override suspend fun process(job: ProfileReevaluationJobRecord): ProfileReevaluationWorkerResult {
        try {
            currentCoroutineContext().ensureActive()
            val currentRevision = store.currentRevision(job.scope)
                ?: return fail(job, FAILURE_HEAD_MISSING)
            if (currentRevision > job.targetRevision) {
                return if (store.markStale(job, currentRevision)) {
                    ProfileReevaluationWorkerResult.STALE
                } else {
                    ProfileReevaluationWorkerResult.LEASE_LOST
                }
            }
            if (currentRevision != job.targetRevision) {
                return fail(job, FAILURE_HEAD_REVISION_MISMATCH)
            }
            if (!store.renewLease(job)) return ProfileReevaluationWorkerResult.LEASE_LOST

            val initialAccess = runtimeGate.read()
            if (!initialAccess.allows(job.scope)) {
                return pause(job, FAILURE_RUNTIME_GATE_DISABLED)
            }

            val assessment = fetchAssessment(job)
            // CRM 호출은 transaction 밖에서 수행되므로 응답을 기다리는 동안 lease가
            // 만료될 수 있습니다. 예약 mutation을 시작하기 전에 소유권을 다시 확인합니다.
            if (!store.renewLease(job)) return ProfileReevaluationWorkerResult.LEASE_LOST
            return processCandidates(job, assessment, initialAccess.mode)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ProfileAssessmentException) {
            return fail(job, failure.code.name, terminal = !failure.retryable)
        } catch (failure: ExposedSQLException) {
            return recordFailure(job, failure, FAILURE_DATABASE, terminal = false)
        } catch (failure: IllegalArgumentException) {
            return recordFailure(job, failure, FAILURE_CONTRACT, terminal = true)
        } catch (failure: IllegalStateException) {
            return recordFailure(job, failure, FAILURE_STATE, terminal = true)
        } catch (failure: Exception) {
            return recordFailure(job, failure, FAILURE_UNEXPECTED, terminal = true)
        }
    }

    private suspend fun recordFailure(
        job: ProfileReevaluationJobRecord,
        failure: Exception,
        failureCode: String,
        terminal: Boolean,
    ): ProfileReevaluationWorkerResult {
        failureObserver.record(
            ProfileReevaluationFailureEvidence(
                jobId = job.id,
                targetRevision = job.targetRevision,
                failureCode = failureCode,
                exceptionType = failure::class.qualifiedName ?: failure.javaClass.name,
            ),
        )
        return fail(job, failureCode, terminal)
    }

    private suspend fun fetchAssessment(job: ProfileReevaluationJobRecord): ProfileSchedulingAssessment =
        withContext(Dispatchers.IO) {
            assessmentClient.fetch(
                FetchProfileAssessment(
                    tenantGroupId = job.scope.tenantGroupId,
                    clinicId = job.scope.clinicId,
                    patientReferenceFingerprint = job.scope.patientReferenceFingerprint,
                    profileRevision = job.targetRevision,
                    assessmentReference = job.assessmentRef,
                    assessmentHash = job.assessmentHash,
                    correlationId = "profile-reevaluation:${job.id}:${job.targetRevision}",
                ),
            )
        }

    private suspend fun processCandidates(
        job: ProfileReevaluationJobRecord,
        assessment: ProfileSchedulingAssessment,
        initialMode: ProfileReevaluationMutationMode,
    ): ProfileReevaluationWorkerResult {
        var processed = 0
        var heldCursor = job.heldCursorAppointmentId ?: 0L
        var proposedCursor = job.proposedCursorAppointmentId ?: 0L
        var leaseRenewedAt = clock.instant()
        var heldExcluded =
            initialMode == ProfileReevaluationMutationMode.APPLY_PROPOSED &&
                job.priorityClass != ProfileReevaluationPriorityClass.PROPOSED_ONLY

        for (status in PROCESSING_ORDER) {
            if (status == AppointmentCommitmentStatus.HELD && heldExcluded) continue
            var cursor = if (status == AppointmentCommitmentStatus.HELD) heldCursor else proposedCursor
            while (processed < maxAppointmentsPerTick) {
                currentCoroutineContext().ensureActive()
                val candidates = store.candidates(job.scope, status, cursor, pageSize)
                if (candidates.isEmpty()) break

                for (candidate in candidates) {
                    currentCoroutineContext().ensureActive()
                    val access = runtimeGate.read()
                    if (!access.allows(job.scope)) {
                        return pause(job, FAILURE_RUNTIME_GATE_DISABLED)
                    }
                    if (!access.mode.includes(status)) {
                        heldExcluded = heldExcluded || status == AppointmentCommitmentStatus.HELD
                        break
                    }

                    val now = clock.instant()
                    if (Duration.between(leaseRenewedAt, now) >= leaseRenewInterval) {
                        if (!store.renewLease(job)) return ProfileReevaluationWorkerResult.LEASE_LOST
                        leaseRenewedAt = now
                    }
                    val observedRevision = store.currentRevision(job.scope)
                    if (observedRevision != job.targetRevision) {
                        return if (
                            observedRevision != null &&
                            observedRevision > job.targetRevision &&
                            store.markStale(job, observedRevision)
                        ) {
                            ProfileReevaluationWorkerResult.STALE
                        } else {
                            ProfileReevaluationWorkerResult.LEASE_LOST
                        }
                    }

                    val outcome = appointmentProcessor.process(job, candidate, assessment, access.mode)
                    cursor = candidate.appointmentId
                    if (status == AppointmentCommitmentStatus.HELD) {
                        heldCursor = cursor
                    } else {
                        proposedCursor = cursor
                    }
                    val checkpoint =
                        ProfileReevaluationCursor(
                            heldCursorAppointmentId =
                                heldCursor.takeIf { status == AppointmentCommitmentStatus.HELD },
                            proposedCursorAppointmentId =
                                proposedCursor.takeIf { status == AppointmentCommitmentStatus.PROPOSED },
                            scannedDelta = 1L,
                            outcomeDeltas = outcome.toCounts(),
                        )
                    if (!store.checkpoint(job, checkpoint)) {
                        return ProfileReevaluationWorkerResult.LEASE_LOST
                    }
                    processed++
                    if (processed >= maxAppointmentsPerTick) {
                        return if (
                            store.defer(
                                job,
                                reasonCode = FAILURE_TICK_BUDGET_EXHAUSTED,
                                delay = Duration.ZERO,
                            )
                        ) {
                            ProfileReevaluationWorkerResult.BOUNDED
                        } else {
                            ProfileReevaluationWorkerResult.LEASE_LOST
                        }
                    }
                }

                if (candidates.size < pageSize) break
                if (status == AppointmentCommitmentStatus.HELD && heldExcluded) break
            }
        }

        if (heldExcluded) return pause(job, FAILURE_RUNTIME_MODE_EXCLUDES_HELD)
        return if (store.complete(job)) {
            ProfileReevaluationWorkerResult.COMPLETED
        } else {
            ProfileReevaluationWorkerResult.LEASE_LOST
        }
    }

    private suspend fun pause(
        job: ProfileReevaluationJobRecord,
        failureCode: String,
    ): ProfileReevaluationWorkerResult =
        if (store.defer(job, failureCode, RUNTIME_GATE_RECHECK_DELAY)) {
            ProfileReevaluationWorkerResult.PAUSED
        } else {
            ProfileReevaluationWorkerResult.LEASE_LOST
        }

    private suspend fun fail(
        job: ProfileReevaluationJobRecord,
        failureCode: String,
        terminal: Boolean = false,
    ): ProfileReevaluationWorkerResult {
        val decision =
            if (terminal) {
                ProfileReevaluationRetryDecision.Failed
            } else {
                retryPolicy.decide(
                    attemptCount = job.attemptCount,
                    firstAttemptAt = job.firstAttemptAt ?: job.updatedAt,
                    now = clock.instant(),
                )
            }
        return when (decision) {
            ProfileReevaluationRetryDecision.Failed -> {
                if (store.retry(job, failureCode, Duration.ZERO, terminal = true)) {
                    ProfileReevaluationWorkerResult.FAILED
                } else {
                    ProfileReevaluationWorkerResult.LEASE_LOST
                }
            }

            is ProfileReevaluationRetryDecision.Retry -> {
                if (store.retry(job, failureCode, decision.delay, terminal = false)) {
                    ProfileReevaluationWorkerResult.RETRY_WAIT
                } else {
                    ProfileReevaluationWorkerResult.LEASE_LOST
                }
            }
        }
    }

    private fun ProfileReevaluationMutationMode.includes(status: AppointmentCommitmentStatus): Boolean =
        when (this) {
            ProfileReevaluationMutationMode.DISABLED -> false
            ProfileReevaluationMutationMode.DRY_RUN -> true
            ProfileReevaluationMutationMode.APPLY_PROPOSED ->
                status == AppointmentCommitmentStatus.PROPOSED
            ProfileReevaluationMutationMode.APPLY_PROPOSED_AND_HELD -> true
        }

    private fun ProfileReevaluationOutcomeType?.toCounts(): ProfileReevaluationOutcomeCounts =
        when (this) {
            null -> ProfileReevaluationOutcomeCounts()
            ProfileReevaluationOutcomeType.PROPOSAL_SUPERSEDED ->
                ProfileReevaluationOutcomeCounts(proposalSuperseded = 1L)
            ProfileReevaluationOutcomeType.HOLD_KEPT ->
                ProfileReevaluationOutcomeCounts(holdKept = 1L)
            ProfileReevaluationOutcomeType.HOLD_REPLACED ->
                ProfileReevaluationOutcomeCounts(holdReplaced = 1L)
            ProfileReevaluationOutcomeType.FALLBACK_TO_PROPOSED ->
                ProfileReevaluationOutcomeCounts(fallbackToProposed = 1L)
            ProfileReevaluationOutcomeType.SKIPPED_INELIGIBLE ->
                ProfileReevaluationOutcomeCounts(skippedIneligible = 1L)
            ProfileReevaluationOutcomeType.SKIPPED_UNCHANGED ->
                ProfileReevaluationOutcomeCounts(skippedUnchanged = 1L)
        }

    private companion object {
        val PROCESSING_ORDER =
            listOf(AppointmentCommitmentStatus.HELD, AppointmentCommitmentStatus.PROPOSED)
        val RUNTIME_GATE_RECHECK_DELAY: Duration = Duration.ofSeconds(5)
        const val FAILURE_HEAD_MISSING = "HEAD_MISSING"
        const val FAILURE_HEAD_REVISION_MISMATCH = "HEAD_REVISION_MISMATCH"
        const val FAILURE_DATABASE = "PROCESSING_DATABASE_FAILED"
        const val FAILURE_CONTRACT = "PROCESSING_CONTRACT_FAILED"
        const val FAILURE_STATE = "PROCESSING_STATE_FAILED"
        const val FAILURE_UNEXPECTED = "PROCESSING_UNEXPECTED_FAILED"
        const val FAILURE_RUNTIME_GATE_DISABLED = "RUNTIME_GATE_DISABLED"
        const val FAILURE_RUNTIME_MODE_EXCLUDES_HELD = "RUNTIME_MODE_EXCLUDES_HELD"
        const val FAILURE_TICK_BUDGET_EXHAUSTED = "TICK_BUDGET_EXHAUSTED"
    }
}

/**
 * 프로필 재평가 처리 실패를 운영 로그나 외부 관찰기로 전달할 때 사용하는 비식별 증거입니다.
 *
 * 예외 메시지와 환자·assessment payload는 포함하지 않습니다.
 */
data class ProfileReevaluationFailureEvidence(
    val jobId: Long,
    val targetRevision: Long,
    val failureCode: String,
    val exceptionType: String,
)

/**
 * 프로필 재평가 worker의 비식별 실패 증거를 기록합니다.
 */
fun interface ProfileReevaluationFailureObserver {
    fun record(evidence: ProfileReevaluationFailureEvidence)
}

private object LoggingProfileReevaluationFailureObserver :
    ProfileReevaluationFailureObserver,
    KLogging() {
    override fun record(evidence: ProfileReevaluationFailureEvidence) {
        log.error {
            "Profile reevaluation worker failed: jobId=${evidence.jobId}, " +
                "targetRevision=${evidence.targetRevision}, failureCode=${evidence.failureCode}, " +
                "exceptionType=${evidence.exceptionType}"
        }
    }
}

enum class ProfileReevaluationWorkerResult {
    COMPLETED,
    BOUNDED,
    PAUSED,
    RETRY_WAIT,
    FAILED,
    STALE,
    LEASE_LOST,
}

enum class ProfileReevaluationMutationMode {
    DISABLED,
    DRY_RUN,
    APPLY_PROPOSED,
    APPLY_PROPOSED_AND_HELD,
}

data class ProfileReevaluationRuntimeAccess(
    val mode: ProfileReevaluationMutationMode,
    val allowedClinicIds: Set<Long>? = null,
) {
    init {
        require(allowedClinicIds == null || allowedClinicIds.all { it > 0L }) {
            "allowedClinicIds must contain only positive IDs"
        }
    }

    fun allows(scope: ProfileReevaluationScope): Boolean =
        mode != ProfileReevaluationMutationMode.DISABLED &&
            (allowedClinicIds == null || scope.clinicId in allowedClinicIds)

    companion object {
        fun enabled(
            mode: ProfileReevaluationMutationMode,
            allowedClinicIds: Set<Long>? = null,
        ): ProfileReevaluationRuntimeAccess {
            require(mode != ProfileReevaluationMutationMode.DISABLED) {
                "enabled access requires a non-disabled mode"
            }
            return ProfileReevaluationRuntimeAccess(mode, allowedClinicIds)
        }

        fun disabled(): ProfileReevaluationRuntimeAccess =
            ProfileReevaluationRuntimeAccess(ProfileReevaluationMutationMode.DISABLED)
    }
}

fun interface ProfileReevaluationRuntimeGate {
    fun read(): ProfileReevaluationRuntimeAccess
}

fun interface ProfileReevaluationAppointmentProcessor {
    suspend fun process(
        job: ProfileReevaluationJobRecord,
        candidate: ProfileReevaluationAppointmentCandidate,
        assessment: ProfileSchedulingAssessment,
        mode: ProfileReevaluationMutationMode,
    ): ProfileReevaluationOutcomeType?
}

interface ProfileReevaluationWorkStore {
    suspend fun claim(command: ClaimProfileReevaluationJobs): List<ProfileReevaluationJobRecord>

    suspend fun failedForRedrive(limit: Int): List<ProfileReevaluationJobRecord> = emptyList()

    suspend fun currentRevision(scope: ProfileReevaluationScope): Long?

    suspend fun candidates(
        scope: ProfileReevaluationScope,
        status: AppointmentCommitmentStatus,
        afterAppointmentId: Long,
        limit: Int,
    ): List<ProfileReevaluationAppointmentCandidate>

    suspend fun renewLease(job: ProfileReevaluationJobRecord): Boolean

    suspend fun checkpoint(
        job: ProfileReevaluationJobRecord,
        cursor: ProfileReevaluationCursor,
    ): Boolean

    suspend fun complete(job: ProfileReevaluationJobRecord): Boolean

    suspend fun markStale(
        job: ProfileReevaluationJobRecord,
        observedRevision: Long,
    ): Boolean

    suspend fun defer(
        job: ProfileReevaluationJobRecord,
        reasonCode: String,
        delay: Duration,
    ): Boolean

    suspend fun retry(
        job: ProfileReevaluationJobRecord,
        failureCode: String,
        delay: Duration,
        terminal: Boolean,
    ): Boolean

    suspend fun redrive(command: RedriveProfileReevaluationJob): ProfileReevaluationJobRecord?
}

/**
 * Exposed 저장소 호출마다 짧은 독립 transaction을 여는 worker 저장소 구현입니다.
 */
class ExposedProfileReevaluationWorkStore(
    private val database: Database,
    private val profileRepository: ProfileReevaluationRepository,
    private val appointmentRepository: AppointmentRepository,
    private val metrics: ProfileReevaluationMetrics? = null,
) : ProfileReevaluationWorkStore {
    override suspend fun claim(command: ClaimProfileReevaluationJobs): List<ProfileReevaluationJobRecord> =
        io { profileRepository.claimFairJobs(command) }
            .onEach { metrics?.recordJob(ProfileReevaluationJobStatus.RUNNING) }

    override suspend fun failedForRedrive(limit: Int): List<ProfileReevaluationJobRecord> =
        io { profileRepository.findFailedJobs(limit) }

    override suspend fun currentRevision(scope: ProfileReevaluationScope): Long? =
        io { profileRepository.findHead(scope)?.latestRevision }

    override suspend fun candidates(
        scope: ProfileReevaluationScope,
        status: AppointmentCommitmentStatus,
        afterAppointmentId: Long,
        limit: Int,
    ): List<ProfileReevaluationAppointmentCandidate> =
        io {
            appointmentRepository.findProfileReevaluationCandidates(
                tenantGroupId = scope.tenantGroupId,
                clinicId = scope.clinicId,
                patientReferenceFingerprint = scope.patientReferenceFingerprint,
                status = status,
                afterAppointmentId = afterAppointmentId,
                limit = limit,
            )
        }

    override suspend fun renewLease(job: ProfileReevaluationJobRecord): Boolean =
        io {
            profileRepository.renewLease(
                jobId = job.id,
                revision = job.targetRevision,
                leaseOwner = job.requireLeaseOwner(),
            )
        }.also { renewed ->
            if (renewed) {
                metrics?.recordLeaseRenewalSucceeded()
            } else {
                metrics?.recordOperational(ProfileReevaluationOperationalMetric.LEASE_LOST)
            }
        }

    override suspend fun checkpoint(
        job: ProfileReevaluationJobRecord,
        cursor: ProfileReevaluationCursor,
    ): Boolean =
        io {
            profileRepository.advanceCursor(
                jobId = job.id,
                revision = job.targetRevision,
                leaseOwner = job.requireLeaseOwner(),
                cursor = cursor,
            )
        }.also { checkpointed ->
            if (!checkpointed) metrics?.recordOperational(ProfileReevaluationOperationalMetric.LEASE_LOST)
        }

    override suspend fun complete(job: ProfileReevaluationJobRecord): Boolean =
        io {
            profileRepository.complete(
                jobId = job.id,
                revision = job.targetRevision,
                leaseOwner = job.requireLeaseOwner(),
            )
        }.also { completed ->
            if (completed) metrics?.recordJob(ProfileReevaluationJobStatus.COMPLETED)
        }

    override suspend fun markStale(
        job: ProfileReevaluationJobRecord,
        observedRevision: Long,
    ): Boolean =
        io {
            profileRepository.markStale(
                jobId = job.id,
                observedRevision = observedRevision,
                leaseOwner = job.requireLeaseOwner(),
            )
        }.also { stale ->
            if (stale) metrics?.recordJob(ProfileReevaluationJobStatus.STALE)
        }

    override suspend fun defer(
        job: ProfileReevaluationJobRecord,
        reasonCode: String,
        delay: Duration,
    ): Boolean =
        io {
            profileRepository.defer(
                jobId = job.id,
                revision = job.targetRevision,
                leaseOwner = job.requireLeaseOwner(),
                reasonCode = reasonCode,
                delay = delay,
            )
        }.also { deferred ->
            if (deferred) {
                metrics?.recordOperational(ProfileReevaluationOperationalMetric.DEFER)
            }
        }

    override suspend fun retry(
        job: ProfileReevaluationJobRecord,
        failureCode: String,
        delay: Duration,
        terminal: Boolean,
    ): Boolean =
        io {
            profileRepository.scheduleRetry(
                jobId = job.id,
                revision = job.targetRevision,
                leaseOwner = job.requireLeaseOwner(),
                failureCode = failureCode,
                delay = delay,
                terminal = terminal,
            )
        }.also { scheduled ->
            if (scheduled) {
                metrics?.recordOperational(
                    if (terminal) {
                        ProfileReevaluationOperationalMetric.FAILED
                    } else {
                        ProfileReevaluationOperationalMetric.RETRY
                    },
                )
            }
        }

    override suspend fun redrive(
        command: RedriveProfileReevaluationJob,
    ): ProfileReevaluationJobRecord? =
        io { profileRepository.redriveFailed(command) }
            .also { if (it != null) metrics?.recordOperational(ProfileReevaluationOperationalMetric.REDRIVE) }

    private suspend fun <T> io(block: () -> T): T =
        withContext(Dispatchers.IO) {
            transaction(database) { block() }
        }

    private fun ProfileReevaluationJobRecord.requireLeaseOwner(): String =
        requireNotNull(leaseOwner) { "running profile reevaluation job requires leaseOwner" }
}
