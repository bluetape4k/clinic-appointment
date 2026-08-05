package io.bluetape4k.clinic.appointment.api.tenant

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.clinic.appointment.api.security.JwtAuthenticationFilter
import io.bluetape4k.clinic.appointment.api.security.JwtTokenParser
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class TenantPathValidationFilterTest {

    @Test
    fun `malformed and ambiguous tenant paths fail before JWT parser`() {
        val parser = mockk<JwtTokenParser>()
        val jwtFilter = JwtAuthenticationFilter(parser)
        val filter = TenantPathValidationFilter()

        listOf(
            PathShape("/api/Tenant-A/clinics"),
            PathShape("/api/v1/clinics"),
            PathShape("/api/v2/clinics"),
            PathShape("/api/tenant-a%2fclinics", servletPath = "/api/tenant-a/clinics"),
            PathShape("/api/tenant-a/clinics", servletPath = "/api/tenant-a%2fclinics"),
            PathShape("/api/tenant-a%252fclinics"),
            PathShape("/api/tenant-a;param/clinics"),
            PathShape("/api/tenant-a/clinics;param"),
            PathShape("/api/%zz/clinics"),
        ).forEach { shape ->
            val request = shape.toRequest().apply {
                addHeader("Authorization", "Bearer token")
            }
            val response = MockHttpServletResponse()
            val terminal = CapturingFilterChain()

            filter.doFilter(request, response, FilterChain { nextRequest, nextResponse ->
                jwtFilter.doFilter(nextRequest, nextResponse, terminal)
            })

            response.status shouldBeEqualTo HttpStatus.NOT_FOUND.value()
            response.contentAsString.shouldContain("RESOURCE_NOT_FOUND")
            terminal.called.shouldBeFalse()
        }

        verify(exactly = 0) { parser.parse(any()) }
    }

    @Test
    fun `canonical unknown tenant continues to JWT authentication`() {
        val parser = mockk<JwtTokenParser>()
        every { parser.parse("token") } returns null
        val jwtFilter = JwtAuthenticationFilter(parser)
        val filter = TenantPathValidationFilter()
        val request = MockHttpServletRequest("GET", "/api/unknown-tenant/clinics").apply {
            servletPath = "/api/unknown-tenant/clinics"
            addHeader("Authorization", "Bearer token")
        }
        val response = MockHttpServletResponse()
        val terminal = CapturingFilterChain()

        filter.doFilter(request, response, FilterChain { nextRequest, nextResponse ->
            jwtFilter.doFilter(nextRequest, nextResponse, terminal)
        })

        terminal.called.shouldBeTrue()
        verify(exactly = 1) { parser.parse("token") }
    }

    @Test
    fun `servlet path and path info are validated as one canonical path`() {
        val filter = TenantPathValidationFilter()
        val request = MockHttpServletRequest("GET", "/api/tenant-a/clinics").apply {
            servletPath = "/api/tenant-a"
            pathInfo = "/clinics%5cshadow"
        }
        val response = MockHttpServletResponse()
        val terminal = CapturingFilterChain()

        filter.doFilter(request, response, terminal)

        response.status shouldBeEqualTo HttpStatus.NOT_FOUND.value()
        terminal.called.shouldBeFalse()
    }

    @Test
    fun `different raw and servlet paths fail closed even when both slugs are canonical`() {
        val filter = TenantPathValidationFilter()
        val request = MockHttpServletRequest("GET", "/api/tenant-a/clinics").apply {
            servletPath = "/api/tenant-b/clinics"
        }
        val response = MockHttpServletResponse()
        val terminal = CapturingFilterChain()

        filter.doFilter(request, response, terminal)

        response.status shouldBeEqualTo HttpStatus.NOT_FOUND.value()
        terminal.called.shouldBeFalse()
    }

    private data class PathShape(
        val requestUri: String,
        val servletPath: String = requestUri,
        val pathInfo: String? = null,
    ) {
        fun toRequest(): MockHttpServletRequest = MockHttpServletRequest("GET", requestUri).apply {
            servletPath = this@PathShape.servletPath
            pathInfo = this@PathShape.pathInfo
        }
    }

    private class CapturingFilterChain : FilterChain {
        var called: Boolean = false

        override fun doFilter(request: ServletRequest, response: ServletResponse) {
            called = true
        }
    }
}
