package io.bluetape4k.clinic.appointment.api.config

import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.core.env.Environment

class PlanFoundationPropertiesValidator(
    private val properties: PlanFoundationProperties,
    private val environment: Environment,
    private val outboxTransportCapability: ObjectProvider<OutboxTransportCapability>,
) : SmartInitializingSingleton {

    override fun afterSingletonsInstantiated() {
        validate()
    }

    fun validate() {
        require(properties.consumerMaxAttempts > 0) { "consumerMaxAttempts must be positive" }
        require(!properties.consumerInitialBackoff.isNegative && !properties.consumerInitialBackoff.isZero) {
            "consumerInitialBackoff must be positive"
        }
        require(properties.consumerMaxBackoff >= properties.consumerInitialBackoff) {
            "consumerMaxBackoff must not be shorter than consumerInitialBackoff"
        }
        require(properties.consumerJitter in 0.0..1.0) { "consumerJitter must be between 0 and 1" }
        requirePositive(properties.eventReplayWindow, "eventReplayWindow")
        properties.scopeOverrides.forEach { override ->
            require(override.tenantGroupId > 0) { "scope override tenantGroupId must be positive" }
            require(override.clinicId > 0) { "scope override clinicId must be positive" }
        }
        require(
            properties.scopeOverrides
                .map { it.tenantGroupId to it.clinicId }
                .distinct()
                .size == properties.scopeOverrides.size
        ) { "scope overrides must be unique by tenantGroupId and clinicId" }

        val testLike = environment.activeProfiles.any { profile -> profile == "test" || profile == "dev" }
        val writeRequested = properties.purchaseConsumerMode == PurchaseConsumerMode.WRITE ||
            properties.scopeOverrides.any { it.purchaseConsumerMode == PurchaseConsumerMode.WRITE }
        if (!testLike && writeRequested) {
            val capability = outboxTransportCapability.ifAvailable
            check(capability?.isAvailable() == true) {
                "WRITE purchase consumer mode requires an available OutboxTransportCapability"
            }
        }
    }

    private fun requirePositive(duration: java.time.Duration, propertyName: String) {
        require(!duration.isNegative && !duration.isZero) { "$propertyName must be positive" }
    }
}
