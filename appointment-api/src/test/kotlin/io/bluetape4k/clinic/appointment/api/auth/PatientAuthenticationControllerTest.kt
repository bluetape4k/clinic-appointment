package io.bluetape4k.clinic.appointment.api.auth

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.api.security.CorrelationIdFilter
import io.bluetape4k.clinic.appointment.api.security.PatientSessionCookie
import io.bluetape4k.clinic.appointment.api.security.SchedulingRole
import io.bluetape4k.clinic.appointment.api.security.SchedulingUserPrincipal
import io.bluetape4k.clinic.appointment.model.identity.PatientLoginIdentifierKey
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.security.web.method.annotation.CsrfTokenArgumentResolver
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** 환자 auth controller의 status, response, cookie privacy 계약입니다. */
class PatientAuthenticationControllerTest {

    private val now = Instant.parse("2026-08-12T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val service = mockk<PatientAuthenticationService>()
    private val properties = PatientAuthenticationProperties(
        sessionTtl = java.time.Duration.ofHours(1),
        cookieSecure = false,
        dummyPasswordHash = "\$2a\$10\$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy",
    )
    private val cookie = PatientSessionCookie(properties, clock)
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(PatientAuthenticationController(service, cookie))
            .setControllerAdvice(io.bluetape4k.clinic.appointment.api.config.GlobalExceptionHandler())
            .setCustomArgumentResolvers(CsrfTokenArgumentResolver())
            .addFilters<org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder>(CorrelationIdFilter())
            .build()
    }

    @Test
    fun `csrf bootstrap returns harmless readiness body without credential`() {
        mockMvc.perform(
            get("/api/tenant-default/auth/csrf")
                .header(CorrelationIdFilter.HEADER_NAME, "csrf-correlation")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.ready").value(true))
            .andExpect(jsonPath("$.data.secret").doesNotExist())
            .andExpect(jsonPath("$.data.token").doesNotExist())
    }

    @Test
    fun `register returns created without session cookie or opaque subject`() {
        val request = PatientRegisterRequest(
            displayName = "홍길동",
            password = "correct horse battery staple",
            identifiers = listOf(
                PatientLoginIdentifierRequest(PatientLoginIdentifierKey.EMAIL, "patient@example.com"),
            ),
        )
        every { service.register("tenant-default", request) } returns PatientRegistrationResult(
            accountId = 11L,
            patientSubject = "patient-opaque-subject",
            identifierKeys = setOf(PatientLoginIdentifierKey.EMAIL),
        )

        mockMvc.perform(
            post("/api/tenant-default/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"displayName":"홍길동","password":"correct horse battery staple","identifiers":[{"key":"EMAIL","value":"patient@example.com"}]}""",
                )
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.registered").value(true))
            .andExpect(jsonPath("$.data.patientSubject").doesNotExist())
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
    }

    @Test
    fun `successful login returns summary and hardened httpOnly cookie`() {
        val request = PatientLoginRequest(
            identifier = PatientLoginIdentifierRequest(PatientLoginIdentifierKey.PHONE, "010-1234-5678"),
            password = "correct horse battery staple",
        )
        val expiresAt = now.plusSeconds(3_600)
        every { service.login("tenant-default", request, any()) } returns PatientLoginResult(
            token = "jwt-token-without-pii",
            session = PatientSessionSummary(
                tenantCode = "tenant-default",
                role = SchedulingRole.PATIENT,
                displayName = "홍길동",
                expiresAt = expiresAt,
            ),
        )

        val result = mockMvc.perform(
            post("/api/tenant-default/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"identifier":{"key":"PHONE","value":"010-1234-5678"},"password":"correct horse battery staple"}""",
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.tenantCode").value("tenant-default"))
            .andExpect(jsonPath("$.data.role").value("PATIENT"))
            .andExpect(jsonPath("$.data.displayName").value("홍길동"))
            .andExpect(jsonPath("$.data.expiresAt").exists())
            .andExpect(jsonPath("$.data.token").doesNotExist())
            .andExpect(jsonPath("$.data.password").doesNotExist())
            .andReturn()

        val setCookie = result.response.getHeader(HttpHeaders.SET_COOKIE).orEmpty()
        setCookie.contains("appointment_patient_session=jwt-token-without-pii") shouldBeEqualTo true
        setCookie.contains("HttpOnly") shouldBeEqualTo true
        setCookie.contains("Path=/") shouldBeEqualTo true
        setCookie.contains("SameSite=Strict") shouldBeEqualTo true
        setCookie.contains("Max-Age=3600") shouldBeEqualTo true
        setCookie.contains("Secure") shouldBeEqualTo false
    }

    @Test
    fun `session endpoint returns only summary and logout deletes cookie`() {
        val expiresAt = now.plusSeconds(3_600)
        val principal = SchedulingUserPrincipal(
            userId = "patient-subject",
            clinicId = null,
            roles = setOf(SchedulingRole.PATIENT),
            allowedTenants = setOf("tenant-default"),
            actorType = io.bluetape4k.clinic.appointment.api.security.ActorType.PATIENT,
            patientSubjectId = "patient-subject",
            expiresAt = expiresAt,
        )
        val summary = PatientSessionSummary("tenant-default", SchedulingRole.PATIENT, "홍길동", expiresAt)
        every { service.session("tenant-default", principal) } returns summary

        val session = PatientAuthenticationController(service, cookie).session("tenant-default", principal)
        session.statusCode.value() shouldBeEqualTo 200
        session.body?.data?.displayName shouldBeEqualTo "홍길동"

        mockMvc.perform(post("/api/tenant-default/auth/logout"))
            .andExpect(status().isNoContent)
            .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("Max-Age=0")))
            .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("HttpOnly")))
    }
}
