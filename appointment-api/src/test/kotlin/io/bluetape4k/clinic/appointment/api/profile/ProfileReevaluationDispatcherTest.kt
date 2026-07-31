package io.bluetape4k.clinic.appointment.api.profile

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.clinic.appointment.model.dto.ClaimProfileReevaluationJobs
import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationClinicCursor
import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationJobRecord
import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationOutcomeCounts
import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationPriorityClass
import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationScope
import io.bluetape4k.clinic.appointment.model.dto.RedriveProfileReevaluationJob
import io.bluetape4k.clinic.appointment.model.profile.ProfileReevaluationJobStatus
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal class ProfileReevaluationDispatcherTest {
    @Test
    fun `서로 다른 병원 작업이 끝나면 permit registry 항목을 모두 제거한다`() {
        runBlocking {
            val clinicCount = 512
            val jobs =
                (1L..clinicCount.toLong())
                    .map { clinicId -> workerJob(clinicId, clinicId) }
                    .toMutableList()
            val registry = SimpleMeterRegistry()
            val dispatcher =
                ProfileReevaluationDispatcher(
                    store = QueueWorkStore(jobs),
                    worker = ProfileReevaluationJobWorker { ProfileReevaluationWorkerResult.COMPLETED },
                    leaseOwner = "dispatcher-a",
                    globalConcurrency = 16,
                    perClinicConcurrency = 2,
                    runtimeGate = enabledRuntimeGate(),
                    metrics = ProfileReevaluationMetrics(registry),
                )

            while (jobs.isNotEmpty()) {
                dispatcher.dispatchOnce()
            }

            registry.find(ProfileReevaluationMetrics.CLINIC_PERMIT_REGISTRY_SIZE)
                .gauge()
                .shouldNotBeNull()
                .value() shouldBeEqualTo 0.0
            registry.find(ProfileReevaluationMetrics.CLINIC_PERMIT_EVICTIONS)
                .counter()
                .shouldNotBeNull()
                .count() shouldBeEqualTo clinicCount.toDouble()
        }
    }

    @Test
    fun `permit 보유자와 대기자를 취소해도 registry 참조가 남지 않는다`() {
        runBlocking {
            val registry = SimpleMeterRegistry()
            val workerStarted = CompletableDeferred<Unit>()
            val dispatcher =
                ProfileReevaluationDispatcher(
                    store =
                        SingleBatchWorkStore(
                            listOf(
                                workerJob(1L, 1L),
                                workerJob(2L, 1L),
                                workerJob(3L, 1L),
                            ),
                        ),
                    worker =
                        ProfileReevaluationJobWorker {
                            workerStarted.complete(Unit)
                            awaitCancellation()
                        },
                    leaseOwner = "dispatcher-a",
                    globalConcurrency = 3,
                    perClinicConcurrency = 1,
                    runtimeGate = enabledRuntimeGate(),
                    metrics = ProfileReevaluationMetrics(registry),
                )

            val dispatch = async { dispatcher.dispatchOnce() }
            workerStarted.await()
            delay(25)
            dispatch.cancelAndJoin()

            registry.find(ProfileReevaluationMetrics.CLINIC_PERMIT_REGISTRY_SIZE)
                .gauge()
                .shouldNotBeNull()
                .value() shouldBeEqualTo 0.0
            registry.find(ProfileReevaluationMetrics.CLINIC_PERMIT_EVICTIONS)
                .counter()
                .shouldNotBeNull()
                .count() shouldBeEqualTo 1.0
        }
    }

    @RepeatedTest(10)
    fun `제거와 재확보가 경쟁해도 같은 병원은 설정한 동시 실행 수를 넘지 않는다`() {
        runBlocking {
            val registry = SimpleMeterRegistry()
            val active = AtomicInteger()
            val maximum = AtomicInteger()
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val secondStarted = CompletableDeferred<Unit>()
            val releaseSecond = CompletableDeferred<Unit>()
            val thirdStarted = CompletableDeferred<Unit>()
            val lateBatchClaimed = CompletableDeferred<Unit>()
            val store =
                StagedWorkStore(
                    ArrayDeque(
                        listOf(
                            listOf(workerJob(1L, 1L), workerJob(2L, 1L)),
                            listOf(workerJob(3L, 1L)),
                        ),
                    ),
                ) { claimCount ->
                    if (claimCount == 2) {
                        lateBatchClaimed.complete(Unit)
                    }
                }
            val dispatcher =
                ProfileReevaluationDispatcher(
                    store = store,
                    worker = ProfileReevaluationJobWorker { job ->
                        val current = active.incrementAndGet()
                        maximum.accumulateAndGet(current, ::maxOf)
                        try {
                            when (job.id) {
                                1L -> {
                                    firstStarted.complete(Unit)
                                    releaseFirst.await()
                                }
                                2L -> {
                                    secondStarted.complete(Unit)
                                    releaseSecond.await()
                                }
                                3L -> thirdStarted.complete(Unit)
                            }
                            ProfileReevaluationWorkerResult.COMPLETED
                        } finally {
                            active.decrementAndGet()
                        }
                    },
                    leaseOwner = "dispatcher-a",
                    globalConcurrency = 3,
                    perClinicConcurrency = 1,
                    runtimeGate = enabledRuntimeGate(),
                    metrics = ProfileReevaluationMetrics(registry),
                )

            val firstDispatch = async { dispatcher.dispatchOnce() }
            firstStarted.await()
            yield()
            releaseFirst.complete(Unit)
            secondStarted.await()

            val lateDispatch = async { dispatcher.dispatchOnce() }
            lateBatchClaimed.await()
            yield()

            thirdStarted.isCompleted shouldBeEqualTo false
            maximum.get() shouldBeEqualTo 1

            releaseSecond.complete(Unit)
            thirdStarted.await()
            firstDispatch.await()
            lateDispatch.await()
            registry.find(ProfileReevaluationMetrics.CLINIC_PERMIT_REGISTRY_SIZE)
                .gauge()
                .shouldNotBeNull()
                .value() shouldBeEqualTo 0.0
        }
    }

    @Test
    fun `32개 병원의 backlog도 전역과 병원별 동시 실행 한도를 넘지 않는다`() {
        runBlocking {
            val jobs =
                (1L..32L).flatMap { clinicId ->
                    listOf(
                        workerJob(id = clinicId * 10L, clinicId = clinicId),
                        workerJob(id = clinicId * 10L + 1L, clinicId = clinicId),
                    )
                }.toMutableList()
            val store = QueueWorkStore(jobs)
            val globalActive = AtomicInteger()
            val globalMaximum = AtomicInteger()
            val clinicActive = ConcurrentHashMap<Long, AtomicInteger>()
            val clinicMaximum = ConcurrentHashMap<Long, AtomicInteger>()
            val worker =
                ProfileReevaluationJobWorker { job ->
                    val global = globalActive.incrementAndGet()
                    globalMaximum.accumulateAndGet(global, ::maxOf)
                    val active = clinicActive.computeIfAbsent(job.scope.clinicId) { AtomicInteger() }
                    val maximum = clinicMaximum.computeIfAbsent(job.scope.clinicId) { AtomicInteger() }
                    maximum.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                    delay(10)
                    active.decrementAndGet()
                    globalActive.decrementAndGet()
                    ProfileReevaluationWorkerResult.COMPLETED
                }
            val dispatcher =
                ProfileReevaluationDispatcher(
                    store = store,
                    worker = worker,
                    leaseOwner = "dispatcher-a",
                    globalConcurrency = 8,
                    perClinicConcurrency = 2,
                    runtimeGate = enabledRuntimeGate(),
                )

            while (jobs.isNotEmpty()) {
                dispatcher.dispatchOnce()
            }

            globalMaximum.get() shouldBeEqualTo 8
            clinicMaximum.values.maxOf(AtomicInteger::get) shouldBeEqualTo 2
            clinicMaximum.size shouldBeEqualTo 32
        }
    }

    @Test
    fun `동일 due에서는 선점 우선순위를 따르되 오래 기다린 제안 작업도 실행한다`() {
        runBlocking {
            val held = workerJob(1L, 1L, ProfileReevaluationPriorityClass.HELD_PRESENT)
            val agedProposed = workerJob(2L, 2L, ProfileReevaluationPriorityClass.PROPOSED_ONLY)
            val store = QueueWorkStore(mutableListOf(held, agedProposed))
            val order = mutableListOf<Long>()
            val dispatcher =
                ProfileReevaluationDispatcher(
                    store = store,
                    worker =
                        ProfileReevaluationJobWorker { job ->
                            order += job.id
                            ProfileReevaluationWorkerResult.COMPLETED
                        },
                    leaseOwner = "dispatcher-a",
                    globalConcurrency = 2,
                    perClinicConcurrency = 1,
                    runtimeGate = enabledRuntimeGate(),
                )

            dispatcher.dispatchOnce()

            order.toSet() shouldBeEqualTo setOf(held.id, agedProposed.id)
        }
    }

    @Test
    fun `dispatcher는 claim 결과의 마지막 병원을 다음 tick cursor로 이어 간다`() {
        runBlocking {
            val cursors = mutableListOf<ProfileReevaluationClinicCursor?>()
            val batches =
                ArrayDeque(
                    listOf(
                        listOf(workerJob(1L, 1L), workerJob(2L, 2L)),
                        listOf(workerJob(3L, 3L), workerJob(4L, 4L)),
                        listOf(workerJob(5L, 5L), workerJob(6L, 6L)),
                        listOf(workerJob(7L, 1L), workerJob(8L, 2L)),
                        emptyList(),
                    ),
                )
            val store =
                object : ProfileReevaluationWorkStore by UnsupportedWorkStore {
                    override suspend fun claim(command: ClaimProfileReevaluationJobs): List<ProfileReevaluationJobRecord> {
                        cursors += command.afterClinic
                        return batches.removeFirst()
                    }
                }
            val dispatcher =
                ProfileReevaluationDispatcher(
                    store = store,
                    worker = ProfileReevaluationJobWorker { ProfileReevaluationWorkerResult.COMPLETED },
                    leaseOwner = "dispatcher-a",
                    globalConcurrency = 2,
                    perClinicConcurrency = 1,
                    runtimeGate = enabledRuntimeGate(),
                )

            repeat(5) { dispatcher.dispatchOnce() }

            cursors shouldBeEqualTo
                listOf(
                    null,
                    ProfileReevaluationClinicCursor(1L, 2L),
                    ProfileReevaluationClinicCursor(1L, 4L),
                    ProfileReevaluationClinicCursor(1L, 6L),
                    ProfileReevaluationClinicCursor(1L, 2L),
                )
        }
    }

    @Test
    fun `최종 실패는 cooldown 뒤 두 번까지만 자동 redrive한다`() {
        runBlocking {
            val now = Instant.parse("2026-08-01T00:10:00Z")
            val failed =
                workerJob(1L, 1L)
                    .copy(
                        status = ProfileReevaluationJobStatus.FAILED,
                        redriveCount = 0,
                        redriveGeneration = 1,
                        updatedAt = now.minus(Duration.ofMinutes(30)),
                    )
            var redriveCommand: RedriveProfileReevaluationJob? = null
            val store =
                object : ProfileReevaluationWorkStore by UnsupportedWorkStore {
                    override suspend fun failedForRedrive(limit: Int) = listOf(failed)

                    override suspend fun redrive(
                        command: RedriveProfileReevaluationJob,
                    ): ProfileReevaluationJobRecord? {
                        redriveCommand = command
                        return null
                    }

                    override suspend fun claim(command: ClaimProfileReevaluationJobs) =
                        emptyList<ProfileReevaluationJobRecord>()
                }
            val dispatcher =
                ProfileReevaluationDispatcher(
                    store = store,
                    worker = ProfileReevaluationJobWorker { ProfileReevaluationWorkerResult.COMPLETED },
                    leaseOwner = "dispatcher-a",
                    globalConcurrency = 2,
                    perClinicConcurrency = 1,
                    runtimeGate = enabledRuntimeGate(),
                    clock = Clock.fixed(now, ZoneOffset.UTC),
                )

            dispatcher.dispatchOnce()

            redriveCommand?.jobId shouldBeEqualTo failed.id
            redriveCommand?.expectedRedriveCount shouldBeEqualTo 0
        }
    }

    @Test
    fun `runtime gate가 비활성이면 redrive와 claim을 시작하지 않는다`() {
        runBlocking {
            var failedLookupCount = 0
            var claimCount = 0
            val store =
                object : ProfileReevaluationWorkStore by UnsupportedWorkStore {
                    override suspend fun failedForRedrive(limit: Int): List<ProfileReevaluationJobRecord> {
                        failedLookupCount++
                        return emptyList()
                    }

                    override suspend fun claim(command: ClaimProfileReevaluationJobs): List<ProfileReevaluationJobRecord> {
                        claimCount++
                        return emptyList()
                    }
                }
            val dispatcher =
                ProfileReevaluationDispatcher(
                    store = store,
                    worker = ProfileReevaluationJobWorker { ProfileReevaluationWorkerResult.COMPLETED },
                    leaseOwner = "dispatcher-a",
                    globalConcurrency = 2,
                    perClinicConcurrency = 1,
                    runtimeGate = ProfileReevaluationRuntimeGate {
                        ProfileReevaluationRuntimeAccess.disabled()
                    },
                )

            dispatcher.dispatchOnce() shouldBeEqualTo emptyList()

            failedLookupCount shouldBeEqualTo 0
            claimCount shouldBeEqualTo 0
        }
    }

    @Test
    fun `첫 선점 대기 시간은 우선순위별로 한 번만 기록한다`() {
        runBlocking {
            val now = Instant.parse("2026-08-01T00:10:00Z")
            val registry = SimpleMeterRegistry()
            val jobs =
                mutableListOf(
                    workerJob(1L, 1L, ProfileReevaluationPriorityClass.HELD_PRESENT),
                    workerJob(2L, 2L, ProfileReevaluationPriorityClass.PROPOSED_ONLY),
                    workerJob(3L, 3L, ProfileReevaluationPriorityClass.HELD_PRESENT)
                        .copy(attemptCount = 2),
                )
            val dispatcher =
                ProfileReevaluationDispatcher(
                    store = QueueWorkStore(jobs),
                    worker = ProfileReevaluationJobWorker { ProfileReevaluationWorkerResult.COMPLETED },
                    leaseOwner = "dispatcher-a",
                    globalConcurrency = 3,
                    perClinicConcurrency = 1,
                    runtimeGate = enabledRuntimeGate(),
                    metrics = ProfileReevaluationMetrics(registry),
                    clock = Clock.fixed(now, ZoneOffset.UTC),
                )

            dispatcher.dispatchOnce()

            registry.get(ProfileReevaluationMetrics.FAIR_WAIT)
                .tag("priority_class", "held_present")
                .timer()
                .count() shouldBeEqualTo 1L
            registry.get(ProfileReevaluationMetrics.FAIR_WAIT)
                .tag("priority_class", "proposed_only")
                .timer()
                .count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `catch-up은 clinic keyset cursor를 남기고 한 tick 범위를 넘지 않는다`() {
        runBlocking {
            val candidates =
                (1L..250L).map { appointmentId ->
                    ProfileReevaluationCatchUpCandidate(
                        cursor = ProfileReevaluationCatchUpCursor(1L, 1L, appointmentId),
                        scope = ProfileReevaluationScope(1L, 1L, "f".repeat(64)),
                    )
                }
            val enqueued = mutableListOf<Long>()
            val store =
                object : ProfileReevaluationCatchUpStore {
                    override suspend fun findActiveScopes(
                        afterExclusive: ProfileReevaluationCatchUpCursor,
                        limit: Int,
                    ): List<ProfileReevaluationCatchUpCandidate> =
                        candidates.filter { it.cursor > afterExclusive }.take(limit)

                    override suspend fun enqueueSyntheticRevision(
                        candidate: ProfileReevaluationCatchUpCandidate,
                    ): Boolean {
                        enqueued += candidate.cursor.appointmentId
                        return true
                    }
                }
            val runner =
                ProfileReevaluationCatchUpRunner(
                    store = store,
                    maxScopesPerTick = 100,
                    pageSize = 30,
                )

            val result = runner.run()

            result.scanned shouldBeEqualTo 100
            result.enqueued shouldBeEqualTo 100
            result.nextCursor shouldBeEqualTo ProfileReevaluationCatchUpCursor(1L, 1L, 100L)
            result.completed shouldBeEqualTo false
            enqueued shouldBeEqualTo (1L..100L).toList()
        }
    }
}

private fun enabledRuntimeGate(): ProfileReevaluationRuntimeGate =
    ProfileReevaluationRuntimeGate {
        ProfileReevaluationRuntimeAccess.enabled(
            ProfileReevaluationMutationMode.APPLY_PROPOSED_AND_HELD,
        )
    }

private class QueueWorkStore(
    private val jobs: MutableList<ProfileReevaluationJobRecord>,
) : ProfileReevaluationWorkStore by UnsupportedWorkStore {
    override suspend fun claim(command: ClaimProfileReevaluationJobs): List<ProfileReevaluationJobRecord> {
        val clinicCounts = mutableMapOf<Long, Int>()
        val selected =
            jobs
                .sortedWith(
                    compareBy<ProfileReevaluationJobRecord>(
                        { it.dueAt },
                        { it.priorityClass != ProfileReevaluationPriorityClass.HELD_PRESENT },
                        { it.id },
                    ),
                ).filter { job ->
                    val count = clinicCounts.getOrDefault(job.scope.clinicId, 0)
                    if (count >= command.perClinicLimit) {
                        false
                    } else {
                        clinicCounts[job.scope.clinicId] = count + 1
                        true
                    }
                }.take(command.limit)
        jobs.removeAll(selected.toSet())
        return selected
    }
}

private class SingleBatchWorkStore(
    private var jobs: List<ProfileReevaluationJobRecord>,
) : ProfileReevaluationWorkStore by UnsupportedWorkStore {
    override suspend fun claim(command: ClaimProfileReevaluationJobs): List<ProfileReevaluationJobRecord> =
        jobs.also { jobs = emptyList() }
}

private class StagedWorkStore(
    private val batches: ArrayDeque<List<ProfileReevaluationJobRecord>>,
    private val onClaim: (Int) -> Unit,
) : ProfileReevaluationWorkStore by UnsupportedWorkStore {
    private var claimCount = 0

    override suspend fun claim(command: ClaimProfileReevaluationJobs): List<ProfileReevaluationJobRecord> {
        claimCount++
        onClaim(claimCount)
        return batches.removeFirstOrNull().orEmpty()
    }
}

private object UnsupportedWorkStore : ProfileReevaluationWorkStore {
    override suspend fun claim(command: ClaimProfileReevaluationJobs): List<ProfileReevaluationJobRecord> =
        error("unsupported")

    override suspend fun currentRevision(
        scope: io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationScope,
    ): Long? = error("unsupported")

    override suspend fun candidates(
        scope: io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationScope,
        status: io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitmentStatus,
        afterAppointmentId: Long,
        limit: Int,
    ): List<io.bluetape4k.clinic.appointment.repository.ProfileReevaluationAppointmentCandidate> =
        error("unsupported")

    override suspend fun renewLease(job: ProfileReevaluationJobRecord): Boolean = error("unsupported")

    override suspend fun checkpoint(
        job: ProfileReevaluationJobRecord,
        cursor: io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationCursor,
    ): Boolean = error("unsupported")

    override suspend fun complete(job: ProfileReevaluationJobRecord): Boolean = error("unsupported")

    override suspend fun markStale(
        job: ProfileReevaluationJobRecord,
        observedRevision: Long,
    ): Boolean = error("unsupported")

    override suspend fun defer(
        job: ProfileReevaluationJobRecord,
        reasonCode: String,
        delay: java.time.Duration,
    ): Boolean = error("unsupported")

    override suspend fun retry(
        job: ProfileReevaluationJobRecord,
        failureCode: String,
        delay: java.time.Duration,
        terminal: Boolean,
    ): Boolean = error("unsupported")

    override suspend fun redrive(
        command: io.bluetape4k.clinic.appointment.model.dto.RedriveProfileReevaluationJob,
    ): ProfileReevaluationJobRecord? = error("unsupported")
}

private fun workerJob(
    id: Long,
    clinicId: Long,
    priority: ProfileReevaluationPriorityClass = ProfileReevaluationPriorityClass.HELD_PRESENT,
): ProfileReevaluationJobRecord {
    val now = Instant.parse("2026-08-01T00:10:00Z")
    return ProfileReevaluationJobRecord(
        id = id,
        headId = id,
        scope = ProfileReevaluationScope(1L, clinicId, "f".repeat(64)),
        targetRevision = 2L,
        eventId = "event-$id",
        assessmentRef = "assessment-2",
        assessmentHash = "a".repeat(64),
        status = ProfileReevaluationJobStatus.RUNNING,
        occurredAt = now.minusSeconds(30),
        dueAt = now.minusSeconds(10),
        targetDuration = Duration.ofSeconds(20),
        heldTarget = Duration.ofSeconds(20),
        proposedTarget = Duration.ofMinutes(5),
        targetPolicyRef = "platform-default",
        targetPolicyGeneration = 1L,
        nextAttemptAt = now,
        leaseOwner = "worker-a",
        leaseExpiresAt = now.plusSeconds(30),
        attemptCount = 1,
        firstAttemptAt = now.minusSeconds(5),
        redriveCount = 0,
        rootJobId = id,
        redriveOfJobId = null,
        redriveGeneration = 0,
        priorityClass = priority,
        heldCursorAppointmentId = null,
        proposedCursorAppointmentId = null,
        scannedCount = 0L,
        outcomeCounts = ProfileReevaluationOutcomeCounts(),
        lastFailureCode = null,
        createdAt = now.minusSeconds(30),
        updatedAt = now.minusSeconds(5),
    )
}
