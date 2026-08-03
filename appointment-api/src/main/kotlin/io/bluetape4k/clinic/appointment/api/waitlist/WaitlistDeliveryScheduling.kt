package io.bluetape4k.clinic.appointment.api.waitlist

import io.bluetape4k.clinic.appointment.model.waitlist.CorrelationId
import java.time.Clock
import java.time.Instant

/** Redis leader lease를 DB fencing과 분리해 주입하기 위한 최소 port입니다. */
fun interface WaitlistLeaderLease {
    fun tryAcquire(owner: String, leaseUntil: Instant): Boolean

    fun release(owner: String) = Unit
}

/** vacancy dispatch를 담당하는 application 경계입니다. */
fun interface WaitlistVacancyDispatcher {
    fun dispatch(limit: Int, now: Instant): Int
}

/** 만료 offer/hold를 bounded batch로 terminal 처리하는 경계입니다. */
fun interface WaitlistOfferExpiryRunner {
    fun expire(limit: Int, now: Instant): Int
}

/** terminal offer에 매달린 알림을 suppression하는 경계입니다. */
fun interface WaitlistNotificationSuppressionRunner {
    fun suppress(limit: Int, now: Instant): Int
}

/** active flag와 무관하게 hold를 DB authority로 회수하는 경계입니다. */
fun interface WaitlistHoldReconciler {
    fun reconcile(limit: Int, now: Instant): Int
}

/** 한 scheduler tick의 관측 가능한 결과입니다. */
data class WaitlistDeliveryTickResult(
    val mode: DeliveryMode,
    val dispatchCount: Int,
    val expiryCount: Int,
    val suppressionCount: Int,
    val holdReconcileCount: Int,
    val leaderAcquired: Boolean,
) {
    init {
        require(dispatchCount >= 0) { "dispatchCount must be non-negative" }
        require(expiryCount >= 0) { "expiryCount must be non-negative" }
        require(suppressionCount >= 0) { "suppressionCount must be non-negative" }
        require(holdReconcileCount >= 0) { "holdReconcileCount must be non-negative" }
    }
}

/**
 * Waitlist 운영 작업의 순서를 보장하는 bounded scheduler입니다.
 *
 * expiry/suppression/reconcile은 global-off와 clinic-disabled에서도 먼저 실행합니다. leader
 * lease를 잃은 경우에는 DB mutation을 시작하지 않으며, lease가 만료된 뒤 다른 인스턴스가
 * 같은 batch를 재개합니다. 실제 row-level fencing은 각 core repository가 소유합니다.
 */
class WaitlistDeliverySchedulingRunner(
    private val properties: WaitlistDeliveryProperties,
    private val leaderLease: WaitlistLeaderLease,
    private val vacancyDispatcher: WaitlistVacancyDispatcher,
    private val offerExpiryRunner: WaitlistOfferExpiryRunner,
    private val notificationSuppressionRunner: WaitlistNotificationSuppressionRunner,
    private val holdReconciler: WaitlistHoldReconciler,
    private val leaseOwner: String = DEFAULT_OWNER,
    private val clock: Clock = Clock.systemUTC(),
    private val metrics: WaitlistDeliveryMetrics? = null,
) {
    init {
        require(leaseOwner.isNotBlank() && leaseOwner.length <= 160) {
            "leaseOwner must contain 1..160 characters"
        }
    }

    fun tick(clinicId: Long = ALLOW_ALL_CLINICS): WaitlistDeliveryTickResult {
        val now = clock.instant()
        val mode = if (clinicId == ALLOW_ALL_CLINICS) {
            if (properties.enabled) DeliveryMode.ACTIVE else DeliveryMode.GLOBAL_OFF
        } else {
            properties.modeFor(clinicId)
        }
        val acquired = leaderLease.tryAcquire(leaseOwner, now.plus(properties.jobLease))
        if (!acquired) {
            metrics?.recordLeaseLost()
            return WaitlistDeliveryTickResult(
                mode = mode,
                dispatchCount = 0,
                expiryCount = 0,
                suppressionCount = 0,
                holdReconcileCount = 0,
                leaderAcquired = false,
            )
        }

        return try {
            // Safety work is deliberately outside the dispatch flag.
            val expiry = offerExpiryRunner.expire(properties.batchSize, now)
            val suppression = notificationSuppressionRunner.suppress(properties.batchSize, now)
            val reconciled = holdReconciler.reconcile(properties.batchSize, now)
            val dispatched = if (mode == DeliveryMode.ACTIVE) {
                vacancyDispatcher.dispatch(properties.batchSize, now)
            } else {
                0
            }
            requireNonNegative(expiry, "expiry")
            requireNonNegative(suppression, "suppression")
            requireNonNegative(reconciled, "holdReconcile")
            requireNonNegative(dispatched, "dispatch")
            metrics?.recordTick(mode, dispatched, expiry, suppression, reconciled)
            WaitlistDeliveryTickResult(mode, dispatched, expiry, suppression, reconciled, true)
        } finally {
            leaderLease.release(leaseOwner)
        }
    }

    private fun requireNonNegative(value: Int, name: String) {
        require(value >= 0) { "$name count must be non-negative" }
    }

    companion object {
        const val DEFAULT_OWNER = "waitlist-delivery-scheduler"
        /** clinic-independent scheduled tick sentinel; it never reaches a repository predicate. */
        const val ALLOW_ALL_CLINICS = 0L
    }
}

/** Correlation id factory for scheduled recovery commands. */
fun waitlistSchedulerCorrelation(now: Instant): CorrelationId =
    CorrelationId("waitlist-scheduler:${now.epochSecond}")
