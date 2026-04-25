package io.bluetape4k.clinic.appointment.model.dto

import java.io.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * 예약 재배정 후보 레코드.
 *
 * @property id 재배정 후보 ID
 * @property originalAppointmentId 원본 예약 ID
 * @property candidateDate 후보 예약 날짜
 * @property startTime 후보 시작 시간
 * @property endTime 후보 종료 시간
 * @property doctorId 후보 의사 ID
 * @property priority 후보 우선순위
 * @property selected 선택 여부
 * @property createdAt 생성 시각
 */
data class RescheduleCandidateRecord(
    val id: Long? = null,
    val originalAppointmentId: Long,
    val candidateDate: LocalDate,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val doctorId: Long,
    val priority: Int = 0,
    val selected: Boolean = false,
    val createdAt: Instant? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
