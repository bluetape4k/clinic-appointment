package io.bluetape4k.clinic.appointment.api.security

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

class PatientCsrfRequestMatcherTest {

    private val matcher = PatientCsrfRequestMatcher("appointment_patient_session")

    @Test
    fun `patient login mutation requires csrf before cookie exists`() {
        matcher.matches(MockHttpServletRequest("POST", "/api/tenant-a/auth/login")).shouldBeTrue()
    }

    @Test
    fun `patient cookie mutation requires csrf while bearer mutation stays compatible`() {
        val cookieRequest = MockHttpServletRequest("POST", "/api/tenant-a/appointments").apply {
            setCookies(Cookie("appointment_patient_session", "opaque"))
        }
        matcher.matches(cookieRequest).shouldBeTrue()

        val bearerRequest = MockHttpServletRequest("POST", "/api/tenant-a/appointments").apply {
            addHeader("Authorization", "Bearer workforce-token")
            setCookies(Cookie("appointment_patient_session", "stale"))
        }
        matcher.matches(bearerRequest).shouldBeFalse()
    }

    @Test
    fun `safe requests and unrelated anonymous API do not require csrf`() {
        matcher.matches(MockHttpServletRequest("GET", "/api/tenant-a/auth/session")).shouldBeFalse()
        matcher.matches(MockHttpServletRequest("POST", "/api/tenant-a/appointments")).shouldBeFalse()
    }
}
