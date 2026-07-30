package io.bluetape4k.clinic.appointment.api.profile

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitmentStatus
import io.bluetape4k.clinic.appointment.model.dto.ClaimProfileReevaluationJobs
import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationCursor
import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationJobRecord
import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationOutcomeCounts
import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationPriorityClass
import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationScope
import io.bluetape4k.clinic.appointment.model.dto.RedriveProfileReevaluationJob
import io.bluetape4k.clinic.appointment.model.profile.ProfileReevaluationJobStatus
import io.bluetape4k.clinic.appointment.model.profile.ProfileReevaluationOutcomeType
import io.bluetape4k.clinic.appointment.repository.ProfileReevaluationAppointmentCandidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

internal class ProfileReevaluationWorkerTest {
    @Test
    fun `한 tick은 설정된 예약 수만 처리하고 상태별 cursor를 checkpoint한다`() {
        runBlocking {
            val job = job()
            val store =
                FakeWorkStore(
                    currentRevision = job.targetRevision,
                    candidates =
                        listOf(
                            candidate(1L, AppointmentCommitmentStatus.HELD),
                            candidate(2L, AppointmentCommitmentStatus.HELD),
                            candidate(3L, AppointmentCommitmentStatus.PROPOSED),
                            candidate(4L, AppointmentCommitmentStatus.PROPOSED),
                            candidate(5L, AppointmentCommitmentStatus.PROPOSED),
                        ),
                )
            val processed = mutableListOf<Long>()
            val worker =
                worker(
                    store = store,
                    maxAppointmentsPerTick = 3,
                    pageSize = 2,
                    processor =
                        ProfileReevaluationAppointmentProcessor { _, appointment, _, _ ->
                            processed += appointment.appointmentId
                            ProfileReevaluationOutcomeType.HOLD_KEPT
                        },
                )

            val result = worker.process(job)

            result shouldBeEqualTo ProfileReevaluationWorkerResult.BOUNDED
            processed shouldBeEqualTo listOf(1L, 2L, 3L)
            store.checkpoints.size shouldBeEqualTo 3
            store.checkpoints.last().heldCursorAppointmentId shouldBeEqualTo 2L
            store.checkpoints.last().proposedCursorAppointmentId shouldBeEqualTo 3L
            store.deferReasonCode shouldBeEqualTo "TICK_BUDGET_EXHAUSTED"
            store.retryFailureCode shouldBeEqualTo null
            store.completed.shouldBeFalse()
        }
    }

    @Test
    fun `더 최신 revision을 발견하면 stale로 닫고 예약을 처리하지 않는다`() {
        runBlocking {
            val job = job()
            val store =
                FakeWorkStore(
                    currentRevision = job.targetRevision + 1L,
                    candidates = listOf(candidate(1L, AppointmentCommitmentStatus.HELD)),
                )
            var processed = 0
            val result =
                worker(
                    store = store,
                    processor =
                        ProfileReevaluationAppointmentProcessor { _, _, _, _ ->
                            processed++
                            ProfileReevaluationOutcomeType.HOLD_KEPT
                        },
                ).process(job)

            result shouldBeEqualTo ProfileReevaluationWorkerResult.STALE
            store.markedStaleRevision shouldBeEqualTo job.targetRevision + 1L
            processed shouldBeEqualTo 0
        }
    }

    @Test
    fun `lease 갱신 실패와 runtime gate 중단 뒤에는 추가 mutation을 만들지 않는다`() {
        runBlocking {
            val job = job()
            val leaseLostStore =
                FakeWorkStore(
                    currentRevision = job.targetRevision,
                    candidates = listOf(candidate(1L, AppointmentCommitmentStatus.HELD)),
                    renewLeaseResult = false,
                )
            var leaseLostMutations = 0

            val leaseLost =
                worker(
                    store = leaseLostStore,
                    processor =
                        ProfileReevaluationAppointmentProcessor { _, _, _, _ ->
                            leaseLostMutations++
                            ProfileReevaluationOutcomeType.HOLD_KEPT
                        },
                ).process(job)

            leaseLost shouldBeEqualTo ProfileReevaluationWorkerResult.LEASE_LOST
            leaseLostMutations shouldBeEqualTo 0

            val gateStore =
                FakeWorkStore(
                    currentRevision = job.targetRevision,
                    candidates =
                        listOf(
                            candidate(1L, AppointmentCommitmentStatus.HELD),
                            candidate(2L, AppointmentCommitmentStatus.HELD),
                        ),
                )
            var gateReads = 0
            var gateMutations = 0
            val changingGate =
                ProfileReevaluationRuntimeGate {
                    gateReads++
                    if (gateReads <= 2) {
                        ProfileReevaluationRuntimeAccess.enabled(
                            ProfileReevaluationMutationMode.APPLY_PROPOSED_AND_HELD,
                        )
                    } else {
                        ProfileReevaluationRuntimeAccess.disabled()
                    }
                }

            val paused =
                worker(
                    store = gateStore,
                    runtimeGate = changingGate,
                    processor =
                        ProfileReevaluationAppointmentProcessor { _, _, _, _ ->
                            gateMutations++
                            ProfileReevaluationOutcomeType.HOLD_KEPT
                        },
                ).process(job)

            paused shouldBeEqualTo ProfileReevaluationWorkerResult.PAUSED
            gateMutations shouldBeEqualTo 1
            gateStore.deferReasonCode shouldBeEqualTo "RUNTIME_GATE_DISABLED"
            gateStore.retryFailureCode shouldBeEqualTo null
        }
    }

    @Test
    fun `coroutine cancellation은 retry로 바꾸지 않고 즉시 전파한다`() {
        val job = job()
        val store =
            FakeWorkStore(
                currentRevision = job.targetRevision,
                candidates = listOf(candidate(1L, AppointmentCommitmentStatus.HELD)),
            )
        val worker =
            worker(
                store = store,
                processor =
                    ProfileReevaluationAppointmentProcessor { _, _, _, _ ->
                        throw CancellationException("cancelled")
                    },
            )

        assertFailsWith<CancellationException> {
            runBlocking { worker.process(job) }
        }
        store.retryFailureCode shouldBeEqualTo null
    }

    @Test
    fun `예상 밖 처리 실패는 민감정보 없이 구분 가능한 terminal 증거를 남긴다`() {
        runBlocking {
            val job = job()
            val store =
                FakeWorkStore(
                    currentRevision = job.targetRevision,
                    candidates = listOf(candidate(1L, AppointmentCommitmentStatus.HELD)),
                )
            val observed = mutableListOf<ProfileReevaluationFailureEvidence>()
            val result =
                worker(
                    store = store,
                    failureObserver = ProfileReevaluationFailureObserver(observed::add),
                    processor =
                        ProfileReevaluationAppointmentProcessor { _, _, _, _ ->
                            throw IllegalStateException("must not be persisted")
                        },
                ).process(job)

            result shouldBeEqualTo ProfileReevaluationWorkerResult.FAILED
            store.retryFailureCode shouldBeEqualTo "PROCESSING_STATE_FAILED"
            store.retryTerminal.shouldBeTrue()
            observed.single() shouldBeEqualTo
                ProfileReevaluationFailureEvidence(
                    jobId = job.id,
                    targetRevision = job.targetRevision,
                    failureCode = "PROCESSING_STATE_FAILED",
                    exceptionType = IllegalStateException::class.qualifiedName!!,
                )
        }
    }

    @Test
    fun `runtime mode가 제안 전용으로 낮아지면 이후 선점은 변경하지 않는다`() {
        runBlocking {
            val job = job()
            val store =
                FakeWorkStore(
                    currentRevision = job.targetRevision,
                    candidates =
                        listOf(
                            candidate(1L, AppointmentCommitmentStatus.HELD),
                            candidate(2L, AppointmentCommitmentStatus.HELD),
                            candidate(3L, AppointmentCommitmentStatus.PROPOSED),
                        ),
                )
            var reads = 0
            val processed = mutableListOf<Pair<Long, ProfileReevaluationMutationMode>>()
            val gate =
                ProfileReevaluationRuntimeGate {
                    reads++
                    ProfileReevaluationRuntimeAccess.enabled(
                        if (reads <= 2) {
                            ProfileReevaluationMutationMode.APPLY_PROPOSED_AND_HELD
                        } else {
                            ProfileReevaluationMutationMode.APPLY_PROPOSED
                        },
                    )
                }

            val result =
                worker(
                    store = store,
                    runtimeGate = gate,
                    processor =
                        ProfileReevaluationAppointmentProcessor { _, appointment, _, mode ->
                            processed += appointment.appointmentId to mode
                            ProfileReevaluationOutcomeType.SKIPPED_UNCHANGED
                        },
                ).process(job)

            result shouldBeEqualTo ProfileReevaluationWorkerResult.PAUSED
            processed shouldBeEqualTo
                listOf(
                    1L to ProfileReevaluationMutationMode.APPLY_PROPOSED_AND_HELD,
                    3L to ProfileReevaluationMutationMode.APPLY_PROPOSED,
                )
            store.deferReasonCode shouldBeEqualTo "RUNTIME_MODE_EXCLUDES_HELD"
            store.retryFailureCode shouldBeEqualTo null
        }
    }

    @Test
    fun `dry run은 계산 모드를 전달하되 결과 mutation count를 만들지 않는다`() {
        runBlocking {
            val job = job()
            val store =
                FakeWorkStore(
                    currentRevision = job.targetRevision,
                    candidates = listOf(candidate(1L, AppointmentCommitmentStatus.PROPOSED)),
                )
            var observedMode: ProfileReevaluationMutationMode? = null

            val result =
                worker(
                    store = store,
                    runtimeGate =
                        ProfileReevaluationRuntimeGate {
                            ProfileReevaluationRuntimeAccess.enabled(
                                ProfileReevaluationMutationMode.DRY_RUN,
                            )
                        },
                    processor =
                        ProfileReevaluationAppointmentProcessor { _, _, _, mode ->
                            observedMode = mode
                            null
                        },
                ).process(job)

            result shouldBeEqualTo ProfileReevaluationWorkerResult.COMPLETED
            observedMode shouldBeEqualTo ProfileReevaluationMutationMode.DRY_RUN
            store.checkpoints.single().outcomeDeltas shouldBeEqualTo ProfileReevaluationOutcomeCounts()
            store.completed.shouldBeTrue()
        }
    }

    @Test
    fun `제안 전용 작업은 APPLY_PROPOSED에서 선점 대기 없이 완료한다`() {
        runBlocking {
            val job = job(priority = ProfileReevaluationPriorityClass.PROPOSED_ONLY)
            val store =
                FakeWorkStore(
                    currentRevision = job.targetRevision,
                    candidates = listOf(candidate(1L, AppointmentCommitmentStatus.PROPOSED)),
                )

            val result =
                worker(
                    store = store,
                    runtimeGate =
                        ProfileReevaluationRuntimeGate {
                            ProfileReevaluationRuntimeAccess.enabled(
                                ProfileReevaluationMutationMode.APPLY_PROPOSED,
                            )
                        },
                    processor =
                        ProfileReevaluationAppointmentProcessor { _, _, _, _ ->
                            ProfileReevaluationOutcomeType.PROPOSAL_SUPERSEDED
                        },
                ).process(job)

            result shouldBeEqualTo ProfileReevaluationWorkerResult.COMPLETED
            store.completed.shouldBeTrue()
            store.retryFailureCode shouldBeEqualTo null
        }
    }

    @Test
    fun `retry는 attempt와 elapsed time 중 먼저 도달한 한도에서 실패한다`() {
        val policy =
            ProfileReevaluationRetryPolicy(
                maxAttempts = 4,
                maxElapsedTime = Duration.ofMinutes(10),
                initialBackoff = Duration.ofSeconds(2),
                maxBackoff = Duration.ofSeconds(20),
                jitterRatio = 0.25,
                randomFraction = { 0.5 },
            )
        val now = Instant.parse("2026-08-01T00:10:00Z")

        policy.decide(attemptCount = 3, firstAttemptAt = now.minusSeconds(60), now = now) shouldBeEqualTo
            ProfileReevaluationRetryDecision.Retry(Duration.ofSeconds(8))
        policy.decide(attemptCount = 4, firstAttemptAt = now.minusSeconds(60), now = now) shouldBeEqualTo
            ProfileReevaluationRetryDecision.Failed
        policy.decide(attemptCount = 2, firstAttemptAt = now.minus(Duration.ofMinutes(10)), now = now) shouldBeEqualTo
            ProfileReevaluationRetryDecision.Failed
    }

    @Test
    fun `자동 redrive는 두 번과 cooldown 범위를 넘지 않는다`() {
        val policy = ProfileReevaluationRedrivePolicy()
        val failed = job().copy(
            status = ProfileReevaluationJobStatus.FAILED,
            redriveCount = 0,
            redriveGeneration = 1,
            updatedAt = NOW.minus(Duration.ofMinutes(30)),
        )

        policy.commandFor(failed, NOW)?.jobId shouldBeEqualTo failed.id
        policy.commandFor(failed.copy(redriveGeneration = 2), NOW) shouldBeEqualTo null
        policy.commandFor(failed.copy(redriveCount = 1), NOW) shouldBeEqualTo null
        policy.commandFor(failed.copy(updatedAt = NOW.minus(Duration.ofMinutes(29))), NOW) shouldBeEqualTo null
    }

    private fun worker(
        store: FakeWorkStore,
        maxAppointmentsPerTick: Int = 10,
        pageSize: Int = 10,
        runtimeGate: ProfileReevaluationRuntimeGate =
            ProfileReevaluationRuntimeGate {
                ProfileReevaluationRuntimeAccess.enabled(
                    ProfileReevaluationMutationMode.APPLY_PROPOSED_AND_HELD,
                )
            },
        failureObserver: ProfileReevaluationFailureObserver =
            ProfileReevaluationFailureObserver { },
        processor: ProfileReevaluationAppointmentProcessor,
    ) = ProfileReevaluationWorker(
        store = store,
        assessmentClient = ProfileAssessmentClient { assessment() },
        appointmentProcessor = processor,
        runtimeGate = runtimeGate,
        failureObserver = failureObserver,
        retryPolicy = ProfileReevaluationRetryPolicy(randomFraction = { 0.5 }),
        maxAppointmentsPerTick = maxAppointmentsPerTick,
        pageSize = pageSize,
        leaseRenewInterval = Duration.ofSeconds(10),
        clock = Clock.fixed(NOW, ZoneOffset.UTC),
    )

    private fun assessment() =
        ProfileSchedulingAssessment(
            tenantGroupId = 1L,
            clinicId = 10L,
            patientReferenceFingerprint = FINGERPRINT,
            profileRevision = 2L,
            assessmentReference = "assessment-2",
            assessmentHash = "a".repeat(64),
            eligibleServiceCodes = emptySet(),
            requiredResourceTags = emptySet(),
            allowedTimeWindows = emptyList(),
        )

    private fun candidate(
        id: Long,
        status: AppointmentCommitmentStatus,
    ) = ProfileReevaluationAppointmentCandidate(
        appointmentId = id,
        commitmentId = id,
        commitmentStatus = status,
        commitmentVersion = 1L,
        effectivePolicySnapshotId = 7L,
    )

    private fun job(
        id: Long = 1L,
        clinicId: Long = 10L,
        priority: ProfileReevaluationPriorityClass = ProfileReevaluationPriorityClass.HELD_PRESENT,
    ) = ProfileReevaluationJobRecord(
        id = id,
        headId = id,
        scope = ProfileReevaluationScope(1L, clinicId, FINGERPRINT),
        targetRevision = 2L,
        eventId = "event-$id",
        assessmentRef = "assessment-2",
        assessmentHash = "a".repeat(64),
        status = ProfileReevaluationJobStatus.RUNNING,
        occurredAt = NOW.minusSeconds(30),
        dueAt = NOW.minusSeconds(10),
        targetDuration = Duration.ofSeconds(20),
        heldTarget = Duration.ofSeconds(20),
        proposedTarget = Duration.ofMinutes(5),
        targetPolicyRef = "platform-default",
        targetPolicyGeneration = 1L,
        nextAttemptAt = NOW,
        leaseOwner = "worker-a",
        leaseExpiresAt = NOW.plusSeconds(30),
        attemptCount = 1,
        firstAttemptAt = NOW.minusSeconds(5),
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
        createdAt = NOW.minusSeconds(30),
        updatedAt = NOW.minusSeconds(5),
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-01T00:10:00Z")
        const val FINGERPRINT = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
    }
}

private class FakeWorkStore(
    var currentRevision: Long,
    private val candidates: List<ProfileReevaluationAppointmentCandidate>,
    private val renewLeaseResult: Boolean = true,
) : ProfileReevaluationWorkStore {
    val checkpoints = mutableListOf<ProfileReevaluationCursor>()
    var completed = false
    var markedStaleRevision: Long? = null
    var retryFailureCode: String? = null
    var retryTerminal: Boolean = false
    var deferReasonCode: String? = null

    override suspend fun claim(command: ClaimProfileReevaluationJobs): List<ProfileReevaluationJobRecord> =
        emptyList()

    override suspend fun currentRevision(scope: ProfileReevaluationScope): Long? = currentRevision

    override suspend fun candidates(
        scope: ProfileReevaluationScope,
        status: AppointmentCommitmentStatus,
        afterAppointmentId: Long,
        limit: Int,
    ): List<ProfileReevaluationAppointmentCandidate> =
        candidates
            .filter { it.commitmentStatus == status && it.appointmentId > afterAppointmentId }
            .take(limit)

    override suspend fun renewLease(job: ProfileReevaluationJobRecord): Boolean = renewLeaseResult

    override suspend fun checkpoint(
        job: ProfileReevaluationJobRecord,
        cursor: ProfileReevaluationCursor,
    ): Boolean {
        checkpoints +=
            cursor.copy(
                heldCursorAppointmentId =
                    cursor.heldCursorAppointmentId ?: checkpoints.lastOrNull()?.heldCursorAppointmentId,
                proposedCursorAppointmentId =
                    cursor.proposedCursorAppointmentId ?: checkpoints.lastOrNull()?.proposedCursorAppointmentId,
            )
        return true
    }

    override suspend fun complete(job: ProfileReevaluationJobRecord): Boolean {
        completed = true
        return true
    }

    override suspend fun markStale(
        job: ProfileReevaluationJobRecord,
        observedRevision: Long,
    ): Boolean {
        markedStaleRevision = observedRevision
        return true
    }

    override suspend fun retry(
        job: ProfileReevaluationJobRecord,
        failureCode: String,
        delay: Duration,
        terminal: Boolean,
    ): Boolean {
        retryFailureCode = failureCode
        retryTerminal = terminal
        return true
    }

    override suspend fun defer(
        job: ProfileReevaluationJobRecord,
        reasonCode: String,
        delay: Duration,
    ): Boolean {
        deferReasonCode = reasonCode
        return true
    }

    override suspend fun redrive(command: RedriveProfileReevaluationJob): ProfileReevaluationJobRecord? = null
}
