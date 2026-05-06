package io.bluetape4k.clinic.appointment.model.dto

import java.io.Serializable

/**
 * 진료 유형과 장비의 연결 레코드.
 *
 * @property id 연결 ID
 * @property treatmentTypeId 진료 유형 ID
 * @property equipmentId 장비 ID
 */
data class TreatmentEquipmentRecord(
    val id: Long? = null,
    val treatmentTypeId: Long,
    val equipmentId: Long,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
