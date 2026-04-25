package io.bluetape4k.clinic.appointment.solver.domain

import java.io.Serializable

/**
 * 의사 정보 Problem Fact.
 *
 * @property id 의사 ID
 * @property clinicId 소속 병원 ID
 * @property providerType 진료 제공자 유형
 * @property maxConcurrentPatients 의사별 동시 진료 가능 환자 수. null이면 병원 기본값을 사용합니다.
 */
data class DoctorFact(
    val id: Long,
    val clinicId: Long,
    val providerType: String,
    val maxConcurrentPatients: Int?,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
