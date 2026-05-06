package io.bluetape4k.clinic.appointment.event

import java.io.Serializable
import java.time.Instant

/**
 * 예약 이벤트 로그 레코드.
 *
 * @property id 이벤트 로그 ID
 * @property eventType 이벤트 유형
 * @property entityType 이벤트 대상 엔티티 유형
 * @property entityId 이벤트 대상 엔티티 ID
 * @property clinicId 병원 ID
 * @property payloadJson 이벤트 페이로드 JSON
 * @property createdAt 생성 시각
 */
data class AppointmentEventLogRecord(
    val id: Long? = null,
    val eventType: String,
    val entityType: String,
    val entityId: Long,
    val clinicId: Long,
    val payloadJson: String,
    val createdAt: Instant? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
