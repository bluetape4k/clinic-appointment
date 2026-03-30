package io.bluetape4k.clinic.appointment.api.dto

import java.io.Serializable
import java.time.LocalDate
import java.time.LocalTime

/**
 * 장비 사용불가 스케줄과 충돌하는 예약 정보.
 *
 * @property appointmentId 예약 ID
 * @property patientName 환자명
 * @property appointmentDate 예약 날짜
 * @property startTime 예약 시작 시간
 * @property endTime 예약 종료 시간
 * @property doctorId 담당 의사 ID
 * @property equipmentId 사용 장비 ID
 */
data class ConflictingAppointmentResponse(
    val appointmentId: Long,
    val patientName: String,
    val appointmentDate: LocalDate,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val doctorId: Long,
    val equipmentId: Long,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
