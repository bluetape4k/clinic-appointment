package io.bluetape4k.clinic.appointment.api.dto

import com.fasterxml.jackson.annotation.JsonInclude
import java.io.Serializable

/** waitlist API가 공개하는 privacy-safe 오류 envelope입니다. */
data class WaitlistApiErrorResponse(
    val error: String,
    val reasonCode: String,
    val correlationId: String,
    val retryable: Boolean,
    val action: String,
    val retryAfterSeconds: Long? = null,
    /** 기존 API envelope와의 호환성을 위해 오류 응답은 항상 실패 상태를 명시합니다. */
    val success: Boolean = false,
    @field:JsonInclude(JsonInclude.Include.ALWAYS)
    val data: Any? = null,
    /** 기존 scheduling 오류 소비자가 사용하는 별칭이며 reasonCode와 항상 같습니다. */
    val errorCode: String = reasonCode,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
