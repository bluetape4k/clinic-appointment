package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitmentStatus
import io.bluetape4k.clinic.appointment.model.profile.ProfileReevaluationTargets
import java.time.Duration

/**
 * 예약 상태별 재평가 처리 목표를 clinic, tenant, 플랫폼 순서로 해석합니다.
 */
object ProfileReevaluationTargetResolver {
    private val minimumHeldTarget: Duration = Duration.ofMinutes(1)
    private val maximumHeldTarget: Duration = Duration.ofMinutes(15)
    private val minimumProposedTarget: Duration = Duration.ofMinutes(5)
    private val maximumProposedTarget: Duration = Duration.ofMinutes(120)

    /**
     * 현재 합의 상태에 적용할 처리 목표를 반환합니다.
     *
     * @param status 재평가할 예약의 현재 일정 합의 상태입니다.
     * @param platform 시스템 환경설정에서 제공한 필수 기본값입니다.
     * @param tenant tenant 정책이 정의한 선택 값입니다.
     * @param clinic clinic 정책이 정의한 선택 값입니다.
     * @throws IllegalArgumentException 대상이 아닌 상태이거나, 선택된 값이 없거나,
     * 허용 범위를 벗어나면 발생합니다.
     */
    fun resolve(
        status: AppointmentCommitmentStatus,
        platform: ProfileReevaluationTargets,
        tenant: ProfileReevaluationTargets? = null,
        clinic: ProfileReevaluationTargets? = null,
    ): Duration {
        val target = when (status) {
            AppointmentCommitmentStatus.HELD ->
                clinic?.heldTarget ?: tenant?.heldTarget ?: platform.heldTarget
            AppointmentCommitmentStatus.PROPOSED ->
                clinic?.proposedTarget ?: tenant?.proposedTarget ?: platform.proposedTarget
            else -> throw IllegalArgumentException(
                "$status is not eligible for profile reevaluation",
            )
        }
        val resolved = requireNotNull(target) {
            "$status profile reevaluation target must be configured"
        }
        validate(status, resolved)
        return resolved
    }

    private fun validate(
        status: AppointmentCommitmentStatus,
        target: Duration,
    ) {
        val range = when (status) {
            AppointmentCommitmentStatus.HELD -> minimumHeldTarget..maximumHeldTarget
            AppointmentCommitmentStatus.PROPOSED -> minimumProposedTarget..maximumProposedTarget
            else -> error("profile reevaluation target validation requires an eligible status")
        }
        require(target in range) {
            "$status profile reevaluation target($target) is outside the allowed range"
        }
    }
}
