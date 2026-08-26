package io.bluetape4k.clinic.appointment.api.waitlist

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Waitlist delivery rollout과 bounded worker budget을 정의합니다.
 *
 * [enabled]는 새 vacancy dispatch/notification의 global switch입니다. expiry, suppression,
 * hold recovery는 이 값과 무관하게 실행되어 rollback 중에도 capacity를 안전하게 반환합니다.
 */
@ConfigurationProperties(prefix = "appointment.waitlist.delivery")
data class WaitlistDeliveryProperties(
    val enabled: Boolean = false,
    val clinicAllowlist: Set<Long> = emptySet(),
    val batchSize: Int = 25,
    val jobLease: Duration = Duration.ofSeconds(30),
    val fenceEpoch: Long = 1L,
    val tickBudget: Duration = Duration.ofSeconds(25),
    val maxAttempts: Int = 5,
    val pollInterval: Duration = Duration.ofSeconds(1),
    val retentionBatchSize: Int = 100,
) {
    init {
        require(clinicAllowlist.all { it > 0 }) { "clinicAllowlist must contain positive ids" }
        require(batchSize in 1..100) { "batchSize must be between 1 and 100" }
        require(jobLease.isPositive) { "jobLease must be positive" }
        require(fenceEpoch > 0L) { "fenceEpoch must be positive" }
        require(tickBudget.isPositive) { "tickBudget must be positive" }
        require(tickBudget < jobLease) { "tickBudget must be shorter than jobLease" }
        require(maxAttempts in 1..50) { "maxAttempts must be between 1 and 50" }
        require(pollInterval.isPositive) { "pollInterval must be positive" }
        require(retentionBatchSize in 1..100) {
            "retentionBatchSize must be between 1 and 100"
        }
    }

    fun modeFor(clinicId: Long): DeliveryMode = when {
        !enabled -> DeliveryMode.GLOBAL_OFF
        clinicId <= 0L || (clinicAllowlist.isNotEmpty() && clinicId !in clinicAllowlist) ->
            DeliveryMode.CLINIC_DISABLED
        else -> DeliveryMode.ACTIVE
    }
}

/** 새 delivery dispatch를 허용하는 rollout 상태입니다. */
enum class DeliveryMode {
    ACTIVE,
    CLINIC_DISABLED,
    GLOBAL_OFF,
}
