package io.bluetape4k.clinic.appointment.model.tables

import io.bluetape4k.clinic.appointment.model.commitment.AppointmentModelVersion
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.time
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * 예약 정보 테이블.
 *
 * 환자의 방문 identity와 확정 일정 projection을 저장합니다.
 * [modelVersion]이 [AppointmentModelVersion.COMMITMENT_V2]인 미확정 row는
 * [doctorId], [treatmentTypeId], [appointmentDate], [startTime], [endTime]이 비어 있을 수 있다.
 * 이 값들은 확정 proposal과 allocation을 같은 transaction에서 교체할 때 함께 채워야 한다.
 *
 * [AppointmentModelVersion.LEGACY] 생성 경로는 기존처럼 모든 projection을 입력하며, 조회
 * repository는 불완전한 v2 row를 legacy DTO로 강제 변환하지 않는다.
 *
 * 상태는 [AppointmentState]로 추적되며, 동시 예약 수와 장비 사용량을 인덱싱해 슬롯 계산을 가속화합니다.
 */
object Appointments : LongIdTable("scheduling_appointments") {
    val clinicId = reference("clinic_id", Clinics, onDelete = ReferenceOption.CASCADE)
    val modelVersion =
        enumerationByName<AppointmentModelVersion>("model_version", 24)
            .default(AppointmentModelVersion.LEGACY)
    val doctorId = reference("doctor_id", Doctors, onDelete = ReferenceOption.CASCADE).nullable()
    val treatmentTypeId = reference("treatment_type_id", TreatmentTypes, onDelete = ReferenceOption.CASCADE).nullable()
    val equipmentId = optReference("equipment_id", Equipments, onDelete = ReferenceOption.SET_NULL)
    val patientName = varchar("patient_name", 255)
    val patientPhone = varchar("patient_phone", 50).nullable()
    val patientExternalId = varchar("patient_external_id", 255).nullable()
    val patientReferenceFingerprint = varchar("patient_reference_fingerprint", 128).nullable()
    val appointmentDate = date("appointment_date").nullable()
    val startTime = time("start_time").nullable()
    val endTime = time("end_time").nullable()
    val consultationTopicId = optReference("consultation_topic_id", ConsultationTopics, onDelete = ReferenceOption.SET_NULL)
    val consultationMethod = varchar("consultation_method", 30).nullable()
    val rescheduleFromId = long("reschedule_from_id").nullable()
    val status = appointmentState("status").clientDefault { AppointmentState.REQUESTED }
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)

    init {
        // 의사별 날짜 조회 (중복 체크, 슬롯 조회)
        index("idx_appointments_doctor_date", false, doctorId, appointmentDate)
        // 병원별 날짜+상태 조회 (활성 예약 목록, 상태 일괄 업데이트)
        index("idx_appointments_clinic_date_status", false, clinicId, appointmentDate, status)
        // 장비별 날짜 조회 (장비 사용량 체크)
        index("idx_appointments_equipment_date", false, equipmentId, appointmentDate)
        // 날짜+상태 조회 (전체 활성 예약 조회, 리마인더 스케줄러)
        index("idx_appointments_date_status", false, appointmentDate, status)
        // 정책 미리보기 keyset scan: clinic/status로 범위를 제한한 뒤 예약 시각과 ID 순으로 진행
        index(
            "idx_appointments_policy_preview",
            false,
            clinicId,
            status,
            appointmentDate,
            startTime,
            id,
        )
        // 프로필 재평가 대상의 환자 범위 keyset scan
        index(
            "idx_appointment_profile_reevaluation",
            false,
            clinicId,
            patientReferenceFingerprint,
            id,
        )
    }
}
