package io.bluetape4k.clinic.appointment.solver.domain

import java.io.Serializable

/**
 * 병원 정보 Problem Fact.
 *
 * @property id 병원 ID
 * @property slotDurationMinutes 예약 슬롯 단위(분)
 * @property maxConcurrentPatients 병원 기본 동시 진료 가능 환자 수
 * @property openOnHolidays 공휴일 운영 여부
 */
data class ClinicFact(
    val id: Long,
    val slotDurationMinutes: Int,
    val maxConcurrentPatients: Int,
    val openOnHolidays: Boolean,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
