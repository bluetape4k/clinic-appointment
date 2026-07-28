package io.bluetape4k.clinic.appointment.api.policy

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
import io.bluetape4k.clinic.appointment.repository.SchedulingPolicyImpactRepository
import io.bluetape4k.clinic.appointment.repository.SchedulingPolicyJobRepository
import io.bluetape4k.clinic.appointment.repository.SchedulingPolicyRepository
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * preview 생성 시 immutable하게 고정되는 caller 입력이다.
 *
 * @property scope Gateway 인증과 tenant/clinic 재검증이 끝난 tenant baseline 또는 clinic
 * override scope다. tenant baseline은 tenant 안의 병원을 ID 순으로 bounded scan한다.
 * @property definitionId 영향을 평가할 양수 draft definition ID다.
 * @property draftRevision 요청자가 본 정확한 양수 draft revision이다.
 * @property generation scan 시작 시점의 tenant/clinic effective generation이다.
 * @property horizonFrom 영향 후보를 포함하기 시작하는 UTC instant다.
 * @property horizonUntil 영향 후보를 제외하기 시작하는 UTC instant다.
 * @property requestedAt 서버가 감사 목적으로 기록한 UTC 요청 시각이다. deadline, due, lease를
 * 계산하는 권위 시각은 아니며 queue admission 트랜잭션이 읽은 DB 시각을 사용한다.
 */
data class CreateSchedulingPolicyPreviewCommand(
    val scope: PolicyScopeRef,
    val definitionId: Long,
    val draftRevision: Long,
    val generation: PolicyGenerationVector,
    val horizonFrom: Instant,
    val horizonUntil: Instant,
    val requestedAt: Instant,
) {
    init {
        if (scope.scope == PolicyScope.TENANT_DEFAULT) {
            require(generation.clinicGeneration == 0L) {
                "TENANT_DEFAULT preview requires clinicGeneration zero"
            }
        }
        require(definitionId > 0) { "definitionId must be positive" }
        require(draftRevision > 0) { "draftRevision must be positive" }
        require(generation.tenantGeneration >= 0) { "tenantGeneration must be non-negative" }
        require(generation.clinicGeneration >= 0) { "clinicGeneration must be non-negative" }
        require(horizonUntil > horizonFrom) { "horizonUntil must be later than horizonFrom" }
    }
}

/** preview 요청 직후 caller가 관찰하는 실행 결과 종류다. */
enum class SchedulingPolicyPreviewDisposition {
    /** 동기 시간·행 예산 안에서 완전한 증적까지 생성됐다. */
    COMPLETED,

    /** durable checkpoint 이후 worker가 이어서 실행해야 한다. */
    ACCEPTED_ASYNC,

    /** 실행 중 draft revision 또는 generation이 바뀌어 partial 결과를 폐기했다. */
    STALE,

    /** 명시적 취소로 partial 결과와 증적을 폐기했다. */
    CANCELLED,

    /** 안정적 오류 코드와 함께 종결됐다. */
    FAILED,
}

/**
 * preview submit/process 결과다.
 *
 * @property disposition 동기 완료, 비동기 수락 또는 terminal 사유다.
 * @property job 해당 전이가 커밋된 뒤 다시 읽은 durable job이다.
 */
data class SchedulingPolicyPreviewResult(
    val disposition: SchedulingPolicyPreviewDisposition,
    val job: SchedulingPolicyPreviewJobRecord,
)

/**
 * 정책 scope별 queue admission 트랜잭션이 확정한 생성 결과다.
 *
 * @property acceptedAt DB가 판정한 요청 수락 시각이다. deadline, 최초 due 시각, 동기 claim
 * 시각은 모두 이 값에서 계산하여 API 서버와 DB 노드의 시계 편차가 실행 규칙에 섞이지 않게 한다.
 * @property job 같은 트랜잭션에서 queue capacity를 확인한 뒤 생성한 durable PENDING 작업이다.
 */
data class SchedulingPolicyPreviewAdmission(
    val acceptedAt: Instant,
    val job: SchedulingPolicyPreviewJobRecord,
)

/**
 * scheduled worker가 preview job 하나의 bounded page 처리를 요청하는 좁은 실행 계약이다.
 *
 * `null`은 다른 worker가 먼저 claim했음을 뜻한다. 성공 반환은 한 page 처리 후 terminal
 * 또는 lease가 해제된 `PENDING` row여야 한다.
 */
fun interface ScheduledPolicyPreviewPageProcessor {
    fun process(
        jobId: Long,
        owner: String,
        databaseNow: Instant,
    ): SchedulingPolicyPreviewResult?
}

/**
 * preview application service가 요구하는 짧은 영속 작업 계약이다.
 *
 * 구현체는 각 메서드마다 필요한 Exposed `transaction {}`을 소유해야 한다. [scan]은
 * [PolicyImpactPage] 하나만 반환하며 전체 후보를 누적해서는 안 된다. 조건부 전이가 lease
 * 또는 상태 경쟁에서 지면 nullable 반환값으로 알려 오래된 in-memory runnable이 더 이상
 * row를 변경하지 못하게 한다.
 */
interface SchedulingPolicyPreviewStore {
    /**
     * 정책 scope별 runnable queue capacity 확인과 작업 생성을 하나의 직렬화 트랜잭션으로 수행한다.
     *
     * @return capacity 여유가 있어 생성된 admission. 이미 포화되었으면 `null`이며 행을 만들지 않는다.
     */
    fun tryCreate(
        command: CreateSchedulingPolicyPreviewCommand,
        capacity: Int,
        jobDeadline: Duration,
    ): SchedulingPolicyPreviewAdmission?

    /** lease, due, deadline 전이에 사용할 database current UTC instant를 읽는다. */
    fun databaseNow(): Instant

    /** page boundary에서 cancellation 또는 lease 상실을 확인할 현재 durable row를 읽는다. */
    fun find(jobId: Long): SchedulingPolicyPreviewJobRecord?

    /** 조건부 lease claim에 성공한 경우에만 RUNNING row를 반환한다. */
    fun claim(
        jobId: Long,
        owner: String,
        now: Instant,
        leaseUntil: Instant,
    ): SchedulingPolicyPreviewJobRecord?

    /** exact draft revision과 generation이 아직 현재인지 권위 저장소에서 확인한다. */
    fun isPinnedStateCurrent(job: SchedulingPolicyPreviewJobRecord): Boolean

    /** caller 소유 트랜잭션 안에서 bounded impact page 하나를 읽는다. */
    fun scan(
        job: SchedulingPolicyPreviewJobRecord,
        after: PolicyImpactCursor?,
        limit: Int,
    ): PolicyImpactPage

    /** RUNNING lease를 유지하면서 복합 cursor와 단조 progress를 저장한다. */
    fun checkpoint(
        jobId: Long,
        owner: String,
        cursor: PolicyImpactCursor,
        progress: PolicyPreviewProgress,
        checkpointedAt: Instant,
    ): SchedulingPolicyPreviewJobRecord?

    /** 복합 cursor를 저장하고 lease를 해제해 PENDING 비동기 작업으로 돌려놓는다. */
    fun defer(
        jobId: Long,
        owner: String,
        cursor: PolicyImpactCursor,
        progress: PolicyPreviewProgress,
        nextAttemptAt: Instant,
        deferredAt: Instant,
    ): SchedulingPolicyPreviewJobRecord?

    /** 전체 scan 결과와 opaque activation 증적을 원자적으로 확정한다. */
    fun complete(
        jobId: Long,
        owner: String,
        progress: PolicyPreviewProgress,
        resultHash: String,
        activationEvidenceToken: String,
        completedAt: Instant,
    ): SchedulingPolicyPreviewJobRecord?

    /** partial evidence 없이 STALE/FAILED/CANCELLED terminal row를 기록한다. */
    fun terminate(
        jobId: Long,
        owner: String,
        status: PolicyPreviewJobStatus,
        errorCode: String,
        completedAt: Instant,
    ): SchedulingPolicyPreviewJobRecord?
}

/**
 * bounded impact preview의 동기 fast path와 durable async 전환을 조정한다.
 *
 * 모든 요청은 먼저 job row를 만든다. 같은 tenant에서 [SchedulingPolicyProperties.previewTenantConcurrency]
 * 개까지만 동기 실행하며 permit을 얻지 못한 요청은 durable PENDING으로 반환한다. scan은
 * page 사이에서 pinned revision/generation, 단조 deadline, row 상한을 다시 확인한다.
 * 완료되지 않은 어떤 경로도 activation token을 만들지 않는다.
 *
 * @property store 짧은 트랜잭션과 owner-fenced 상태 전이를 소유하는 영속 어댑터다.
 * @property properties feature 순서와 page/queue/concurrency/deadline 상한이다.
 * @property monotonicNanos wall clock 보정의 영향을 받지 않는 프로세스 경과 시간 source다.
 * @property ownerFactory 로그에 개인정보를 넣지 않는 bounded opaque worker owner 생성기다.
 * @property evidenceTokenFactory 완료 job에만 저장할 opaque token 생성기다.
 * @property impactEvaluator candidate key가 실제 영향 후보인지 판정하는 순수 함수다.
 */
class SchedulingPolicyPreviewService(
    private val store: SchedulingPolicyPreviewStore,
    private val properties: SchedulingPolicyProperties,
    private val monotonicNanos: () -> Long = System::nanoTime,
    private val ownerFactory: () -> String = { "preview-${UUID.randomUUID()}" },
    private val evidenceTokenFactory: () -> String = { UUID.randomUUID().toString() },
    private val impactEvaluator: (PolicyImpactKey) -> Boolean = { true },
) : ScheduledPolicyPreviewPageProcessor {
    private val tenantPermits = ConcurrentHashMap<Long, Semaphore>()

    /**
     * 새 preview를 수락하고 가능하면 동기 예산 안에서 완료한다.
     *
     * queue가 이미 포화된 경우에만 retryable `POLICY_PREVIEW_LIMITED`를 던진다. tenant permit을
     * 얻지 못했지만 queue 여유가 있으면 job은 유실하지 않고 `ACCEPTED_ASYNC`로 반환한다.
     */
    fun submit(command: CreateSchedulingPolicyPreviewCommand): SchedulingPolicyPreviewResult {
        val admission = store.tryCreate(
            command = command,
            capacity = properties.previewQueueCapacity,
            jobDeadline = properties.previewJobDeadline,
        ) ?: throw SchedulingPolicyApiException(
                SchedulingPolicyErrorCode.POLICY_PREVIEW_LIMITED,
                "The policy preview queue reached its configured capacity.",
        )
        val job = admission.job
        val permit = tenantPermit(command.scope.tenantGroupId)
        if (!permit.tryAcquire()) {
            return SchedulingPolicyPreviewResult(
                SchedulingPolicyPreviewDisposition.ACCEPTED_ASYNC,
                job,
            )
        }

        val owner = ownerFactory()
        return try {
            val claimed = store.claim(
                jobId = requireNotNull(job.id),
                owner = owner,
                now = admission.acceptedAt,
                leaseUntil = admission.acceptedAt.plus(properties.workerLease),
            ) ?: return SchedulingPolicyPreviewResult(
                SchedulingPolicyPreviewDisposition.ACCEPTED_ASYNC,
                job,
            )
            processClaimed(
                job = claimed,
                owner = owner,
                startedNanos = monotonicNanos(),
                enforceSynchronousBudget = true,
            )
        } finally {
            permit.release()
        }
    }

    /**
     * 이미 claim된 job을 page boundary 단위로 실행한다.
     *
     * worker도 같은 메서드를 사용하되 [enforceSynchronousBudget]을 `false`로 전달한다.
     * durable hard deadline과 pinned state 검사는 두 경로 모두 동일하다.
     */
    internal fun processClaimed(
        job: SchedulingPolicyPreviewJobRecord,
        owner: String,
        startedNanos: Long = monotonicNanos(),
        enforceSynchronousBudget: Boolean = false,
    ): SchedulingPolicyPreviewResult =
        processClaimed(
            job = job,
            owner = owner,
            startedNanos = startedNanos,
            enforceSynchronousBudget = enforceSynchronousBudget,
            maxPages = Int.MAX_VALUE,
        )

    /**
     * worker tick에서 정확히 한 bounded page만 처리하고 lease를 해제한다.
     *
     * page가 마지막이면 완료할 수 있지만, 다음 cursor가 있으면 checkpoint를 메모리에
     * 유지하지 않고 즉시 `PENDING`으로 defer한다. 따라서 한 job이 worker thread와 DB
     * connection을 독점하지 못하며 다음 tick에서 공정하게 다시 선점된다.
     */
    internal fun processClaimedPage(
        job: SchedulingPolicyPreviewJobRecord,
        owner: String,
    ): SchedulingPolicyPreviewResult =
        processClaimed(
            job = job,
            owner = owner,
            startedNanos = monotonicNanos(),
            enforceSynchronousBudget = false,
            maxPages = 1,
        )

    /**
     * worker가 선택한 due ID를 짧게 claim하고 정확히 한 page만 처리한다.
     *
     * due ID 선택과 claim 사이의 경쟁은 정상이며, claim을 잃으면 `null`을 반환한다.
     */
    override fun process(
        jobId: Long,
        owner: String,
        databaseNow: Instant,
    ): SchedulingPolicyPreviewResult? {
        val current = store.find(jobId) ?: return null
        val permit = tenantPermit(current.tenantGroupId)
        if (!permit.tryAcquire()) {
            return null
        }
        return try {
            val claimed = store.claim(
                jobId = jobId,
                owner = owner,
                now = databaseNow,
                leaseUntil = databaseNow.plus(properties.workerLease),
            ) ?: return null
            processClaimedPage(claimed, owner)
        } finally {
            permit.release()
        }
    }

    private fun processClaimed(
        job: SchedulingPolicyPreviewJobRecord,
        owner: String,
        startedNanos: Long,
        enforceSynchronousBudget: Boolean,
        maxPages: Int,
    ): SchedulingPolicyPreviewResult {
        require(job.status == PolicyPreviewJobStatus.RUNNING && job.leaseOwner == owner) {
            "processClaimed requires the current RUNNING lease owner"
        }
        require(maxPages > 0) { "maxPages must be positive" }
        val jobId = requireNotNull(job.id)
        var cursor = job.toImpactCursor()
        var progress = PolicyPreviewProgress(job.scannedCount, job.affectedCount)
        var processedPages = 0

        while (true) {
            findTerminalResult(jobId, owner)?.let { return it }
            val pageStartedAt = store.databaseNow()
            if (pageStartedAt >= job.deadlineAt) {
                val failed = requireNotNull(
                    store.terminate(
                        jobId,
                        owner,
                        PolicyPreviewJobStatus.FAILED,
                        PREVIEW_DEADLINE_EXCEEDED,
                        pageStartedAt,
                    )
                ) { "preview lease was lost while recording hard deadline" }
                return SchedulingPolicyPreviewResult(SchedulingPolicyPreviewDisposition.FAILED, failed)
            }
            if (!store.isPinnedStateCurrent(job)) {
                val stale = requireNotNull(
                    store.terminate(
                        jobId,
                        owner,
                        PolicyPreviewJobStatus.STALE,
                        SchedulingPolicyErrorCode.POLICY_PREVIEW_STALE.name,
                        pageStartedAt,
                    )
                ) { "preview lease was lost while recording stale state" }
                return SchedulingPolicyPreviewResult(SchedulingPolicyPreviewDisposition.STALE, stale)
            }

            val page = store.scan(job, cursor, properties.previewPageSize)
            require(page.items.size <= properties.previewPageSize) {
                "impact repository returned an oversized page"
            }
            findTerminalResult(jobId, owner)?.let { return it }
            val pageCompletedAt = store.databaseNow()
            if (pageCompletedAt >= job.deadlineAt) {
                val failed = requireNotNull(
                    store.terminate(
                        jobId,
                        owner,
                        PolicyPreviewJobStatus.FAILED,
                        PREVIEW_DEADLINE_EXCEEDED,
                        pageCompletedAt,
                    )
                ) { "preview lease was lost while recording hard deadline" }
                return SchedulingPolicyPreviewResult(SchedulingPolicyPreviewDisposition.FAILED, failed)
            }
            val affectedInPage = page.items.count(impactEvaluator)
            progress = PolicyPreviewProgress(
                scannedCount = progress.scannedCount + page.items.size,
                affectedCount = progress.affectedCount + affectedInPage,
            )
            processedPages++

            val next = page.nextCursor
            if (next == null) {
                if (!store.isPinnedStateCurrent(job)) {
                    val stale = requireNotNull(
                        store.terminate(
                            jobId,
                            owner,
                            PolicyPreviewJobStatus.STALE,
                            SchedulingPolicyErrorCode.POLICY_PREVIEW_STALE.name,
                            pageCompletedAt,
                        )
                    ) { "preview lease was lost while recording final stale state" }
                    return SchedulingPolicyPreviewResult(SchedulingPolicyPreviewDisposition.STALE, stale)
                }
                val completed = requireNotNull(
                    store.complete(
                        jobId = jobId,
                        owner = owner,
                        progress = progress,
                        resultHash = resultHash(job, progress),
                        activationEvidenceToken = evidenceTokenFactory(),
                        completedAt = pageCompletedAt,
                    )
                ) { "preview lease was lost before completion" }
                return SchedulingPolicyPreviewResult(SchedulingPolicyPreviewDisposition.COMPLETED, completed)
            }

            val elapsed = monotonicNanos() - startedNanos
            val processingBudgetExhausted =
                processedPages >= maxPages ||
                    (
                        enforceSynchronousBudget &&
                    (
                        progress.scannedCount >= properties.previewSyncRowLimit ||
                            elapsed >= properties.previewSyncDeadline.toNanos()
                        )
                        )
            if (processingBudgetExhausted) {
                val deferred = requireNotNull(
                    store.defer(
                        jobId = jobId,
                        owner = owner,
                        cursor = next,
                        progress = progress,
                        nextAttemptAt = pageCompletedAt,
                        deferredAt = pageCompletedAt,
                    )
                ) { "preview lease was lost while deferring async work" }
                return SchedulingPolicyPreviewResult(
                    SchedulingPolicyPreviewDisposition.ACCEPTED_ASYNC,
                    deferred,
                )
            }

            store.checkpoint(jobId, owner, next, progress, pageCompletedAt)
                ?: error("preview lease was lost while checkpointing")
            cursor = next
        }
    }

    /**
     * page 경계에서 외부 cancellation과 owner fencing 결과를 durable row로 재확인한다.
     */
    private fun findTerminalResult(
        jobId: Long,
        owner: String,
    ): SchedulingPolicyPreviewResult? {
        val current = store.find(jobId) ?: error("preview job disappeared during processing")
        if (current.status == PolicyPreviewJobStatus.RUNNING && current.leaseOwner == owner) {
            return null
        }
        val disposition = when (current.status) {
            PolicyPreviewJobStatus.COMPLETED -> SchedulingPolicyPreviewDisposition.COMPLETED
            PolicyPreviewJobStatus.STALE -> SchedulingPolicyPreviewDisposition.STALE
            PolicyPreviewJobStatus.FAILED -> SchedulingPolicyPreviewDisposition.FAILED
            PolicyPreviewJobStatus.CANCELLED -> SchedulingPolicyPreviewDisposition.CANCELLED
            PolicyPreviewJobStatus.PENDING,
            PolicyPreviewJobStatus.RUNNING,
            -> error("preview lease ownership changed before a terminal transition")
        }
        return SchedulingPolicyPreviewResult(disposition, current)
    }

    private fun SchedulingPolicyPreviewJobRecord.toImpactCursor(): PolicyImpactCursor? {
        val scheduledAt = cursorScheduledAt ?: return null
        val aggregateType = requireNotNull(cursorAggregateType)
        val aggregateId = requireNotNull(cursorAggregateId)
        return PolicyImpactCursor(
            clinicId = requireNotNull(cursorClinicId),
            scheduledAt = scheduledAt,
            aggregateType = PolicyImpactAggregateType.valueOf(aggregateType),
            aggregateId = aggregateId,
        )
    }

    /**
     * 동기 submit과 scheduled worker가 공유하는 프로세스 로컬 tenant permit을 반환한다.
     *
     * durable lease가 job 중복 실행을 막고, 이 permit은 한 프로세스가 동일 tenant의 무거운
     * impact scan을 설정 개수보다 많이 동시에 수행하지 못하게 한다.
     */
    private fun tenantPermit(tenantGroupId: Long): Semaphore =
        tenantPermits.computeIfAbsent(tenantGroupId) {
            Semaphore(properties.previewTenantConcurrency, true)
        }

    private fun resultHash(
        job: SchedulingPolicyPreviewJobRecord,
        progress: PolicyPreviewProgress,
    ): String {
        val canonical = listOf(
            job.tenantGroupId,
            job.scope,
            job.clinicId,
            job.clinicScopeKey,
            job.definitionId,
            job.draftRevision,
            job.tenantGeneration,
            job.clinicGeneration,
            job.clinicGenerationDigest,
            job.horizonFrom,
            job.horizonUntil,
            progress.scannedCount,
            progress.affectedCount,
        ).joinToString(separator = "|")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it) }
    }
}

/**
 * preview service의 각 영속 primitive에 짧은 Exposed 트랜잭션을 제공하는 adapter다.
 *
 * scan page, claim, checkpoint, terminal 전이는 서로 다른 트랜잭션이다. 따라서 5,000행
 * 조회나 정책 평가가 scope-head/lease 행 잠금을 오래 보유하지 않는다. revision과 generation
 * 검사는 매 page 전에 새 트랜잭션으로 수행되어 worker 재시작과 동시 정책 편집을 stale로
 * 종결한다.
 *
 * @property jobRepository owner-fenced durable job primitive다.
 * @property impactRepository bounded 복합 keyset 조회 primitive다.
 * @property policyRepository definition과 scope-head의 권위 상태를 읽는 primitive다.
 */
class ExposedSchedulingPolicyPreviewStore(
    private val jobRepository: SchedulingPolicyJobRepository,
    private val impactRepository: SchedulingPolicyImpactRepository,
    private val policyRepository: SchedulingPolicyRepository,
) : SchedulingPolicyPreviewStore {

    override fun tryCreate(
        command: CreateSchedulingPolicyPreviewCommand,
        capacity: Int,
        jobDeadline: Duration,
    ): SchedulingPolicyPreviewAdmission? =
        transaction {
            policyRepository.lockScopeHead(command.scope)
            if (jobRepository.isPreviewQueueSaturated(command.scope, capacity)) {
                return@transaction null
            }
            val acceptedAt = currentDatabaseInstant()
            val clinicGenerationDigest = if (command.scope.scope == PolicyScope.TENANT_DEFAULT) {
                policyRepository.clinicGenerationDigest(command.scope.tenantGroupId)
            } else {
                null
            }
            val job = jobRepository.createPreviewJob(
                SchedulingPolicyPreviewJobRecord(
                    tenantGroupId = command.scope.tenantGroupId,
                    scope = command.scope.scope,
                    clinicId = command.scope.clinicId,
                    clinicScopeKey = command.scope.clinicScopeKey,
                    definitionId = command.definitionId,
                    draftRevision = command.draftRevision,
                    tenantGeneration = command.generation.tenantGeneration,
                    clinicGeneration = command.generation.clinicGeneration,
                    clinicGenerationDigest = clinicGenerationDigest,
                    partitionCount = PolicyImpactAggregateType.entries.size,
                    deadlineAt = acceptedAt.plus(jobDeadline),
                    nextAttemptAt = acceptedAt,
                    horizonFrom = command.horizonFrom,
                    horizonUntil = command.horizonUntil,
                )
            )
            SchedulingPolicyPreviewAdmission(acceptedAt, job)
        }

    override fun databaseNow(): Instant =
        transaction { currentDatabaseInstant() }

    override fun find(jobId: Long): SchedulingPolicyPreviewJobRecord? =
        transaction { jobRepository.findPreviewJob(jobId) }

    override fun claim(
        jobId: Long,
        owner: String,
        now: Instant,
        leaseUntil: Instant,
    ): SchedulingPolicyPreviewJobRecord? =
        transaction {
            if (jobRepository.claimDuePreview(jobId, owner, now, leaseUntil)) {
                jobRepository.findPreviewJob(jobId)
            } else {
                null
            }
        }

    override fun isPinnedStateCurrent(job: SchedulingPolicyPreviewJobRecord): Boolean =
        transaction {
            val definition = policyRepository.findDefinition(job.definitionId) ?: return@transaction false
            if (definition.tenantGroupId != job.tenantGroupId ||
                definition.scope != job.scope ||
                definition.clinicId != job.clinicId ||
                definition.revision != job.draftRevision
            ) {
                return@transaction false
            }
            val tenantHead = policyRepository.findScopeHead(
                PolicyScopeRef(job.tenantGroupId, PolicyScope.TENANT_DEFAULT)
            ) ?: return@transaction false
            if (tenantHead.generation != job.tenantGeneration) {
                return@transaction false
            }
            when (job.scope) {
                PolicyScope.TENANT_DEFAULT ->
                    job.clinicGeneration == 0L &&
                        job.clinicGenerationDigest ==
                        policyRepository.clinicGenerationDigest(job.tenantGroupId)
                PolicyScope.CLINIC_OVERRIDE -> {
                    val clinicHead = policyRepository.findScopeHead(
                        PolicyScopeRef(job.tenantGroupId, PolicyScope.CLINIC_OVERRIDE, job.clinicId)
                    )
                    (clinicHead?.generation ?: 0L) == job.clinicGeneration
                }
            }
        }

    override fun scan(
        job: SchedulingPolicyPreviewJobRecord,
        after: PolicyImpactCursor?,
        limit: Int,
    ): PolicyImpactPage =
        transaction {
            impactRepository.scanFutureWork(
                scope = PolicyScopeRef(
                    job.tenantGroupId,
                    job.scope,
                    job.clinicId,
                ),
                horizonFrom = job.horizonFrom,
                horizonUntil = job.horizonUntil,
                after = after,
                limit = limit,
            )
        }

    override fun checkpoint(
        jobId: Long,
        owner: String,
        cursor: PolicyImpactCursor,
        progress: PolicyPreviewProgress,
        checkpointedAt: Instant,
    ): SchedulingPolicyPreviewJobRecord? =
        transaction {
            if (jobRepository.checkpointImpactPreview(jobId, owner, cursor, progress, checkpointedAt)) {
                jobRepository.findPreviewJob(jobId)
            } else {
                null
            }
        }

    override fun defer(
        jobId: Long,
        owner: String,
        cursor: PolicyImpactCursor,
        progress: PolicyPreviewProgress,
        nextAttemptAt: Instant,
        deferredAt: Instant,
    ): SchedulingPolicyPreviewJobRecord? =
        transaction {
            if (jobRepository.deferPreview(jobId, owner, cursor, progress, nextAttemptAt, deferredAt)) {
                jobRepository.findPreviewJob(jobId)
            } else {
                null
            }
        }

    override fun complete(
        jobId: Long,
        owner: String,
        progress: PolicyPreviewProgress,
        resultHash: String,
        activationEvidenceToken: String,
        completedAt: Instant,
    ): SchedulingPolicyPreviewJobRecord? =
        transaction {
            if (
                jobRepository.completePreview(
                    jobId = jobId,
                    owner = owner,
                    resultHash = resultHash,
                    activationEvidenceToken = activationEvidenceToken,
                    progress = progress,
                    completedAt = completedAt,
                )
            ) {
                jobRepository.findPreviewJob(jobId)
            } else {
                null
            }
        }

    override fun terminate(
        jobId: Long,
        owner: String,
        status: PolicyPreviewJobStatus,
        errorCode: String,
        completedAt: Instant,
    ): SchedulingPolicyPreviewJobRecord? =
        transaction {
            if (jobRepository.markPreviewTerminal(jobId, owner, status, errorCode, completedAt)) {
                jobRepository.findPreviewJob(jobId)
            } else {
                null
            }
        }
}

/**
 * activation 명령이 제출한 opaque token을 로컬 durable preview evidence와 대조한다.
 *
 * 네트워크 호출 없이 exact unique-index 조회만 수행한다. `COMPLETED` 상태와 definition,
 * revision, tenant/clinic generation이 모두 일치해야 true다. tenant preview는 추가로
 * 모든 clinic override 세대의 정규 digest를 현재 권위 저장소와 비교한다. token 자체는
 * 로그나 예외 메시지에 포함하지 않는다.
 */
class PersistedPolicyPreviewEvidenceVerifier(
    private val jobRepository: SchedulingPolicyJobRepository,
    private val policyRepository: SchedulingPolicyRepository,
) : PolicyPreviewEvidenceVerifier {
    override fun verify(
        evidence: PolicyPreviewEvidence,
        definition: io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyDefinitionRecord,
        generation: PolicyGenerationVector,
    ): Boolean =
        try {
            transaction {
                val job = jobRepository.findCompletedPreviewByToken(evidence.evidenceId)
                    ?: return@transaction false
                job.definitionId == definition.id &&
                    job.definitionId == evidence.definitionId &&
                    job.tenantGroupId == definition.tenantGroupId &&
                    job.scope == definition.scope &&
                    job.clinicId == definition.clinicId &&
                    job.draftRevision == definition.revision &&
                    job.draftRevision == evidence.draftRevision &&
                    job.tenantGeneration == generation.tenantGeneration &&
                    job.tenantGeneration == evidence.tenantGeneration &&
                    job.clinicGeneration == generation.clinicGeneration &&
                    job.clinicGeneration == evidence.clinicGeneration &&
                    when (job.scope) {
                        PolicyScope.TENANT_DEFAULT ->
                            job.clinicGenerationDigest ==
                                policyRepository.clinicGenerationDigest(job.tenantGroupId)
                        PolicyScope.CLINIC_OVERRIDE -> job.clinicGenerationDigest == null
                    }
            }
        } catch (_: IllegalArgumentException) {
            false
        }
}

private fun JdbcTransaction.currentDatabaseInstant(): Instant =
    exec("SELECT CURRENT_TIMESTAMP") { result ->
        check(result.next()) { "database current timestamp query returned no row" }
        result.getTimestamp(1).toInstant()
    } ?: error("database current timestamp query returned no result")

private const val PREVIEW_DEADLINE_EXCEEDED = "PREVIEW_DEADLINE_EXCEEDED"
