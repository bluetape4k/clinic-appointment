package io.bluetape4k.clinic.appointment.api.waitlist

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.time.Duration

class WaitlistDeliveryHealthTest {
    @Test
    fun `health exposes only bounded operational details`() {
        val health = WaitlistDeliveryHealthIndicator(
            WaitlistDeliveryHealthSource {
                WaitlistDeliveryOperationalSnapshot(
                    adapterReady = true,
                    schemaReady = true,
                    activePolicyPresent = true,
                    oldestVacancyAge = Duration.ofMinutes(3),
                    unknownDeliveries = 1,
                    providerFailureRatio = 0.06,
                )
            },
        ).health()

        health.status.code shouldBeEqualTo "DEGRADED"
        health.details.containsKey("memberId") shouldBeEqualTo false
        health.details.containsKey("offerId") shouldBeEqualTo false
    }

    @Test
    fun `missing required dependency is out of service`() {
        val health = WaitlistDeliveryHealthIndicator(
            WaitlistDeliveryHealthSource {
                WaitlistDeliveryOperationalSnapshot(
                    adapterReady = false,
                    schemaReady = true,
                    activePolicyPresent = true,
                )
            },
        ).health()

        health.status.code shouldBeEqualTo "OUT_OF_SERVICE"
    }
}
