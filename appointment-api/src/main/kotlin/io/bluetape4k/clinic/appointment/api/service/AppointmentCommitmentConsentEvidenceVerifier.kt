package io.bluetape4k.clinic.appointment.api.service

import io.bluetape4k.clinic.appointment.api.config.AppointmentCommitmentApiError
import io.bluetape4k.clinic.appointment.api.config.AppointmentCommitmentApiException
import io.bluetape4k.clinic.appointment.api.dto.commitment.ConsentEvidenceRequest
import io.bluetape4k.clinic.appointment.model.commitment.ConsentDecisionType
import java.io.Serializable
import java.time.Duration
import java.time.Instant

/**
 * 예약 commitment API가 외부 동의 서비스의 opaque evidence를 검증하는 fail-closed 경계이다.
 *
 * 예약 서비스는 동의 원문, 환자 실명, 녹취, 서명 파일을 소유하지 않는다. 대신 이 경계가
 * 외부 동의 서비스 또는 그 local projection이 반환한 권위 있는 metadata를 받아 현재
 * Gateway scope, Plan/appointment 환자 fingerprint, proposal, 정책 snapshot, 약관 hash와
 * 정확히 대조한다. 구현체가 없으면 [FailClosedAppointmentCommitmentConsentEvidenceVerifier]처럼
 * 모든 증빙을 거절해야 하며, request body의 `evidenceAuthority`와 `evidenceId`만으로
 * 동의를 인정하면 안 된다.
 */
internal fun interface AppointmentCommitmentConsentEvidenceVerifier {
    /**
     * 외부 동의 증빙을 현재 예약 결정 문맥에 결합해 검증 완료 metadata를 반환한다.
     *
     * 반환값은 caller가 제출한 opaque 참조와 같은 증빙이어야 하며, tenant·clinic·환자·
     * proposal·정책·약관 조건이 하나라도 다르면 [AppointmentCommitmentApiException]으로
     * 닫힌 실패를 반환해야 한다. 원문 증빙이나 개인정보는 반환하지 않는다.
     */
    fun verify(request: AppointmentCommitmentConsentEvidenceVerificationRequest): VerifiedAppointmentCommitmentConsentEvidence
}

/** 외부 동의 projection이 준비되지 않은 배포에서 모든 commitment 동의 결정을 차단한다. */
internal object FailClosedAppointmentCommitmentConsentEvidenceVerifier :
    AppointmentCommitmentConsentEvidenceVerifier {
    override fun verify(
        request: AppointmentCommitmentConsentEvidenceVerificationRequest,
    ): VerifiedAppointmentCommitmentConsentEvidence =
        throw AppointmentCommitmentApiException(
            AppointmentCommitmentApiError.CONSENT_REQUIRED,
            "appointment commitment consent evidence verifier is not configured",
        )
}

/**
 * 외부 동의 증빙을 예약 결정에 결합할 때 서버가 계산한 검증 대상이다.
 *
 * @property evidence caller가 제출한 opaque evidence 참조이다.
 * @property tenantGroupId Gateway actor와 Plan/appointment lookup이 확인한 tenant group ID이다.
 * @property clinicId Gateway actor가 선택했고 Plan/appointment가 실제 속한 clinic ID이다.
 * @property patientReferenceFingerprint 구매 이벤트에서 전달된 보호 환자 fingerprint이다.
 * @property appointmentPlanId 신규 예약이면 구매 Plan ID이고 기존 예약 결정이면 `null`이다.
 * @property appointmentId 기존 예약 proposal 결정이면 appointment ID이고 신규 예약이면 `null`이다.
 * @property proposalId 영속 proposal이면 proposal ID이고 신규 예약 생성 전 proposal이면 `null`이다.
 * @property proposalHash 영속 proposal의 canonical hash 또는 생성 전 proposal의 서버 계산
 * 참조 hash이다. 외부 동의 서비스는 이 값과 정확히 같은 proposal에 대한 동의만 반환해야 한다.
 * @property policySnapshotId proposal 계산에 사용한 유효 정책 snapshot ID이다.
 * @property policySnapshotHash 유효 정책 payload의 canonical SHA-256이다.
 * @property decision 기록할 고객 동의 결정이다.
 * @property allowedEvidenceTypes 현재 정책이 허용하는 증빙 유형이다. `null`이면 유형 집합 검증을
 * 외부 동의 projection의 scope 검증에만 맡긴다.
 * @property maximumEvidenceAge 현재 정책이 허용하는 증빙 최대 연령이다. `null`이면 신선도
 * 상한을 이 경계에서 강제하지 않는다.
 * @property termsHashRequired 약관 hash가 반드시 있어야 하는 정책인지 나타낸다.
 * @property verifiedAt 증빙 신선도와 미래 시각 조작을 판단할 권위 UTC 시각이다.
 */
internal data class AppointmentCommitmentConsentEvidenceVerificationRequest(
    val evidence: ConsentEvidenceRequest,
    val tenantGroupId: Long,
    val clinicId: Long,
    val patientReferenceFingerprint: String,
    val appointmentPlanId: Long?,
    val appointmentId: Long?,
    val proposalId: Long?,
    val proposalHash: String,
    val policySnapshotId: Long,
    val policySnapshotHash: String,
    val decision: ConsentDecisionType,
    val allowedEvidenceTypes: Set<String>? = null,
    val maximumEvidenceAge: Duration? = null,
    val termsHashRequired: Boolean = false,
    val verifiedAt: Instant,
) : Serializable

/**
 * 외부 동의 서비스 또는 local projection이 반환한 권위 있는 동의 metadata이다.
 *
 * 각 scope 필드는 [AppointmentCommitmentConsentEvidenceVerificationRequest]의 대응 값과
 * 정확히 같아야 한다. 예약 application service가 한 번 더 구조적 대조를 수행하므로,
 * 구현체 버그나 prefix 유사 tenant, 다른 proposal 증빙 재사용, 만료 증빙, 약관 hash 누락은
 * command service에 도달하기 전에 차단된다.
 */
internal data class VerifiedAppointmentCommitmentConsentEvidence(
    val evidenceAuthority: String,
    val evidenceId: String,
    val evidenceType: String,
    val evidenceHash: String,
    val decidedAt: Instant,
    val termsHash: String?,
    val tenantGroupId: Long,
    val clinicId: Long,
    val patientReferenceFingerprint: String,
    val appointmentPlanId: Long?,
    val appointmentId: Long?,
    val proposalId: Long?,
    val proposalHash: String,
    val policySnapshotId: Long,
    val policySnapshotHash: String,
) : Serializable
