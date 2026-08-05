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

    @Test
    fun `resolve only canonical tenant code`() {
        listOf(
            "/api/Tenant-A/clinics",
            "/api/tenant a/clinics",
            "/api/tenant_a/clinics",
            "/api/tenant..a/clinics",
            "/api/-tenant-a/clinics",
            "/api/tenant-a-/clinics",
            "/api/tenant--a/clinics",
            "/api/v1/clinics",
            "/api/v2/clinics",
            "/api//clinics",
        ).forEach { path ->
            val request = MockHttpServletRequest("GET", path).apply {
                servletPath = path
            }

            TenantPathResolver.resolve(request).shouldBeNull()
        }
    }

    @Test
    fun `enforce flyway tenant code length`() {
        val valid = "a".repeat(64)
        val invalid = "a".repeat(65)

        TenantCodeRules.isCanonical(valid).shouldBeEqualTo(true)
        TenantCodeRules.isCanonical(invalid).shouldBeEqualTo(false)
    }

    @Test
    fun `combine servlet path and path info before resolving`() {
        val request = MockHttpServletRequest("GET", "/api/tenant-a/clinics").apply {
            servletPath = "/api"
            pathInfo = "/tenant-a/clinics"
        }

        TenantPathResolver.resolve(request) shouldBeEqualTo "tenant-a"
    }
}
