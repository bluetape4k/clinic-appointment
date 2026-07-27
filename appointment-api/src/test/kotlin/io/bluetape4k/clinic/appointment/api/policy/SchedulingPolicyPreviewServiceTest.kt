package io.bluetape4k.clinic.appointment.api.policy

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.clinic.appointment.api.config.SchedulingPolicyApiException
import io.bluetape4k.clinic.appointment.api.config.SchedulingPolicyErrorCode
import io.bluetape4k.clinic.appointment.api.config.SchedulingPolicyProperties
import io.bluetape4k.clinic.appointment.model.dto.PolicyPreviewJobStatus
import io.bluetape4k.clinic.appointment.model.dto.PolicyPreviewProgress
import io.bluetape4k.clinic.appointment.model.dto.PolicyScopeRef
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyPreviewJobRecord
import io.bluetape4k.clinic.appointment.model.policy.PolicyGenerationVector
import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import io.bluetape4k.clinic.appointment.repository.PolicyImpactAggregateType
import io.bluetape4k.clinic.appointment.repository.PolicyImpactCursor
import io.bluetape4k.clinic.appointment.repository.PolicyImpactKey
import io.bluetape4k.clinic.appointment.repository.PolicyImpactPage
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 영향도 preview가 동기 응답과 durable 비동기 작업 사이에서 동일한 bounded scan 계약을
 * 사용하는지 검증한다.
 *
 * fake monotonic ticker와 in-memory persistence를 사용해 wall clock 변화와 무관하게 5,000행
 * page, 10,000행 동기 상한, stale 전이, terminal evidence 제거, queue 포화 오류를
 * 결정적으로 확인한다. fake store는 한 번에 전달된 최대 item 수를 기록해 서비스가 전체
 * 10,000행을 별도 collection으로 보유하지 않는지도 검증한다.
 */
class SchedulingPolicyPreviewServiceTest {

    private val now = Instant.parse("2026-07-27T00:00:00Z")
    private val command = CreateSchedulingPolicyPreviewCommand(
        scope = PolicyScopeRef(1L, PolicyScope.CLINIC_OVERRIDE, 41L),
        definitionId = 7L,
        draftRevision = 3L,
        generation = PolicyGenerationVector(2L, 1L),
        horizonFrom = now,
        horizonUntil = now.plus(Duration.ofDays(30)),
        requestedAt = now,
    )

    @Test
    fun `small preview completes synchronously with one persisted job and bounded pages`() {
        val store = FakePreviewStore(
            pages = ArrayDeque(
                listOf(
                    page(size = 5_000, next = cursor(5_000)),
                    page(size = 3, next = null, startId = 5_001),
                )
            )
        )
        val service = service(store)

        val result = service.submit(command)

        result.disposition shouldBeEqualTo SchedulingPolicyPreviewDisposition.COMPLETED
        result.job.status shouldBeEqualTo PolicyPreviewJobStatus.COMPLETED
        result.job.scannedCount shouldBeEqualTo 5_003L
        result.job.affectedCount shouldBeEqualTo 5_003L
        result.job.resultHash.shouldNotBeNull()
        result.job.activationEvidenceToken shouldBeEqualTo "preview-token-1"
        store.createdCount shouldBeEqualTo 1
        store.maxRequestedPageSize shouldBeEqualTo 5_000
        store.maxMaterializedPageSize shouldBeEqualTo 5_000
    }

    @Test
    fun `row threshold converts to durable async work and keeps no partial activation evidence`() {
        val store = FakePreviewStore(
            pages = ArrayDeque(
                listOf(
                    page(size = 5_000, next = cursor(5_000)),
                    page(size = 5_000, next = cursor(10_000), startId = 5_001),
                )
            )
        )
        val service = service(store)

        val result = service.submit(command)

        result.disposition shouldBeEqualTo SchedulingPolicyPreviewDisposition.ACCEPTED_ASYNC
        result.job.status shouldBeEqualTo PolicyPreviewJobStatus.PENDING
        result.job.scannedCount shouldBeEqualTo 10_000L
        result.job.resultHash.shouldBeNull()
        result.job.activationEvidenceToken.shouldBeNull()
        result.job.leaseOwner.shouldBeNull()
        store.maxMaterializedPageSize shouldBeEqualTo 5_000
    }

    @Test
    fun `monotonic two second budget converts to async without relying on wall clock`() {
        val store = FakePreviewStore(
            pages = ArrayDeque(
                listOf(
                    page(size = 5_000, next = cursor(5_000)),
                    page(size = 1, next = null, startId = 5_001),
                )
            )
        )
        val ticks = ArrayDeque(listOf(0L, Duration.ofSeconds(2).toNanos()))
        val service = SchedulingPolicyPreviewService(
            store = store,
            properties = SchedulingPolicyProperties(),
            monotonicNanos = { ticks.removeFirst() },
            ownerFactory = { "preview-owner-1" },
            evidenceTokenFactory = { "preview-token-1" },
        )

        val result = service.submit(command)

        result.disposition shouldBeEqualTo SchedulingPolicyPreviewDisposition.ACCEPTED_ASYNC
        result.job.status shouldBeEqualTo PolicyPreviewJobStatus.PENDING
        result.job.scannedCount shouldBeEqualTo 5_000L
        result.job.activationEvidenceToken.shouldBeNull()
        store.scanCalls shouldBeEqualTo 1
    }

    @Test
    fun `stale revision or generation terminates before another page is read`() {
        val store = FakePreviewStore(
            pages = ArrayDeque(listOf(page(size = 10, next = null))),
            pinnedStateCurrent = false,
        )
        val service = service(store)

        val result = service.submit(command)

        result.disposition shouldBeEqualTo SchedulingPolicyPreviewDisposition.STALE
        result.job.status shouldBeEqualTo PolicyPreviewJobStatus.STALE
        result.job.resultHash.shouldBeNull()
        result.job.activationEvidenceToken.shouldBeNull()
        store.scanCalls shouldBeEqualTo 0
    }

    @Test
    fun `only a saturated durable queue returns retryable preview limited`() {
        val store = FakePreviewStore(
            pages = ArrayDeque(),
            queueSaturated = true,
        )
        val service = service(store)

        val error = assertFailsWith<SchedulingPolicyApiException> {
            service.submit(command)
        }

        error.errorCode shouldBeEqualTo SchedulingPolicyErrorCode.POLICY_PREVIEW_LIMITED
        store.createdCount shouldBeEqualTo 0
    }

    @Test
    fun `database admission time controls deadline and first claim despite caller clock skew`() {
        val databaseNow = now.plusSeconds(120)
        val store = FakePreviewStore(
            pages = ArrayDeque(listOf(page(size = 0, next = null))),
            databaseNow = databaseNow,
        )
        val service = service(store)

        val result = service.submit(command.copy(requestedAt = now.minus(Duration.ofDays(30))))

        result.disposition shouldBeEqualTo SchedulingPolicyPreviewDisposition.COMPLETED
        result.job.deadlineAt shouldBeEqualTo databaseNow.plus(Duration.ofMinutes(5))
        store.lastClaimNow shouldBeEqualTo databaseNow
    }

    @Test
    fun `worker processing releases its lease after exactly one bounded page`() {
        val store = FakePreviewStore(
            pages = ArrayDeque(
                listOf(
                    page(size = 5_000, next = cursor(5_000)),
                    page(size = 5_000, next = cursor(10_000), startId = 5_001),
                )
            )
        )
        val service = service(store)
        val pending = store.seed(command, now.plusSeconds(300))
        val claimed = store.claim(pending.id!!, "worker-1", now, now.plusSeconds(30)).shouldNotBeNull()

        val result = service.processClaimedPage(claimed, "worker-1")

        result.disposition shouldBeEqualTo SchedulingPolicyPreviewDisposition.ACCEPTED_ASYNC
        result.job.status shouldBeEqualTo PolicyPreviewJobStatus.PENDING
        result.job.scannedCount shouldBeEqualTo 5_000L
        result.job.leaseOwner.shouldBeNull()
        result.job.activationEvidenceToken.shouldBeNull()
        store.scanCalls shouldBeEqualTo 1
        store.maxMaterializedPageSize shouldBeEqualTo 5_000
    }

    @Test
    fun `hard deadline fails before another page and never creates evidence`() {
        val store = FakePreviewStore(
            pages = ArrayDeque(listOf(page(size = 1, next = null))),
            databaseNow = now.plusSeconds(301),
        )
        val service = service(store)
        val pending = store.seed(command, now.plusSeconds(300))
        val claimed = store.claim(pending.id!!, "worker-1", now, now.plusSeconds(400)).shouldNotBeNull()

        val result = service.processClaimedPage(claimed, "worker-1")

        result.disposition shouldBeEqualTo SchedulingPolicyPreviewDisposition.FAILED
        result.job.status shouldBeEqualTo PolicyPreviewJobStatus.FAILED
        result.job.lastErrorCode shouldBeEqualTo "PREVIEW_DEADLINE_EXCEEDED"
        result.job.activationEvidenceToken.shouldBeNull()
        store.scanCalls shouldBeEqualTo 0
    }

    @Test
    fun `hard deadline crossed during a page discards uncommitted partial progress`() {
        val store = FakePreviewStore(
            pages = ArrayDeque(listOf(page(size = 10, next = null))),
            databaseNowSequence = ArrayDeque(listOf(now, now.plusSeconds(301))),
        )
        val service = service(store)
        val pending = store.seed(command, now.plusSeconds(300))
        val claimed = store.claim(pending.id!!, "worker-1", now, now.plusSeconds(400)).shouldNotBeNull()

        val result = service.processClaimedPage(claimed, "worker-1")

        result.disposition shouldBeEqualTo SchedulingPolicyPreviewDisposition.FAILED
        result.job.status shouldBeEqualTo PolicyPreviewJobStatus.FAILED
        result.job.scannedCount shouldBeEqualTo 0L
        result.job.affectedCount shouldBeEqualTo 0L
        result.job.resultHash.shouldBeNull()
        result.job.activationEvidenceToken.shouldBeNull()
        store.scanCalls shouldBeEqualTo 1
    }

    @Test
    fun `cancellation observed at page boundary discards the in memory page`() {
        val store = FakePreviewStore(
            pages = ArrayDeque(listOf(page(size = 10, next = null))),
            cancelAfterScan = true,
        )
        val service = service(store)
        val pending = store.seed(command, now.plusSeconds(300))
        val claimed = store.claim(pending.id!!, "worker-1", now, now.plusSeconds(30)).shouldNotBeNull()

        val result = service.processClaimedPage(claimed, "worker-1")

        result.disposition shouldBeEqualTo SchedulingPolicyPreviewDisposition.CANCELLED
        result.job.status shouldBeEqualTo PolicyPreviewJobStatus.CANCELLED
        result.job.scannedCount shouldBeEqualTo 0L
        result.job.resultHash.shouldBeNull()
        result.job.activationEvidenceToken.shouldBeNull()
        store.scanCalls shouldBeEqualTo 1
    }

    @Test
    fun `tenant concurrency two defers a third submit and worker claim until permits release`() {
        val store = ConcurrentPreviewStore(now)
        val service = SchedulingPolicyPreviewService(
            store = store,
            properties = SchedulingPolicyProperties(),
            monotonicNanos = { 0L },
            ownerFactory = { "preview-owner-${store.createdCount.get()}" },
            evidenceTokenFactory = { "preview-token-${store.createdCount.get()}" },
        )
        val executor = Executors.newFixedThreadPool(2)

        try {
            val first = executor.submit<SchedulingPolicyPreviewResult> {
                service.submit(command.copy(definitionId = 7L))
            }
            val second = executor.submit<SchedulingPolicyPreviewResult> {
                service.submit(command.copy(definitionId = 8L))
            }
            store.awaitTwoScans()

            val third = service.submit(command.copy(definitionId = 9L))

            third.disposition shouldBeEqualTo SchedulingPolicyPreviewDisposition.ACCEPTED_ASYNC
            third.job.status shouldBeEqualTo PolicyPreviewJobStatus.PENDING
            service.process(third.job.id!!, "worker-3", now).shouldBeNull()
            store.claimCalls.get() shouldBeEqualTo 2
            store.scanCalls.get() shouldBeEqualTo 2

            store.releaseScans()
            first.get(5, TimeUnit.SECONDS).disposition shouldBeEqualTo
                SchedulingPolicyPreviewDisposition.COMPLETED
            second.get(5, TimeUnit.SECONDS).disposition shouldBeEqualTo
                SchedulingPolicyPreviewDisposition.COMPLETED

            val resumed = service.process(third.job.id!!, "worker-3", now).shouldNotBeNull()
            resumed.disposition shouldBeEqualTo SchedulingPolicyPreviewDisposition.COMPLETED
            store.claimCalls.get() shouldBeEqualTo 3
            store.scanCalls.get() shouldBeEqualTo 3
        } finally {
            store.releaseScans()
            executor.shutdownNow()
        }
    }

    private fun service(store: FakePreviewStore) =
        SchedulingPolicyPreviewService(
            store = store,
            properties = SchedulingPolicyProperties(),
            monotonicNanos = { 0L },
            ownerFactory = { "preview-owner-1" },
            evidenceTokenFactory = { "preview-token-1" },
        )

    private fun page(
        size: Int,
        next: PolicyImpactCursor?,
        startId: Int = 1,
    ) = PolicyImpactPage(
        items = (startId until startId + size).map { id ->
            PolicyImpactKey(
                scheduledAt = now.plusSeconds(id.toLong()),
                aggregateType = PolicyImpactAggregateType.APPOINTMENT,
                aggregateId = id.toString(),
            )
        },
        nextCursor = next,
    )

    private fun cursor(id: Int) = PolicyImpactCursor(
        scheduledAt = now.plusSeconds(id.toLong()),
        aggregateType = PolicyImpactAggregateType.APPOINTMENT,
        aggregateId = id.toString(),
    )

    private class FakePreviewStore(
        private val pages: ArrayDeque<PolicyImpactPage>,
        private val pinnedStateCurrent: Boolean = true,
        private val queueSaturated: Boolean = false,
        private val databaseNow: Instant = Instant.parse("2026-07-27T00:00:00Z"),
        private val databaseNowSequence: ArrayDeque<Instant> = ArrayDeque(),
        private val cancelAfterScan: Boolean = false,
    ) : SchedulingPolicyPreviewStore {
        private var record: SchedulingPolicyPreviewJobRecord? = null
        var createdCount: Int = 0
        var scanCalls: Int = 0
        var maxRequestedPageSize: Int = 0
        var maxMaterializedPageSize: Int = 0
        var lastClaimNow: Instant? = null

        override fun tryCreate(
            command: CreateSchedulingPolicyPreviewCommand,
            capacity: Int,
            jobDeadline: Duration,
        ): SchedulingPolicyPreviewAdmission? =
            if (queueSaturated) {
                null
            } else {
                SchedulingPolicyPreviewAdmission(
                    acceptedAt = databaseNow,
                    job = seed(command, databaseNow.plus(jobDeadline)),
                )
            }

        override fun databaseNow(): Instant =
            databaseNowSequence.removeFirstOrNull() ?: databaseNow

        override fun find(jobId: Long): SchedulingPolicyPreviewJobRecord? = record

        fun seed(
            command: CreateSchedulingPolicyPreviewCommand,
            deadlineAt: Instant,
        ): SchedulingPolicyPreviewJobRecord {
            createdCount++
            return SchedulingPolicyPreviewJobRecord(
                id = 1L,
                tenantGroupId = command.scope.tenantGroupId,
                clinicId = command.scope.clinicId!!,
                definitionId = command.definitionId,
                draftRevision = command.draftRevision,
                tenantGeneration = command.generation.tenantGeneration,
                clinicGeneration = command.generation.clinicGeneration,
                partitionCount = 2,
                deadlineAt = deadlineAt,
                nextAttemptAt = databaseNow,
                horizonFrom = command.horizonFrom,
                horizonUntil = command.horizonUntil,
            ).also { record = it }
        }

        override fun claim(
            jobId: Long,
            owner: String,
            now: Instant,
            leaseUntil: Instant,
        ): SchedulingPolicyPreviewJobRecord? {
            lastClaimNow = now
            return record?.copy(
                status = PolicyPreviewJobStatus.RUNNING,
                leaseOwner = owner,
                leaseUntil = leaseUntil,
            )?.also { record = it }
        }

        override fun isPinnedStateCurrent(job: SchedulingPolicyPreviewJobRecord): Boolean =
            pinnedStateCurrent

        override fun scan(
            job: SchedulingPolicyPreviewJobRecord,
            after: PolicyImpactCursor?,
            limit: Int,
        ): PolicyImpactPage {
            scanCalls++
            maxRequestedPageSize = maxOf(maxRequestedPageSize, limit)
            return pages.removeFirst().also {
                maxMaterializedPageSize = maxOf(maxMaterializedPageSize, it.items.size)
                if (cancelAfterScan) {
                    record = record?.copy(
                        status = PolicyPreviewJobStatus.CANCELLED,
                        leaseOwner = null,
                        leaseUntil = null,
                        resultHash = null,
                        activationEvidenceToken = null,
                        lastErrorCode = "PREVIEW_CANCELLED",
                    )
                }
            }
        }

        override fun checkpoint(
            jobId: Long,
            owner: String,
            cursor: PolicyImpactCursor,
            progress: PolicyPreviewProgress,
            checkpointedAt: Instant,
        ): SchedulingPolicyPreviewJobRecord? =
            record?.copy(
                cursorPartition = cursor.aggregateType.ordinal,
                cursorLastAppointmentId = cursor.aggregateId.toLong(),
                cursorScheduledAt = cursor.scheduledAt,
                cursorAggregateType = cursor.aggregateType.name,
                cursorAggregateId = cursor.aggregateId,
                scannedCount = progress.scannedCount,
                affectedCount = progress.affectedCount,
            )?.also { record = it }

        override fun defer(
            jobId: Long,
            owner: String,
            cursor: PolicyImpactCursor,
            progress: PolicyPreviewProgress,
            nextAttemptAt: Instant,
            deferredAt: Instant,
        ): SchedulingPolicyPreviewJobRecord? =
            record?.copy(
                status = PolicyPreviewJobStatus.PENDING,
                cursorPartition = cursor.aggregateType.ordinal,
                cursorLastAppointmentId = cursor.aggregateId.toLong(),
                cursorScheduledAt = cursor.scheduledAt,
                cursorAggregateType = cursor.aggregateType.name,
                cursorAggregateId = cursor.aggregateId,
                scannedCount = progress.scannedCount,
                affectedCount = progress.affectedCount,
                nextAttemptAt = nextAttemptAt,
                leaseOwner = null,
                leaseUntil = null,
                resultHash = null,
                activationEvidenceToken = null,
            )?.also { record = it }

        override fun complete(
            jobId: Long,
            owner: String,
            progress: PolicyPreviewProgress,
            resultHash: String,
            activationEvidenceToken: String,
            completedAt: Instant,
        ): SchedulingPolicyPreviewJobRecord? =
            record?.copy(
                status = PolicyPreviewJobStatus.COMPLETED,
                scannedCount = progress.scannedCount,
                affectedCount = progress.affectedCount,
                leaseOwner = null,
                leaseUntil = null,
                resultHash = resultHash,
                activationEvidenceToken = activationEvidenceToken,
            )?.also { record = it }

        override fun terminate(
            jobId: Long,
            owner: String,
            status: PolicyPreviewJobStatus,
            errorCode: String,
            completedAt: Instant,
        ): SchedulingPolicyPreviewJobRecord? =
            record?.copy(
                status = status,
                leaseOwner = null,
                leaseUntil = null,
                resultHash = null,
                activationEvidenceToken = null,
                lastErrorCode = errorCode,
            )?.also { record = it }
    }

    /**
     * 실제 thread 경합에서 tenant permit을 관찰하기 위한 최소 영속 store다.
     *
     * 첫 두 scan을 latch로 고정해 세 번째 submit/worker가 durable row는 보존하되 claim하지
     * 않는지 확인한다. release 이후에는 같은 PENDING row가 정상적으로 다시 claim된다.
     */
    private class ConcurrentPreviewStore(
        private val now: Instant,
    ) : SchedulingPolicyPreviewStore {
        private val sequence = AtomicLong(0)
        private val records = ConcurrentHashMap<Long, SchedulingPolicyPreviewJobRecord>()
        private val scansEntered = CountDownLatch(2)
        private val scansReleased = CountDownLatch(1)
        val createdCount = AtomicInteger(0)
        val claimCalls = AtomicInteger(0)
        val scanCalls = AtomicInteger(0)

        override fun tryCreate(
            command: CreateSchedulingPolicyPreviewCommand,
            capacity: Int,
            jobDeadline: Duration,
        ): SchedulingPolicyPreviewAdmission {
            val acceptedAt = now
            val id = sequence.incrementAndGet()
            createdCount.incrementAndGet()
            val job = SchedulingPolicyPreviewJobRecord(
                id = id,
                tenantGroupId = command.scope.tenantGroupId,
                clinicId = command.scope.clinicId!!,
                definitionId = command.definitionId,
                draftRevision = command.draftRevision,
                tenantGeneration = command.generation.tenantGeneration,
                clinicGeneration = command.generation.clinicGeneration,
                partitionCount = 2,
                deadlineAt = acceptedAt.plus(jobDeadline),
                nextAttemptAt = acceptedAt,
                horizonFrom = command.horizonFrom,
                horizonUntil = command.horizonUntil,
            ).also { records[id] = it }
            return SchedulingPolicyPreviewAdmission(acceptedAt, job)
        }

        override fun databaseNow(): Instant = now

        override fun find(jobId: Long): SchedulingPolicyPreviewJobRecord? = records[jobId]

        override fun claim(
            jobId: Long,
            owner: String,
            now: Instant,
            leaseUntil: Instant,
        ): SchedulingPolicyPreviewJobRecord? {
            claimCalls.incrementAndGet()
            return records.computeIfPresent(jobId) { _, current ->
                if (current.status != PolicyPreviewJobStatus.PENDING) {
                    current
                } else {
                    current.copy(
                        status = PolicyPreviewJobStatus.RUNNING,
                        leaseOwner = owner,
                        leaseUntil = leaseUntil,
                    )
                }
            }?.takeIf { it.status == PolicyPreviewJobStatus.RUNNING && it.leaseOwner == owner }
        }

        override fun isPinnedStateCurrent(job: SchedulingPolicyPreviewJobRecord): Boolean = true

        override fun scan(
            job: SchedulingPolicyPreviewJobRecord,
            after: PolicyImpactCursor?,
            limit: Int,
        ): PolicyImpactPage {
            scanCalls.incrementAndGet()
            scansEntered.countDown()
            check(scansReleased.await(5, TimeUnit.SECONDS)) { "concurrent preview scan release timed out" }
            return PolicyImpactPage(emptyList(), null)
        }

        override fun checkpoint(
            jobId: Long,
            owner: String,
            cursor: PolicyImpactCursor,
            progress: PolicyPreviewProgress,
            checkpointedAt: Instant,
        ): SchedulingPolicyPreviewJobRecord? = error("checkpoint is not used by the one-page fixture")

        override fun defer(
            jobId: Long,
            owner: String,
            cursor: PolicyImpactCursor,
            progress: PolicyPreviewProgress,
            nextAttemptAt: Instant,
            deferredAt: Instant,
        ): SchedulingPolicyPreviewJobRecord? = error("defer is not used by the terminal fixture")

        override fun complete(
            jobId: Long,
            owner: String,
            progress: PolicyPreviewProgress,
            resultHash: String,
            activationEvidenceToken: String,
            completedAt: Instant,
        ): SchedulingPolicyPreviewJobRecord? =
            records.computeIfPresent(jobId) { _, current ->
                if (current.status == PolicyPreviewJobStatus.RUNNING && current.leaseOwner == owner) {
                    current.copy(
                        status = PolicyPreviewJobStatus.COMPLETED,
                        scannedCount = progress.scannedCount,
                        affectedCount = progress.affectedCount,
                        leaseOwner = null,
                        leaseUntil = null,
                        resultHash = resultHash,
                        activationEvidenceToken = activationEvidenceToken,
                    )
                } else {
                    current
                }
            }?.takeIf { it.status == PolicyPreviewJobStatus.COMPLETED }

        override fun terminate(
            jobId: Long,
            owner: String,
            status: PolicyPreviewJobStatus,
            errorCode: String,
            completedAt: Instant,
        ): SchedulingPolicyPreviewJobRecord? = error("terminate is not used by the terminal fixture")

        fun awaitTwoScans() {
            check(scansEntered.await(5, TimeUnit.SECONDS)) { "two concurrent preview scans did not start" }
        }

        fun releaseScans() {
            scansReleased.countDown()
        }
    }
}
