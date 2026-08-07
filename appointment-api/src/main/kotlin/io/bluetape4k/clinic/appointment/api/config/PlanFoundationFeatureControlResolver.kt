package io.bluetape4k.clinic.appointment.api.config

/**
 * 하나의 tenant/clinic scope에 적용할 Foundation 제어값을 해석합니다.
 *
 * 전역 값은 fail-safe 기본값입니다. 정확히 일치하는 tenant/clinic override는
 * 해당 scope만 변경하므로 canary 활성화와 rollback이 프로세스의 모든 clinic으로
 * 영향 범위를 넓히지 않습니다.
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
