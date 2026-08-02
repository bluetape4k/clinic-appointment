package io.bluetape4k.clinic.appointment.api.commitment

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * commitment 운영 metric이 안정적인 저카디널리티 tag만 노출하는지 검증한다.
 */
class AppointmentCommitmentMetricsTest {

    @Test
    fun `records the approved operational signals without identity tags`() {
        val registry = SimpleMeterRegistry()
        val metrics = AppointmentCommitmentMetrics(registry)

        metrics.recordProposalLatency("tenant-a", "clinic-7", CommitmentMetricResult.SUCCESS, Duration.ofMillis(12))
        metrics.recordProposalExpiry("tenant-a", "clinic-7", CommitmentExpiryReason.TTL)
        metrics.recordAllocationConflict("tenant-a", "clinic-7", CommitmentConflictReason.OVERLAP)
        metrics.recordOutboxLag("tenant-a", "clinic-7", Duration.ofMinutes(6))
        metrics.recordQuarantine("tenant-a", "clinic-7", CommitmentQuarantineReason.VERSION_GAP, Duration.ofHours(25))
        metrics.recordMigrationRejection("tenant-a", "clinic-7", CommitmentMigrationReason.CONSENT_REQUIRED)
        metrics.recordOperationalExceptionAckLatency(
            "tenant-a",
            "clinic-7",
            CommitmentExceptionType.RESOURCE_DISRUPTION,
            Duration.ofMinutes(16),
        )

        val forbidden = setOf("patientId", "productId", "eventId", "appointmentId", "proposalId")
        registry.meters.forEach { meter ->
            meter.id.tags.map { it.key }.none(forbidden::contains).shouldBeTrue()
        }
        registry.find("appointment.commitment.proposal.expired").counter().shouldNotBeNull().count() shouldBeEqualTo 1.0
        registry.find("appointment.commitment.allocation.conflict").counter().shouldNotBeNull().count() shouldBeEqualTo 1.0
        registry.find("appointment.commitment.migration.rejected").counter().shouldNotBeNull().count() shouldBeEqualTo 1.0
    }
}
