package io.bluetape4k.clinic.appointment.api.security

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Profile

class SecurityProfileAssertionTest {

    @Test
    fun `real security is active for integration-test even when test is active`() {
        SecurityConfig::class.java.getAnnotation(Profile::class.java).value
            .toList() shouldBeEqualTo listOf("(!dev & !test) | integration-test")
    }

    @Test
    fun `no-op security is disabled for integration-test`() {
        NoOpSecurityConfig::class.java.getAnnotation(Profile::class.java).value
            .toList() shouldBeEqualTo listOf("(dev | test) & !integration-test")
    }

    @Test
    fun `no-op security provides actor resolver for route controllers`() {
        NoOpSecurityConfig().actorContextResolver()::class shouldBeEqualTo ActorContextResolver::class
    }
}
