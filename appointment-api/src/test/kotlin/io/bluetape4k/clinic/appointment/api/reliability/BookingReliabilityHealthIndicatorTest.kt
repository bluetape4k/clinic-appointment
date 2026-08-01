package io.bluetape4k.clinic.appointment.api.reliability

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.time.Duration

class BookingReliabilityHealthIndicatorTest {
    @Test
    fun `schema failure and unavailable backlog degrade health without identifiers`() {
        val health = BookingReliabilityHealthIndicator(
            BookingReliabilityHealthSource {
                BookingReliabilityOperationalSnapshot(
                    schemaReady = false,
                    pendingJobs = 3,
                    oldestBacklogAge = Duration.ofHours(1),
                    unavailableDecisions = 2,
                    leaseLostJobs = 1,
                    mode = BookingReliabilityProperties.Mode.ENFORCE,
                )
            },
        ).health()

        health.status.code shouldBeEqualTo "DEGRADED"
        health.details.containsKey("memberId") shouldBeEqualTo false
    }
}
