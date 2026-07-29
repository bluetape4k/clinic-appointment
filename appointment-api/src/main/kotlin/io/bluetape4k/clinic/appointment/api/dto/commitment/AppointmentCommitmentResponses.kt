package io.bluetape4k.clinic.appointment.api.dto.commitment

import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitmentStatus
import io.bluetape4k.clinic.appointment.model.dto.AppointmentCommitmentRecord
import io.bluetape4k.clinic.appointment.model.dto.AppointmentProposalRecord
import java.io.Serializable
import java.time.Instant

/**
 * 고객 가예약 생성 결과이다.
 *
 * @property appointmentId 이후 API path에서 사용하는 예약 식별자.
 * @property commitmentId v2 업무 상태를 소유하는 commitment 식별자.
 * @property proposalId 현재 제안된 불변 일정 식별자.
 * @property status 관리자 승인 전에는 `PROPOSED`이다.
 * @property version 다음 mutation의 `If-Match`에 사용할 aggregate version.
 * @property expiresAt 고객·병원이 proposal을 처리해야 하는 UTC 만료 시각.
 */
data class AppointmentProposalResponse(
    val appointmentId: Long,
    val commitmentId: Long,
    val proposalId: Long,
    val status: AppointmentCommitmentStatus,
    val version: Long,
    val expiresAt: Instant,
) : Serializable {
    private companion object {
        const val serialVersionUID = 1L
    }
}

/**
 * 확정·변경·거절 뒤 반환하는 commitment query projection이다.
 *
 * nullable legacy appointment column을 노출하지 않고 commitment와 proposal read model만
 * 사용한다.
 */
data class AppointmentCommitmentResponse(
    val appointmentId: Long,
    val commitmentId: Long,
    val status: AppointmentCommitmentStatus,
    val version: Long,
    val currentProposal: AppointmentProposalSummary,
    val confirmedProposalId: Long?,
) : Serializable {
    private companion object {
        const val serialVersionUID = 1L
    }
}

/**
 * 외부에 공개 가능한 불변 proposal 요약이다.
 *
 * 정책 snapshot ID, 내부 actor audit ref, 자원 allocation 세부사항은 노출하지 않는다.
 */
data class AppointmentProposalSummary(
    val proposalId: Long,
    val revision: Long,
    val startsAt: Instant,
    val endsAt: Instant,
    val expiresAt: Instant,
    val expired: Boolean,
    val representativeTreatmentName: String,
) : Serializable {
    private companion object {
        const val serialVersionUID = 1L
    }
}

internal fun AppointmentCommitmentRecord.toProposalResponse(
    proposal: AppointmentProposalRecord,
): AppointmentProposalResponse =
    AppointmentProposalResponse(
        appointmentId = appointmentId,
        commitmentId = id,
        proposalId = proposal.id,
        status = status,
        version = version,
        expiresAt = proposal.expiresAt,
    )

internal fun AppointmentCommitmentRecord.toResponse(
    proposal: AppointmentProposalRecord,
): AppointmentCommitmentResponse =
    AppointmentCommitmentResponse(
        appointmentId = appointmentId,
        commitmentId = id,
        status = status,
        version = version,
        currentProposal = proposal.toSummary(),
        confirmedProposalId = confirmedProposalId,
    )

private fun AppointmentProposalRecord.toSummary(): AppointmentProposalSummary =
    AppointmentProposalSummary(
        proposalId = id,
        revision = revision,
        startsAt = proposedStartAt,
        endsAt = proposedEndAt,
        expiresAt = expiresAt,
        expired = expiredAt != null,
        representativeTreatmentName = representativeTreatmentName,
    )
