package io.bluetape4k.clinic.appointment.api.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "appointment.plan-foundation")
data class PlanFoundationProperties(
    val catalogSyncEnabled: Boolean = false,
    val planReadEnabled: Boolean = false,
    val purchaseConsumerMode: PurchaseConsumerMode = PurchaseConsumerMode.OFF,
    val scopeOverrides: List<PlanFoundationScopeOverride> = emptyList(),
    val consumerMaxAttempts: Int = 5,
    val consumerInitialBackoff: Duration = Duration.ofSeconds(5),
    val consumerMaxBackoff: Duration = Duration.ofMinutes(5),
    val consumerJitter: Double = 0.20,
    val eventReplayWindow: Duration = Duration.ofMinutes(15),
)

data class PlanFoundationScopeOverride(
    val tenantGroupId: Long,
    val clinicId: Long,
    val catalogSyncEnabled: Boolean? = null,
    val planReadEnabled: Boolean? = null,
    val purchaseConsumerMode: PurchaseConsumerMode? = null,
)

enum class PurchaseConsumerMode {
    OFF,
    SHADOW,
    WRITE,
}
