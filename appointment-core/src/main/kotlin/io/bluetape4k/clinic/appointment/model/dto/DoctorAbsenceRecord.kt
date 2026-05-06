package io.bluetape4k.clinic.appointment.model.dto

import java.io.Serializable
import java.time.LocalDate
import java.time.LocalTime

/**
 * 의사 부재 레코드.
 *
 * @property id 부재 ID
 * @property doctorId 의사 ID
 * @property absenceDate 부재 날짜
 * @property startTime 부재 시작 시간. null이면 종일 부재입니다.
 * @property endTime 부재 종료 시간. null이면 종일 부재입니다.
 * @property reason 부재 사유
 */
data class DoctorAbsenceRecord(
    val id: Long? = null,
    val doctorId: Long,
    val absenceDate: LocalDate,
    val startTime: LocalTime? = null,
    val endTime: LocalTime? = null,
    val reason: String? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
