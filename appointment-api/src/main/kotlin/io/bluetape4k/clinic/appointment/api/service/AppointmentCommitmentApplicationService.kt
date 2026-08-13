package io.bluetape4k.clinic.appointment.api.service

import io.bluetape4k.clinic.appointment.api.dto.commitment.AppointmentCommitmentResponse
import io.bluetape4k.clinic.appointment.api.dto.commitment.AppointmentProposalResponse
import io.bluetape4k.clinic.appointment.api.dto.commitment.ApproveProposalRequest
import io.bluetape4k.clinic.appointment.api.dto.commitment.CancelAppointmentRequest
import io.bluetape4k.clinic.appointment.api.dto.commitment.CreateAppointmentRequestV2
import io.bluetape4k.clinic.appointment.api.dto.commitment.CreateChangeProposalRequest
import io.bluetape4k.clinic.appointment.api.dto.commitment.DeclineProposalRequest
import io.bluetape4k.clinic.appointment.api.dto.commitment.DirectConfirmRequest
import io.bluetape4k.clinic.appointment.api.dto.commitment.DirectCreateAppointmentRequest
import io.bluetape4k.clinic.appointment.api.dto.commitment.ProposalDecisionRequest
import io.bluetape4k.clinic.appointment.api.security.ActorContext

/**
 * HTTP adapter와 commitment command/query 구현 사이의 actor-scoped application 경계이다.
 *
 * 모든 메서드는 request body와 분리된 [ActorContext], 멱등성 key, HTTP precondition을
 * 명시적으로 받는다. 구현체는 유효 정책 snapshot과 자원 inventory를 조회해 내부
 * `CommitmentCommandContext`, `DirectConfirmationPolicyDecision`,
 * `ConfirmedAppointmentProjectionTarget`을 조립해야 하며 controller DTO를 내부
 * command로 직접 cast하거나 역직렬화하면 안 된다.
 *
 * 구체 구현체는 command/query마다 [AppointmentCommitmentAccessResolver]로 Gateway의
 * tenant·clinic scope와 환자 소유권을 먼저 검증하고, 동의가 필요한 command는
 * `evidenceAuthority`의 tenant namespace도 검증해야 한다.
 *
 * Task 9의 feature control/wiring이 이 계약의 구현체를 등록하고 API property를 켤 때만
 * commitment controller가 활성화된다.
 */
interface AppointmentCommitmentApplicationService {

    /**
     * 고객의 Plan 범위와 patient subject를 검증하고 최초 proposal을 생성한다.
     *
     * 구현체는 [actor]의 patient subject를 Plan의 보호된 환자 fingerprint와 비교하고,
     * [request]가 가리키는 활성 Plan revision·정책 snapshot·자원 inventory로 내부
     * command를 조립해야 한다. [idempotencyKey] 원문은 저장 전에 HMAC 처리한다.
     */
    fun requestAppointment(
        actor: ActorContext,
        idempotencyKey: String,
        createOnly: Boolean,
        request: CreateAppointmentRequestV2,
    ): AppointmentProposalResponse

    /**
     * 병원 정책이 허용하는 경우에만 관리자 예약을 생성과 동시에 확정한다.
     *
     * 직접 확정 정책, 동의 원문 검증 결과, 담당자·진료·자원 mapping은 body가 아니라
     * [actor]의 tenant·clinic 범위에서 서버가 조회한다.
     */
    fun directCreate(
        actor: ActorContext,
        idempotencyKey: String,
        createOnly: Boolean,
        request: DirectCreateAppointmentRequest,
    ): AppointmentCommitmentResponse

    /**
     * 고객이 동의한 최초 proposal을 병원 승인으로 확정한다.
     *
     * [expectedVersion]과 영속 proposal revision/hash가 모두 현재값이어야 하며,
     * stale 요청은 기존 예약과 자원 점유를 바꾸지 않는다.
     */
    fun approveProposal(
        actor: ActorContext,
        appointmentId: Long,
        expectedVersion: Long,
        idempotencyKey: String,
        request: ApproveProposalRequest,
    ): AppointmentCommitmentResponse

    /**
     * 고객이 현재 변경 proposal을 수락하고 새 자원 점유로 원자 교체한다.
     *
     * 구현체는 [actor]의 patient subject와 appointment 소유 환자를 다시 비교하고,
     * [request]의 증빙 참조를 정확한 proposal hash에 결합해야 한다.
     */
    fun decideProposal(
        actor: ActorContext,
        appointmentId: Long,
        proposalId: Long,
        expectedVersion: Long,
        idempotencyKey: String,
        request: ProposalDecisionRequest,
    ): AppointmentCommitmentResponse

    /**
     * 고객의 proposal 거절 증빙을 기록하되 현재 확정 예약은 유지한다.
     *
     * 거절과 수락·만료는 같은 proposal 종결 경쟁에 참여하므로
     * [expectedVersion]을 소비하고 한 결과만 성공해야 한다.
     */
    fun declineProposal(
        actor: ActorContext,
        appointmentId: Long,
        proposalId: Long,
        expectedVersion: Long,
        idempotencyKey: String,
        request: DeclineProposalRequest,
    ): AppointmentCommitmentResponse

    /**
     * 유효 병원 정책과 고객 동의 증빙을 서버에서 확인해 선택 proposal을 직접 확정한다.
     *
     * body의 proposal ID는 식별자일 뿐 정책·자원 권위가 아니며, 구현체는 영속 proposal과
     * canonical hash, inventory mapping을 다시 조회한다.
     */
    fun directConfirm(
        actor: ActorContext,
        appointmentId: Long,
        expectedVersion: Long,
        idempotencyKey: String,
        request: DirectConfirmRequest,
    ): AppointmentCommitmentResponse

    /**
     * 현재 확정을 보존한 채 새 일정 proposal revision을 생성한다.
     *
     * Plan의 미완료 미래 항목과 정책·inventory만 사용하며 proposal 생성만으로 기존
     * allocation이나 확정 포인터를 해제하면 안 된다.
     */
    fun createChangeProposal(
        actor: ActorContext,
        appointmentId: Long,
        expectedVersion: Long,
        idempotencyKey: String,
        request: CreateChangeProposalRequest,
    ): AppointmentProposalResponse

    /**
     * 만료 시각에 도달한 proposal을 idempotent하게 종결한다.
     *
     * HELD 최초 proposal이면 allocation을 해제하고 commitment를 `EXPIRED`로 전환한다.
     * 확정 예약의 변경 proposal이면 기존 확정 포인터와 allocation을 보존한다.
     */
    fun expireProposal(
        actor: ActorContext,
        appointmentId: Long,
        proposalId: Long,
        expectedVersion: Long,
        idempotencyKey: String,
    ): AppointmentCommitmentResponse

    /**
     * 현재 가예약 또는 확정 예약을 취소하고 보유한 자원 allocation을 해제한다.
     *
     * 결제·환불 자체는 다른 서비스의 책임이며 [request]에는 등록된 취소 사유 code와
     * 서버 registry에 등록된 선택적 환자 안내 문구만 전달한다. 구현체는 현재 확정
     * proposal 또는 최초 미확정 proposal에 명령을 결합한다.
     */
    fun cancelAppointment(
        actor: ActorContext,
        appointmentId: Long,
        expectedVersion: Long,
        idempotencyKey: String,
        request: CancelAppointmentRequest,
    ): AppointmentCommitmentResponse

    /**
     * actor scope와 환자 소유권을 검증한 commitment 전용 read model을 반환한다.
     *
     * legacy nullable projection을 조합하거나 다른 tenant·clinic의 존재 여부를
     * 구분해 노출하면 안 된다.
     */
    fun query(
        actor: ActorContext,
        appointmentId: Long,
    ): AppointmentCommitmentResponse
}
