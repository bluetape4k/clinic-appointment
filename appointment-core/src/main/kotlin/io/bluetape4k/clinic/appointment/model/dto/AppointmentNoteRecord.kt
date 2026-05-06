package io.bluetape4k.clinic.appointment.model.dto

import java.io.Serializable
import java.time.Instant

/**
 * 예약 노트 레코드.
 *
 * @property id 예약 노트 ID
 * @property appointmentId 예약 ID
 * @property noteType 노트 유형
 * @property content 노트 내용
 * @property createdBy 작성자
 * @property createdAt 작성 시각
 */
data class AppointmentNoteRecord(
    val id: Long? = null,
    val appointmentId: Long,
    val noteType: String,
    val content: String,
    val createdBy: String? = null,
    val createdAt: Instant? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
