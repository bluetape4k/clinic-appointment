package io.bluetape4k.clinic.appointment.api.security

import io.bluetape4k.clinic.appointment.model.policy.ActorRole
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.Authentication
import java.io.Serializable
import java.time.Instant

/**
 * 스케줄링 명령 하나에 대한 불변 authorization/audit context이다.
 *
 * correlation ID만 [CorrelationIdFilter]에서 오고, 나머지 모든 필드는 검증된
 * [SchedulingUserPrincipal]에서 온다. request DTO는 actor, tenant, clinic, patient,
 * assurance, token evidence를 덮어쓸 수 없다.
 *
 * @property actorId 안정적이고 신뢰된 Gateway subject. display name 또는 credential이 아니다.
 * @property actorType 인증된 주체의 기본 identity category.
 * @property roles 명령 평가에 사용하는 닫힌 role 집합.
 * @property scopes 길이가 제한된 OAuth-style capability 집합.
 * @property allowedTenantCodes Gateway가 허가한 정확한 tenant code 집합.
 * @property allowedClinicIds Gateway가 허가한 양수 clinic ID 집합. 비어 있으면 tenant-only
 * authority이며, 모든 clinic에 대한 무제한 권한이 아니다.
 * @property patientSubjectId patient actor에만 존재하는 안정적인 patient-domain subject.
 * @property assurance Gateway 인증 증적. 이 service는 MFA를 수행하거나 승격하지 않는다.
 * @property issuer 감사 목적으로 보존하는 검증된 JWT issuer.
 * @property tokenId 검증된 non-blank JWT `jti`. idempotency key가 아니다.
 * @property authenticatedAt JWT `auth_time`의 UTC instant.
 * @property correlationId 길이가 제한된 request trace ID. causation event ID가 아니다.
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
 * 검증된 Spring authentication을 path-scoped [ActorContext]로 변환한다.
 *
 * upstream authorization manager가 이미 실행되었더라도 tenant/clinic membership을 여기서
 * 다시 확인한다. 이렇게 해야 controller mapping 실수나 다른 scope 권한을 가진 principal로
 * application service가 직접 호출되는 경우에도 안전하게 실패한다.
 */
class ActorContextResolver {

    /**
     * tenant 또는 clinic 정책 명령 하나에 사용할 actor를 해석한다.
     *
     * @param authentication principal이 [SchedulingUserPrincipal]이어야 하는 인증된 Spring token.
     * @param tenantCode 요청 경로의 정확한 tenant code.
     * @param clinicId clinic scope이면 요청 경로의 양수 clinic ID, tenant-default 명령이면 `null`.
     * @param correlationId 검증된 request correlation ID.
     * @throws AccessDeniedException authentication 또는 path scope가 허가되지 않은 경우.
     * 메시지에는 token 또는 claim 값을 포함하지 않는다.
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
