package io.bluetape4k.clinic.appointment.messaging

/** 인증된 caller와 replay scope를 연결하는 production security adapter입니다. */
data class AppointmentReplayActor(
    val subject: String,
    val tenantGroupIds: Set<Long>,
    val roles: Set<String>,
    /** tenant별 clinic allow-list입니다. 빈 set은 어떤 clinic도 승인하지 않습니다. */
    val clinicIdsByTenant: Map<Long, Set<Long>> = emptyMap(),
) {
    init {
        require(subject.matches(ACTOR_PATTERN)) { "replay actor subject is not canonical" }
        require(tenantGroupIds.isNotEmpty() && tenantGroupIds.all { it > 0 }) {
            "replay actor must have a positive tenant scope"
        }
        require(clinicIdsByTenant.keys.all { it > 0 } && clinicIdsByTenant.values.flatten().all { it > 0 }) {
            "replay actor must have a positive clinic scope"
        }
    }
}

fun interface AppointmentReplayAuthorizer {
    fun authorize(actor: AppointmentReplayActor, request: AppointmentReplayRequest)
}

/**
 * HTTP/security-context adapter가 넘긴 actor가 승인자·tenant·role을 모두 만족해야 합니다.
 * controller는 이 authorizer를 우회해 request.approver를 직접 신뢰해서는 안 됩니다.
 */
class TenantScopedAppointmentReplayAuthorizer(
    private val requiredRole: String = REPLAY_OPERATOR_ROLE,
) : AppointmentReplayAuthorizer {
    override fun authorize(actor: AppointmentReplayActor, request: AppointmentReplayRequest) {
        require(actor.subject == request.approver) {
            "replay actor must match the approved subject"
        }
        require(request.tenantGroupId in actor.tenantGroupIds) {
            "replay actor is not authorized for the requested tenant"
        }
        require(request.clinicId in actor.clinicIdsByTenant[request.tenantGroupId].orEmpty()) {
            "replay actor is not authorized for the requested clinic"
        }
        require(requiredRole in actor.roles) {
            "replay actor lacks the required replay role"
        }
    }

    companion object {
        const val REPLAY_OPERATOR_ROLE = "APPOINTMENT_REPLAY_OPERATOR"
    }
}

class AppointmentReplayAuthorizationException(message: String) : RuntimeException(message)

private val ACTOR_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
