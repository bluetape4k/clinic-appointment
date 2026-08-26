package io.bluetape4k.clinic.appointment.api.waitlist

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Duration
import java.util.EnumMap
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
    private val tickCounters = EnumMap<DeliveryMode, Counter>(DeliveryMode::class.java)
    private val dispatchedCounters = EnumMap<DeliveryMode, Counter>(DeliveryMode::class.java)
    private val expiredCounters = EnumMap<DeliveryMode, Counter>(DeliveryMode::class.java)
    private val suppressedCounters = EnumMap<DeliveryMode, Counter>(DeliveryMode::class.java)
    private val holdReconciledCounters = EnumMap<DeliveryMode, Counter>(DeliveryMode::class.java)
    private val providerAttemptCounters = EnumMap<WaitlistProviderOutcome, Counter>(WaitlistProviderOutcome::class.java)
    private val providerLatencyTimers = EnumMap<WaitlistProviderOutcome, Timer>(WaitlistProviderOutcome::class.java)
    private val leaseAcquireCounters = EnumMap<WaitlistLeaseMetricOutcome, Counter>(WaitlistLeaseMetricOutcome::class.java)
    private val leaseAcquireTimers = EnumMap<WaitlistLeaseMetricOutcome, Timer>(WaitlistLeaseMetricOutcome::class.java)
    private val schedulerTickTimers = EnumMap<DeliveryMode, Timer>(DeliveryMode::class.java)
    private val ownershipLossCounters = EnumMap<WaitlistOwnershipLossSource, Counter>(WaitlistOwnershipLossSource::class.java)
    private val leaseReclaimsCounter: Counter = counter(LEASE_RECLAIMS, "reason", "leader_lost")
    private val lockWaitTimer: Timer = timer(LOCK_WAIT)

    init {
        Gauge.builder(ACTIVE_OFFERS, activeOffers) { it.get().toDouble() }.register(registry)
        Gauge.builder(ACTIVE_HOLDS, activeHolds) { it.get().toDouble() }.register(registry)
        Gauge.builder(EXPIRED_BACKLOG, expiredBacklog) { it.get().toDouble() }.register(registry)
        Gauge.builder(OLDEST_VACANCY_SECONDS, oldestVacancySeconds) { it.get().toDouble() }
            .register(registry)
        DeliveryMode.entries.forEach { mode ->
            tickCounters[mode] = counter(TICKS, "mode", mode.metricValue)
            dispatchedCounters[mode] = counter(DISPATCHED, "mode", mode.metricValue)
            expiredCounters[mode] = counter(EXPIRED, "mode", mode.metricValue)
            suppressedCounters[mode] = counter(SUPPRESSED, "mode", mode.metricValue)
            holdReconciledCounters[mode] = counter(HOLD_RECONCILED, "mode", mode.metricValue)
            schedulerTickTimers[mode] = timer(SCHEDULER_TICK, "mode", mode.metricValue)
        }
        WaitlistProviderOutcome.entries.forEach { outcome ->
            providerAttemptCounters[outcome] = counter(PROVIDER_ATTEMPTS, "outcome", outcome.metricValue)
            providerLatencyTimers[outcome] = timer(PROVIDER_LATENCY, "outcome", outcome.metricValue)
        }
        WaitlistLeaseMetricOutcome.entries.forEach { outcome ->
            leaseAcquireCounters[outcome] = counter(LEASE_ACQUIRE_TOTAL, "outcome", outcome.metricValue)
            leaseAcquireTimers[outcome] = timer(LEASE_ACQUIRE_SECONDS, "outcome", outcome.metricValue)
        }
        WaitlistOwnershipLossSource.entries.forEach { source ->
            ownershipLossCounters[source] = counter(OWNERSHIP_LOSS_TOTAL, "source", source.metricValue)
        }
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
        tickCounters.getValue(mode).increment()
        dispatchedCounters.getValue(mode).increment(dispatchCount.toDouble())
        expiredCounters.getValue(mode).increment(expiryCount.toDouble())
        suppressedCounters.getValue(mode).increment(suppressionCount.toDouble())
        holdReconciledCounters.getValue(mode).increment(holdReconcileCount.toDouble())
    }

    fun recordProviderAttempt(outcome: WaitlistProviderOutcome, duration: Duration = Duration.ZERO) {
        providerAttemptCounters.getValue(outcome).increment()
        if (!duration.isNegative && !duration.isZero) {
            providerLatencyTimers.getValue(outcome).record(duration)
        }
    }

    fun recordLeaseLost() = leaseReclaimsCounter.increment()

    fun recordLeaseAcquire(outcome: WaitlistLeaseMetricOutcome, duration: Duration) {
        require(!duration.isNegative) { "duration must be non-negative" }
        leaseAcquireCounters.getValue(outcome).increment()
        leaseAcquireTimers.getValue(outcome).record(duration)
    }

    fun recordSchedulerTick(mode: DeliveryMode, duration: Duration) {
        require(!duration.isNegative) { "duration must be non-negative" }
        schedulerTickTimers.getValue(mode).record(duration)
    }

    fun recordOwnershipLoss(source: WaitlistOwnershipLossSource) =
        ownershipLossCounters.getValue(source).increment()

    fun recordLockWait(duration: Duration) {
        require(!duration.isNegative) { "duration must be non-negative" }
        lockWaitTimer.record(duration)
    }

    private fun counter(name: String, key: String, value: String): Counter =
        Counter.builder(name).tag(key, value).register(registry)

    private fun timer(name: String, key: String? = null, value: String? = null): Timer =
        Timer.builder(name).apply {
            if (key != null && value != null) tag(key, value)
        }.publishPercentileHistogram().register(registry)

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
