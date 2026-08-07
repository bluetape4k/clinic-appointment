package io.bluetape4k.clinic.appointment.api.security

import io.bluetape4k.clinic.appointment.api.tenant.TenantPathResolver
import io.bluetape4k.clinic.appointment.api.tenant.TenantCodeRules
import org.springframework.security.authorization.AuthorizationDecision
import org.springframework.security.authorization.AuthorizationManager
import org.springframework.security.authorization.AuthorizationResult
import org.springframework.security.core.Authentication
import org.springframework.security.web.access.intercept.RequestAuthorizationContext
import java.util.function.Supplier

/**
 * URL tenant code가 JWT의 `allowedTenants` claim에 포함될 때 요청을 허가합니다.
 */
class TenantAuthorizationManager : AuthorizationManager<RequestAuthorizationContext> {

    override fun authorize(
        authentication: Supplier<out Authentication>,
        context: RequestAuthorizationContext,
    ): AuthorizationResult {
        val tenantCode = context.variables["tenantCode"]
            ?: TenantPathResolver.resolve(context.request)
            ?: return AuthorizationDecision(false)
        if (!TenantCodeRules.isCanonical(tenantCode)) {
            return AuthorizationDecision(false)
        }

        val principal = authentication.get().principal as? SchedulingUserPrincipal
            ?: return AuthorizationDecision(false)

        return AuthorizationDecision(tenantCode in principal.allowedTenants)
    }
}
