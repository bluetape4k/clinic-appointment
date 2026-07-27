package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

class PlanFoundationFeatureControlResolverTest {

    @Test
    fun `exact clinic override does not enable another clinic`() {
        val resolver = PlanFoundationFeatureControlResolver(
            PlanFoundationProperties(
                catalogSyncEnabled = false,
                planReadEnabled = false,
                purchaseConsumerMode = PurchaseConsumerMode.OFF,
                scopeOverrides = listOf(
                    PlanFoundationScopeOverride(
                        tenantGroupId = 7,
                        clinicId = 11,
                        catalogSyncEnabled = true,
                        planReadEnabled = true,
                        purchaseConsumerMode = PurchaseConsumerMode.SHADOW,
                    )
                ),
            )
        )

        resolver.resolve(7, 11) shouldBeEqualTo EffectivePlanFoundationControls(
            catalogSyncEnabled = true,
            planReadEnabled = true,
            purchaseConsumerMode = PurchaseConsumerMode.SHADOW,
        )
        resolver.resolve(7, 12) shouldBeEqualTo EffectivePlanFoundationControls(
            catalogSyncEnabled = false,
            planReadEnabled = false,
            purchaseConsumerMode = PurchaseConsumerMode.OFF,
        )
        resolver.resolve(8, 11) shouldBeEqualTo EffectivePlanFoundationControls(
            catalogSyncEnabled = false,
            planReadEnabled = false,
            purchaseConsumerMode = PurchaseConsumerMode.OFF,
        )
    }
}
