package io.bluetape4k.clinic.appointment.api.waitlist

import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import java.time.Duration

/** health source는 식별자를 반환하지 않고 저카디널리티 운영 snapshot만 제공합니다. */
fun interface WaitlistDeliveryHealthSource {
    fun snapshot(): WaitlistDeliveryOperationalSnapshot
}

data class WaitlistDeliveryOperationalSnapshot(
    val adapterReady: Boolean,
    val schemaReady: Boolean,
    val activePolicyPresent: Boolean,
    val oldestVacancyAge: Duration = Duration.ZERO,
    val expiredBacklog: Long = 0L,
    val failedJobs: Long = 0L,
    val unknownDeliveries: Long = 0L,
    val providerFailureRatio: Double = 0.0,
) {
    init {
        require(!oldestVacancyAge.isNegative) { "oldestVacancyAge must be non-negative" }
        require(expiredBacklog >= 0L && failedJobs >= 0L && unknownDeliveries >= 0L) {
            "waitlist health counts must be non-negative"
        }
        require(providerFailureRatio in 0.0..1.0) { "providerFailureRatio must be between 0 and 1" }
    }
}

/**
 * Issue #170 readiness threshold을 고정합니다.
 *
 * UP: adapter/schema/policy가 준비되고 oldest vacancy < 2분, failed job=0.
 * DEGRADED: 2–5분 backlog, provider failure ratio >=5%, unknown delivery.
 * OUT_OF_SERVICE: 필수 dependency/policy 부재, >5분 backlog, failed job 또는 expired backlog>100.
 */
class WaitlistDeliveryHealthIndicator(
    private val source: WaitlistDeliveryHealthSource,
    private val warningAge: Duration = Duration.ofMinutes(2),
    private val outOfServiceAge: Duration = Duration.ofMinutes(5),
    private val providerFailureThreshold: Double = 0.05,
    private val expiredBacklogOutOfService: Long = 100L,
) : HealthIndicator {
    init {
        require(warningAge.isPositive) { "warningAge must be positive" }
        require(outOfServiceAge > warningAge) { "outOfServiceAge must be greater than warningAge" }
        require(providerFailureThreshold in 0.0..1.0) {
            "providerFailureThreshold must be between 0 and 1"
        }
        require(expiredBacklogOutOfService >= 0L) {
            "expiredBacklogOutOfService must be non-negative"
        }
    }

    override fun health(): Health {
        val snapshot = source.snapshot()
        val status = when {
            !snapshot.adapterReady || !snapshot.schemaReady || !snapshot.activePolicyPresent ||
                snapshot.oldestVacancyAge > outOfServiceAge || snapshot.failedJobs > 0L ||
                snapshot.expiredBacklog > expiredBacklogOutOfService -> "OUT_OF_SERVICE"
            snapshot.providerFailureRatio >= providerFailureThreshold ||
                snapshot.oldestVacancyAge >= warningAge || snapshot.unknownDeliveries > 0L -> "DEGRADED"
            else -> "UP"
        }
        return Health.status(status)
            .withDetail("adapterReady", snapshot.adapterReady)
            .withDetail("schemaReady", snapshot.schemaReady)
            .withDetail("activePolicyPresent", snapshot.activePolicyPresent)
            .withDetail("oldestVacancyAgeSeconds", snapshot.oldestVacancyAge.seconds)
            .withDetail("expiredBacklog", snapshot.expiredBacklog)
            .withDetail("failedJobs", snapshot.failedJobs)
            .withDetail("unknownDeliveries", snapshot.unknownDeliveries)
            .withDetail("providerFailureRatio", snapshot.providerFailureRatio)
            .build()
    }
}
