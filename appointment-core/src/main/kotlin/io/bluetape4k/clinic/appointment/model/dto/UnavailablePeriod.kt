package io.bluetape4k.clinic.appointment.model.dto

import java.io.Serializable
import java.time.LocalDate
import java.time.LocalTime

/**
 * 반복 규칙에서 전개된 실제 사용불가 기간.
 *
 * @property equipmentId 장비 ID
 * @property date 사용불가 날짜
 * @property startTime 사용불가 시작 시간
 * @property endTime 사용불가 종료 시간
 */
data class UnavailablePeriod(
    val equipmentId: Long,
    val date: LocalDate,
    val startTime: LocalTime,
    val endTime: LocalTime,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
