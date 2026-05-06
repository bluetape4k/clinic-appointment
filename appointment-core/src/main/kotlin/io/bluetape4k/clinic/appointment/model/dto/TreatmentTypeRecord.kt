package io.bluetape4k.clinic.appointment.model.dto

import java.io.Serializable

/**
 * 진료 유형 레코드.
 *
 * @property id 진료 유형 ID
 * @property clinicId 병원 ID
 * @property name 진료 유형 이름
 * @property category 진료 유형 분류
 * @property defaultDurationMinutes 기본 진료 시간(분)
 * @property requiredProviderType 필요한 진료 제공자 유형
 * @property consultationMethod 상담 방식
 * @property requiresEquipment 장비 필요 여부
 * @property maxConcurrentPatients 진료 유형별 동시 진료 가능 환자 수
 */
data class TreatmentTypeRecord(
    val id: Long? = null,
    val clinicId: Long,
    val name: String,
    val category: String = "TREATMENT",
    val defaultDurationMinutes: Int,
    val requiredProviderType: String = "DOCTOR",
    val consultationMethod: String? = null,
    val requiresEquipment: Boolean = false,
    val maxConcurrentPatients: Int? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
