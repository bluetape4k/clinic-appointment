package io.bluetape4k.clinic.appointment.api.config

/**
 * Resolves the effective Foundation controls for one tenant/clinic scope.
 *
 * Global values are fail-safe defaults. An exact tenant/clinic override can
 * change only that scope, so canary activation and rollback do not widen the
 * blast radius to every clinic in the process.
 */
class PlanFoundationFeatureControlResolver(
    private val properties: PlanFoundationProperties,
) {
    fun resolve(tenantGroupId: Long, clinicId: Long): EffectivePlanFoundationControls {
        require(tenantGroupId > 0) { "tenantGroupId must be positive" }
        require(clinicId > 0) { "clinicId must be positive" }
        val override = properties.scopeOverrides.singleOrNull {
            it.tenantGroupId == tenantGroupId && it.clinicId == clinicId
        }
        return EffectivePlanFoundationControls(
            catalogSyncEnabled = override?.catalogSyncEnabled ?: properties.catalogSyncEnabled,
            planReadEnabled = override?.planReadEnabled ?: properties.planReadEnabled,
            purchaseConsumerMode = override?.purchaseConsumerMode ?: properties.purchaseConsumerMode,
        )
    }
}

data class EffectivePlanFoundationControls(
    val catalogSyncEnabled: Boolean,
    val planReadEnabled: Boolean,
    val purchaseConsumerMode: PurchaseConsumerMode,
)
