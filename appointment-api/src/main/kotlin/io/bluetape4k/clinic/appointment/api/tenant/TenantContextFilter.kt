package io.bluetape4k.clinic.appointment.api.tenant

import io.bluetape4k.clinic.appointment.api.config.PlanFoundationError
import io.bluetape4k.clinic.appointment.api.config.SchedulingPolicyErrorCode
import io.bluetape4k.clinic.appointment.api.config.isSchedulingPolicyRequestPath
import io.bluetape4k.clinic.appointment.api.security.JwtTokenParser
import io.bluetape4k.clinic.appointment.api.security.SchedulingUserPrincipal
import io.bluetape4k.clinic.appointment.api.security.SecurityErrorResponseWriter
import io.bluetape4k.clinic.appointment.repository.TenantGroupRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Resolves the tenant path segment after JWT authentication and exposes it
 * through [TenantContext] for downstream request handling.
 */
class TenantContextFilter(
    private val tenantGroupRepository: TenantGroupRepository,
    private val jwtTokenParser: JwtTokenParser,
) : OncePerRequestFilter() {

    companion object : KLogging()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val tenantCode = TenantPathResolver.resolve(request)
        if (tenantCode == null) {
            filterChain.doFilter(request, response)
            return
        }

        val bearerToken = request.extractBearerToken()
        val principal = SecurityContextHolder.getContext().authentication?.principal as? SchedulingUserPrincipal
            ?: bearerToken?.let(jwtTokenParser::parse)

        if (principal == null) {
            filterChain.doFilter(request, response)
            return
        }

        val tenantInfo = transaction {
            tenantGroupRepository.findActiveByCode(tenantCode)?.let(TenantInfo::from)
        }

        if (tenantInfo == null) {
            if (request.isSchedulingPolicyRequest()) {
                SecurityErrorResponseWriter.write(
                    response,
                    SchedulingPolicyErrorCode.POLICY_RESOURCE_NOT_FOUND,
                )
            } else {
                SecurityErrorResponseWriter.write(response, PlanFoundationError.RESOURCE_NOT_FOUND)
            }
            return
        }

        if (tenantCode !in principal.allowedTenants) {
            if (request.isSchedulingPolicyRequest()) {
                SecurityErrorResponseWriter.write(
                    response,
                    SchedulingPolicyErrorCode.POLICY_ACTOR_FORBIDDEN,
                )
            } else {
                SecurityErrorResponseWriter.write(response, PlanFoundationError.FORBIDDEN)
            }
            return
        }

        log.debug { "Tenant resolved: tenantCode=${tenantInfo.tenantCode}, tenantGroupId=${tenantInfo.id}" }
        TenantContext.withTenant(tenantInfo) {
            filterChain.doFilter(request, response)
        }
    }

    private fun HttpServletRequest.extractBearerToken(): String? =
        getHeader("Authorization")
            ?.takeIf { it.startsWith("Bearer ") }
            ?.substring("Bearer ".length)

    /** tenant filter가 controller 전에도 policy 전용 안정 오류 계약을 선택하게 한다. */
    private fun HttpServletRequest.isSchedulingPolicyRequest(): Boolean =
        isSchedulingPolicyRequestPath(requestURI)
}
