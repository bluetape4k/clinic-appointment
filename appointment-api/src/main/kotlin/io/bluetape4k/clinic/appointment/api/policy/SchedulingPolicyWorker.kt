package io.bluetape4k.clinic.appointment.api.policy

import io.bluetape4k.clinic.appointment.api.config.SchedulingPolicyApiException
import io.bluetape4k.clinic.appointment.api.config.SchedulingPolicyProperties
import io.bluetape4k.clinic.appointment.api.security.ActorContext
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyActivationCommandRecord
import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import io.bluetape4k.clinic.appointment.model.policy.SchedulingPolicyKind
import io.bluetape4k.clinic.appointment.repository.SchedulingPolicyJobRepository
import io.bluetape4k.clinic.appointment.repository.SchedulingPolicyRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import jakarta.annotation.PreDestroy
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.math.roundToLong

/**
 * worker가 선점한 activation command와 metric 분류에 필요한 불변 policy kind를 묶는다.
 *
 * @property command 현재 owner의 `CLAIMED` lease를 가진 durable command.
 * @property kind command가 선택한 immutable definition의 닫힌 정책 종류.
 */
data class SchedulingPolicyActivationWork(
    val command: SchedulingPolicyActivationCommandRecord,
    val kind: SchedulingPolicyKind,
)

/**
 * owner-fenced activation 본체의 worker-facing 결과다.
 *
 * @property idempotentReplay 이미 완료된 동일 command 결과를 재사용했으면 `true`.
 */
data class ScheduledPolicyActivationExecutionOutcome(
    val idempotentReplay: Boolean,
)

/**
 * 이미 짧은 transaction에서 claim된 scheduled command를 실행하는 좁은 계약이다.
 *
 * 구현체는 같은 [owner]를 command completion까지 사용해야 하며, 실패 시 claim을 지우지
 * 않아야 한다. 그래야 호출 worker가 별도 transaction에서 retry 또는 missed를 기록한다.
 */
fun interface ScheduledPolicyActivationExecutor {
    fun execute(
        commandId: Long,
        owner: String,
        actor: ActorContext,
        databaseNow: Instant,
    ): ScheduledPolicyActivationExecutionOutcome
}

/**
 * scheduled worker가 요구하는 DB-time, due selection, lease, retry/missed primitive다.
 *
 * 모든 구현 메서드는 짧고 독립적인 transaction을 소유해야 한다. due selection은 ID만
 * 제한 개수로 읽고, claim은 한 command씩 처리하여 한 실패가 batch lock을 유지하지 않게 한다.
 */
interface SchedulingPolicyWorkerStore {
    /** due/lease/deadline 전이에 사용할 database current UTC instant를 읽는다. */
    fun databaseNow(): Instant

    /** 시간순 due activation ID를 [limit] 이하로 읽는다. */
    fun findDueActivationIds(
        databaseNow: Instant,
        limit: Int,
    ): List<Long>

    /** 조건부 claim 성공 시 command와 policy kind를 반환한다. */
    fun claimActivation(
        commandId: Long,
        owner: String,
        databaseNow: Instant,
        leaseUntil: Instant,
    ): SchedulingPolicyActivationWork?

    /** 현재 owner의 일시 실패를 lease 해제된 retry 대기로 전이한다. */
    fun markActivationRetry(
        commandId: Long,
        owner: String,
        errorCode: String,
        nextAttemptAt: Instant,
        retryAt: Instant,
    ): Boolean

    /** 현재 owner의 command를 불변 `MISSED` 상태로 종결한다. */
    fun markActivationMissed(
        commandId: Long,
        owner: String,
        errorCode: String,
        missedAt: Instant,
    ): Boolean

    /** 시간순 due preview ID를 [limit] 이하로 읽는다. */
    fun findDuePreviewIds(
        databaseNow: Instant,
        limit: Int,
    ): List<Long>

    /** preview job이 고정한 definition의 immutable kind를 읽는다. */
    fun findPreviewKind(jobId: Long): SchedulingPolicyKind?

    /** 실패 종결 metric과 로그에 사용할 preview의 immutable policy scope를 읽는다. */
    fun findPreviewScope(jobId: Long): PolicyScope?

    /** 현재 owner가 가진 RUNNING preview를 안정적 오류 코드로 실패 종결한다. */
    fun markPreviewFailed(
        jobId: Long,
        owner: String,
        errorCode: String,
        failedAt: Instant,
    ): Boolean
}

/**
 * DB current time과 owner-fenced repository primitive를 짧은 Exposed transaction으로 감싼다.
 */
class ExposedSchedulingPolicyWorkerStore(
    private val jobRepository: SchedulingPolicyJobRepository,
    private val policyRepository: SchedulingPolicyRepository,
) : SchedulingPolicyWorkerStore {

    override fun databaseNow(): Instant =
        transaction {
            exec("SELECT CURRENT_TIMESTAMP") { result ->
                check(result.next()) { "database current timestamp query returned no row" }
                result.getTimestamp(1).toInstant()
            } ?: error("database current timestamp query returned no result")
        }

    override fun findDueActivationIds(
        databaseNow: Instant,
        limit: Int,
    ): List<Long> =
        transaction { jobRepository.findDueActivationCommandIds(databaseNow, limit) }

    override fun claimActivation(
        commandId: Long,
        owner: String,
        databaseNow: Instant,
        leaseUntil: Instant,
    ): SchedulingPolicyActivationWork? =
        transaction {
            if (!jobRepository.claimDueActivation(commandId, owner, databaseNow, leaseUntil)) {
                return@transaction null
            }
            val command = requireNotNull(jobRepository.findActivation(commandId)) {
                "claimed scheduling policy activation command disappeared"
            }
            val definition = requireNotNull(policyRepository.findDefinition(command.definitionId)) {
                "claimed scheduling policy definition disappeared"
            }
            SchedulingPolicyActivationWork(command, definition.kind)
        }

    override fun markActivationRetry(
        commandId: Long,
        owner: String,
        errorCode: String,
        nextAttemptAt: Instant,
        retryAt: Instant,
    ): Boolean =
        transaction {
            jobRepository.markActivationRetry(commandId, owner, errorCode, nextAttemptAt, retryAt)
        }

    override fun markActivationMissed(
        commandId: Long,
        owner: String,
        errorCode: String,
        missedAt: Instant,
    ): Boolean =
        transaction { jobRepository.markActivationMissed(commandId, owner, errorCode, missedAt) }

    override fun findDuePreviewIds(
        databaseNow: Instant,
        limit: Int,
    ): List<Long> =
        transaction { jobRepository.findDuePreviewJobIds(databaseNow, limit) }

    override fun findPreviewKind(jobId: Long): SchedulingPolicyKind? =
        transaction {
            val definitionId = jobRepository.findPreviewJob(jobId)?.definitionId ?: return@transaction null
            policyRepository.findDefinition(definitionId)?.kind
        }

    override fun findPreviewScope(jobId: Long): PolicyScope? =
        transaction { jobRepository.findPreviewJob(jobId)?.scope }

    override fun markPreviewFailed(
        jobId: Long,
        owner: String,
        errorCode: String,
        failedAt: Instant,
    ): Boolean =
        transaction {
            jobRepository.markPreviewTerminal(
                jobId = jobId,
                owner = owner,
                status = io.bluetape4k.clinic.appointment.model.dto.PolicyPreviewJobStatus.FAILED,
                errorCode = errorCode,
                completedAt = failedAt,
            )
        }
}

/**
 * 예약 정책 activation과 preview를 서로 독립된 bounded batch로 처리하는 crash-safe worker다.
 *
 * activation과 preview ID 조회는 같은 catch-up 호출에서도 각각 독립적으로 실행된다. activation
 * burst가 preview를 굶기지 않으며, 각 ID는 별도 claim/실행 transaction을 사용한다. scheduled
 * activation은 database time으로 due·lease·lateness·missed를 판단하고, preview는 한 claim당
 * 정확히 한 bounded page만 처리한다.
 *
 * 종료 시 [shutdown]은 새 claim을 즉시 중단하고 설정된 grace 동안 현재 호출만 기다린다.
 * 시간이 지나도 terminal row를 덮어쓰지 않으며 미완료 lease는 만료 후 다른 프로세스가
 * 회수한다.
 */
class SchedulingPolicyWorker(
    private val store: SchedulingPolicyWorkerStore,
    private val activationExecutor: ScheduledPolicyActivationExecutor,
    private val previewProcessor: ScheduledPolicyPreviewPageProcessor,
    private val properties: SchedulingPolicyProperties,
    private val metrics: SchedulingPolicyMetrics,
    private val systemActor: ActorContext,
    private val ownerFactory: () -> String = { "policy-worker-${UUID.randomUUID()}" },
    private val jitterUnit: (Long, Int) -> Double = ::deterministicJitterUnit,
    private val monotonicNanos: () -> Long = System::nanoTime,
) {
    private val acceptingWork = AtomicBoolean(true)
    private val activeWork = AtomicInteger(0)

    /** startup catch-up와 주기 polling이 공유하는 activation 다음 preview 순서의 bounded tick이다. */
    fun runCatchUp() {
        if (!acceptingWork.get()) return
        runActivationTick()
        runPreviewTick()
    }

    /** 설정이 활성화된 경우 due activation을 최대 configured claim 수만큼 처리한다. */
    fun runActivationTick() {
        if (!acceptingWork.get() || !properties.scheduledActivationEnabled) return
        val selectionTime = store.databaseNow()
        val commandIds = store.findDueActivationIds(
            selectionTime,
            properties.maxActivationClaimsPerTick,
        )
        commandIds.forEach { commandId ->
            if (!acceptingWork.get()) return
            withActiveWork { processActivation(commandId) }
        }
    }

    /** 설정이 활성화된 경우 due preview를 최대 configured job 수만큼 한 page씩 처리한다. */
    fun runPreviewTick() {
        if (!acceptingWork.get() || !properties.previewWorkerEnabled) return
        val selectionTime = store.databaseNow()
        val jobIds = store.findDuePreviewIds(selectionTime, properties.maxPreviewJobsPerTick)
        jobIds.forEach { jobId ->
            if (!acceptingWork.get()) return
            withActiveWork { processPreview(jobId) }
        }
    }

    /**
     * 새 claim을 중지하고 진행 중인 동기 호출을 bounded grace 동안 기다린다.
     *
     * @return grace 안에 모든 호출이 끝났으면 `true`; lease 회수가 필요한 호출이 남으면 `false`.
     */
    @PreDestroy
    fun shutdown(): Boolean {
        acceptingWork.set(false)
        val deadline = monotonicNanos() + properties.workerShutdownGrace.toNanos()
        while (activeWork.get() > 0 && monotonicNanos() < deadline) {
            val remaining = deadline - monotonicNanos()
            LockSupport.parkNanos(minOf(remaining, SHUTDOWN_POLL_NANOS))
        }
        val drained = activeWork.get() == 0
        if (!drained) {
            log.warn {
                "Scheduling policy worker shutdown grace expired: result=lease_recovery_required"
            }
        }
        return drained
    }

    private fun processActivation(commandId: Long) {
        val claimAt = store.databaseNow()
        val owner = ownerFactory()
        val work = store.claimActivation(
            commandId = commandId,
            owner = owner,
            databaseNow = claimAt,
            leaseUntil = claimAt.plus(properties.workerLease),
        ) ?: return
        val command = work.command
        val executionAt = store.databaseNow()
        val lateness =
            if (executionAt >= command.effectiveFrom) {
                Duration.between(command.effectiveFrom, executionAt)
            } else {
                Duration.ZERO
            }
        if (lateness >= properties.activationLatenessWarning) {
            metrics.recordActivationLateness(lateness, work.kind, command.scope)
            log.warn {
                "Scheduling policy activation is late: " +
                    "result=late, kind=${work.kind}, scope_type=${command.scope}, " +
                    "lateness_seconds=${lateness.seconds}"
            }
        }
        if (lateness >= properties.activationMissedAfter) {
            if (store.markActivationMissed(
                    commandId,
                    owner,
                    MISSED_DEADLINE,
                    executionAt,
                )
            ) {
                metrics.recordActivation(PolicyActivationMetricResult.MISSED, work.kind, command.scope)
                log.warn {
                    "Scheduling policy activation missed its deadline: " +
                        "result=missed, kind=${work.kind}, scope_type=${command.scope}"
                }
            }
            return
        }

        try {
            val result = activationExecutor.execute(commandId, owner, systemActor, executionAt)
            metrics.recordActivation(
                if (result.idempotentReplay) {
                    PolicyActivationMetricResult.IDEMPOTENT_REPLAY
                } else {
                    PolicyActivationMetricResult.COMPLETED
                },
                work.kind,
                command.scope,
            )
        } catch (ex: SchedulingPolicyApiException) {
            val failedAt = store.databaseNow()
            if (store.markActivationMissed(commandId, owner, ex.errorCode.name, failedAt)) {
                metrics.recordActivation(PolicyActivationMetricResult.MISSED, work.kind, command.scope)
                log.warn {
                    "Scheduling policy activation failed closed: " +
                        "result=missed, kind=${work.kind}, scope_type=${command.scope}"
                }
            }
        } catch (_: Exception) {
            val failedAt = store.databaseNow()
            if (command.attempt >= properties.activationMaxAttempts) {
                if (store.markActivationMissed(
                        commandId,
                        owner,
                        RETRY_EXHAUSTED,
                        failedAt,
                    )
                ) {
                    metrics.recordActivation(PolicyActivationMetricResult.MISSED, work.kind, command.scope)
                    log.warn {
                        "Scheduling policy activation exhausted retries: " +
                            "result=missed, kind=${work.kind}, scope_type=${command.scope}"
                    }
                }
            } else {
                val nextAttemptAt = failedAt.plus(retryDelay(commandId, command.attempt))
                if (store.markActivationRetry(
                        commandId,
                        owner,
                        TRANSIENT_ACTIVATION_FAILURE,
                        nextAttemptAt,
                        failedAt,
                    )
                ) {
                    metrics.recordActivation(PolicyActivationMetricResult.RETRY, work.kind, command.scope)
                    log.warn {
                        "Scheduling policy activation scheduled a retry: " +
                            "result=retry, kind=${work.kind}, scope_type=${command.scope}"
                    }
                }
            }
        }
    }

    private fun processPreview(jobId: Long) {
        val owner = ownerFactory()
        val kind = store.findPreviewKind(jobId)
        val scope = store.findPreviewScope(jobId)
        val result =
            try {
                previewProcessor.process(jobId, owner, store.databaseNow())
            } catch (_: Exception) {
                val failedAt = store.databaseNow()
                if (store.markPreviewFailed(jobId, owner, PREVIEW_PROCESSING_FAILED, failedAt)) {
                    if (kind != null && scope != null) {
                        metrics.recordPreview(
                            PolicyPreviewMetricResult.FAILED,
                            kind,
                            scope,
                        )
                    }
                    log.warn {
                        "Scheduling policy preview failed closed: " +
                            "result=failed, kind=${kind ?: "unknown"}, scope_type=${scope ?: "unknown"}"
                    }
                }
                return
            } ?: return
        val observedKind = kind ?: return
        val metricResult = when (result.disposition) {
            SchedulingPolicyPreviewDisposition.COMPLETED -> PolicyPreviewMetricResult.COMPLETED_ASYNC
            SchedulingPolicyPreviewDisposition.ACCEPTED_ASYNC -> return
            SchedulingPolicyPreviewDisposition.STALE -> PolicyPreviewMetricResult.STALE
            SchedulingPolicyPreviewDisposition.CANCELLED -> PolicyPreviewMetricResult.CANCELLED
            SchedulingPolicyPreviewDisposition.FAILED ->
                if (result.job.lastErrorCode == PREVIEW_DEADLINE_CODE) {
                    PolicyPreviewMetricResult.DEADLINE
                } else {
                    PolicyPreviewMetricResult.FAILED
                }
        }
        metrics.recordPreview(metricResult, observedKind, result.job.scope)
    }

    private fun retryDelay(
        commandId: Long,
        attempt: Int,
    ): Duration {
        var base = properties.activationInitialBackoff
        repeat((attempt - 1).coerceAtLeast(0)) {
            base = minOf(base.multipliedBy(2), properties.activationMaxBackoff)
        }
        val unit = jitterUnit(commandId, attempt)
        require(unit in -1.0..1.0) { "jitterUnit must return a value in -1.0..1.0" }
        val factor = 1.0 + unit * properties.activationJitter
        return Duration.ofMillis((base.toMillis() * factor).roundToLong().coerceAtLeast(1L))
    }

    private inline fun withActiveWork(block: () -> Unit) {
        activeWork.incrementAndGet()
        if (!acceptingWork.get()) {
            activeWork.decrementAndGet()
            return
        }
        try {
            block()
        } finally {
            activeWork.decrementAndGet()
        }
    }

    companion object : KLogging() {
        private const val MISSED_DEADLINE = "ACTIVATION_MISSED_DEADLINE"
        private const val RETRY_EXHAUSTED = "ACTIVATION_RETRY_EXHAUSTED"
        private const val TRANSIENT_ACTIVATION_FAILURE = "TRANSIENT_ACTIVATION_FAILURE"
        private const val PREVIEW_DEADLINE_CODE = "PREVIEW_DEADLINE_EXCEEDED"
        private const val PREVIEW_PROCESSING_FAILED = "PREVIEW_PROCESSING_FAILED"
        private const val SHUTDOWN_POLL_NANOS = 10_000_000L

        private fun deterministicJitterUnit(
            commandId: Long,
            attempt: Int,
        ): Double {
            var mixed = commandId xor (attempt.toLong() shl 32)
            mixed = (mixed xor (mixed ushr 33)) * -49064778989728563L
            mixed = (mixed xor (mixed ushr 33)) * -4265267296055464877L
            mixed = mixed xor (mixed ushr 33)
            val normalized = (mixed ushr 11).toDouble() / (1L shl 53).toDouble()
            return normalized * 2.0 - 1.0
        }
    }
}

/**
 * Spring scheduling을 worker 본체와 분리해 단위 테스트가 scheduler thread 없이 실행되게 한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty("scheduling.policy.idempotency-hash-secret")
class SchedulingPolicyWorkerSchedulingConfiguration(
    private val worker: SchedulingPolicyWorker,
) {
    /** 애플리케이션 준비 직후 누적된 durable 작업을 한 bounded tick만큼 회수한다. */
    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        worker.runCatchUp()
    }

    /** 고정된 짧은 polling 간격마다 activation과 preview를 각각 bounded 처리한다. */
    @Scheduled(fixedDelayString = "\${scheduling.policy.worker-poll-interval:PT1S}")
    fun poll() {
        worker.runCatchUp()
    }
}
