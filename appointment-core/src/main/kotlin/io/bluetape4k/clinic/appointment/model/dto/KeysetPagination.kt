package io.bluetape4k.clinic.appointment.model.dto

import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable

/** clinic 목록 정렬의 exclusive 경계를 나타내는 값 객체입니다. */
data class ClinicKeysetCursor(
    val clinicId: Long,
    val id: Long,
) : Serializable {

    init {
        clinicId.requirePositiveNumber("clinicId")
        id.requirePositiveNumber("id")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** 전체 count 없이 다음 경계만 제공하는 bounded page입니다. */
data class ClinicKeysetPage<T>(
    val content: List<T>,
    val nextCursor: ClinicKeysetCursor?,
)
