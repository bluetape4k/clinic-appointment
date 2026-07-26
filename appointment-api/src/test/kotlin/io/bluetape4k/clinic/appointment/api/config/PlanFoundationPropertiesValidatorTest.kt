package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.assertions.assertFailsWith
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.mock.env.MockEnvironment

class PlanFoundationPropertiesValidatorTest {

    @Test
    fun `production rejects WRITE without outbox transport capability`() {
        val validator = validator(PurchaseConsumerMode.WRITE)

        assertFailsWith<IllegalStateException> {
            validator.validate()
        }
    }

    @Test
    fun `production accepts OFF and SHADOW without outbox transport capability`() {
        validator(PurchaseConsumerMode.OFF).validate()
        validator(PurchaseConsumerMode.SHADOW).validate()
    }

    @Test
    fun `production accepts WRITE only with an available transport capability`() {
        validator(
            mode = PurchaseConsumerMode.WRITE,
            capability = OutboxTransportCapability { true },
        ).validate()
    }

    @Test
    fun `test profile may exercise WRITE without a production transport`() {
        validator(
            mode = PurchaseConsumerMode.WRITE,
            profiles = arrayOf("test"),
        ).validate()
    }

    private fun validator(
        mode: PurchaseConsumerMode,
        capability: OutboxTransportCapability? = null,
        profiles: Array<String> = emptyArray(),
    ): PlanFoundationPropertiesValidator {
        val beanFactory = DefaultListableBeanFactory()
        capability?.let { beanFactory.registerSingleton("outboxTransportCapability", it) }
        val environment = MockEnvironment().apply { setActiveProfiles(*profiles) }
        return PlanFoundationPropertiesValidator(
            properties = PlanFoundationProperties(purchaseConsumerMode = mode),
            environment = environment,
            outboxTransportCapability = beanFactory.getBeanProvider(OutboxTransportCapability::class.java),
        )
    }
}
