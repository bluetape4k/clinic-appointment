package io.bluetape4k.clinic.appointment.model.dto

import java.io.Serializable
import java.time.LocalTime

/**
 * 병원 기본 휴게 시간 레코드.
 *
 * @property id 기본 휴게 시간 ID
 * @property clinicId 병원 ID
 * @property name 휴게 시간 이름
 * @property startTime 휴게 시작 시간
 * @property endTime 휴게 종료 시간
 */
data class ClinicDefaultBreakTimeRecord(
    val id: Long? = null,
    val clinicId: Long,
    val name: String,
    val startTime: LocalTime,
    val endTime: LocalTime,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
