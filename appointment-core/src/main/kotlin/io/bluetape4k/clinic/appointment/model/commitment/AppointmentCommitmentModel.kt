package io.bluetape4k.clinic.appointment.model.commitment

import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable
import java.time.Instant

/**
 * 한 방문 예약의 일정 합의 상태를 나타내는 불변 모델입니다.
 *
 * 진료의 접수·진행·완료 상태와 일정에 대한 고객·병원의 합의 상태는 독립된 축입니다.
 * 이 모델은 후자만 표현하며, 이미 확정된 제안을 새 제안이 발행됐다는 이유로 해제하지
 * 않습니다.
 *
 * @property appointmentId 이 약속이 귀속되는 양수 방문 예약 식별자입니다. 여러 회차를
 * 묶은 상품 전체가 아니라 실제로 한 번 방문하는 [Appointment] 하나를 가리킵니다.
 * @property status 현재 합의 수명주기입니다. 고객 요청은 [AppointmentCommitmentStatus.PROPOSED]로
 * 시작하고 고객 동의와 병원 승인이 모두 충족된 제안만
 * [AppointmentCommitmentStatus.CONFIRMED]가 될 수 있습니다.
 * @property origin 최초 일정을 제안한 주체입니다. 권한 판정의 대체물이 아니라 감사와
 * 정책 분기에 사용하는 안정적인 분류입니다.
 * @property confirmedProposalId 현재 자원 점유와 함께 유효한 제안 식별자입니다.
 * [status]가 `CONFIRMED`일 때만 양수여야 합니다. 변경 제안이 대기·거부·실패해도 이
 * 값은 기존 확정 제안을 계속 가리킵니다.
 * @property effectivePolicySnapshotId 이 합의 판단에 사용한 양수 병원 정책 스냅샷
 * 식별자입니다. 현재 정책을 다시 조회해 과거 결정을 재해석해서는 안 됩니다.
 * @property version repository의 compare-and-set에 사용하는 양수 낙관적 잠금
 * version입니다. 업무 revision이나 proposal revision과 혼용하지 않습니다.
 */
data class AppointmentCommitment(
    val appointmentId: Long,
    val status: AppointmentCommitmentStatus,
    val origin: AppointmentOrigin,
    val confirmedProposalId: Long?,
    val effectivePolicySnapshotId: Long,
    val version: Long,
) : Serializable {

    init {
        appointmentId.requirePositiveNumber("appointmentId")
        effectivePolicySnapshotId.requirePositiveNumber("effectivePolicySnapshotId")
        version.requirePositiveNumber("version")
        confirmedProposalId?.requirePositiveNumber("confirmedProposalId")
        require((status == AppointmentCommitmentStatus.CONFIRMED) == (confirmedProposalId != null)) {
            "confirmedProposalId must exist exactly when status is CONFIRMED"
        }
    }

    /**
     * 정확한 [proposal]과 결합된 수락 [consent]로 이 약속을 확정한 새 값을 반환합니다.
     *
     * 이미 확정된 약속을 이 함수로 덮어쓰지 않습니다. 확정 일정 변경은 기존 확정
     * proposal과 allocation을 보호하는 별도의 원자 교체 command에서 처리해야 합니다.
     *
     * @throws IllegalArgumentException 제안이 다른 예약에 속하거나, 동의가 제안 ID,
     * revision, hash에 정확히 결합되지 않았거나, 현재 상태가 확정 가능한 상태가 아니면
     * 발생합니다.
     */
    fun confirm(
        proposalId: Long,
        proposal: AppointmentProposalDraft,
        proposalHash: String,
        consent: ConsentDecision,
    ): AppointmentCommitment {
        proposalId.requirePositiveNumber("proposalId")
        require(appointmentId == proposal.appointmentId) {
            "proposal appointmentId must match commitment appointmentId"
        }
        require(status == AppointmentCommitmentStatus.PROPOSED || status == AppointmentCommitmentStatus.HELD) {
            "only PROPOSED or HELD commitment can be confirmed"
        }
        require(consent.acceptsProposal(proposalId, proposal.revision, proposalHash)) {
            "accepted consent must match the exact proposal id, revision, and hash"
        }
        return copy(
            status = AppointmentCommitmentStatus.CONFIRMED,
            confirmedProposalId = proposalId,
            version = version + 1,
        )
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 일정 합의 수명주기입니다.
 */
enum class AppointmentCommitmentStatus {
    /** 고객 또는 병원이 일정을 제안했지만 자원과 고객 합의가 아직 확정되지 않았습니다. */
    PROPOSED,

    /** 제한된 시간 동안 자원 선점이 유지되지만 최종 확정 조건은 아직 남아 있습니다. */
    HELD,

    /** 정확한 제안과 자원 점유가 원자적으로 결합된 유효한 일정 약속입니다. */
    CONFIRMED,

    /** 제안 또는 hold의 유효 시간이 지나 더 이상 수락할 수 없습니다. */
    EXPIRED,

    /** 명시적인 업무 사유로 약속 후보가 취소되었습니다. */
    CANCELLED,
}

/**
 * 최초 일정 제안의 출처입니다.
 */
enum class AppointmentOrigin {
    /** 고객이 희망 일정을 제출했습니다. 관리자 승인 전에는 가예약입니다. */
    PATIENT,

    /** 병원 관리자가 일정을 제안하거나 정책이 허용한 직접 확정을 시작했습니다. */
    CLINIC,

    /** 예약 규칙 또는 외부 운영 사건에 따라 시스템이 제안을 만들었습니다. */
    SYSTEM,
}

/**
 * 아직 영속화되지 않은 방문 일정 제안입니다.
 *
 * @property appointmentId 제안 대상 방문 예약의 양수 식별자입니다.
 * @property revision 동일 commitment 안에서 1부터 단조 증가하는 proposal revision입니다.
 * 이미 발행한 revision은 수정하지 않고 변경 시 새 revision을 만듭니다.
 * @property startsAt 제안된 전체 방문 점유 구간의 UTC 시작 시각입니다.
 * @property endsAt [startsAt]보다 뒤인 UTC 종료 시각입니다. 개별 항목 시간은 [items]에
 * 별도로 보존합니다.
 * @property items 이 방문에서 수행을 시도할 계획-linked 세부 진료 목록입니다.
 * @property allocations 확정 시 함께 점유해야 하는 담당자·장비·공간·수용량 목록입니다.
 * @property policySnapshotId 이 제안 계산에 사용한 양수 불변 정책 스냅샷 식별자입니다.
 * @property supersedesProposalId 이 제안이 대체하려는 이전 제안 식별자입니다. 이 값이
 * 있어도 이전 확정 제안과 allocation은 새 제안 확정 전까지 해제하지 않습니다.
 */
data class AppointmentProposalDraft(
    val appointmentId: Long,
    val revision: Long,
    val startsAt: Instant,
    val endsAt: Instant,
    val items: List<AppointmentItemDraft>,
    val allocations: List<ResourceAllocationDraft>,
    val policySnapshotId: Long,
    val supersedesProposalId: Long?,
) : Serializable {

    init {
        appointmentId.requirePositiveNumber("appointmentId")
        revision.requirePositiveNumber("revision")
        policySnapshotId.requirePositiveNumber("policySnapshotId")
        supersedesProposalId?.requirePositiveNumber("supersedesProposalId")
        require(startsAt < endsAt) { "startsAt must be before endsAt" }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}
