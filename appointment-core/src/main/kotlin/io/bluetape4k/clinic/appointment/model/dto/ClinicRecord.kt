package io.bluetape4k.clinic.appointment.model.dto

import java.io.Serializable

/**
 * 병원 레코드.
 *
 * @property id 병원 ID
 * @property name 병원 이름
 * @property slotDurationMinutes 예약 슬롯 단위(분)
 * @property timezone 병원 타임존 ID
 * @property locale 병원 기본 locale
 * @property maxConcurrentPatients 병원 기본 동시 진료 가능 환자 수
 * @property openOnHolidays 공휴일 운영 여부
 */
data class ClinicRecord(
    val id: Long? = null,
    val name: String,
    val slotDurationMinutes: Int = 30,
    val timezone: String = "UTC",
    val locale: String = "ko-KR",
    val maxConcurrentPatients: Int = 1,
    val openOnHolidays: Boolean = false,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
