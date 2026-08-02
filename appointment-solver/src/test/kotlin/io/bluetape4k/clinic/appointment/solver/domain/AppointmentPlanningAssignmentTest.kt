package io.bluetape4k.clinic.appointment.solver.domain

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class AppointmentPlanningAssignmentTest {

    private data class Snapshot(
        val doctorId: Long,
        val appointmentDate: LocalDate,
        val startTime: LocalTime,
        val endTime: LocalTime,
    )

    private val date = LocalDate.of(2026, 3, 23)
    private val start = LocalTime.of(9, 0)

    @Test
    fun `withAssigned supplies all non-null assignment values`() {
        val planning = AppointmentPlanning(
            id = 1L,
            durationMinutes = 30,
            doctorId = 10L,
            appointmentDate = date,
            startTime = start,
        )

        val snapshot = planning.withAssigned { doctorId, appointmentDate, startTime, endTime ->
            Snapshot(doctorId, appointmentDate, startTime, endTime)
        }

        snapshot shouldBeEqualTo Snapshot(10L, date, start, LocalTime.of(9, 30))
    }

    @Test
    fun `withAssigned returns null for every incomplete planning variable`() {
        val partialEntities = listOf(
            AppointmentPlanning(doctorId = null, appointmentDate = date, startTime = start),
            AppointmentPlanning(doctorId = 10L, appointmentDate = null, startTime = start),
            AppointmentPlanning(doctorId = 10L, appointmentDate = date, startTime = null),
        )

        partialEntities.forEach { planning ->
            val snapshot = planning.withAssigned { doctorId, appointmentDate, startTime, endTime ->
                Snapshot(doctorId, appointmentDate, startTime, endTime)
            }
            snapshot.shouldBeNull()
        }
    }
}
