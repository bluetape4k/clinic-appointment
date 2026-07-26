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
        requirePositive(properties.trustVerificationTimeout, "trustVerificationTimeout")
        requirePositive(properties.sourceAuthorityTimeout, "sourceAuthorityTimeout")
        requirePositive(properties.redriveDryRunTimeout, "redriveDryRunTimeout")
        require(properties.eventReplayWindow >= properties.trustVerificationTimeout) {
            "eventReplayWindow must not be shorter than trustVerificationTimeout"
        }
        require(properties.redriveDryRunTimeout >= properties.trustVerificationTimeout) {
            "redriveDryRunTimeout must not be shorter than trustVerificationTimeout"
        }
        require(properties.redriveDryRunTimeout >= properties.sourceAuthorityTimeout) {
            "redriveDryRunTimeout must not be shorter than sourceAuthorityTimeout"
        }

        val testLike = environment.activeProfiles.any { profile -> profile == "test" || profile == "dev" }
        if (!testLike && properties.purchaseConsumerMode == PurchaseConsumerMode.WRITE) {
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
