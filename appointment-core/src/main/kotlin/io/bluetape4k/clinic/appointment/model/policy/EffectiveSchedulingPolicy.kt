package io.bluetape4k.clinic.appointment.model.policy

import java.io.Serializable
import java.time.Instant

/**
 * compilation 중 동시 정책 변경을 감지하기 위해 사용하는 generation counter입니다.
 *
 * @property tenantGeneration tenant policy head의 양수 단조 증가 generation입니다.
 * active tenant definition이 바뀔 때마다 증가합니다.
 * @property clinicGeneration clinic override head의 단조 증가 generation입니다.
 * clinic override가 아직 한 번도 activate되지 않았으면 `0`에서 시작하고, active clinic
 * override가 바뀔 때마다 증가합니다.
 */
data class PolicyGenerationVector(
    val tenantGeneration: Long,
    val clinicGeneration: Long,
) : Serializable {
    companion object { private const val serialVersionUID = 1L }
}

/**
 * snapshot의 한 policy kind에 기여한 정확한 source version입니다.
 *
 * @property tenantVersion active tenant definition의 양수 version입니다.
 * @property clinicVersion active clinic override definition의 양수 version입니다.
 * 해당 kind에서 clinic이 tenant policy를 그대로 상속하면 `null`입니다.
 */
data class SourceVersion(
    val tenantVersion: Long,
    val clinicVersion: Long?,
) : Serializable {
    companion object { private const val serialVersionUID = 1L }
}

/** 컴파일된 leaf 값 하나를 제공한 조직적 출처입니다. */
enum class PolicyValueSource {
    /** tenant 값이 대체하지 않아 사용된 immutable platform safety/default 값입니다. */
    PLATFORM,

    /** active tenant baseline 값입니다. */
    TENANT,

    /** 모든 non-relaxation 검사를 통과한 active clinic override 값입니다. */
    CLINIC,
}

/**
 * downstream scheduling decision이 소비하는 완전히 해석된 policy 값입니다.
 *
 * nullable 속성 하나는 독립적으로 activate되는 policy kind 하나에 대응합니다.
 * `null`인 kind는 compiled contract가 없다는 뜻이며, 암묵적 zero나 관대한 default가
 * 아니라 unavailable 상태로 처리해야 합니다.
 *
 * @property bookingCommitment 예약 출처, 승인, hold, 확정 예약 변경에 대한 resolved contract입니다.
 * @property holdAndConsent hold 중 동의 증빙 요구사항입니다.
 * @property capacityAndOverbooking 수용량 count와 hard ceiling입니다.
 * @property priorityAndReliability 객관적 신뢰도 scoring 입력값입니다.
 * @property reconfirmation 재확인 일정과 retry ceiling입니다.
 * @property disruptionRecovery 운영 장애 후 변경 제안 동작입니다.
 * @property operatingExtension 초과 진료와 안전 ceiling입니다.
 * @property notificationAndSla 통지 channel과 필수 응답 한도입니다.
 */
data class CompiledSchedulingPolicy(
    val bookingCommitment: BookingCommitmentPolicy? = null,
    val holdAndConsent: HoldAndConsentPolicy? = null,
    val capacityAndOverbooking: CapacityAndOverbookingPolicy? = null,
    val priorityAndReliability: PriorityAndReliabilityPolicy? = null,
    val reconfirmation: ReconfirmationPolicy? = null,
    val disruptionRecovery: DisruptionRecoveryPolicy? = null,
    val operatingExtension: OperatingExtensionPolicy? = null,
    val notificationAndSla: NotificationAndSlaPolicy? = null,
) : Serializable {
    companion object { private const val serialVersionUID = 1L }
}

/**
 * 전체 effective-policy compilation에 제공되는 typed clinic override 묶음입니다.
 *
 * 속성이 `null`이면 해당 policy kind에 active clinic override definition이 없다는 뜻입니다.
 * non-null 값도 field별 [OverrideValue] 상태를 가지므로, definition 자체의 부재와
 * 각 field의 inherit은 서로 다른 lifecycle 의미를 가집니다.
 *
 * @property bookingCommitment active clinic booking override입니다. 없으면 `null`입니다.
 * @property holdAndConsent active clinic hold/consent override입니다. 없으면 `null`입니다.
 * @property capacityAndOverbooking active clinic capacity override입니다. 없으면 `null`입니다.
 * @property priorityAndReliability active clinic reliability override입니다. 없으면 `null`입니다.
 * @property reconfirmation active clinic reconfirmation override입니다. 없으면 `null`입니다.
 * @property disruptionRecovery active clinic disruption-recovery override입니다. 없으면 `null`입니다.
 * @property operatingExtension active clinic operating-extension override입니다. 없으면 `null`입니다.
 * @property notificationAndSla active clinic notification/SLA override입니다. 없으면 `null`입니다.
 */
data class ClinicSchedulingPolicyOverrides(
    val bookingCommitment: BookingCommitmentOverride? = null,
    val holdAndConsent: HoldAndConsentOverride? = null,
    val capacityAndOverbooking: CapacityAndOverbookingOverride? = null,
    val priorityAndReliability: PriorityAndReliabilityOverride? = null,
    val reconfirmation: ReconfirmationOverride? = null,
    val disruptionRecovery: DisruptionRecoveryOverride? = null,
    val operatingExtension: OperatingExtensionOverride? = null,
    val notificationAndSla: NotificationAndSlaOverride? = null,
) : Serializable {
    companion object { private const val serialVersionUID = 1L }
}

/**
 * 하나의 clinic과 decision에 대해 재현 가능한 immutable scheduling-policy snapshot입니다.
 *
 * snapshot은 두 시간 축으로 평가됩니다. [decisionAt]은 현재 command를 지배하는
 * definition을 선택하고, [serviceAt]은 실제 예약일에 적용될 future-effective rule을
 * 고려하게 합니다. generation vector와 source version은 stale compilation을 감지하게
 * 하고, canonical hash는 결과 재현성을 제공합니다.
 *
 * @property id stable snapshot identity입니다. schema 1에서는 [snapshotHash]와 같은
 * lowercase SHA-256 값을 사용합니다.
 * @property tenantGroupId 양수 tenant boundary입니다.
 * @property clinicId 이 policy가 compile된 양수 clinic identity입니다.
 * @property decisionAt command가 정책을 평가한 UTC instant입니다.
 * @property serviceAt 계획된 시술/진료의 UTC instant입니다. 예약일 기준 future policy
 * version을 선택할 수 있습니다.
 * @property generation compiler가 관찰한 tenant/clinic generation입니다. persistence는
 * snapshot publish 전에 이 값을 다시 검증해야 합니다.
 * @property sourceVersions 포함된 policy kind별 tenant/clinic version pair입니다.
 * @property sourceByPath compiled leaf path별 source입니다. 누락은 platform inherit과
 * 같지 않으며 compiler가 해당 leaf 출처를 기록하지 못한 결함을 의미합니다.
 * @property disabledFeatures 유효한 clinic override가 명시적으로 disable한 semantic path
 * 집합입니다. canonical hash를 위해 정렬됩니다.
 * @property warnings compilation 중 생성된 고객-safe diagnostic code 또는 message입니다.
 * 순서는 hash에서 의미가 있으므로 보존됩니다.
 * @property payload 완전히 해석된 policy 값입니다.
 * @property snapshotHash schema version, tenant/clinic, 평가 instant, generation,
 * source metadata, disabled path, warning, compiled payload를 모두 포함해 계산한
 * lowercase SHA-256입니다.
 */
data class EffectiveSchedulingPolicy(
    val id: String,
    val tenantGroupId: Long,
    val clinicId: Long,
    val decisionAt: Instant,
    val serviceAt: Instant,
    val generation: PolicyGenerationVector,
    val sourceVersions: Map<SchedulingPolicyKind, SourceVersion>,
    val sourceByPath: Map<String, PolicyValueSource>,
    val disabledFeatures: Set<String>,
    val warnings: List<String>,
    val payload: CompiledSchedulingPolicy,
    val snapshotHash: String,
) : Serializable {
    companion object { private const val serialVersionUID = 1L }
}
