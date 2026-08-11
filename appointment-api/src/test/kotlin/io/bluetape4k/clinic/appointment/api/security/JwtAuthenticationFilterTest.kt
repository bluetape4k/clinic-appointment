package io.bluetape4k.clinic.appointment.api.security

import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.clinic.appointment.api.auth.PatientAuthenticationProperties
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder

/**
 * stateless JWT 요청 사이에서 이전 인증 정보가 재사용되지 않는지 검증한다.
 *
 * servlet thread가 재사용되거나 upstream 코드가 잘못된 context를 남기더라도 bearer token이
 * 없는 새 요청은 반드시 anonymous 상태에서 시작해야 한다. 이 규칙은 무인증 요청을 403으로
 * 오분류하거나 이전 tenant 권한을 재사용하는 것을 막는다.
 */
class JwtAuthenticationFilterTest {

    private val filter = JwtAuthenticationFilter(mockk(relaxed = true))

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `request without bearer token clears stale authentication`() {
        val stalePrincipal = SchedulingUserPrincipal(
            userId = "stale-admin",
            clinicId = 7L,
            roles = listOf(SchedulingRole.ADMIN),
            allowedTenants = listOf("tenant-a"),
        )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                stalePrincipal,
                null,
                stalePrincipal.authorities,
            )

        filter.doFilter(
            MockHttpServletRequest(),
            MockHttpServletResponse(),
            MockFilterChain(),
        )

        SecurityContextHolder.getContext().authentication.shouldBeNull()
    }

    @Test
    fun `configured patient cookie authenticates when authorization header is absent`() {
        val parser = mockk<JwtTokenParser>()
        val principal = SchedulingUserPrincipal(
            userId = "patient-1",
            clinicId = null,
            roles = setOf(SchedulingRole.PATIENT),
            allowedTenants = setOf("tenant-a"),
            actorType = ActorType.PATIENT,
            patientSubjectId = "patient-1",
        )
        io.mockk.every { parser.parse("cookie-jwt") } returns principal
        val properties = PatientAuthenticationProperties(cookieSecure = false)
        val filter = JwtAuthenticationFilter(parser, properties)
        val request = MockHttpServletRequest().apply {
            setCookies(jakarta.servlet.http.Cookie(properties.cookieName, "cookie-jwt"))
        }
        var capturedPrincipal: Any? = null

        filter.doFilter(request, MockHttpServletResponse()) { _, _ ->
            capturedPrincipal = SecurityContextHolder.getContext().authentication?.principal
        }

        capturedPrincipal shouldBeEqualTo principal
    }

    @Test
    fun `bearer token takes precedence over patient cookie`() {
        val parser = mockk<JwtTokenParser>()
        val bearerPrincipal = SchedulingUserPrincipal(
            userId = "staff-1",
            clinicId = 7L,
            roles = setOf(SchedulingRole.STAFF),
            allowedTenants = setOf("tenant-a"),
        )
        io.mockk.every { parser.parse("bearer-jwt") } returns bearerPrincipal
        val properties = PatientAuthenticationProperties(cookieSecure = false)
        val filter = JwtAuthenticationFilter(parser, properties)
        val request = MockHttpServletRequest().apply {
            addHeader(HttpHeaders.AUTHORIZATION, "Bearer bearer-jwt")
            setCookies(jakarta.servlet.http.Cookie(properties.cookieName, "cookie-jwt"))
        }
        var capturedPrincipal: Any? = null

        filter.doFilter(request, MockHttpServletResponse()) { _, _ ->
            capturedPrincipal = SecurityContextHolder.getContext().authentication?.principal
        }

        capturedPrincipal shouldBeEqualTo bearerPrincipal
        io.mockk.verify(exactly = 1) { parser.parse("bearer-jwt") }
        io.mockk.verify(exactly = 0) { parser.parse("cookie-jwt") }
    }

    @Test
    fun `invalid patient cookie stays anonymous and is cleared without exposing token`() {
        val parser = mockk<JwtTokenParser>()
        io.mockk.every { parser.parse("invalid-jwt") } returns null
        val properties = PatientAuthenticationProperties(cookieSecure = false)
        val filter = JwtAuthenticationFilter(parser, properties)
        val request = MockHttpServletRequest().apply {
            setCookies(jakarta.servlet.http.Cookie(properties.cookieName, "invalid-jwt"))
        }
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        SecurityContextHolder.getContext().authentication.shouldBeNull()
        val setCookie = response.getHeader(HttpHeaders.SET_COOKIE)
        setCookie.shouldNotBeNull()
        setCookie.contains("${properties.cookieName}=;") shouldBeEqualTo true
        setCookie.contains("invalid-jwt") shouldBeEqualTo false
    }

    @Test
    fun `blank patient cookie is cleared without invoking parser`() {
        val parser = mockk<JwtTokenParser>()
        val properties = PatientAuthenticationProperties(cookieSecure = false)
        val filter = JwtAuthenticationFilter(parser, properties)
        val request = MockHttpServletRequest().apply {
            setCookies(jakarta.servlet.http.Cookie(properties.cookieName, ""))
        }
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        response.getHeader(HttpHeaders.SET_COOKIE).shouldNotBeNull()
        io.mockk.verify(exactly = 0) { parser.parse(any()) }
    }
}
