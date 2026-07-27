package io.bluetape4k.clinic.appointment.model.policy

import java.io.Serializable

/**
 * tenant 정책 값 하나에 대해 clinic이 내리는 명시적 override 지시입니다.
 *
 * 세 상태는 Kotlin `null`과 의도적으로 다릅니다. [Inherit]는 tenant 또는 platform에서
 * 해석된 값을 유지하고, [Set]은 clinic 값을 명시하며, [Disable]은 해당 field 계약이
 * 비활성화 가능하다고 표시한 선택 기능만 끕니다. validator와 compiler는 필수 값,
 * 고객 동의 경계, 안전 ceiling에 대한 [Disable]을 거부해야 합니다.
 *
 * @param T 해당 정책 속성이 허용하는 값 타입입니다. public policy payload는 직렬화 가능한
 * scalar, enum, set, map, value object만 사용해 저장된 payload가 장기 계약으로
 * 유지되도록 합니다.
 */
sealed interface OverrideValue<out T> : Serializable {
    /** tenant baseline 또는 platform default에서 해석된 값을 그대로 사용합니다. */
    data object Inherit : OverrideValue<Nothing> {
        private const val serialVersionUID = 1L
    }

    /**
     * 상속된 값을 명시적으로 제공된 clinic 값으로 대체합니다.
     *
     * @property value 후보 clinic 값입니다. 신뢰 경계 밖 입력일 수 있으므로
     * validation/compilation 단계에서 해당 속성의 단위, 범위, non-relaxation 규칙을
     * 다시 통과해야 합니다.
     */
    data class Set<T>(
        val value: T,
    ) : OverrideValue<T> {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    /**
     * clinic scope에서 선택 기능을 비활성화합니다.
     *
     * 이 상태는 필수 field, 확정 예약 고객 동의, 법규/안전 ceiling, 필수 SLA 한도에는
     * 유효하지 않습니다.
     */
    data object Disable : OverrideValue<Nothing> {
        private const val serialVersionUID = 1L
    }
}
