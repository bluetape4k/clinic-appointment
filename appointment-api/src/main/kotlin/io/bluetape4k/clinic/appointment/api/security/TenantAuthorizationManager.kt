package io.bluetape4k.clinic.appointment.api.security

import io.bluetape4k.clinic.appointment.api.tenant.TenantPathResolver
import org.springframework.security.authorization.AuthorizationDecision
import org.springframework.security.authorization.AuthorizationManager
import org.springframework.security.authorization.AuthorizationResult
import org.springframework.security.core.Authentication
import org.springframework.security.web.access.intercept.RequestAuthorizationContext
import java.util.function.Supplier

/**
 * Authorizes a request when the URL tenant code is present in the JWT
 * `allowedTenants` claim.
 */
class TenantAuthorizationManager : AuthorizationManager<RequestAuthorizationContext> {

    override fun authorize(
        authentication: Supplier<out Authentication>,
        context: RequestAuthorizationContext,
    ): AuthorizationResult {
        val tenantCode = context.variables["tenantCode"]
            ?: TenantPathResolver.resolve(context.request)
            ?: return AuthorizationDecision(false)

        val principal = authentication.get().principal as? SchedulingUserPrincipal
            ?: return AuthorizationDecision(false)

        return AuthorizationDecision(tenantCode in principal.allowedTenants)
    }
}
