package io.bluetape4k.clinic.appointment.api.waitlist

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/** fencing token을 가진 lease만 vacancy dispatch에 전달하는 application port입니다. */
fun interface WaitlistFencedVacancyDispatcher {
    fun dispatch(limit: Int, now: Instant, lease: WaitlistLeaseHandle): Int
}

/** typed runner가 이번 tick에서 관측한 lease 상태입니다. */
enum class WaitlistFencedLeaseOutcome {
    ACQUIRED,
    REENTERED,
    CONTENDED,
    TIMEOUT,
    AMBIGUOUS,
    FAILED,
    CLOSED,
}

/** fenced scheduler의 bounded 실행 결과입니다. */
data class WaitlistFencedDeliveryTickResult(
    val mode: DeliveryMode,
    val leaseOutcome: WaitlistFencedLeaseOutcome,
    val dispatchCount: Int,
    val expiryCount: Int,
    val suppressionCount: Int,
    val holdReconcileCount: Int,
    val leaderAcquired: Boolean,
    val duration: Duration,
) {
    init {
        require(dispatchCount >= 0) { "dispatchCount must be non-negative" }
        require(expiryCount >= 0) { "expiryCount must be non-negative" }
        require(suppressionCount >= 0) { "suppressionCount must be non-negative" }
        require(holdReconcileCount >= 0) { "holdReconcileCount must be non-negative" }
        require(!duration.isNegative) { "duration must be non-negative" }
        require(leaderAcquired == (leaseOutcome == WaitlistFencedLeaseOutcome.ACQUIRED ||
            leaseOutcome == WaitlistFencedLeaseOutcome.REENTERED)) {
            "leaderAcquired must match a recovered lease handle"
        }
    }
}

/**
 * 기존 Boolean runner와 분리된 fenced scheduler입니다.
 *
 * Redis acquire가 명확한 handle을 반환한 경우에만 safety 작업과 typed dispatch를 시작합니다.
 * ambiguous 결과는 동일 owner/request reconcile을 한 번 수행하고, 회복되지 않으면 DB
 * mutation 없이 tick을 종료합니다.
 */
internal class WaitlistFencedDeliverySchedulingRunner(
    private val lease: FencedWaitlistLeaderLease,
    private val dispatcher: WaitlistFencedVacancyDispatcher,
    private val expiry: WaitlistOfferExpiryRunner,
    private val suppression: WaitlistNotificationSuppressionRunner,
    private val holdReconciler: WaitlistHoldReconciler,
    private val properties: WaitlistDeliveryProperties,
    private val metrics: WaitlistDeliveryMetrics,
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {

    private val closed = AtomicBoolean(false)
    private val tickInFlight = AtomicBoolean(false)

    fun tick(clinicId: Long = WaitlistDeliverySchedulingRunner.ALLOW_ALL_CLINICS): WaitlistFencedDeliveryTickResult {
        val started = clock.instant()
        val mode = modeFor(clinicId)
        if (closed.get()) {
            return terminalResult(mode, WaitlistFencedLeaseOutcome.CLOSED, elapsedSince(started))
        }
        if (!tickInFlight.compareAndSet(false, true)) {
            metrics.recordLeaseAcquire(WaitlistLeaseMetricOutcome.FAILED, elapsedSince(started))
            return terminalResult(mode, WaitlistFencedLeaseOutcome.FAILED, elapsedSince(started))
        }

        try {
            val attempt = acquireOrReconcile(started)
            val outcome = attempt.outcome()
            metrics.recordLeaseAcquire(outcome.metricOutcome, elapsedSince(started))
            val handle = attempt.handleOrNull()
                ?: return terminalResult(mode, outcome, elapsedSince(started))

            return try {
                val expiryCount = expiry.expire(properties.batchSize, started)
                val suppressionCount = suppression.suppress(properties.batchSize, started)
                val holdReconcileCount = holdReconciler.reconcile(properties.batchSize, started)
                val dispatchCount = if (mode == DeliveryMode.ACTIVE) {
                    dispatcher.dispatch(properties.batchSize, started, handle)
                } else {
                    0
                }
                requireNonNegative(expiryCount, "expiry")
                requireNonNegative(suppressionCount, "suppression")
                requireNonNegative(holdReconcileCount, "holdReconcile")
                requireNonNegative(dispatchCount, "dispatch")
                WaitlistFencedDeliveryTickResult(
                    mode = mode,
                    leaseOutcome = outcome,
                    dispatchCount = dispatchCount,
                    expiryCount = expiryCount,
                    suppressionCount = suppressionCount,
                    holdReconcileCount = holdReconcileCount,
                    leaderAcquired = true,
                    duration = elapsedSince(started),
                )
            } finally {
                when (lease.release(handle)) {
                    WaitlistLeaseRelease.OWNERSHIP_LOST ->
                        metrics.recordOwnershipLoss(WaitlistOwnershipLossSource.REDIS)
                    else -> Unit
                }
            }
        } finally {
            tickInFlight.set(false)
            metrics.recordSchedulerTick(mode, elapsedSince(started))
        }
    }

    @Synchronized
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            lease.close()
        }
    }

    private fun acquireOrReconcile(now: Instant): WaitlistLeaseAttempt {
        val acquired = runCatching { lease.tryAcquire(now) }
            .getOrElse { return WaitlistLeaseAttempt.Failed(WaitlistLeaseFailure.BACKEND_FAILURE) }
        return if (acquired is WaitlistLeaseAttempt.Ambiguous) {
            runCatching { lease.reconcile(now) }
                .getOrElse { WaitlistLeaseAttempt.Failed(WaitlistLeaseFailure.RECONCILE_FAILED) }
        } else {
            acquired
        }
    }

    private fun modeFor(clinicId: Long): DeliveryMode = if (clinicId == WaitlistDeliverySchedulingRunner.ALLOW_ALL_CLINICS) {
        when {
            !properties.enabled -> DeliveryMode.GLOBAL_OFF
            properties.clinicAllowlist.isNotEmpty() -> DeliveryMode.CLINIC_DISABLED
            else -> DeliveryMode.ACTIVE
        }
    } else {
        properties.modeFor(clinicId)
    }

    private fun terminalResult(
        mode: DeliveryMode,
        outcome: WaitlistFencedLeaseOutcome,
        duration: Duration,
    ): WaitlistFencedDeliveryTickResult = WaitlistFencedDeliveryTickResult(
        mode = mode,
        leaseOutcome = outcome,
        dispatchCount = 0,
        expiryCount = 0,
        suppressionCount = 0,
        holdReconcileCount = 0,
        leaderAcquired = false,
        duration = duration,
    )

    private fun elapsedSince(started: Instant): Duration =
        Duration.between(started, clock.instant()).coerceAtLeast(Duration.ZERO)

    private fun requireNonNegative(value: Int, name: String) {
        require(value >= 0) { "$name count must be non-negative" }
    }

    private fun WaitlistLeaseAttempt.handleOrNull(): WaitlistLeaseHandle? = when (this) {
        is WaitlistLeaseAttempt.Acquired -> handle
        is WaitlistLeaseAttempt.Reentered -> handle
        is WaitlistLeaseAttempt.Contended,
        is WaitlistLeaseAttempt.TimedOut,
        is WaitlistLeaseAttempt.Ambiguous,
        is WaitlistLeaseAttempt.Failed,
        -> null
    }

    private fun WaitlistLeaseAttempt.outcome(): WaitlistFencedLeaseOutcome = when (this) {
        is WaitlistLeaseAttempt.Acquired -> WaitlistFencedLeaseOutcome.ACQUIRED
        is WaitlistLeaseAttempt.Reentered -> WaitlistFencedLeaseOutcome.REENTERED
        is WaitlistLeaseAttempt.Contended -> WaitlistFencedLeaseOutcome.CONTENDED
        is WaitlistLeaseAttempt.TimedOut -> WaitlistFencedLeaseOutcome.TIMEOUT
        is WaitlistLeaseAttempt.Ambiguous -> WaitlistFencedLeaseOutcome.AMBIGUOUS
        is WaitlistLeaseAttempt.Failed -> when (category) {
            WaitlistLeaseFailure.CLOSED -> WaitlistFencedLeaseOutcome.CLOSED
            else -> WaitlistFencedLeaseOutcome.FAILED
        }
    }

    private val WaitlistFencedLeaseOutcome.metricOutcome: WaitlistLeaseMetricOutcome
        get() = when (this) {
            WaitlistFencedLeaseOutcome.ACQUIRED,
            WaitlistFencedLeaseOutcome.REENTERED,
            -> WaitlistLeaseMetricOutcome.ACQUIRED
            WaitlistFencedLeaseOutcome.CONTENDED -> WaitlistLeaseMetricOutcome.CONTENDED
            WaitlistFencedLeaseOutcome.TIMEOUT -> WaitlistLeaseMetricOutcome.TIMEOUT
            WaitlistFencedLeaseOutcome.AMBIGUOUS -> WaitlistLeaseMetricOutcome.AMBIGUOUS
            WaitlistFencedLeaseOutcome.FAILED,
            WaitlistFencedLeaseOutcome.CLOSED,
            -> WaitlistLeaseMetricOutcome.FAILED
        }
}
