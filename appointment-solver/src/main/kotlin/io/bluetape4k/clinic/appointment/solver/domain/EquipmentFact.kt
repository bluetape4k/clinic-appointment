package io.bluetape4k.clinic.appointment.solver.domain

import java.io.Serializable

/**
 * 장비 정보 Problem Fact.
 *
 * @property id 장비 ID
 * @property usageDurationMinutes 장비 점유 시간(분)
 * @property quantity 동일 장비 수량
 */
data class EquipmentFact(
    val id: Long,
    val usageDurationMinutes: Int,
    val quantity: Int,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
