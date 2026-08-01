package io.bluetape4k.clinic.appointment.api.reliability

import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityReasonCode
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityVerdict
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import java.time.Duration
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeEqualTo

class BookingReliabilityMetricsTest {
    @Test
    fun `metrics use closed low cardinality tags only`() {
        val registry = SimpleMeterRegistry()
        BookingReliabilityMetrics(registry).recordDecision(
            mode = BookingReliabilityProperties.Mode.SHADOW,
            verdict = BookingReliabilityVerdict.RESTRICTED,
            reasonCodes = setOf(BookingReliabilityReasonCode.NO_SHOW_THRESHOLD_EXCEEDED),
            duration = Duration.ofMillis(20),
        )

        registry.meters.flatMap { it.id.tags }.any { it.key == "memberId" }.shouldBeFalse()
        registry.get(BookingReliabilityMetrics.DECISIONS)
            .tag("mode", "shadow")
            .tag("verdict", "restricted")
            .counter()
            .count() shouldBeEqualTo 1.0
    }
}
