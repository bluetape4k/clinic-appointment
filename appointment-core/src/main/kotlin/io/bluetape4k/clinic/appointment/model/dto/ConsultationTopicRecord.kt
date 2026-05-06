package io.bluetape4k.clinic.appointment.model.dto

import java.io.Serializable

/**
 * 상담 주제 레코드.
 *
 * @property id 상담 주제 ID
 * @property clinicId 병원 ID
 * @property name 상담 주제 이름
 * @property description 상담 주제 설명
 * @property defaultDurationMinutes 기본 상담 시간(분)
 */
data class ConsultationTopicRecord(
    val id: Long? = null,
    val clinicId: Long,
    val name: String,
    val description: String? = null,
    val defaultDurationMinutes: Int = 30,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
