package io.bluetape4k.clinic.appointment.api.security

import io.bluetape4k.clinic.appointment.model.policy.ActorRole
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.Authentication
import java.io.Serializable
import java.time.Instant

/**
 * Immutable authorization and audit context for one scheduling command.
 *
 * Every field originates from a verified [SchedulingUserPrincipal] except the
 * correlation ID, which comes from [CorrelationIdFilter]. Request DTOs cannot
 * override actor, tenant, clinic, patient, assurance, or token evidence.
 *
 * @property actorId Stable trusted Gateway subject, never a display name or
 * credential.
 * @property actorType Primary authenticated identity category.
 * @property roles Closed roles evaluated for the command.
 * @property scopes Bounded OAuth-style capabilities.
 * @property allowedTenantCodes Exact Gateway-authorized tenant codes.
 * @property allowedClinicIds Positive Gateway-authorized clinic IDs. Empty
 * means tenant-only authority rather than unrestricted clinic authority.
 * @property patientSubjectId Stable patient-domain subject only for patients.
 * @property assurance Gateway authentication evidence; this service does not
 * perform or upgrade MFA.
 * @property issuer Verified JWT issuer retained for audit.
 * @property tokenId Verified non-blank JWT `jti`, not an idempotency key.
 * @property authenticatedAt UTC JWT `auth_time`.
 * @property correlationId Bounded request trace ID, not a causation event ID.
 */
data class ActorContext(
    val actorId: String,
    val actorType: ActorType,
    val roles: Set<ActorRole>,
    val scopes: Set<String>,
    val allowedTenantCodes: Set<String>,
    val allowedClinicIds: Set<Long>,
    val patientSubjectId: String?,
    val assurance: AuthenticationAssurance,
    val issuer: String,
    val tokenId: String,
    val authenticatedAt: Instant,
    val correlationId: String,
) : Serializable {
    private companion object {
        const val serialVersionUID = 1L
    }
}

/**
 * Resolves a verified Spring authentication into a path-scoped [ActorContext].
 *
 * Tenant and clinic membership are checked again here even if an upstream
 * authorization manager already ran. This makes application services safe
 * against controller mapping mistakes and direct invocation with a principal
 * authorized for a different scope.
 */
class ActorContextResolver {

    /**
     * Resolves the actor for one tenant or clinic policy command.
     *
     * @param authentication Authenticated Spring token whose principal must be
     * [SchedulingUserPrincipal].
     * @param tenantCode Exact path tenant code.
     * @param clinicId Positive path clinic ID for clinic scope, or `null` for a
     * tenant-default command.
     * @param correlationId Validated request correlation ID.
     * @throws AccessDeniedException when authentication or path scope is not
     * authorized. No token or claim value is included in the message.
     */
    fun resolve(
        authentication: Authentication?,
        tenantCode: String,
        clinicId: Long?,
        correlationId: String,
    ): ActorContext {
        val principal = authentication
            ?.takeIf(Authentication::isAuthenticated)
            ?.principal as? SchedulingUserPrincipal
            ?: throw AccessDeniedException("Authenticated scheduling principal is required")
        if (tenantCode !in principal.allowedTenants) {
            throw AccessDeniedException("Tenant scope is not authorized")
        }
        if (clinicId != null && clinicId !in principal.allowedClinicIds) {
            throw AccessDeniedException("Clinic scope is not authorized")
        }
        if (principal.issuer.isBlank() || principal.tokenId.isBlank() || principal.authenticatedAt == Instant.EPOCH) {
            throw AccessDeniedException("Authentication evidence is incomplete")
        }
        return ActorContext(
            actorId = principal.userId,
            actorType = principal.actorType,
            roles = principal.roles.mapTo(linkedSetOf(), ActorRole::valueOf),
            scopes = principal.scopes.toSet(),
            allowedTenantCodes = principal.allowedTenants.toSet(),
            allowedClinicIds = principal.allowedClinicIds.toSet(),
            patientSubjectId = principal.patientSubjectId,
            assurance = principal.assurance,
            issuer = principal.issuer,
            tokenId = principal.tokenId,
            authenticatedAt = principal.authenticatedAt,
            correlationId = correlationId,
        )
    }
}
