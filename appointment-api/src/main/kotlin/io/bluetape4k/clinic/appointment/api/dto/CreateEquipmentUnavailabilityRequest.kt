package io.bluetape4k.clinic.appointment.api.dto

import java.io.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * 장비 사용불가 스케줄 생성 요청.
 *
 * @property unavailableDate 사용불가 날짜 (isRecurring=false 시 필수)
 * @property isRecurring 반복 여부
 * @property recurringDayOfWeek 반복 요일 (isRecurring=true 시 필수)
 * @property effectiveFrom 유효 시작일
 * @property effectiveUntil 유효 종료일 (null이면 무기한)
 * @property startTime 사용불가 시작 시간
 * @property endTime 사용불가 종료 시간
 * @property reason 사유
 */
data class CreateEquipmentUnavailabilityRequest(
    val unavailableDate: LocalDate? = null,
    val isRecurring: Boolean = false,
    val recurringDayOfWeek: DayOfWeek? = null,
    val effectiveFrom: LocalDate,
    val effectiveUntil: LocalDate? = null,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val reason: String? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
