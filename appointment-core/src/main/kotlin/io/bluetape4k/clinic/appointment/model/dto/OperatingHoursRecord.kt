package io.bluetape4k.clinic.appointment.model.dto

import java.io.Serializable
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * 병원 운영 시간 레코드.
 *
 * @property id 운영 시간 ID
 * @property clinicId 병원 ID
 * @property dayOfWeek 운영 요일
 * @property openTime 운영 시작 시간
 * @property closeTime 운영 종료 시간
 * @property isActive 활성 여부
 */
data class OperatingHoursRecord(
    val id: Long? = null,
    val clinicId: Long,
    val dayOfWeek: DayOfWeek,
    val openTime: LocalTime,
    val closeTime: LocalTime,
    val isActive: Boolean = true,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
