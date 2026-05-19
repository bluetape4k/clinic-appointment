package io.bluetape4k.clinic.appointment.api.tenant

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.api.security.SchedulingRole
import io.bluetape4k.clinic.appointment.api.security.SchedulingUserPrincipal
import io.bluetape4k.clinic.appointment.api.security.TestJwtProvider
import io.bluetape4k.clinic.appointment.api.security.JwtSecurityProperties
import io.bluetape4k.clinic.appointment.api.security.JwtTokenParser
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.repository.TenantGroupRepository
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

class TenantContextFilterTest {

    private val filter = TenantContextFilter(
        TenantGroupRepository(),
        JwtTokenParser(
            JwtSecurityProperties(
                secret = TestJwtProvider.secret,
                issuer = TestJwtProvider.issuer,
            )
        )
    )

    @BeforeEach
    fun setup() {
        SecurityContextHolder.clearContext()
        TenantContext.clear()

        Database.connect(
            url = "jdbc:h2:mem:tenant-context-${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )

        transaction {
            SchemaUtils.create(TenantGroups)
            TenantGroups.insert {
                it[id] = EntityID(1L, TenantGroups)
                it[tenantCode] = "tenant-a"
                it[displayName] = "Tenant A"
                it[active] = true
            }
        }
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
        TenantContext.clear()
    }

    @Test
    fun `authenticated known tenant installs context during chain and clears after`() {
        val principal = SchedulingUserPrincipal(
            userId = "user-1",
            clinicId = 1L,
            roles = listOf(SchedulingRole.ADMIN),
            allowedTenants = listOf("tenant-a"),
        )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, principal.authorities)

        val request = tenantRequest("/api/tenant-a/clinics")
        val response = MockHttpServletResponse()
        val chain = CapturingFilterChain {
            TenantContext.requireCurrent().tenantCode shouldBeEqualTo "tenant-a"
        }

        filter.doFilter(request, response, chain)

        chain.called.shouldBeTrue()
        response.status shouldBeEqualTo HttpStatus.OK.value()
        TenantContext.current().shouldBeNull()
    }

    @Test
    fun `authenticated unknown tenant returns not found without calling chain`() {
        val principal = SchedulingUserPrincipal(
            userId = "user-1",
            clinicId = 1L,
            roles = listOf(SchedulingRole.ADMIN),
            allowedTenants = listOf("tenant-a"),
        )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, principal.authorities)

        val response = MockHttpServletResponse()
        val chain = CapturingFilterChain()

        filter.doFilter(tenantRequest("/api/missing/clinics"), response, chain)

        chain.called shouldBeEqualTo false
        response.status shouldBeEqualTo HttpStatus.NOT_FOUND.value()
        TenantContext.current().shouldBeNull()
    }

    @Test
    fun `authenticated known tenant outside allowed tenants returns forbidden without calling chain`() {
        val principal = SchedulingUserPrincipal(
            userId = "user-1",
            clinicId = 1L,
            roles = listOf(SchedulingRole.ADMIN),
            allowedTenants = listOf("tenant-b"),
        )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, principal.authorities)

        val response = MockHttpServletResponse()
        val chain = CapturingFilterChain()

        filter.doFilter(tenantRequest("/api/tenant-a/clinics"), response, chain)

        chain.called shouldBeEqualTo false
        response.status shouldBeEqualTo HttpStatus.FORBIDDEN.value()
        TenantContext.current().shouldBeNull()
    }

    @Test
    fun `unauthenticated tenant path skips lookup so authorization can return unauthorized`() {
        val response = MockHttpServletResponse()
        val chain = CapturingFilterChain()

        filter.doFilter(tenantRequest("/api/missing/clinics"), response, chain)

        chain.called.shouldBeTrue()
        response.status shouldBeEqualTo HttpStatus.OK.value()
        TenantContext.current().shouldBeNull()
    }

    private fun tenantRequest(path: String): MockHttpServletRequest =
        MockHttpServletRequest("GET", path).apply {
            servletPath = path
        }

    private class CapturingFilterChain(
        private val block: () -> Unit = {},
    ) : FilterChain {
        var called: Boolean = false

        override fun doFilter(request: ServletRequest, response: ServletResponse) {
            called = true
            block()
        }
    }
}
