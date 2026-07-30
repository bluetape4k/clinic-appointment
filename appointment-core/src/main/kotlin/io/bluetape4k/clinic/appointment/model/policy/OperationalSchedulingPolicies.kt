package io.bluetape4k.clinic.appointment.model.policy

/**
 * 예약 수용량이 hold된 동안 적용되는 동의 증빙 규칙입니다.
 *
 * @property consentEvidenceRequired hold 중인 요청이 확정되기 전에 고객 동의 증빙을
 * 반드시 가져야 하는지 여부입니다.
 * @property maximumConsentAgeSeconds 확정 시점 기준 증빙 최대 연령입니다. 단위는 초이며
 * 증빙이 현재 선택 기능이어도 반드시 양수여야 합니다. 그래야 기능을 켤 때 정의되지
 * 않은 만료 한도가 노출되지 않습니다.
 */
data class HoldAndConsentPolicy(
    val consentEvidenceRequired: Boolean,
    val maximumConsentAgeSeconds: Long,
) : SchedulingPolicyPayload {
    override val kind: SchedulingPolicyKind = SchedulingPolicyKind.HOLD_AND_CONSENT
    companion object { private const val serialVersionUID = 1L }
}

/**
 * hold 상태의 동의 증빙 요구사항에 대한 clinic 단위 조정입니다.
 *
 * @property consentEvidenceRequired clinic의 증빙 요구 지시입니다. 더 강한 platform
 * 규칙이 요구하지 않는 경우에만 `Disable`이 선택 기능을 끄는 값으로 컴파일될 수 있습니다.
 * @property maximumConsentAgeSeconds 증빙 연령 한도입니다. 단위는 초이며 양수여야 합니다.
 * 증빙 요구가 켜진 경우에는 반드시 한도가 필요하므로 `Disable`은 유효하지 않습니다.
 */
data class HoldAndConsentOverride(
    val consentEvidenceRequired: OverrideValue<Boolean>,
    val maximumConsentAgeSeconds: OverrideValue<Long>,
) : SchedulingPolicyPayload {
    override val kind: SchedulingPolicyKind = SchedulingPolicyKind.HOLD_AND_CONSENT
    companion object { private const val serialVersionUID = 1L }
}

/**
 * 시술/진료 전에 고객에게 재확인을 요청하는 규칙입니다.
 *
 * @property required 방문 전 재확인이 필요한지 여부입니다.
 * @property leadTimeSeconds service instant보다 몇 초 전에 첫 재확인 시도가 due가 되는지
 * 나타냅니다. 단위는 초이며 반드시 양수입니다.
 * @property maximumAttempts 재확인 시도 횟수의 상한입니다. 양수이고 비활성화할 수 없습니다.
 * clinic override는 tenant/platform ceiling보다 낮출 수는 있지만 높일 수 없습니다.
 */
data class ReconfirmationPolicy(
    val required: Boolean,
    val leadTimeSeconds: Long,
    val maximumAttempts: Int,
) : SchedulingPolicyPayload {
    override val kind: SchedulingPolicyKind = SchedulingPolicyKind.RECONFIRMATION
    companion object { private const val serialVersionUID = 1L }
}

/**
 * 재확인 규칙에 대한 clinic 단위 조정입니다.
 *
 * @property required 재확인을 켤지에 대한 clinic 지시입니다. 더 강한 platform 규칙이
 * 비활성화를 금지하지 않는 경우에만 `Disable`이 `false`로 컴파일될 수 있습니다.
 * @property leadTimeSeconds 재확인 lead time입니다. 단위는 초이며 양수여야 하고
 * `Disable`은 유효하지 않습니다.
 * @property maximumAttempts 재시도 상한입니다. 양수여야 하며 `Disable`은 유효하지 않습니다.
 * `Set` 값은 상속된 ceiling을 초과할 수 없습니다.
 */
data class ReconfirmationOverride(
    val required: OverrideValue<Boolean>,
    val leadTimeSeconds: OverrideValue<Long>,
    val maximumAttempts: OverrideValue<Int>,
) : SchedulingPolicyPayload {
    override val kind: SchedulingPolicyKind = SchedulingPolicyKind.RECONFIRMATION
    companion object { private const val serialVersionUID = 1L }
}

/**
 * 운영 장애가 발생한 뒤 적용되는 회복 및 재예약 제안 정책입니다.
 *
 * 운영 장애에는 국가 공휴일 변경, 담당 의료진 휴진, 장비 고장, 또는 하나의 예약에서
 * 모든 세부 진료 항목을 완료하지 못한 상황이 포함됩니다. 회복 절차는 변경 제안을
 * 만들 뿐이며, 기존 확정 예약을 고객 동의 없이 조용히 덮어쓰지 않습니다.
 *
 * @property automaticProposalEnabled 시스템이 대체 일정 제안을 자동 계산하고 발송할 수
 * 있는지 여부입니다.
 * @property maximumProposalDelaySeconds 장애 감지부터 제안 생성까지 허용되는 최대
 * 경과 시간입니다. 단위는 초이며 반드시 양수입니다.
 * @property preserveConfirmedAppointment 고객이 대체 일정을 수락하거나 별도 명시적
 * 취소가 발생하기 전까지 현재 확정 예약이 계속 유효해야 한다는 비활성화 불가
 * invariant입니다.
 */
data class DisruptionRecoveryPolicy(
    val automaticProposalEnabled: Boolean,
    val maximumProposalDelaySeconds: Long,
    val preserveConfirmedAppointment: Boolean,
) : SchedulingPolicyPayload {
    override val kind: SchedulingPolicyKind = SchedulingPolicyKind.DISRUPTION_RECOVERY
    companion object { private const val serialVersionUID = 1L }
}

/**
 * 운영 장애 제안 동작에 대한 clinic 단위 조정입니다.
 *
 * @property automaticProposalEnabled 자동 제안 선택 기능입니다. `Disable`은 `false`로
 * 컴파일됩니다.
 * @property maximumProposalDelaySeconds 제안 생성 응답 한도입니다. 단위는 초이며
 * 양수여야 하고 `Disable`은 유효하지 않습니다.
 *
 * [DisruptionRecoveryPolicy.preserveConfirmedAppointment]는 고객 동의 경계이므로
 * clinic이 override할 수 없게 의도적으로 제외했습니다.
 */
data class DisruptionRecoveryOverride(
    val automaticProposalEnabled: OverrideValue<Boolean>,
    val maximumProposalDelaySeconds: OverrideValue<Long>,
) : SchedulingPolicyPayload {
    override val kind: SchedulingPolicyKind = SchedulingPolicyKind.DISRUPTION_RECOVERY
    companion object { private const val serialVersionUID = 1L }
}

/**
 * 정상 영업 스케줄을 넘어 진료 시간을 연장할 때 적용되는 정책입니다.
 *
 * @property extensionEnabled clinic이 통제된 초과근무 예약을 배정할 수 있는지 여부입니다.
 * @property maximumExtensionMinutes 현재 tenant 정책이 허용하는 최대 연장 시간입니다.
 * 단위는 분이며 음수가 될 수 없습니다.
 * @property legalSafetyCeilingMinutes 법규, 근로, 임상 안전 기준에서 정한 비활성화
 * 불가 상한입니다. 단위는 분이며 음수가 될 수 없습니다. [maximumExtensionMinutes]는
 * 이 값을 초과할 수 없습니다.
 */
data class OperatingExtensionPolicy(
    val extensionEnabled: Boolean,
    val maximumExtensionMinutes: Int,
    val legalSafetyCeilingMinutes: Int,
) : SchedulingPolicyPayload {
    override val kind: SchedulingPolicyKind = SchedulingPolicyKind.OPERATING_EXTENSION
    companion object { private const val serialVersionUID = 1L }
}

/**
 * 영업 시간 연장에 대한 clinic 단위 조정입니다.
 *
 * @property extensionEnabled 초과근무 선택 기능입니다. `Disable`은 `false`로 컴파일됩니다.
 * @property maximumExtensionMinutes 연장 시간 한도입니다. 단위는 분이며 음수가 될 수
 * 없습니다. `Disable`은 유효하지 않고, `Set` 값은 상속된 maximum이나 법적 안전
 * ceiling을 초과할 수 없습니다.
 */
data class OperatingExtensionOverride(
    val extensionEnabled: OverrideValue<Boolean>,
    val maximumExtensionMinutes: OverrideValue<Int>,
) : SchedulingPolicyPayload {
    override val kind: SchedulingPolicyKind = SchedulingPolicyKind.OPERATING_EXTENSION
    companion object { private const val serialVersionUID = 1L }
}

/**
 * 고객 통지와 병원 운영 응답 수준에 대한 정책입니다.
 *
 * @property notificationChannels `SMS`, `PUSH`, `EMAIL` 같은 stable channel
 * identifier 집합입니다. 빈 집합은 허용되지 않으며, 실제 주소나 연락처가 아니라
 * routing 선택지만 저장합니다.
 * @property disruptionNoticeSeconds 장애 감지부터 고객 통지 발송까지 허용되는 최대
 * 경과 시간입니다. 단위는 초이며 반드시 양수입니다.
 * @property mandatoryResponseSeconds 병원이 반드시 수행해야 하는 운영 조치의 최대
 * 응답 시간입니다. 단위는 초이며 반드시 양수이고 비활성화할 수 없습니다.
 * @property profileReevaluationHeldTargetSeconds 프로필 변경 후 `HELD` 예약을
 * 재평가할 목표 시간입니다. 단위는 초이며 `null`이면 플랫폼 환경 기본값을 사용합니다.
 * @property profileReevaluationProposedTargetSeconds 프로필 변경 후 `PROPOSED` 예약을
 * 재평가할 목표 시간입니다. 단위는 초이며 `null`이면 플랫폼 환경 기본값을 사용합니다.
 */
data class NotificationAndSlaPolicy(
    val notificationChannels: Set<String>,
    val disruptionNoticeSeconds: Long,
    val mandatoryResponseSeconds: Long,
    val profileReevaluationHeldTargetSeconds: Long? = null,
    val profileReevaluationProposedTargetSeconds: Long? = null,
) : SchedulingPolicyPayload {
    override val kind: SchedulingPolicyKind = SchedulingPolicyKind.NOTIFICATION_AND_SLA
    companion object { private const val serialVersionUID = 1L }
}

/**
 * 고객 통지 정책에 대한 clinic 단위 조정입니다.
 *
 * @property notificationChannels channel set 전체 대체값입니다. 빈 집합은 허용되지
 * 않습니다. 영향을 받는 고객에게 계속 도달 가능해야 하므로 `Disable`은 유효하지 않습니다.
 * @property disruptionNoticeSeconds 고객 통지 한도입니다. 단위는 초이며 양수여야 하고
 * `Disable`은 유효하지 않습니다.
 * @property profileReevaluationHeldTargetSeconds `HELD` 예약 재평가 처리 목표
 * 조정입니다. `Inherit` 또는 1분 이상 15분 이하의 `Set`만 허용합니다.
 * @property profileReevaluationProposedTargetSeconds `PROPOSED` 예약 재평가 처리 목표
 * 조정입니다. `Inherit` 또는 5분 이상 120분 이하의 `Set`만 허용합니다.
 *
 * [NotificationAndSlaPolicy.mandatoryResponseSeconds]는 tenant/platform SLA를
 * clinic이 완화하거나 비활성화할 수 없도록 의도적으로 제외했습니다.
 */
data class NotificationAndSlaOverride(
    val notificationChannels: OverrideValue<Set<String>>,
    val disruptionNoticeSeconds: OverrideValue<Long>,
    val profileReevaluationHeldTargetSeconds: OverrideValue<Long> = OverrideValue.Inherit,
    val profileReevaluationProposedTargetSeconds: OverrideValue<Long> = OverrideValue.Inherit,
) : SchedulingPolicyPayload {
    override val kind: SchedulingPolicyKind = SchedulingPolicyKind.NOTIFICATION_AND_SLA
    companion object { private const val serialVersionUID = 1L }
}
