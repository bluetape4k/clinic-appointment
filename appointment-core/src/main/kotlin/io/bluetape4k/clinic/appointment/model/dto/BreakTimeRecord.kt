package io.bluetape4k.clinic.appointment.model.dto

import java.io.Serializable
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * 병원 휴게 시간 레코드.
 *
 * @property id 휴게 시간 ID
 * @property clinicId 병원 ID
 * @property dayOfWeek 적용 요일
 * @property startTime 휴게 시작 시간
 * @property endTime 휴게 종료 시간
 */
data class BreakTimeRecord(
    val id: Long? = null,
    val clinicId: Long,
    val dayOfWeek: DayOfWeek,
    val startTime: LocalTime,
    val endTime: LocalTime,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
