package io.bluetape4k.clinic.appointment.api.waitlist

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class WaitlistDeliverySchedulingTest {
    private val clock = Clock.fixed(Instant.parse("2026-08-03T10:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `global off still runs expiry suppression and hold recovery`() {
        val calls = mutableListOf<String>()
        val runner = WaitlistDeliverySchedulingRunner(
            properties = WaitlistDeliveryProperties(enabled = false),
            leaderLease = lease(acquired = true, calls),
            vacancyDispatcher = WaitlistVacancyDispatcher { _, _ -> calls += "dispatch"; 1 },
            offerExpiryRunner = WaitlistOfferExpiryRunner { _, _ -> calls += "expiry"; 2 },
            notificationSuppressionRunner = WaitlistNotificationSuppressionRunner { _, _ ->
                calls += "suppression"
                3
            },
            holdReconciler = WaitlistHoldReconciler { _, _ -> calls += "reconcile"; 4 },
            clock = clock,
        )

        val result = runner.tick()

        result.mode shouldBeEqualTo DeliveryMode.GLOBAL_OFF
        result.dispatchCount shouldBeEqualTo 0
        result.expiryCount shouldBeEqualTo 2
        result.suppressionCount shouldBeEqualTo 3
        result.holdReconcileCount shouldBeEqualTo 4
        calls shouldBeEqualTo listOf("lease", "expiry", "suppression", "reconcile", "release")
    }

    @Test
    fun `lost leader does not start any job`() {
        var dispatches = 0
        val runner = WaitlistDeliverySchedulingRunner(
            properties = WaitlistDeliveryProperties(enabled = true),
            leaderLease = lease(acquired = false, mutableListOf()),
            vacancyDispatcher = WaitlistVacancyDispatcher { _, _ -> dispatches++; 1 },
            offerExpiryRunner = WaitlistOfferExpiryRunner { _, _ -> error("must not run") },
            notificationSuppressionRunner = WaitlistNotificationSuppressionRunner { _, _ ->
                error("must not run")
            },
            holdReconciler = WaitlistHoldReconciler { _, _ -> error("must not run") },
            clock = clock,
        )

        val result = runner.tick()

        result.leaderAcquired.shouldBeFalse()
        dispatches shouldBeEqualTo 0
    }

    @Test
    fun `allowlisted clinic is active and other clinic is disabled`() {
        val properties = WaitlistDeliveryProperties(enabled = true, clinicAllowlist = setOf(7L))
        properties.modeFor(7L) shouldBeEqualTo DeliveryMode.ACTIVE
        properties.modeFor(8L) shouldBeEqualTo DeliveryMode.CLINIC_DISABLED
    }

    @Test
    fun `unscoped tick fails closed when clinic allowlist is configured`() {
        val calls = mutableListOf<String>()
        val runner = WaitlistDeliverySchedulingRunner(
            properties = WaitlistDeliveryProperties(enabled = true, clinicAllowlist = setOf(7L)),
            leaderLease = lease(acquired = true, calls),
            vacancyDispatcher = WaitlistVacancyDispatcher { _, _ -> calls += "dispatch"; 1 },
            offerExpiryRunner = WaitlistOfferExpiryRunner { _, _ -> calls += "expiry"; 1 },
            notificationSuppressionRunner = WaitlistNotificationSuppressionRunner { _, _ -> calls += "suppression"; 1 },
            holdReconciler = WaitlistHoldReconciler { _, _ -> calls += "reconcile"; 1 },
            clock = clock,
        )

        val result = runner.tick()

        result.mode shouldBeEqualTo DeliveryMode.CLINIC_DISABLED
        result.dispatchCount shouldBeEqualTo 0
        calls.contains("dispatch").shouldBeFalse()
        calls.contains("expiry").shouldBeTrue()
    }

    private fun lease(acquired: Boolean, calls: MutableList<String>): WaitlistLeaderLease =
        object : WaitlistLeaderLease {
            override fun tryAcquire(owner: String, leaseUntil: Instant): Boolean {
                calls += "lease"
                return acquired
            }

            override fun release(owner: String) {
                calls += "release"
            }
        }
}
