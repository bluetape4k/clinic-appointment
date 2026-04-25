package io.bluetape4k.clinic.appointment.model.dto

import java.io.Serializable
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * 의사 근무 시간 레코드.
 *
 * @property id 근무 시간 ID
 * @property doctorId 의사 ID
 * @property dayOfWeek 근무 요일
 * @property startTime 근무 시작 시간
 * @property endTime 근무 종료 시간
 */
data class DoctorScheduleRecord(
    val id: Long? = null,
    val doctorId: Long,
    val dayOfWeek: DayOfWeek,
    val startTime: LocalTime,
    val endTime: LocalTime,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
