package io.bluetape4k.clinic.appointment.api.policy

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.api.config.SchedulingPolicyProperties
import io.bluetape4k.clinic.appointment.api.security.ActorContext
import io.bluetape4k.clinic.appointment.api.security.ActorType
import io.bluetape4k.clinic.appointment.api.security.AuthenticationAssurance
import io.bluetape4k.clinic.appointment.model.dto.PolicyActivationCommandStatus
import io.bluetape4k.clinic.appointment.model.dto.PolicyPreviewJobStatus
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyActivationCommandRecord
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyPreviewJobRecord
import io.bluetape4k.clinic.appointment.model.policy.ActorRole
import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import io.bluetape4k.clinic.appointment.model.policy.SchedulingPolicyKind
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * activation/preview worker가 DB 시각, bounded claim, owner fencing, 독립적 공정성,
 * backoff 및 종료 계약을 지키는지 외부 스케줄러 없이 결정적으로 검증한다.
 */
class SchedulingPolicyWorkerTest {

    private val now = Instant.parse("2026-07-27T00:00:00Z")

    @Test
    fun `one catch up tick bounds activation and preview independently`() {
        val store = FakeWorkerStore(
            now = now,
            activationIds = (1L..30L).toList(),
            previewIds = (101L..120L).toList(),
        )
        val executed = mutableListOf<Long>()
        val previewed = mutableListOf<Long>()
        val worker = worker(
            store = store,
            executor = ScheduledPolicyActivationExecutor { commandId, _, _, _ ->
                executed += commandId
                ScheduledPolicyActivationExecutionOutcome(idempotentReplay = false)
            },
            previewProcessor = ScheduledPolicyPreviewPageProcessor { jobId, _, _ ->
                previewed += jobId
                SchedulingPolicyPreviewResult(
                    SchedulingPolicyPreviewDisposition.ACCEPTED_ASYNC,
                    preview(jobId),
                )
            },
        )

        worker.runCatchUp()

        store.requestedActivationLimit shouldBeEqualTo 25
        store.requestedPreviewLimit shouldBeEqualTo 10
        executed.size shouldBeEqualTo 25
        previewed.size shouldBeEqualTo 10
    }

    @Test
    fun `activation beyond missed deadline preserves prior state and never invokes executor`() {
        val commandId = 1L
        val store = FakeWorkerStore(
            now = now,
            activationIds = listOf(commandId),
            previewIds = emptyList(),
            effectiveFrom = now.minusSeconds(301),
        )
        var executorCalled = false
        val worker = worker(
            store = store,
            executor = ScheduledPolicyActivationExecutor { _, _, _, _ ->
                executorCalled = true
                ScheduledPolicyActivationExecutionOutcome(false)
            },
        )

        worker.runActivationTick()

        executorCalled.shouldBeFalse()
        store.missedIds shouldBeEqualTo listOf(commandId)
        store.retryIds shouldBeEqualTo emptyList()
    }

    @Test
    fun `transient failure records deterministic exponential retry using database time`() {
        val commandId = 7L
        val store = FakeWorkerStore(
            now = now,
            activationIds = listOf(commandId),
            previewIds = emptyList(),
            attempt = 2,
        )
        val worker = worker(
            store = store,
            executor = ScheduledPolicyActivationExecutor { _, _, _, _ ->
                throw IllegalStateException("simulated transient failure")
            },
        )

        worker.runActivationTick()

        store.retryIds shouldBeEqualTo listOf(commandId)
        store.retryAt shouldBeEqualTo now
        store.retryNextAttemptAt shouldBeEqualTo now.plusSeconds(10)
        store.claimNow shouldBeEqualTo now
        store.retryErrorCode shouldBeEqualTo "TRANSIENT_ACTIVATION_FAILURE"
    }

    @Test
    fun `default off flags and shutdown stop all new claims`() {
        val store = FakeWorkerStore(now, listOf(1L), listOf(2L))
        val disabled = SchedulingPolicyWorker(
            store = store,
            activationExecutor = ScheduledPolicyActivationExecutor { _, _, _, _ ->
                error("disabled worker must not execute")
            },
            previewProcessor = ScheduledPolicyPreviewPageProcessor { _, _, _ ->
                error("disabled worker must not execute")
            },
            properties = SchedulingPolicyProperties(),
            metrics = SchedulingPolicyMetrics(SimpleMeterRegistry()),
            systemActor = systemActor(),
            ownerFactory = { "worker-1" },
            jitterUnit = { _, _ -> 0.0 },
        )

        disabled.runCatchUp()
        store.dueSelectionCalls shouldBeEqualTo 0

        val enabled = worker(store)
        enabled.shutdown().shouldBeTrue()
        enabled.runCatchUp()
        store.dueSelectionCalls shouldBeEqualTo 0
    }

    @Test
    fun `preview processor exception is owner fenced into durable failed state`() {
        val jobId = 101L
        val store = FakeWorkerStore(now, emptyList(), listOf(jobId))
        val registry = SimpleMeterRegistry()
        val worker = SchedulingPolicyWorker(
            store = store,
            activationExecutor = ScheduledPolicyActivationExecutor { _, _, _, _ ->
                ScheduledPolicyActivationExecutionOutcome(false)
            },
            previewProcessor = ScheduledPolicyPreviewPageProcessor { _, _, _ ->
                throw IllegalStateException("payload must not escape into logs or durable error codes")
            },
            properties = enabledProperties(),
            metrics = SchedulingPolicyMetrics(registry),
            systemActor = systemActor(),
            ownerFactory = { "worker-1" },
            jitterUnit = { _, _ -> 0.0 },
        )

        worker.runPreviewTick()

        store.failedPreviewIds shouldBeEqualTo listOf(jobId)
        store.previewFailureCode shouldBeEqualTo "PREVIEW_PROCESSING_FAILED"
        registry.get("clinic.scheduling.policy.preview")
            .tag("result", "failed")
            .counter()
            .count() shouldBeEqualTo 1.0
    }

    @Test
    fun `worker completion is measured as asynchronous completion`() {
        val jobId = 101L
        val store = FakeWorkerStore(now, emptyList(), listOf(jobId))
        val registry = SimpleMeterRegistry()
        val worker = SchedulingPolicyWorker(
            store = store,
            activationExecutor = ScheduledPolicyActivationExecutor { _, _, _, _ ->
                ScheduledPolicyActivationExecutionOutcome(false)
            },
            previewProcessor = ScheduledPolicyPreviewPageProcessor { _, _, _ ->
                SchedulingPolicyPreviewResult(
                    SchedulingPolicyPreviewDisposition.COMPLETED,
                    preview(jobId).copy(status = PolicyPreviewJobStatus.COMPLETED),
                )
            },
            properties = enabledProperties(),
            metrics = SchedulingPolicyMetrics(registry),
            systemActor = systemActor(),
            ownerFactory = { "worker-1" },
            jitterUnit = { _, _ -> 0.0 },
        )

        worker.runPreviewTick()

        registry.get("clinic.scheduling.policy.preview")
            .tag("result", "completed_async")
            .counter()
            .count() shouldBeEqualTo 1.0
    }

    @Test
    fun `shutdown observes already admitted work and waits until its bounded call returns`() {
        val store = FakeWorkerStore(now, emptyList(), listOf(101L))
        val processorEntered = CountDownLatch(1)
        val processorReleased = CountDownLatch(1)
        val shutdownObserved = CountDownLatch(1)
        val worker = SchedulingPolicyWorker(
            store = store,
            activationExecutor = ScheduledPolicyActivationExecutor { _, _, _, _ ->
                ScheduledPolicyActivationExecutionOutcome(false)
            },
            previewProcessor = ScheduledPolicyPreviewPageProcessor { jobId, _, _ ->
                processorEntered.countDown()
                check(processorReleased.await(5, TimeUnit.SECONDS)) {
                    "preview processor release timed out"
                }
                SchedulingPolicyPreviewResult(
                    SchedulingPolicyPreviewDisposition.COMPLETED,
                    preview(jobId).copy(status = PolicyPreviewJobStatus.COMPLETED),
                )
            },
            properties = enabledProperties(),
            metrics = SchedulingPolicyMetrics(SimpleMeterRegistry()),
            systemActor = systemActor(),
            ownerFactory = { "worker-1" },
            jitterUnit = { _, _ -> 0.0 },
            monotonicNanos = {
                shutdownObserved.countDown()
                0L
            },
        )
        val executor = Executors.newFixedThreadPool(2)

        try {
            val tick = executor.submit { worker.runPreviewTick() }
            processorEntered.await(5, TimeUnit.SECONDS).shouldBeTrue()
            val shutdown = executor.submit<Boolean> { worker.shutdown() }
            shutdownObserved.await(5, TimeUnit.SECONDS).shouldBeTrue()
            shutdown.isDone.shouldBeFalse()

            processorReleased.countDown()

            tick.get(5, TimeUnit.SECONDS)
            shutdown.get(5, TimeUnit.SECONDS).shouldBeTrue()
        } finally {
            processorReleased.countDown()
            executor.shutdownNow()
        }
    }

    private fun worker(
        store: FakeWorkerStore,
        executor: ScheduledPolicyActivationExecutor =
            ScheduledPolicyActivationExecutor { _, _, _, _ ->
                ScheduledPolicyActivationExecutionOutcome(false)
            },
        previewProcessor: ScheduledPolicyPreviewPageProcessor =
            ScheduledPolicyPreviewPageProcessor { _, _, _ -> null },
    ) = SchedulingPolicyWorker(
        store = store,
        activationExecutor = executor,
        previewProcessor = previewProcessor,
        properties = enabledProperties(),
        metrics = SchedulingPolicyMetrics(SimpleMeterRegistry()),
        systemActor = systemActor(),
        ownerFactory = { "worker-1" },
        jitterUnit = { _, _ -> 0.0 },
    )

    private fun enabledProperties() = SchedulingPolicyProperties(
        shadowCompileEnabled = true,
        effectiveReadEnabled = true,
        adminWriteEnabled = true,
        previewWorkerEnabled = true,
        scheduledActivationEnabled = true,
    )

    private fun systemActor() = ActorContext(
        actorId = "scheduling-policy-worker",
        actorType = ActorType.SYSTEM,
        roles = setOf(ActorRole.SYSTEM),
        scopes = setOf("policy:scheduled-activation"),
        allowedTenantCodes = emptySet(),
        allowedClinicIds = emptySet(),
        patientSubjectId = null,
        assurance = AuthenticationAssurance.SERVICE,
        issuer = "clinic-appointment",
        tokenId = "internal-scheduling-policy-worker",
        authenticatedAt = now,
        correlationId = "scheduled-policy-worker",
    )

    private fun activation(
        id: Long,
        effectiveFrom: Instant,
        attempt: Int,
        status: PolicyActivationCommandStatus = PolicyActivationCommandStatus.PENDING,
        owner: String? = null,
        leaseUntil: Instant? = null,
    ) = SchedulingPolicyActivationCommandRecord(
        id = id,
        tenantGroupId = 1L,
        scope = PolicyScope.TENANT_DEFAULT,
        definitionId = 7L,
        expectedDraftRevision = 3L,
        expectedActiveRevision = 2L,
        expectedTenantGeneration = 1L,
        expectedClinicGeneration = 0L,
        previewEvidenceToken = "preview-token-$id",
        idempotencyKeyHash = "a".repeat(64),
        requestFingerprint = "b".repeat(64),
        status = status,
        effectiveFrom = effectiveFrom,
        nextAttemptAt = effectiveFrom,
        leaseOwner = owner,
        leaseUntil = leaseUntil,
        attempt = attempt,
    )

    private fun preview(id: Long) = SchedulingPolicyPreviewJobRecord(
        id = id,
        tenantGroupId = 1L,
        clinicId = 41L,
        definitionId = 7L,
        draftRevision = 3L,
        tenantGeneration = 1L,
        clinicGeneration = 0L,
        partitionCount = 2,
        status = PolicyPreviewJobStatus.PENDING,
        deadlineAt = now.plusSeconds(300),
        nextAttemptAt = now,
    )

    private inner class FakeWorkerStore(
        private val now: Instant,
        private val activationIds: List<Long>,
        private val previewIds: List<Long>,
        private val effectiveFrom: Instant = now,
        private val attempt: Int = 1,
    ) : SchedulingPolicyWorkerStore {
        var requestedActivationLimit: Int = 0
        var requestedPreviewLimit: Int = 0
        var dueSelectionCalls: Int = 0
        var claimNow: Instant? = null
        val missedIds = mutableListOf<Long>()
        val retryIds = mutableListOf<Long>()
        val failedPreviewIds = mutableListOf<Long>()
        var retryAt: Instant? = null
        var retryNextAttemptAt: Instant? = null
        var retryErrorCode: String? = null
        var previewFailureCode: String? = null

        override fun databaseNow(): Instant = now

        override fun findDueActivationIds(
            databaseNow: Instant,
            limit: Int,
        ): List<Long> {
            dueSelectionCalls++
            requestedActivationLimit = limit
            return activationIds.take(limit)
        }

        override fun claimActivation(
            commandId: Long,
            owner: String,
            databaseNow: Instant,
            leaseUntil: Instant,
        ): SchedulingPolicyActivationWork {
            claimNow = databaseNow
            return SchedulingPolicyActivationWork(
                activation(
                    id = commandId,
                    effectiveFrom = effectiveFrom,
                    attempt = attempt,
                    status = PolicyActivationCommandStatus.CLAIMED,
                    owner = owner,
                    leaseUntil = leaseUntil,
                ),
                SchedulingPolicyKind.BOOKING_COMMITMENT,
            )
        }

        override fun markActivationRetry(
            commandId: Long,
            owner: String,
            errorCode: String,
            nextAttemptAt: Instant,
            retryAt: Instant,
        ): Boolean {
            retryIds += commandId
            this.retryErrorCode = errorCode
            this.retryNextAttemptAt = nextAttemptAt
            this.retryAt = retryAt
            return true
        }

        override fun markActivationMissed(
            commandId: Long,
            owner: String,
            errorCode: String,
            missedAt: Instant,
        ): Boolean {
            missedIds += commandId
            return true
        }

        override fun findDuePreviewIds(
            databaseNow: Instant,
            limit: Int,
        ): List<Long> {
            dueSelectionCalls++
            requestedPreviewLimit = limit
            return previewIds.take(limit)
        }

        override fun findPreviewKind(jobId: Long): SchedulingPolicyKind =
            SchedulingPolicyKind.BOOKING_COMMITMENT

        override fun markPreviewFailed(
            jobId: Long,
            owner: String,
            errorCode: String,
            failedAt: Instant,
        ): Boolean {
            failedPreviewIds += jobId
            previewFailureCode = errorCode
            return true
        }
    }
}
