package io.bluetape4k.clinic.appointment.model.dto

import java.io.Serializable
import java.time.LocalDate
import java.time.LocalTime

/**
 * 병원 휴진 레코드.
 *
 * @property id 휴진 ID
 * @property clinicId 병원 ID
 * @property closureDate 휴진 날짜
 * @property reason 휴진 사유
 * @property isFullDay 종일 휴진 여부
 * @property startTime 부분 휴진 시작 시간
 * @property endTime 부분 휴진 종료 시간
 */
data class ClinicClosureRecord(
    val id: Long? = null,
    val clinicId: Long,
    val closureDate: LocalDate,
    val reason: String? = null,
    val isFullDay: Boolean = true,
    val startTime: LocalTime? = null,
    val endTime: LocalTime? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
