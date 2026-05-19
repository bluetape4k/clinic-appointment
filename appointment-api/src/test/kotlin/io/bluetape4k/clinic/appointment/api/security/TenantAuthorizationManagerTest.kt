package io.bluetape4k.clinic.appointment.api.security

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.web.access.intercept.RequestAuthorizationContext

class TenantAuthorizationManagerTest {

    private val manager = TenantAuthorizationManager()

    @Test
    fun `grant when URL tenant is allowed by JWT claim`() {
        val decision = manager.authorize(
            { authentication(allowedTenants = listOf("tenant-a", "tenant-b")) },
            requestContext("tenant-a"),
        )

        decision.isGranted.shouldBeTrue()
    }

    @Test
    fun `deny when URL tenant is not allowed by JWT claim`() {
        val decision = manager.authorize(
            { authentication(allowedTenants = listOf("tenant-b")) },
            requestContext("tenant-a"),
        )

        decision.isGranted.shouldBeFalse()
    }

    @Test
    fun `deny when principal is not scheduling user`() {
        val decision = manager.authorize(
            { UsernamePasswordAuthenticationToken("user", null, emptyList()) },
            requestContext("tenant-a"),
        )

        decision.isGranted.shouldBeFalse()
    }

    @Test
    fun `use request path when matcher variables are empty`() {
        val request = MockHttpServletRequest("GET", "/api/tenant-a/clinics").apply {
            servletPath = "/api/tenant-a/clinics"
        }

        val decision = manager.authorize(
            { authentication(allowedTenants = listOf("tenant-a")) },
            RequestAuthorizationContext(request),
        )

        decision.isGranted.shouldBeTrue()
    }

    private fun requestContext(tenantCode: String): RequestAuthorizationContext {
        val request = MockHttpServletRequest("GET", "/api/$tenantCode/clinics").apply {
            servletPath = "/api/$tenantCode/clinics"
        }
        return RequestAuthorizationContext(request, mapOf("tenantCode" to tenantCode))
    }

    private fun authentication(allowedTenants: List<String>): UsernamePasswordAuthenticationToken {
        val principal = SchedulingUserPrincipal(
            userId = "user-1",
            clinicId = 1L,
            roles = listOf(SchedulingRole.ADMIN),
            allowedTenants = allowedTenants,
        )
        return UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
    }
}
