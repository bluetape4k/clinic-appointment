package io.bluetape4k.clinic.appointment.api.dto

import jakarta.validation.constraints.NotBlank
import java.io.Serializable

/**
 * 예약 상태 변경 요청.
 *
 * @property status 변경할 상태 (REQUESTED, CONFIRMED, CHECKED_IN, IN_PROGRESS, COMPLETED, CANCELLED 등)
 * @property reason 상태 변경 사유. `CANCELLED`는 등록된 대문자 reason code만 허용한다.
 * 다른 legacy 상태 전이의 자유 입력은 상태 머신 호환성에만 사용하며 durable messaging,
 * audit payload, 일반 로그에는 복제하지 않는다.
 */
data class UpdateStatusRequest(
    @field:NotBlank
    val status: String,
    val reason: String? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
