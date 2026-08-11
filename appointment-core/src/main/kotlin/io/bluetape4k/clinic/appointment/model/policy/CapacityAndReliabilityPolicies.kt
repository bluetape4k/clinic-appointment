package io.bluetape4k.clinic.appointment.model.policy

import java.io.Serializable

/** schema-one에서 threshold가 없을 때 재현 가능한 compatibility 값입니다. */
internal object BookingReliabilityPolicyDefaults {
    const val LOOKBACK_DAYS: Int = 180
    const val LATE_CANCELLATION_WINDOW_MINUTES: Int = 120
    const val NO_SHOW_THRESHOLD: Int = 3
    const val LATE_CANCELLATION_THRESHOLD: Int = 3
    const val COOLING_OFF_HOURS: Int = 24
}

/**
 * 하나의 scheduling bucket에 적용되는 tenant 수용량과 의도적 overbooking 한도입니다.
 *
 * bucket은 downstream allocator가 정의합니다. 예를 들어 담당의, 진료실, 장비,
 * 또는 이들의 조합으로 만들어진 시간 슬롯일 수 있습니다. 이 정책은 숫자 한도만
 * 저장하며 실제 예약 배정이나 대기열 순서는 수행하지 않습니다.
 *
 * @property nominalCapacity overbooking 없이 해당 bucket에 정상적으로 들어갈 것으로
 * 기대하는 예약 수입니다. 단위는 예약 건수이며 반드시 양수입니다.
 * @property overbookingQuota 예상 no-show를 상쇄하기 위해 병원이 의도적으로 추가
 * 수락할 수 있는 예약 수입니다. 단위는 예약 건수이며 음수가 될 수 없습니다.
 * @property absoluteBookingLimit 비활성화할 수 없는 hard ceiling입니다. 최소한
 * [nominalCapacity] 이상이고 `nominalCapacity + overbookingQuota` 이상이어야 합니다.
 * clinic override는 컴파일된 값을 이 한도보다 높일 수 없습니다.
 * @property automaticReductionEnabled 런타임 배정기가 객관적 신뢰도나 운영 장애
 * 신호에 따라 overbooking 허용분을 줄일 수 있는지 여부입니다. 이 값은
 * [absoluteBookingLimit]을 높이는 근거가 될 수 없습니다.
 */
data class CapacityAndOverbookingPolicy(
    val nominalCapacity: Int,
    val overbookingQuota: Int,
    val absoluteBookingLimit: Int,
    val automaticReductionEnabled: Boolean,
) : SchedulingPolicyPayload, Serializable {
    override val kind: SchedulingPolicyKind = SchedulingPolicyKind.CAPACITY_AND_OVERBOOKING

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * tenant hard ceiling 안에서만 허용되는 clinic 단위 수용량 조정입니다.
 *
 * @property nominalCapacity clinic 기준 정상 예약 수입니다. 단위는 예약 건수입니다.
 * `Set` 값은 양수여야 하며 tenant [CapacityAndOverbookingPolicy.absoluteBookingLimit]를
 * 넘는 컴파일 결과를 만들 수 없습니다. `Disable`은 유효하지 않습니다.
 * @property overbookingQuota clinic 기준 overbooking 수입니다. 단위는 예약 건수입니다.
 * `Set` 값은 음수가 아니어야 하고, 컴파일된 정상 예약 수와 quota 합은 tenant
 * hard ceiling 안에 있어야 합니다. `Disable`은 유효하지 않습니다.
 * @property automaticReductionEnabled 자동 축소 기능의 clinic 지시입니다. 선택 기능이므로
 * `Disable`이 허용되며 컴파일 시 `false`로 해석됩니다.
 */
data class CapacityAndOverbookingOverride(
    val nominalCapacity: OverrideValue<Int>,
    val overbookingQuota: OverrideValue<Int>,
    val automaticReductionEnabled: OverrideValue<Boolean>,
) : SchedulingPolicyPayload, Serializable {
    override val kind: SchedulingPolicyKind = SchedulingPolicyKind.CAPACITY_AND_OVERBOOKING

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 충돌하는 예약 후보의 순위를 정할 때 사용하는 객관적 신뢰도 입력값입니다.
 *
 * 이 계약은 낙인성 고객 라벨을 의도적으로 피합니다. no-show, 당일 취소처럼 관찰된
 * 이력을 stable signal로 표현합니다. 계산된 점수를 제안 순위에 어떻게 반영할지는
 * downstream optimizer가 결정하며, 이 점수만으로 기존 확정 예약을 밀어내면 안 됩니다.
 *
 * @property priorityWeights 설정된 객관 signal별 가중치입니다. key는 사람이 임의로
 * 작성한 고객 분류가 아니라 stable machine identifier이며, weight는 음수가 될 수 없습니다.
 * @property noShowPenalty 기록된 no-show에 대해 차감하는 점수입니다. 단위는 score이며
 * 음수가 될 수 없습니다.
 * @property sameDayCancellationPenalty 고객이 당일 취소한 경우 차감하는 점수입니다.
 * 단위는 score이며 음수가 될 수 없습니다.
 * @property minimumPriorityScore 모든 가중치와 penalty를 적용한 뒤 보장하는 하한입니다.
 * 비활성화할 수 없고 음수가 될 수 없습니다.
 * @property lookbackDays 고객 책임 사건을 조회할 과거 기간입니다. 단위는 일입니다.
 * @property lateCancellationWindowMinutes 진료 시작 전 이 시간 이내의 취소를 late
 * cancellation으로 분류하는 기준입니다. 단위는 분입니다.
 * @property noShowThreshold lookback 기간 안 고객 책임 no-show 누적 기준입니다. `0`은
 * 비활성화를 의미하지 않으며 즉시 제한 기준으로 해석됩니다.
 * @property lateCancellationThreshold lookback 기간 안 고객 책임 late cancellation 누적
 * 기준입니다. `0`은 비활성화를 의미하지 않습니다.
 * @property coolingOffHours 제한 결정의 기본 유효 기간입니다. 단위는 시간입니다.
 * @property thresholdsPresent schema-one legacy payload에 다섯 threshold가 모두 있었는지
 * 나타냅니다. `false`이면 compiler가 reliability threshold 판단을 비활성화하고 기존 scoring
 * 입력만 보존합니다.
 */
data class PriorityAndReliabilityPolicy(
    val priorityWeights: Map<String, Int>,
    val noShowPenalty: Int,
    val sameDayCancellationPenalty: Int,
    val minimumPriorityScore: Int,
    val lookbackDays: Int = BookingReliabilityPolicyDefaults.LOOKBACK_DAYS,
    val lateCancellationWindowMinutes: Int =
        BookingReliabilityPolicyDefaults.LATE_CANCELLATION_WINDOW_MINUTES,
    val noShowThreshold: Int = BookingReliabilityPolicyDefaults.NO_SHOW_THRESHOLD,
    val lateCancellationThreshold: Int =
        BookingReliabilityPolicyDefaults.LATE_CANCELLATION_THRESHOLD,
    val coolingOffHours: Int = BookingReliabilityPolicyDefaults.COOLING_OFF_HOURS,
    val thresholdsPresent: Boolean = true,
) : SchedulingPolicyPayload, Serializable {
    override val kind: SchedulingPolicyKind = SchedulingPolicyKind.PRIORITY_AND_RELIABILITY

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 신뢰도 가중치와 penalty에 대한 clinic 단위 조정입니다.
 *
 * @property priorityWeights signal-weight map 전체를 대체하는 값입니다. 모든 key는
 * 공백이 아니어야 하고 모든 weight는 음수가 될 수 없습니다. `Disable`은 유효하지 않습니다.
 * @property noShowPenalty no-show penalty 대체값입니다. 단위는 score이며 음수가 될 수
 * 없고 `Disable`도 유효하지 않습니다.
 * @property sameDayCancellationPenalty 당일 취소 penalty 대체값입니다. 단위는 score이며
 * 음수가 될 수 없고 `Disable`도 유효하지 않습니다.
 * @property lookbackDays 사건 조회 기간의 clinic 대체값입니다. `Disable`은 유효하지 않습니다.
 * @property lateCancellationWindowMinutes late cancellation cutoff의 clinic 대체값입니다.
 * `Disable`은 유효하지 않습니다.
 * @property noShowThreshold no-show 누적 기준의 clinic 대체값입니다. `Disable`이면 해당
 * 기준만 제한 없이 상속 값으로 유지하고 decision snapshot에서 비활성 경로로 기록합니다.
 * @property lateCancellationThreshold late cancellation 누적 기준의 clinic 대체값입니다.
 * `Disable`이면 해당 기준만 제한 없이 상속 값으로 유지하고 decision snapshot에서 비활성
 * 경로로 기록합니다.
 * @property coolingOffHours 제한 유효 기간의 clinic 대체값입니다. `Disable`은 유효하지 않습니다.
 *
 * tenant [PriorityAndReliabilityPolicy.minimumPriorityScore]는 안전 하한이므로
 * clinic에서 override할 수 없도록 의도적으로 제외했습니다.
 */
data class PriorityAndReliabilityOverride(
    val priorityWeights: OverrideValue<Map<String, Int>>,
    val noShowPenalty: OverrideValue<Int>,
    val sameDayCancellationPenalty: OverrideValue<Int>,
    val lookbackDays: OverrideValue<Int> = OverrideValue.Inherit,
    val lateCancellationWindowMinutes: OverrideValue<Int> = OverrideValue.Inherit,
    val noShowThreshold: OverrideValue<Int> = OverrideValue.Inherit,
    val lateCancellationThreshold: OverrideValue<Int> = OverrideValue.Inherit,
    val coolingOffHours: OverrideValue<Int> = OverrideValue.Inherit,
) : SchedulingPolicyPayload, Serializable {
    override val kind: SchedulingPolicyKind = SchedulingPolicyKind.PRIORITY_AND_RELIABILITY

    companion object {
        private const val serialVersionUID = 1L
    }
}
