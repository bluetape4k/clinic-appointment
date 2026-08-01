package io.bluetape4k.clinic.appointment.api.reliability

import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/** 식별자 없이 reliability worker/schema readiness를 요약하는 health indicator입니다. */
class BookingReliabilityHealthIndicator(
    private val source: BookingReliabilityHealthSource,
    private val backlogWarningAge: Duration = Duration.ofMinutes(30),
) : HealthIndicator {
    init {
        require(backlogWarningAge.isPositive) { "backlogWarningAge must be positive" }
    }

    override fun health(): Health {
        val snapshot = source.snapshot()
        val degraded =
                !snapshot.schemaReady ||
                snapshot.oldestBacklogAge >= backlogWarningAge ||
                snapshot.unavailableDecisions > 0 ||
                snapshot.deadLetterJobs > 0 ||
                snapshot.leaseLostJobs > 0
        val builder = if (degraded) Health.status("DEGRADED") else Health.up()
        return builder
            .withDetail("schemaReady", snapshot.schemaReady)
            .withDetail("oldestBacklogAgeSeconds", snapshot.oldestBacklogAge.seconds)
            .withDetail("pendingJobs", snapshot.pendingJobs)
            .withDetail("unavailableDecisions", snapshot.unavailableDecisions)
            .withDetail("deadLetterJobs", snapshot.deadLetterJobs)
            .withDetail("leaseLostJobs", snapshot.leaseLostJobs)
            .withDetail("mode", snapshot.mode.name)
            .build()
    }
}

/** process 수명 동안 fencing loss를 누적하는 식별자 없는 운영 상태입니다. */
class BookingReliabilityOperationalState {
    private val leaseLost = AtomicLong(0)

    fun recordLeaseLost() {
        leaseLost.incrementAndGet()
    }

    fun leaseLostJobs(): Long = leaseLost.get()
}

fun interface BookingReliabilityHealthSource {
    fun snapshot(): BookingReliabilityOperationalSnapshot
}

data class BookingReliabilityOperationalSnapshot(
    val schemaReady: Boolean,
    val pendingJobs: Long = 0,
    val oldestBacklogAge: Duration = Duration.ZERO,
    val unavailableDecisions: Long = 0,
    val deadLetterJobs: Long = 0,
    val leaseLostJobs: Long = 0,
    val mode: BookingReliabilityProperties.Mode = BookingReliabilityProperties.Mode.OFF,
) {
    init {
        require(pendingJobs >= 0) { "pendingJobs must be non-negative" }
        require(!oldestBacklogAge.isNegative) { "oldestBacklogAge must be non-negative" }
        require(unavailableDecisions >= 0) { "unavailableDecisions must be non-negative" }
        require(deadLetterJobs >= 0) { "deadLetterJobs must be non-negative" }
        require(leaseLostJobs >= 0) { "leaseLostJobs must be non-negative" }
    }
}
