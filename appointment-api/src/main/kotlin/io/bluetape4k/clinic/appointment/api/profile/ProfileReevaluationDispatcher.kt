package io.bluetape4k.clinic.appointment.api.profile

import io.bluetape4k.clinic.appointment.model.dto.ClaimProfileReevaluationJobs
import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationClinicCursor
import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationJobRecord
import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * 실행 가능한 재평가 작업을 병원별 공정 선점 후 제한된 동시성으로 실행합니다.
 */
class ProfileReevaluationDispatcher(
    private val store: ProfileReevaluationWorkStore,
    private val worker: ProfileReevaluationJobWorker,
    private val leaseOwner: String,
    private val globalConcurrency: Int,
    private val perClinicConcurrency: Int,
    private val redrivePolicy: ProfileReevaluationRedrivePolicy = ProfileReevaluationRedrivePolicy(),
    private val autoRedriveLimit: Int = globalConcurrency,
    private val metrics: ProfileReevaluationMetrics? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val globalPermits: Semaphore
    private val clinicPermits = ConcurrentHashMap<ClinicKey, Semaphore>()
    private val claimMutex = Mutex()
    private var clinicCursor: ProfileReevaluationClinicCursor? = null

    init {
        require(leaseOwner.isNotBlank() && leaseOwner.length <= 160) {
            "leaseOwner must contain 1..160 characters"
        }
        require(globalConcurrency > 0) { "globalConcurrency must be positive" }
        require(perClinicConcurrency in 1..globalConcurrency) {
            "perClinicConcurrency must be between 1 and globalConcurrency"
        }
        require(autoRedriveLimit > 0) { "autoRedriveLimit must be positive" }
        globalPermits = Semaphore(globalConcurrency)
    }

    suspend fun dispatchOnce(): List<ProfileReevaluationWorkerResult> {
        redriveEligibleFailures()
        val jobs = claimFairBatch()
        recordFirstClaimWait(jobs)
        return coroutineScope {
            jobs.map { job ->
                async {
                    globalPermits.withPermit {
                        clinicSemaphore(job).withPermit {
                            worker.process(job)
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun claimFairBatch(): List<ProfileReevaluationJobRecord> =
        claimMutex.withLock {
            val previousCursor = clinicCursor
            val jobs =
                store.claim(
                ClaimProfileReevaluationJobs(
                    leaseOwner = leaseOwner,
                    limit = globalConcurrency,
                    perClinicLimit = perClinicConcurrency,
                    afterClinic = previousCursor,
                ),
            )
            clinicCursor = nextClinicCursor(previousCursor, jobs)
            jobs
        }

    private fun nextClinicCursor(
        previous: ProfileReevaluationClinicCursor?,
        jobs: List<ProfileReevaluationJobRecord>,
    ): ProfileReevaluationClinicCursor? {
        val cursors =
            jobs.map { job ->
                ProfileReevaluationClinicCursor(
                    tenantGroupId = job.scope.tenantGroupId,
                    clinicId = job.scope.clinicId,
                )
            }.distinct()
        if (cursors.isEmpty()) return previous
        val wrapped = previous?.let { cursor -> cursors.filter { it <= cursor } }.orEmpty()
        return (wrapped.ifEmpty { cursors }).maxOrNull()
    }

    private fun recordFirstClaimWait(jobs: List<ProfileReevaluationJobRecord>) {
        val claimedAt = clock.instant()
        jobs.asSequence()
            .filter { it.attemptCount == 1 }
            .forEach { job ->
                val elapsed = Duration.between(job.occurredAt, claimedAt)
                metrics?.recordFairWait(
                    priorityClass = job.priorityClass,
                    duration = if (elapsed.isNegative) Duration.ZERO else elapsed,
                )
            }
    }

    private suspend fun redriveEligibleFailures() {
        val now = clock.instant()
        store.failedForRedrive(autoRedriveLimit)
            .mapNotNull { redrivePolicy.commandFor(it, now) }
            .forEach { store.redrive(it) }
    }

    private fun clinicSemaphore(job: ProfileReevaluationJobRecord): Semaphore =
        clinicPermits.computeIfAbsent(
            ClinicKey(job.scope.tenantGroupId, job.scope.clinicId),
        ) {
            Semaphore(perClinicConcurrency)
        }

    private data class ClinicKey(
        val tenantGroupId: Long,
        val clinicId: Long,
    )
}

fun interface ProfileReevaluationJobWorker {
    suspend fun process(job: ProfileReevaluationJobRecord): ProfileReevaluationWorkerResult
}

/**
 * 기능 활성화나 병원 allowlist 확대 때 active 예약 범위를 제한된 keyset 페이지로 훑습니다.
 *
 * 실제 synthetic revision 생성은 [ProfileReevaluationCatchUpStore]가 담당하며, runner는
 * 한 tick의 조회·enqueue 수와 resume cursor만 관리합니다.
 */
class ProfileReevaluationCatchUpRunner(
    private val store: ProfileReevaluationCatchUpStore,
    private val maxScopesPerTick: Int = 100,
    private val pageSize: Int = 50,
) {
    init {
        require(maxScopesPerTick > 0) { "maxScopesPerTick must be positive" }
        require(pageSize in 1..100) { "pageSize must be between 1 and 100" }
    }

    suspend fun run(
        afterExclusive: ProfileReevaluationCatchUpCursor = ProfileReevaluationCatchUpCursor.START,
    ): ProfileReevaluationCatchUpResult {
        var cursor = afterExclusive
        var scanned = 0
        var enqueued = 0
        while (scanned < maxScopesPerTick) {
            currentCoroutineContext().ensureActive()
            val requested = minOf(pageSize, maxScopesPerTick - scanned)
            val page = store.findActiveScopes(cursor, requested)
            if (page.isEmpty()) {
                return ProfileReevaluationCatchUpResult(cursor, scanned, enqueued, completed = true)
            }
            for (candidate in page) {
                currentCoroutineContext().ensureActive()
                require(candidate.cursor > cursor) { "catch-up cursor must increase monotonically" }
                if (store.enqueueSyntheticRevision(candidate)) enqueued++
                cursor = candidate.cursor
                scanned++
            }
            if (page.size < requested) {
                return ProfileReevaluationCatchUpResult(cursor, scanned, enqueued, completed = true)
            }
        }
        return ProfileReevaluationCatchUpResult(cursor, scanned, enqueued, completed = false)
    }
}

interface ProfileReevaluationCatchUpStore {
    suspend fun findActiveScopes(
        afterExclusive: ProfileReevaluationCatchUpCursor,
        limit: Int,
    ): List<ProfileReevaluationCatchUpCandidate>

    suspend fun enqueueSyntheticRevision(candidate: ProfileReevaluationCatchUpCandidate): Boolean
}

data class ProfileReevaluationCatchUpCandidate(
    val cursor: ProfileReevaluationCatchUpCursor,
    val scope: ProfileReevaluationScope,
) {
    init {
        require(cursor.tenantGroupId == scope.tenantGroupId && cursor.clinicId == scope.clinicId) {
            "catch-up cursor must match candidate scope"
        }
    }
}

data class ProfileReevaluationCatchUpCursor(
    val tenantGroupId: Long,
    val clinicId: Long,
    val appointmentId: Long,
) : Comparable<ProfileReevaluationCatchUpCursor> {
    init {
        require(tenantGroupId >= 0L) { "tenantGroupId must be non-negative" }
        require(clinicId >= 0L) { "clinicId must be non-negative" }
        require(appointmentId >= 0L) { "appointmentId must be non-negative" }
    }

    override fun compareTo(other: ProfileReevaluationCatchUpCursor): Int =
        compareValuesBy(this, other, ProfileReevaluationCatchUpCursor::tenantGroupId)
            .takeIf { it != 0 }
            ?: compareValuesBy(this, other, ProfileReevaluationCatchUpCursor::clinicId)
                .takeIf { it != 0 }
            ?: compareValuesBy(this, other, ProfileReevaluationCatchUpCursor::appointmentId)

    companion object {
        val START = ProfileReevaluationCatchUpCursor(0L, 0L, 0L)
    }
}

data class ProfileReevaluationCatchUpResult(
    val nextCursor: ProfileReevaluationCatchUpCursor,
    val scanned: Int,
    val enqueued: Int,
    val completed: Boolean,
)
