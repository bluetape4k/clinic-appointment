package io.bluetape4k.clinic.appointment.api.waitlist

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * Waitlist 운영 metric facade입니다. tenant/member/entry/offer 같은 식별자는 tag로 받지
 * 않으며, 모든 tag는 닫힌 enum/상태 문자열만 허용합니다.
 */
class WaitlistDeliveryMetrics(
    private val registry: MeterRegistry,
) {
    private val activeOffers = AtomicLong()
    private val activeHolds = AtomicLong()
    private val expiredBacklog = AtomicLong()
    private val oldestVacancySeconds = AtomicLong()

    init {
        Gauge.builder(ACTIVE_OFFERS, activeOffers) { it.get().toDouble() }.register(registry)
        Gauge.builder(ACTIVE_HOLDS, activeHolds) { it.get().toDouble() }.register(registry)
        Gauge.builder(EXPIRED_BACKLOG, expiredBacklog) { it.get().toDouble() }.register(registry)
        Gauge.builder(OLDEST_VACANCY_SECONDS, oldestVacancySeconds) { it.get().toDouble() }
            .register(registry)
    }

    fun setBacklog(activeOffers: Long, activeHolds: Long, expiredBacklog: Long, oldestVacancy: Duration) {
        require(activeOffers >= 0L && activeHolds >= 0L && expiredBacklog >= 0L) {
            "waitlist counts must be non-negative"
        }
        require(!oldestVacancy.isNegative) { "oldestVacancy must be non-negative" }
        this.activeOffers.set(activeOffers)
        this.activeHolds.set(activeHolds)
        this.expiredBacklog.set(expiredBacklog)
        this.oldestVacancySeconds.set(oldestVacancy.seconds)
    }

    fun recordTick(
        mode: DeliveryMode,
        dispatchCount: Int,
        expiryCount: Int,
        suppressionCount: Int,
        holdReconcileCount: Int,
    ) {
        counter(TICKS, "mode", mode.metricValue).increment()
        counter(DISPATCHED, "mode", mode.metricValue).increment(dispatchCount.toDouble())
        counter(EXPIRED, "mode", mode.metricValue).increment(expiryCount.toDouble())
        counter(SUPPRESSED, "mode", mode.metricValue).increment(suppressionCount.toDouble())
        counter(HOLD_RECONCILED, "mode", mode.metricValue).increment(holdReconcileCount.toDouble())
    }

    fun recordProviderAttempt(outcome: WaitlistProviderOutcome, duration: Duration = Duration.ZERO) {
        counter(PROVIDER_ATTEMPTS, "outcome", outcome.metricValue).increment()
        if (!duration.isNegative && !duration.isZero) {
            Timer.builder(PROVIDER_LATENCY)
                .tag("outcome", outcome.metricValue)
                .publishPercentileHistogram()
                .register(registry)
                .record(duration)
        }
    }

    fun recordLeaseLost() = counter(LEASE_RECLAIMS, "reason", "leader_lost").increment()

    fun recordLeaseAcquire(outcome: WaitlistLeaseMetricOutcome, duration: Duration) {
        require(!duration.isNegative) { "duration must be non-negative" }
        counter(LEASE_ACQUIRE_TOTAL, "outcome", outcome.metricValue).increment()
        Timer.builder(LEASE_ACQUIRE_SECONDS)
            .tag("outcome", outcome.metricValue)
            .publishPercentileHistogram()
            .register(registry)
            .record(duration)
    }

    fun recordSchedulerTick(mode: DeliveryMode, duration: Duration) {
        require(!duration.isNegative) { "duration must be non-negative" }
        Timer.builder(SCHEDULER_TICK)
            .tag("mode", mode.metricValue)
            .publishPercentileHistogram()
            .register(registry)
            .record(duration)
    }

    fun recordOwnershipLoss(source: WaitlistOwnershipLossSource) =
        counter(OWNERSHIP_LOSS_TOTAL, "source", source.metricValue).increment()

    fun recordLockWait(duration: Duration) {
        require(!duration.isNegative) { "duration must be non-negative" }
        Timer.builder(LOCK_WAIT).publishPercentileHistogram().register(registry).record(duration)
    }

    private fun counter(name: String, key: String, value: String): Counter =
        Counter.builder(name).tag(key, value).register(registry)

    companion object {
        const val ACTIVE_OFFERS = "appointment_waitlist_active_offers"
        const val ACTIVE_HOLDS = "appointment_waitlist_active_holds"
        const val EXPIRED_BACKLOG = "appointment_waitlist_expired_backlog"
        const val OLDEST_VACANCY_SECONDS = "appointment_waitlist_oldest_vacancy_seconds"
        const val TICKS = "appointment_waitlist_scheduler_ticks_total"
        const val DISPATCHED = "appointment_waitlist_vacancy_dispatch_total"
        const val EXPIRED = "appointment_waitlist_offer_expiry_total"
        const val SUPPRESSED = "appointment_waitlist_notification_suppression_total"
        const val HOLD_RECONCILED = "appointment_waitlist_hold_reconcile_total"
        const val PROVIDER_ATTEMPTS = "appointment_waitlist_provider_attempts_total"
        const val PROVIDER_LATENCY = "appointment_waitlist_provider_latency"
        const val LEASE_RECLAIMS = "appointment_waitlist_lease_reclaims_total"
        const val LOCK_WAIT = "appointment_waitlist_lock_wait_seconds"
        const val LEASE_ACQUIRE_TOTAL = "appointment_waitlist_lease_acquire_total"
        const val LEASE_ACQUIRE_SECONDS = "appointment_waitlist_lease_acquire_seconds"
        const val SCHEDULER_TICK = "appointment_waitlist_scheduler_tick_seconds"
        const val OWNERSHIP_LOSS_TOTAL = "appointment_waitlist_ownership_loss_total"
    }
}

enum class WaitlistProviderOutcome(val metricValue: String) {
    SENT("sent"),
    RETRYABLE("retryable"),
    SUPPRESSED("suppressed"),
    UNKNOWN("unknown"),
    LEASE_LOST("lease_lost"),
}

enum class WaitlistLeaseMetricOutcome(val metricValue: String) {
    ACQUIRED("acquired"),
    CONTENDED("contended"),
    TIMEOUT("timeout"),
    AMBIGUOUS("ambiguous"),
    FAILED("failed"),
}

enum class WaitlistOwnershipLossSource(val metricValue: String) {
    REDIS("redis"),
    DB("db"),
}

private val DeliveryMode.metricValue: String
    get() = name.lowercase()
