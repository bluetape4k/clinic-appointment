package io.bluetape4k.clinic.appointment.solver.domain

import java.io.Serializable

/**
 * 진료 유형 Problem Fact.
 *
 * @property id 진료 유형 ID
 * @property defaultDurationMinutes 기본 진료 시간(분)
 * @property requiredProviderType 필요한 진료 제공자 유형
 * @property requiresEquipment 장비 필요 여부
 * @property maxConcurrentPatients 동시 진료 가능 환자 수. null이면 병원 기본값을 사용합니다.
 */
data class TreatmentFact(
    val id: Long,
    val defaultDurationMinutes: Int,
    val requiredProviderType: String,
    val requiresEquipment: Boolean,
    val maxConcurrentPatients: Int?,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
