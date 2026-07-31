package io.bluetape4k.clinic.appointment.api.notification

import io.bluetape4k.clinic.appointment.api.security.ActorContext
import io.bluetape4k.clinic.appointment.api.service.ResolvedAppointmentPlanAccess
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import java.time.Clock
import java.time.Instant

/**
 * 예약 진입점의 회원 참조를 검증된 [MemberId]로 변환하는 경계입니다.
 *
 * legacy 요청은 회원 ID 하나만 입력으로 받습니다. v2 요청은 request body에 회원
 * ID를 추가하지 않고 인증 주체와 구매 Plan의 보호된 참조를 회원 서비스가 해석합니다.
 */
interface LegacyAppointmentMemberResolver {
    fun resolveLegacy(
        tenantGroupId: Long,
        clinicId: Long,
        requested: MemberId?,
    ): MemberResolution
}

internal interface AppointmentMemberResolver : LegacyAppointmentMemberResolver {
    fun resolvePlan(
        actor: ActorContext,
        access: ResolvedAppointmentPlanAccess,
    ): MemberId
}

sealed interface MemberResolution {
    data class Resolved(val memberId: MemberId) : MemberResolution

    data object LegacyMissing : MemberResolution
}

/**
 * 회원 서비스가 반환할 수 있는 닫힌 결과 집합입니다.
 */
internal sealed interface MemberDirectoryResult {
    data class Resolved(val memberId: MemberId) : MemberDirectoryResult

    data object NotFound : MemberDirectoryResult

    data object ScopeMismatch : MemberDirectoryResult

    data object Ambiguous : MemberDirectoryResult

    data object Unavailable : MemberDirectoryResult
}

/**
 * legacy 회원 ID 검증 요청입니다. 이름과 전화번호는 의도적으로 포함하지 않습니다.
 */
internal data class MemberDirectoryRequest(
    val tenantGroupId: Long,
    val clinicId: Long,
    val memberId: MemberId,
)

/**
 * 인증 주체와 구매 Plan의 보호된 참조를 결합한 v2 회원 해석 요청입니다.
 */
internal data class MemberPlanDirectoryRequest(
    val tenantGroupId: Long,
    val clinicId: Long,
    val patientSubjectId: String?,
    val patientReferenceFingerprint: String,
)

/**
 * 실제 회원 서비스를 연결하는 어댑터 경계입니다.
 */
internal interface AppointmentMemberDirectory {
    fun resolveMember(request: MemberDirectoryRequest): MemberDirectoryResult

    fun resolvePlan(request: MemberPlanDirectoryRequest): MemberDirectoryResult
}

/**
 * 회원 서비스 어댑터가 없는 배포에서 신규 예약을 안전하게 막는 기본 구현입니다.
 */
internal object FailClosedAppointmentMemberDirectory : AppointmentMemberDirectory {
    override fun resolveMember(request: MemberDirectoryRequest): MemberDirectoryResult =
        MemberDirectoryResult.Unavailable

    override fun resolvePlan(request: MemberPlanDirectoryRequest): MemberDirectoryResult =
        MemberDirectoryResult.Unavailable
}

internal class DefaultAppointmentMemberResolver(
    private val directory: AppointmentMemberDirectory,
    private val properties: NotificationMemberIdProperties,
    private val clock: Clock,
) : AppointmentMemberResolver {

    override fun resolveLegacy(
        tenantGroupId: Long,
        clinicId: Long,
        requested: MemberId?,
    ): MemberResolution {
        if (requested == null) {
            return when (properties.modeFor(tenantGroupId, clinicId, Instant.now(clock))) {
                MemberIdEnforcementMode.OBSERVE -> MemberResolution.LegacyMissing
                MemberIdEnforcementMode.ENFORCE ->
                    throw NotificationMemberApiException(NotificationMemberApiError.MEMBER_ID_REQUIRED)
            }
        }
        return when (
            val result = directory.resolveMember(
                MemberDirectoryRequest(
                    tenantGroupId = tenantGroupId,
                    clinicId = clinicId,
                    memberId = requested,
                ),
            )
        ) {
            is MemberDirectoryResult.Resolved -> MemberResolution.Resolved(result.memberId)
            else -> throw result.toApiException()
        }
    }

    override fun resolvePlan(
        actor: ActorContext,
        access: ResolvedAppointmentPlanAccess,
    ): MemberId =
        when (
            val result = directory.resolvePlan(
                MemberPlanDirectoryRequest(
                    tenantGroupId = access.tenantGroupId,
                    clinicId = access.clinicId,
                    patientSubjectId = actor.patientSubjectId,
                    patientReferenceFingerprint = access.plan.patientReferenceFingerprint,
                ),
            )
        ) {
            is MemberDirectoryResult.Resolved -> result.memberId
            else -> throw result.toApiException()
        }
}

private fun MemberDirectoryResult.toApiException(): NotificationMemberApiException =
    NotificationMemberApiException(
        when (this) {
            MemberDirectoryResult.NotFound -> NotificationMemberApiError.MEMBER_NOT_FOUND
            MemberDirectoryResult.ScopeMismatch -> NotificationMemberApiError.MEMBER_SCOPE_MISMATCH
            MemberDirectoryResult.Ambiguous -> NotificationMemberApiError.MEMBER_REFERENCE_AMBIGUOUS
            MemberDirectoryResult.Unavailable -> NotificationMemberApiError.MEMBER_DIRECTORY_UNAVAILABLE
            is MemberDirectoryResult.Resolved -> error("resolved member cannot be converted to an API error")
        },
    )
