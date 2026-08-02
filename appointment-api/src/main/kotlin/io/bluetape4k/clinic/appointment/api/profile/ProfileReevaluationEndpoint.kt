package io.bluetape4k.clinic.appointment.api.profile

import io.bluetape4k.clinic.appointment.api.security.SchedulingUserPrincipal
import kotlinx.coroutines.reactor.mono
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import reactor.core.publisher.Mono
import java.time.Instant

/**
 * 일반 업무 API와 분리된 프로필 재평가 운영 endpoint입니다.
 */
@Endpoint(id = "profileReevaluation")
class ProfileReevaluationEndpoint(
    private val adminService: ProfileReevaluationAdminService,
    private val actorResolver: ProfileReevaluationAdminActorResolver =
        ProfileReevaluationAdminActorResolver(),
) {
    @ReadOperation
    fun status(): Mono<ProfileReevaluationOperationalSnapshot> =
        mono { adminService.snapshot() }

    @WriteOperation
    fun redrive(
        action: ProfileReevaluationAdminAction,
        reason: String,
        idempotencyKey: String,
        tenantGroupId: Long? = null,
        clinicId: Long? = null,
        targetRevision: Long? = null,
        limit: Int = 50,
    ): Mono<ProfileReevaluationAdminResult> {
        val scope =
            ProfileReevaluationAdminScope(
                tenantGroupId = tenantGroupId,
                clinicId = clinicId,
                targetRevision = targetRevision,
            )
        val command =
            ProfileReevaluationAdminCommand(
                action = action,
                actor = actorResolver.resolve(scope),
                reason = reason,
                idempotencyKey = idempotencyKey,
                scope = scope,
                limit = limit,
            )
        return mono {
            adminService.redrive(
                command,
            )
        }
    }
}

/**
 * 운영 mutation의 감사 주체를 검증된 Spring Security token에서만 가져옵니다.
 */
class ProfileReevaluationAdminActorResolver {
    fun resolve(scope: ProfileReevaluationAdminScope): String {
        val principal =
            SecurityContextHolder.getContext().authentication
                ?.takeIf(Authentication::isAuthenticated)
                ?.principal as? SchedulingUserPrincipal
                ?: throw AccessDeniedException("Authenticated scheduling principal is required")
        if (
            principal.issuer.isBlank() ||
            principal.tokenId.isBlank() ||
            principal.authenticatedAt == Instant.EPOCH
        ) {
            throw AccessDeniedException("Authentication evidence is incomplete")
        }
        val clinicId = scope.clinicId
        if (
            scope.tenantGroupId == null ||
            clinicId == null ||
            clinicId !in principal.allowedClinicIds
        ) {
            throw AccessDeniedException("Profile reevaluation redrive requires an allowed clinic scope")
        }
        return principal.userId
    }
}

internal const val PROFILE_REEVALUATION_OPERATE_SCOPE = "profile-reevaluation:operate"
