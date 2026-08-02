package io.bluetape4k.clinic.appointment.solver.domain

import java.time.LocalDate
import java.time.LocalTime

inline fun <T> AppointmentPlanning.withAssigned(
    block: (doctorId: Long, appointmentDate: LocalDate, startTime: LocalTime, endTime: LocalTime) -> T,
): T? {
    val doctorId = this.doctorId ?: return null
    val appointmentDate = this.appointmentDate ?: return null
    val startTime = this.startTime ?: return null
    val endTime = this.endTime ?: return null
    return block(doctorId, appointmentDate, startTime, endTime)
}
