package io.bluetape4k.clinic.appointment.solver.domain

import java.time.LocalDate
import java.time.LocalTime

/**
 * Executes [block] only when all nullable planning values are assigned.
 *
 * A partially initialized Timefold entity is valid solver input, but it is not a
 * complete appointment result or constraint predicate. In that state this
 * helper returns `null` without manufacturing a default assignment.
 */
internal inline fun <T> AppointmentPlanning.withAssigned(
    block: (doctorId: Long, appointmentDate: LocalDate, startTime: LocalTime, endTime: LocalTime) -> T,
): T? {
    val doctorId = this.doctorId ?: return null
    val appointmentDate = this.appointmentDate ?: return null
    val startTime = this.startTime ?: return null
    val endTime = this.endTime ?: return null
    return block(doctorId, appointmentDate, startTime, endTime)
}
