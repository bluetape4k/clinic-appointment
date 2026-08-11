package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.api.security.PatientLoginAttemptLimiter
import org.junit.jupiter.api.Test

class PatientAuthenticationWiringTest {

    @Test
    fun `protected profile fails closed without a real login attempt limiter`() {
        assertFailsWith<IllegalStateException> {
            PatientLoginAttemptLimiter.resolve(activeProfiles = setOf("prod"), configured = null)
        }
    }

    @Test
    fun `dev and test profiles use a bounded no op adapter`() {
        val limiter = PatientLoginAttemptLimiter.resolve(activeProfiles = setOf("test"), configured = null)
        limiter.allow(tenantGroupId = 1L, identifierKey = "LOGIN_ID", clientFingerprint = "test")
            .shouldBeTrue()
    }

    @Test
    fun `integration tests retain the bounded adapter when the test profile is active`() {
        val limiter = PatientLoginAttemptLimiter.resolve(
            activeProfiles = setOf("test", "integration-test"),
            configured = null,
        )
        limiter.allow(tenantGroupId = 1L, identifierKey = "LOGIN_ID", clientFingerprint = "test")
            .shouldBeTrue()
    }

    @Test
    fun `an unprofiled local context uses the bounded adapter`() {
        val limiter = PatientLoginAttemptLimiter.resolve(activeProfiles = emptySet(), configured = null)
        limiter.allow(tenantGroupId = 1L, identifierKey = "LOGIN_ID", clientFingerprint = "test")
            .shouldBeTrue()
    }
}
