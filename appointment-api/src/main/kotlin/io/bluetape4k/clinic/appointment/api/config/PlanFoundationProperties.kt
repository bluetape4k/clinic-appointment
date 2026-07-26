package io.bluetape4k.clinic.appointment.api.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "appointment.plan-foundation")
data class PlanFoundationProperties(
    val catalogSyncEnabled: Boolean = false,
    val planReadEnabled: Boolean = false,
    val purchaseConsumerMode: PurchaseConsumerMode = PurchaseConsumerMode.OFF,
    val consumerMaxAttempts: Int = 5,
    val consumerInitialBackoff: Duration = Duration.ofSeconds(5),
    val consumerMaxBackoff: Duration = Duration.ofMinutes(5),
    val consumerJitter: Double = 0.20,
    val eventReplayWindow: Duration = Duration.ofMinutes(15),
    val trustVerificationTimeout: Duration = Duration.ofMillis(500),
    val sourceAuthorityTimeout: Duration = Duration.ofSeconds(2),
    val redriveDryRunTimeout: Duration = Duration.ofSeconds(10),
)

enum class PurchaseConsumerMode {
    OFF,
    SHADOW,
    WRITE,
}
