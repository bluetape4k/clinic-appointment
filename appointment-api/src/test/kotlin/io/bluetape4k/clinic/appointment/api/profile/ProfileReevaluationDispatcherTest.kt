package io.bluetape4k.clinic.appointment.api.profile

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.model.dto.ClaimProfileReevaluationJobs
import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationJobRecord
import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationOutcomeCounts
import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationPriorityClass
import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationScope
import io.bluetape4k.clinic.appointment.model.dto.RedriveProfileReevaluationJob
import io.bluetape4k.clinic.appointment.model.profile.ProfileReevaluationJobStatus
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal class ProfileReevaluationDispatcherTest {
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
                )

            dispatcher.dispatchOnce()

            order.toSet() shouldBeEqualTo setOf(held.id, agedProposed.id)
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
                    clock = Clock.fixed(now, ZoneOffset.UTC),
                )

            dispatcher.dispatchOnce()

            redriveCommand?.jobId shouldBeEqualTo failed.id
            redriveCommand?.expectedRedriveCount shouldBeEqualTo 0
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
