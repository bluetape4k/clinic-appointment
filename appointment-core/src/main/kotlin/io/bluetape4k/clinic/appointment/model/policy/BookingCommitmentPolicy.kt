package io.bluetape4k.clinic.appointment.model.policy

import java.io.Serializable
import java.time.Duration

/** 인증된 관리자가 고객 대신 예약을 생성할 때 허용되는 확정 방식입니다. */
enum class AdminBookingMode {
    /**
     * 관리자는 가예약 제안만 만들 수 있고 고객이 그 정확한 제안을 수락한 뒤에만
     * 확정합니다. 직접 확정 command는 이 정책에서 허용되지 않습니다.
     */
    PROPOSAL_REQUIRES_CUSTOMER_ACCEPTANCE,

    /**
     * 감사 가능한 고객 동의 증빙이 관리 명령과 함께 제공된 경우에만
     * 확정 예약을 바로 생성합니다.
     */
    DIRECT_CONFIRM_WITH_CONSENT_EVIDENCE,
}

/** 고객이 직접 요청한 예약이 예약 워크플로에 진입하는 방식을 나타냅니다. */
enum class PatientBookingMode {
    /**
     * 먼저 가예약 요청을 만들고, 권한 있는 병원 담당자가 승인한 뒤에만
     * 확정 예약으로 전환합니다.
     */
    PROVISIONAL_APPROVAL_REQUIRED,
}

/** 고객 요청이 가예약 상태일 때 의료진, 공간, 장비 수용량을 점유하는 방식입니다. */
enum class ProvisionalCapacityMode {
    /** 승인 전에는 의료진, 진료실, 장비 수용량을 전혀 점유하지 않습니다. */
    NO_HOLD,

    /** 선호 시간만 표현하고, 같은 자원을 다른 예약 후보가 사용할 수 있게 둡니다. */
    SOFT_HOLD,

    /** 짧고 제한된 시간 동안 필요한 자원을 배타적으로 선점합니다. */
    HARD_HOLD,
}

/** 이미 확정된 예약을 변경할 때 반드시 따라야 하는 고객 동의 워크플로입니다. */
enum class ConfirmedChangeMode {
    /**
     * 기존 확정 예약은 유지한 채 변경 제안을 새로 만들고, 고객이 새로 동의한
     * 뒤에만 변경을 적용합니다.
     */
    NEW_PROPOSAL_AND_CUSTOMER_CONSENT,
}

/**
 * 관리자가 고객 대신 예약을 확정할 때 필요한 동의 증빙 계약입니다.
 *
 * @property allowedEvidenceTypes `SIGNED_FORM`, `VERBAL_RECORDING`처럼 설정으로
 * 닫힌 증빙 유형 식별자입니다. 빈 컬렉션은 허용되지 않으며, 값은 외부 동의
 * 서비스에 저장된 증빙을 가리키는 신뢰 경계 메타데이터입니다. 원본 증빙이나
 * 인증 정보는 이 payload에 포함하지 않습니다.
 * @property maximumAge 예약 결정 시점 기준으로 허용되는 증빙 최대 연령입니다.
 * `Duration` 단위이며 반드시 양수여야 하고, 호출자는 UTC instant 기준으로
 * 만료 여부를 비교합니다.
 * @property termsHashRequired 증빙이 고객이 동의한 약관의 정확한 hash를 참조해야
 * 하는지 여부입니다. 약관이 변경될 수 있는 상품/시술 계약에서는 `true`를
 * 유지해 동의 범위를 재현 가능하게 만들어야 합니다.
 */
data class ConsentEvidenceRequirement(
    val allowedEvidenceTypes: Set<String>,
    val maximumAge: Duration,
    val termsHashRequired: Boolean,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 관리자 예약과 고객 예약 요청에 적용되는 tenant 단위 예약 확정 정책입니다.
 *
 * 고객 요청은 항상 가예약으로 시작하며 병원 승인이 필요합니다. 관리자는 고객
 * 동의 증빙이 있을 때만 바로 확정할 수 있습니다. 이미 확정된 예약은 조용히
 * 이동시킬 수 없고, 변경 제안이 고객의 새 동의를 기다리는 동안 기존 확정
 * 예약은 계속 유효해야 합니다.
 *
 * @property adminBookingMode 관리자 등록 예약의 확정 계약입니다. schema 1에서는
 * 동의 증빙이 첨부된 직접 확정만 허용합니다.
 * @property patientBookingMode 고객이 직접 등록한 예약 요청의 계약입니다. schema 1은
 * 항상 승인 대기 가예약을 생성합니다.
 * @property provisionalCapacityMode 가예약이 수용량을 점유하지 않을지, soft hold로
 * 선호만 표시할지, hard hold로 짧게 배타 선점할지를 결정합니다.
 * @property provisionalRequestTtl 승인되지 않은 요청의 생존 시간입니다. 생성 시점부터
 * 측정하며 허용 범위는 `5 minutes..7 days` 양끝 포함입니다.
 * @property resourceHoldTtl 배타 자원 선점의 생존 시간입니다.
 * [ProvisionalCapacityMode.NO_HOLD]와 [ProvisionalCapacityMode.SOFT_HOLD]에서는
 * 반드시 `null`이어야 합니다. [ProvisionalCapacityMode.HARD_HOLD]에서는 필수이고
 * `1..30 minutes` 양끝 포함 범위에 있어야 하며 [provisionalRequestTtl]을 넘을 수 없습니다.
 * @property approvalRoles 고객 기원 가예약을 승인할 수 있는 신뢰된 gateway role
 * 집합입니다. 빈 집합은 허용되지 않으며, request body가 아니라 인증 컨텍스트에서
 * 온 role과 비교됩니다.
 * @property adminConsentEvidence 관리자가 바로 확정할 때 필요한 증빙 신선도와 무결성
 * 조건입니다. 원본 증빙은 외부 서비스에 남기고 예약 정책에는 검증 가능한 조건만 저장합니다.
 * @property confirmedChangeMode 확정 예약 변경의 비활성화 불가 규칙입니다. schema 1은
 * 새 제안과 고객 동의를 요구합니다.
 */
data class BookingCommitmentPolicy(
    val adminBookingMode: AdminBookingMode,
    val patientBookingMode: PatientBookingMode,
    val provisionalCapacityMode: ProvisionalCapacityMode,
    val provisionalRequestTtl: Duration,
    val resourceHoldTtl: Duration?,
    val approvalRoles: Set<ActorRole>,
    val adminConsentEvidence: ConsentEvidenceRequirement,
    val confirmedChangeMode: ConfirmedChangeMode,
) : SchedulingPolicyPayload {
    override val kind: SchedulingPolicyKind = SchedulingPolicyKind.BOOKING_COMMITMENT

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * [BookingCommitmentPolicy]에 대한 clinic 단위 override입니다.
 *
 * 모든 속성은 명시적으로 inherit, set, disable 중 하나를 선택합니다. 예약 진입
 * 방식, 승인 role, 동의 증빙, 확정 예약 변경 보호는 필수 계약이므로 disable할
 * 수 없습니다. [resourceHoldTtlSeconds]는 배타 선점이 없음을 표현하기 위해
 * disable될 수 있지만, 컴파일 결과는 선택된 가예약 수용량 모드와 일관되어야 합니다.
 *
 * @property adminBookingMode 관리자 기원 예약 workflow에 대한 clinic 지시입니다.
 * `Disable`은 유효하지 않습니다.
 * @property patientBookingMode 고객 기원 예약 workflow에 대한 clinic 지시입니다.
 * `Disable`은 유효하지 않습니다.
 * @property provisionalCapacityMode clinic의 가예약 자원 hold 전략입니다. 기능을
 * 끄려면 `Disable`이 아니라 명시적인 [ProvisionalCapacityMode.NO_HOLD]를 사용합니다.
 * @property provisionalRequestTtlSeconds 요청 생존 시간입니다. 단위는 초이며,
 * `Set` 값은 컴파일 후 `300..604800` 양끝 포함 범위에 있어야 합니다.
 * @property resourceHoldTtlSeconds 배타 hold 생존 시간입니다. 단위는 초이며,
 * `Set` 값은 `60..1800` 양끝 포함 범위에 있어야 합니다. `Disable`은 최종 모드가
 * `HARD_HOLD`가 아닐 때만 `null`로 컴파일됩니다.
 * @property approvalRoles 승인자 role 집합입니다. 빈 집합과 `Disable`은 모두
 * 유효하지 않으며, role은 신뢰된 인증 경계에서 온 값이어야 합니다.
 * @property adminConsentEvidence 동의 증빙 계약입니다. `Disable`은 유효하지 않습니다.
 * @property confirmedChangeMode 확정 예약 변경 보호 규칙입니다. 비활성화할 수 없습니다.
 */
data class BookingCommitmentOverride(
    val adminBookingMode: OverrideValue<AdminBookingMode>,
    val patientBookingMode: OverrideValue<PatientBookingMode>,
    val provisionalCapacityMode: OverrideValue<ProvisionalCapacityMode>,
    val provisionalRequestTtlSeconds: OverrideValue<Long>,
    val resourceHoldTtlSeconds: OverrideValue<Long>,
    val approvalRoles: OverrideValue<Set<ActorRole>>,
    val adminConsentEvidence: OverrideValue<ConsentEvidenceRequirement>,
    val confirmedChangeMode: OverrideValue<ConfirmedChangeMode>,
) : SchedulingPolicyPayload {
    override val kind: SchedulingPolicyKind = SchedulingPolicyKind.BOOKING_COMMITMENT

    companion object {
        private const val serialVersionUID = 1L
    }
}
