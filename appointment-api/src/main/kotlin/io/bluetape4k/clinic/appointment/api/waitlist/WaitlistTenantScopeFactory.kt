package io.bluetape4k.clinic.appointment.api.waitlist

import io.bluetape4k.clinic.appointment.api.security.ActorContextResolver
import io.bluetape4k.clinic.appointment.api.security.CorrelationIdFilter
import io.bluetape4k.clinic.appointment.api.tenant.TenantClinicAccessChecker
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.Authentication

class WaitlistTenantScopeFactory(
    private val accessChecker: TenantClinicAccessChecker,
    private val actorContextResolver: ActorContextResolver,
) {
    fun resolve(
        tenantCode: String,
        clinicId: Long,
        authentication: Authentication?,
        request: HttpServletRequest,
    ): WaitlistTenantScope {
        val tenant = accessChecker.verifyClinic(tenantCode, clinicId)
        val correlationId = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE) as? String ?: "api"
        val actor = actorContextResolver.resolve(authentication, tenantCode, clinicId, correlationId)
        return WaitlistTenantScope(
            tenantGroupId = tenant.id,
            tenantCode = tenantCode,
            clinicId = clinicId,
            actor = actor,
            correlationId = correlationId,
        )
    }
}
