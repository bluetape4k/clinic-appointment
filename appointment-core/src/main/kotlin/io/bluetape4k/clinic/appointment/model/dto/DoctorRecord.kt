package io.bluetape4k.clinic.appointment.model.dto

import java.io.Serializable

/**
 * 의사 레코드.
 *
 * @property id 의사 ID
 * @property clinicId 소속 병원 ID
 * @property name 의사 이름
 * @property specialty 전문 분야
 * @property providerType 진료 제공자 유형
 * @property maxConcurrentPatients 의사별 동시 진료 가능 환자 수
 */
data class DoctorRecord(
    val id: Long? = null,
    val clinicId: Long,
    val name: String,
    val specialty: String? = null,
    val providerType: String = "DOCTOR",
    val maxConcurrentPatients: Int? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
