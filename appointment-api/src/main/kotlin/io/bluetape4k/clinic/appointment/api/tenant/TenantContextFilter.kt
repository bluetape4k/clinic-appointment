package io.bluetape4k.clinic.appointment.api.tenant

import io.bluetape4k.clinic.appointment.api.config.PlanFoundationError
import io.bluetape4k.clinic.appointment.api.config.SchedulingPolicyErrorCode
import io.bluetape4k.clinic.appointment.api.config.isSchedulingPolicyRequestPath
import io.bluetape4k.clinic.appointment.api.config.isPatientCancellationHistoryRequestPath
import io.bluetape4k.clinic.appointment.api.service.PatientHistoryApiError
import io.bluetape4k.clinic.appointment.api.security.CorrelationIdFilter
import io.bluetape4k.clinic.appointment.api.security.JwtTokenParser
import io.bluetape4k.clinic.appointment.api.security.SchedulingUserPrincipal
import io.bluetape4k.clinic.appointment.api.security.SecurityErrorResponseWriter
import io.bluetape4k.clinic.appointment.repository.TenantGroupRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * JWT 인증 후 tenant 경로 segment를 해석하고, 이후 요청 처리를 위해
 * [TenantContext]로 노출합니다.
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
        // 오류나 비동기 dispatch 뒤에 servlet thread를 재사용할 수 있다. 이전 요청의
        // tenant를 절대 상속하지 않는다. 요청 경계가 정리를 담당하고, withTenant은
        // 단일 요청 안의 중첩 scope를 계속 보존한다.
        TenantContext.clear()
        try {
            val tenantCode = TenantPathResolver.resolve(request)
            if (tenantCode == null) {
                filterChain.doFilter(request, response)
                return
            }

            val bearerToken = request.extractBearerToken()
            val principal = SecurityContextHolder.getContext().authentication?.principal as? SchedulingUserPrincipal
                ?: bearerToken?.let(jwtTokenParser::parse)

            // 회원가입/login/CSRF bootstrap은 아직 principal이 없지만 tenant 자체가
            // active인지 확인한 뒤에만 controller로 전달한다. 반면 일반 tenant API는
            // 인증 filter가 401을 작성할 수 있도록 기존처럼 lookup을 생략한다.
            if (principal == null && !request.isPublicPatientAuthRequest()) {
                filterChain.doFilter(request, response)
                return
            }

            val tenantInfo = try {
                transaction {
                    tenantGroupRepository.findActiveByCode(tenantCode)?.let(TenantInfo::from)
                }
            } catch (_: Exception) {
                log.warn {
                    "Tenant lookup failed: correlation_id=${request.correlationIdForLog()}, tenant_code=$tenantCode"
                }
                if (request.isSchedulingPolicyRequest()) {
                    SecurityErrorResponseWriter.write(
                        response,
                        SchedulingPolicyErrorCode.POLICY_INTERNAL_ERROR,
                    )
                } else if (request.isPatientHistoryRequest()) {
                    SecurityErrorResponseWriter.write(response, PatientHistoryApiError.UNAVAILABLE)
                } else {
                    SecurityErrorResponseWriter.write(response, PlanFoundationError.INTERNAL_ERROR)
                }
                return
            }

            if (tenantInfo == null) {
                if (request.isSchedulingPolicyRequest()) {
                    SecurityErrorResponseWriter.write(
                        response,
                        SchedulingPolicyErrorCode.POLICY_RESOURCE_NOT_FOUND,
                    )
                } else if (request.isPatientHistoryRequest()) {
                    SecurityErrorResponseWriter.write(response, PatientHistoryApiError.TENANT_NOT_FOUND)
                } else {
                    SecurityErrorResponseWriter.write(response, PlanFoundationError.RESOURCE_NOT_FOUND)
                }
                return
            }

            if (principal != null && tenantCode !in principal.allowedTenants) {
                if (request.isSchedulingPolicyRequest()) {
                    SecurityErrorResponseWriter.write(
                        response,
                        SchedulingPolicyErrorCode.POLICY_ACTOR_FORBIDDEN,
                    )
                } else if (request.isPatientHistoryRequest()) {
                    SecurityErrorResponseWriter.write(response, PatientHistoryApiError.SCOPE_FORBIDDEN)
                } else {
                    SecurityErrorResponseWriter.write(response, PlanFoundationError.FORBIDDEN)
                }
                return
            }

            log.debug { "Tenant resolved: tenantCode=${tenantInfo.tenantCode}" }
            TenantContext.withTenant(tenantInfo) {
                filterChain.doFilter(request, response)
            }
        } finally {
            TenantContext.clear()
        }
    }

    private fun HttpServletRequest.extractBearerToken(): String? =
        getHeader("Authorization")
            ?.takeIf { it.startsWith("Bearer ") }
            ?.substring("Bearer ".length)

    private fun HttpServletRequest.isPublicPatientAuthRequest(): Boolean {
        val tenantCode = TenantPathResolver.resolve(this) ?: return false
        val path = requestURI.removePrefix(contextPath.orEmpty())
        val suffix = path.removePrefix("/api/$tenantCode/")
        return when (method) {
            "GET" -> suffix == "auth/csrf"
            "POST" -> suffix == "auth/register" || suffix == "auth/login"
            else -> false
        }
    }

    /** tenant filter가 controller 전에도 policy 전용 안정 오류 계약을 선택하게 한다. */
    private fun HttpServletRequest.isSchedulingPolicyRequest(): Boolean =
        isSchedulingPolicyRequestPath(requestURI)

    private fun HttpServletRequest.isPatientHistoryRequest(): Boolean =
        isPatientCancellationHistoryRequestPath(requestURI)

    private fun HttpServletRequest.correlationIdForLog(): String =
        (getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE) as? String)
            ?.takeIf { it.isNotBlank() }
            ?: "unknown"
}
