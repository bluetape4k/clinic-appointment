package io.bluetape4k.clinic.appointment.model.dto

import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import java.io.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * 예약 레코드.
 *
 * @property id 예약 ID
 * @property clinicId 병원 ID
 * @property doctorId 담당 의사 ID
 * @property treatmentTypeId 진료 유형 ID
 * @property equipmentId 사용 장비 ID
 * @property consultationTopicId 상담 주제 ID
 * @property consultationMethod 상담 방식
 * @property rescheduleFromId 재배정 원본 예약 ID
 * @property patientName 환자명
 * @property patientPhone 환자 전화번호
 * @property patientExternalId 외부 시스템 환자 ID
 * @property appointmentDate 예약 날짜
 * @property startTime 예약 시작 시간
 * @property endTime 예약 종료 시간
 * @property status 예약 상태
 * @property createdAt 생성 시각
 * @property updatedAt 수정 시각
 */
data class AppointmentRecord(
    val id: Long? = null,
    val clinicId: Long,
    val doctorId: Long,
    val treatmentTypeId: Long,
    val equipmentId: Long? = null,
    val consultationTopicId: Long? = null,
    val consultationMethod: String? = null,
    val rescheduleFromId: Long? = null,
    val patientName: String,
    val patientPhone: String? = null,
    val patientExternalId: String? = null,
    val appointmentDate: LocalDate,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val status: AppointmentState = AppointmentState.REQUESTED,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
