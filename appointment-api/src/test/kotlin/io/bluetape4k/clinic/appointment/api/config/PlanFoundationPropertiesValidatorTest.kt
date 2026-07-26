package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.assertions.assertFailsWith
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.mock.env.MockEnvironment
import java.time.Duration

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

    @Test
    fun `attempt backoff and jitter bounds fail closed`() {
        listOf(0, -1).forEach { attempts ->
            assertFailsWith<IllegalArgumentException> {
                validator(properties = PlanFoundationProperties(consumerMaxAttempts = attempts)).validate()
            }
        }
        assertFailsWith<IllegalArgumentException> {
            validator(
                properties = PlanFoundationProperties(
                    consumerInitialBackoff = Duration.ofSeconds(6),
                    consumerMaxBackoff = Duration.ofSeconds(5),
                )
            ).validate()
        }
        listOf(-0.01, 1.01).forEach { jitter ->
            assertFailsWith<IllegalArgumentException> {
                validator(properties = PlanFoundationProperties(consumerJitter = jitter)).validate()
            }
        }
    }

    @Test
    fun `timeouts and their dependent windows must be positive and ordered`() {
        listOf(
            PlanFoundationProperties(eventReplayWindow = Duration.ZERO),
            PlanFoundationProperties(trustVerificationTimeout = Duration.ofMillis(-1)),
            PlanFoundationProperties(sourceAuthorityTimeout = Duration.ZERO),
            PlanFoundationProperties(redriveDryRunTimeout = Duration.ofSeconds(-1)),
            PlanFoundationProperties(
                eventReplayWindow = Duration.ofMillis(499),
                trustVerificationTimeout = Duration.ofMillis(500),
            ),
            PlanFoundationProperties(
                redriveDryRunTimeout = Duration.ofMillis(499),
                trustVerificationTimeout = Duration.ofMillis(500),
            ),
            PlanFoundationProperties(
                redriveDryRunTimeout = Duration.ofMillis(999),
                sourceAuthorityTimeout = Duration.ofSeconds(1),
            ),
        ).forEach { properties ->
            assertFailsWith<IllegalArgumentException> {
                validator(properties = properties).validate()
            }
        }
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

    private fun validator(
        properties: PlanFoundationProperties,
    ): PlanFoundationPropertiesValidator {
        val beanFactory = DefaultListableBeanFactory()
        return PlanFoundationPropertiesValidator(
            properties = properties,
            environment = MockEnvironment(),
            outboxTransportCapability = beanFactory.getBeanProvider(OutboxTransportCapability::class.java),
        )
    }
}
