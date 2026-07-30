package io.bluetape4k.clinic.appointment.event.profile

import io.bluetape4k.clinic.appointment.event.integration.CanonicalFrameWriter
import java.security.MessageDigest
import java.time.Instant

/**
 * CRM이 산출한 예약 적합성 평가가 바뀌었다는 최소 신호입니다.
 *
 * 원본 프로필, 객관적 특징, 점수, 설명, 교정값은 CRM 경계 밖으로 전달하지 않습니다.
 * 예약서비스는 비식별 환자 지문과 재평가 결과 조회 참조만 받아 진행 중 예약을 다시
 * 평가합니다.
 */
data class PatientSchedulingAssessmentChanged(
    val eventId: String,
    val tenantGroupId: Long,
    val clinicId: Long,
    val patientReferenceFingerprint: String,
    val profileRevision: Long,
    val materialChange: Boolean,
    val assessmentRef: String,
    val assessmentHash: String,
    val occurredAt: Instant,
)

/**
 * 프로필 변경 payload의 field 순서와 타입을 고정한 SHA-256 계산기입니다.
 */
object PatientSchedulingAssessmentChangedHasher {
    fun hash(event: PatientSchedulingAssessmentChanged): String =
        MessageDigest.getInstance("SHA-256")
            .digest(canonicalBytes(event))
            .joinToString("") { byte -> "%02x".format(byte) }

    internal fun canonicalBytes(event: PatientSchedulingAssessmentChanged): ByteArray =
        CanonicalFrameWriter().apply {
            string("eventId", event.eventId)
            long("tenantGroupId", event.tenantGroupId)
            long("clinicId", event.clinicId)
            string("patientReferenceFingerprint", event.patientReferenceFingerprint)
            long("profileRevision", event.profileRevision)
            boolean("materialChange", event.materialChange)
            string("assessmentRef", event.assessmentRef)
            string("assessmentHash", event.assessmentHash)
            instant("occurredAt", event.occurredAt)
        }.toByteArray()
}
