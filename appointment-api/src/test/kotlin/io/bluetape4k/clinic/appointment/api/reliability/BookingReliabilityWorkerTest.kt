package io.bluetape4k.clinic.appointment.api.reliability

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.time.Duration

class BookingReliabilityWorkerTest {
    @Test
    fun `retry policy preserves cancellation and caps exponential delay`() {
        val policy = BookingReliabilityRetryPolicy(
            baseDelay = Duration.ofSeconds(2),
            maximumDelay = Duration.ofSeconds(5),
            maximumAttempts = 3,
        )
        policy.shouldRetry(0) shouldBeEqualTo true
        policy.shouldRetry(3) shouldBeEqualTo false
        policy.shouldRetry(0, cancelled = true) shouldBeEqualTo false
        policy.delayFor(5) shouldBeEqualTo Duration.ofSeconds(5)
    }

    @Test
    fun `schema readiness blocks worker when migration is incomplete`() {
        val properties = BookingReliabilityProperties(workerEnabled = true)
        val readiness = DefaultBookingReliabilitySchemaReadiness(
            BookingReliabilitySchemaProbe {
                BookingReliabilitySchemaReadiness(17, true, false, true)
            },
        )
        readiness.canStartWorker(properties) shouldBeEqualTo false
        readiness.canEnforce(properties.copy(mode = BookingReliabilityProperties.Mode.OFF)) shouldBeEqualTo true
    }
}
