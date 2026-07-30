package io.bluetape4k.clinic.appointment.api.profile

import kotlinx.coroutines.runBlocking
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation

/**
 * 일반 업무 API와 분리된 프로필 재평가 운영 endpoint입니다.
 */
@Endpoint(id = "profileReevaluation")
class ProfileReevaluationEndpoint(
    private val adminService: ProfileReevaluationAdminService,
) {
    @ReadOperation
    fun status(): ProfileReevaluationOperationalSnapshot =
        runBlocking { adminService.snapshot() }

    @WriteOperation
    fun redrive(
        action: ProfileReevaluationAdminAction,
        actor: String,
        reason: String,
        idempotencyKey: String,
        tenantGroupId: Long? = null,
        clinicId: Long? = null,
        targetRevision: Long? = null,
        limit: Int = 50,
    ): ProfileReevaluationAdminResult =
        runBlocking {
            adminService.redrive(
                ProfileReevaluationAdminCommand(
                    action = action,
                    actor = actor,
                    reason = reason,
                    idempotencyKey = idempotencyKey,
                    scope = ProfileReevaluationAdminScope(
                        tenantGroupId = tenantGroupId,
                        clinicId = clinicId,
                        targetRevision = targetRevision,
                    ),
                    limit = limit,
                ),
            )
        }
}
