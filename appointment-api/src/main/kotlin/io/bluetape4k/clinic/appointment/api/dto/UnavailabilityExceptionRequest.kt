package io.bluetape4k.clinic.appointment.api.dto

import io.bluetape4k.clinic.appointment.model.tables.ExceptionType
import java.io.Serializable
import java.time.LocalDate
import java.time.LocalTime

/**
 * 장비 사용불가 예외 처리 요청.
 *
 * @property originalDate 원래 사용불가 날짜
 * @property exceptionType 예외 유형 (SKIP: 해당 날짜 건너뜀, RESCHEDULE: 일정 변경)
 * @property rescheduledDate 변경된 날짜 (exceptionType=RESCHEDULE 시 필수)
 * @property rescheduledStartTime 변경된 시작 시간 (exceptionType=RESCHEDULE 시 필수)
 * @property rescheduledEndTime 변경된 종료 시간 (exceptionType=RESCHEDULE 시 필수)
 * @property reason 사유
 */
data class UnavailabilityExceptionRequest(
    val originalDate: LocalDate,
    val exceptionType: ExceptionType,
    val rescheduledDate: LocalDate? = null,
    val rescheduledStartTime: LocalTime? = null,
    val rescheduledEndTime: LocalTime? = null,
    val reason: String? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
