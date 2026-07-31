package io.bluetape4k.clinic.appointment.model.identity

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

/**
 * 회원 서비스가 발급한 불투명 회원 식별자입니다.
 *
 * 예약 도메인은 이 값을 파싱하거나 의미 있는 부분으로 분해하지 않습니다. 기존
 * `patient_external_id` 호환 컬럼에 저장하되 도메인 경계에서는 [MemberId]로만 다룹니다.
 *
 * @property value 회원 식별자의 원문입니다.
 */
@JvmInline
value class MemberId(
    val value: String,
) : Serializable {
    init {
        value.requireNotBlank("memberId")
        require(value.length <= MAX_LENGTH) {
            "memberId must not exceed $MAX_LENGTH characters"
        }
    }

    companion object {
        private const val serialVersionUID = 1L
        private const val MAX_LENGTH = 255
    }
}
