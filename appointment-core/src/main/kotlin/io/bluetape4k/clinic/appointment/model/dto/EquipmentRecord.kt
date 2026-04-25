package io.bluetape4k.clinic.appointment.model.dto

import java.io.Serializable

/**
 * 장비 레코드.
 *
 * @property id 장비 ID
 * @property clinicId 병원 ID
 * @property name 장비 이름
 * @property usageDurationMinutes 예약당 장비 점유 시간(분)
 * @property quantity 동일 장비 수량
 */
data class EquipmentRecord(
    val id: Long? = null,
    val clinicId: Long,
    val name: String,
    val usageDurationMinutes: Int,
    val quantity: Int = 1,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
