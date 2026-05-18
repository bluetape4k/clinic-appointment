package io.bluetape4k.clinic.appointment.api.dto

import io.bluetape4k.support.requireNotNull
import io.bluetape4k.clinic.appointment.model.tables.AppointmentStateHistoryRecord
import java.io.Serializable
import java.time.Instant

/**
 * Appointment state history response for API consumers.
 *
 * @property id history entry ID
 * @property appointmentId appointment ID
 * @property fromState previous state name
 * @property toState new state name
 * @property reason state change reason
 * @property changedAt timestamp of the state change
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
