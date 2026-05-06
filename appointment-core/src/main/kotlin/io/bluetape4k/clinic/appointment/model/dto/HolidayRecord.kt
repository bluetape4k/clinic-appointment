package io.bluetape4k.clinic.appointment.model.dto

import java.io.Serializable
import java.time.LocalDate

/**
 * 휴일 레코드.
 *
 * @property id 휴일 ID
 * @property holidayDate 휴일 날짜
 * @property name 휴일 이름
 * @property recurring 매년 반복 여부
 */
data class HolidayRecord(
    val id: Long? = null,
    val holidayDate: LocalDate,
    val name: String,
    val recurring: Boolean = false,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
