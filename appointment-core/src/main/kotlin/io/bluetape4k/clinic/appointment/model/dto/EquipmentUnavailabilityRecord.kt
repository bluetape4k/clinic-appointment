package io.bluetape4k.clinic.appointment.model.dto

import io.bluetape4k.clinic.appointment.model.tables.ExceptionType
import java.io.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * 장비 사용불가 스케줄 레코드.
 *
 * @property id 장비 사용불가 스케줄 ID
 * @property equipmentId 장비 ID
 * @property clinicId 병원 ID
 * @property unavailableDate 단일 사용불가 날짜
 * @property isRecurring 반복 스케줄 여부
 * @property recurringDayOfWeek 반복 요일
 * @property effectiveFrom 반복 스케줄 유효 시작일
 * @property effectiveUntil 반복 스케줄 유효 종료일
 * @property startTime 사용불가 시작 시간
 * @property endTime 사용불가 종료 시간
 * @property reason 사용불가 사유
 */
data class EquipmentUnavailabilityRecord(
    val id: Long,
    val equipmentId: Long,
    val clinicId: Long,
    val unavailableDate: LocalDate?,
    val isRecurring: Boolean,
    val recurringDayOfWeek: DayOfWeek?,
    val effectiveFrom: LocalDate,
    val effectiveUntil: LocalDate?,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val reason: String?,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 장비 사용불가 스케줄 예외 레코드.
 *
 * @property id 예외 ID
 * @property unavailabilityId 원본 장비 사용불가 스케줄 ID
 * @property originalDate 예외가 적용되는 원래 날짜
 * @property exceptionType 예외 유형
 * @property rescheduledDate 변경된 날짜
 * @property rescheduledStartTime 변경된 시작 시간
 * @property rescheduledEndTime 변경된 종료 시간
 * @property reason 예외 사유
 */
data class EquipmentUnavailabilityExceptionRecord(
    val id: Long,
    val unavailabilityId: Long,
    val originalDate: LocalDate,
    val exceptionType: ExceptionType,
    val rescheduledDate: LocalDate?,
    val rescheduledStartTime: LocalTime?,
    val rescheduledEndTime: LocalTime?,
    val reason: String?,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
