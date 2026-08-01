package io.bluetape4k.clinic.appointment.api.reliability

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.time.Duration

class BookingReliabilityCanaryReadinessTest {
    @Test
    fun `promotion requires observation volume and privacy reliability gates`() {
        val readiness = BookingReliabilityCanaryReadiness()
        val evidence = BookingReliabilityCanaryEvidence(
            observation = Duration.ofHours(24),
            decisions = 1_000,
            p95LatencyMillis = 100,
            p99LatencyMillis = 300,
            duplicateDecisions = 0,
            unavailableBacklog = 0,
            attributionMissingRatio = 0.0,
            rawPiiFindings = 0,
            closedMetricTags = true,
        )
        readiness.ready(evidence) shouldBeEqualTo true
        readiness.ready(evidence.copy(rawPiiFindings = 1)) shouldBeEqualTo false
    }
}
