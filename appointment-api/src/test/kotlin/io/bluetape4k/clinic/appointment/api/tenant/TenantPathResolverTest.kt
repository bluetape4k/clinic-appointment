package io.bluetape4k.clinic.appointment.api.tenant

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

class TenantPathResolverTest {

    @Test
    fun `resolve tenant code from API path`() {
        val request = MockHttpServletRequest("GET", "/api/tenant-a/clinics")
        request.servletPath = "/api/tenant-a/clinics"

        TenantPathResolver.resolve(request) shouldBeEqualTo "tenant-a"
    }

    @Test
    fun `return null for non API path`() {
        val request = MockHttpServletRequest("GET", "/actuator/health")
        request.servletPath = "/actuator/health"

        TenantPathResolver.resolve(request).shouldBeNull()
    }

    @Test
    fun `return null for API root without tenant segment`() {
        val request = MockHttpServletRequest("GET", "/api/")
        request.servletPath = "/api/"

        TenantPathResolver.resolve(request).shouldBeNull()
    }

    @Test
    fun `return null for actor scoped versioned API without tenant path`() {
        val request = MockHttpServletRequest("POST", "/api/v2/appointment-requests")
        request.servletPath = "/api/v2/appointment-requests"

        TenantPathResolver.resolve(request).shouldBeNull()
    }
}
