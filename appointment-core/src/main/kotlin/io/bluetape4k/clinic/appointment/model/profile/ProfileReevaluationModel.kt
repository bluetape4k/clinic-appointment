package io.bluetape4k.clinic.appointment.model.profile

import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitmentStatus
import java.io.Serializable
import java.time.Duration

/**
 * 프로필 변경에 따른 예약 재평가 작업의 저장 수명주기입니다.
 */
enum class ProfileReevaluationJobStatus {
    /** 처리 대기 중입니다. */
    PENDING,

    /** worker가 lease를 획득해 처리 중입니다. */
    RUNNING,

    /** 일시적 실패 후 다음 시도를 기다립니다. */
    RETRY_WAIT,

    /** 최신 revision의 모든 대상 예약 처리를 마쳤습니다. */
    COMPLETED,

    /** 더 최신 revision이 도착해 현재 작업을 더 이상 처리하지 않습니다. */
    STALE,

    /** 자동 복구 한도를 소진해 운영자 조치가 필요합니다. */
    FAILED,
}

/**
 * 예약 한 건에 대한 프로필 재평가 결과입니다.
 */
enum class ProfileReevaluationOutcomeType {
    /** 기존 제안을 새 제안으로 대체했습니다. */
    PROPOSAL_SUPERSEDED,

    /** 기존 선점이 여전히 유효해 그대로 유지했습니다. */
    HOLD_KEPT,

    /** 기존 선점을 새 선점으로 원자적으로 교체했습니다. */
    HOLD_REPLACED,

    /** 대체 후보가 없어 선점을 해제하고 제안 상태로 되돌렸습니다. */
    FALLBACK_TO_PROPOSED,

    /** 현재 합의 상태가 자동 재평가 대상이 아니어서 건너뛰었습니다. */
    SKIPPED_INELIGIBLE,

    /** 평가 입력이 이전과 같아 예약을 변경하지 않았습니다. */
    SKIPPED_UNCHANGED,
}

/**
 * 현재 일정 합의 상태가 프로필 변경에 따른 자동 재평가 대상인지 반환합니다.
 *
 * 확정 예약은 고객과 병원의 합의를 보호하기 위해 항상 `false`이며, 자동 재평가는
 * [AppointmentCommitmentStatus.PROPOSED]와 [AppointmentCommitmentStatus.HELD]에만
 * 허용됩니다.
 */
val AppointmentCommitmentStatus.isProfileReevaluationEligible: Boolean
    get() = this == AppointmentCommitmentStatus.PROPOSED ||
        this == AppointmentCommitmentStatus.HELD

/**
 * 예약 상태별 프로필 재평가 처리 목표 후보입니다.
 *
 * `null`은 해당 조직 수준에서 값을 정의하지 않았다는 뜻입니다. 실제 처리 목표는
 * clinic, tenant, 플랫폼 환경 기본값 순서로 해석하며, 플랫폼 값까지 없으면 오류로
 * 처리합니다.
 *
 * @property heldTarget 선점 예약을 재평가할 목표 시간입니다.
 * @property proposedTarget 제안 예약을 재평가할 목표 시간입니다.
 */
data class ProfileReevaluationTargets(
    val heldTarget: Duration? = null,
    val proposedTarget: Duration? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
