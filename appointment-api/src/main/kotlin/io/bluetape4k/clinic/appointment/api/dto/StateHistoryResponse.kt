package io.bluetape4k.clinic.appointment.api.dto

import io.bluetape4k.support.requireNotNull
import io.bluetape4k.clinic.appointment.model.tables.AppointmentStateHistoryRecord
import java.io.Serializable
import java.time.Instant

/**
 * API 소비자를 위한 예약 상태 이력 응답입니다.
 *
 * @property id 이력 항목 ID
 * @property appointmentId 예약 ID
 * @property fromState 이전 상태 이름
 * @property toState 새 상태 이름
 * @property reason 상태 변경 사유
 * @property changedAt 상태가 변경된 시각
 */
data class StateHistoryResponse(
    val id: Long,
    val appointmentId: Long,
    val fromState: String,
    val toState: String,
    val reason: String? = null,
    val changedAt: Instant? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

fun AppointmentStateHistoryRecord.toResponse() = StateHistoryResponse(
    id = id.requireNotNull("id"),
    appointmentId = appointmentId,
    fromState = fromState.name,
    toState = toState.name,
    reason = reason,
    changedAt = changedAt,
)
