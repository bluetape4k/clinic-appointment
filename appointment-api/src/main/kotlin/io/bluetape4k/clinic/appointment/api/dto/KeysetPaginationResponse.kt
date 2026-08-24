package io.bluetape4k.clinic.appointment.api.dto

import java.io.Serializable

/** clinic 목록 cursor API의 bounded 응답입니다. */
data class KeysetPageResponse<T : Serializable>(
    val items: List<T>,
    val nextCursor: String? = null,
) : Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }
}
