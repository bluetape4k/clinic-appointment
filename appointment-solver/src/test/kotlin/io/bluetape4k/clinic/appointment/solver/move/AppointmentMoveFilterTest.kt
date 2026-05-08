package io.bluetape4k.clinic.appointment.solver.move

import ai.timefold.solver.core.api.score.director.ScoreDirector
import ai.timefold.solver.core.impl.heuristic.move.Move
import io.bluetape4k.clinic.appointment.model.dto.ClinicClosureRecord
import io.bluetape4k.clinic.appointment.model.dto.DoctorAbsenceRecord
import io.bluetape4k.clinic.appointment.model.dto.HolidayRecord
import io.bluetape4k.clinic.appointment.model.dto.OperatingHoursRecord
import io.bluetape4k.clinic.appointment.solver.domain.AppointmentPlanning
import io.bluetape4k.clinic.appointment.solver.domain.ClinicFact
import io.bluetape4k.clinic.appointment.solver.domain.DoctorFact
import io.bluetape4k.clinic.appointment.solver.domain.ScheduleSolution
import io.mockk.every
import io.mockk.mockk
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class AppointmentMoveFilterTest {

    private val filter = AppointmentMoveFilter()

    private val clinicId = 1L
    private val doctorId = 10L
    private val monday = LocalDate.of(2026, 4, 6) // Monday

    private val clinic = ClinicFact(id = clinicId, slotDurationMinutes = 30, maxConcurrentPatients = 3, openOnHolidays = false)
    private val doctor = DoctorFact(id = doctorId, clinicId = clinicId, providerType = "DOCTOR", maxConcurrentPatients = 1)
    private val operatingHours = OperatingHoursRecord(clinicId = clinicId, dayOfWeek = DayOfWeek.MONDAY, openTime = LocalTime.of(9, 0), closeTime = LocalTime.of(18, 0), isActive = true)

    private lateinit var entity: AppointmentPlanning
    private lateinit var sd: ScoreDirector<ScheduleSolution>
    private lateinit var move: Move<ScheduleSolution>

    @BeforeEach
    fun setup() {
        entity = AppointmentPlanning(
            id = 1L,
            clinicId = clinicId,
            requiredProviderType = "DOCTOR",
        )

        sd = mockk()
        move = mockk()
        every { move.planningEntities } returns listOf(entity)
    }

    private fun solutionWith(
        closures: List<ClinicClosureRecord> = emptyList(),
        holidays: List<HolidayRecord> = emptyList(),
        operatingHoursList: List<OperatingHoursRecord> = listOf(operatingHours),
        doctorAbsences: List<DoctorAbsenceRecord> = emptyList(),
        doctors: List<DoctorFact> = listOf(doctor),
        openOnHolidays: Boolean = false,
    ) = ScheduleSolution(
        clinic = clinic.copy(openOnHolidays = openOnHolidays),
        doctors = doctors,
        closures = closures,
        holidays = holidays,
        operatingHours = operatingHoursList,
        doctorAbsences = doctorAbsences,
    )

    @Test
    fun `accept returns true for valid date and doctor`() {
        val solution = solutionWith()
        every { sd.workingSolution } returns solution
        every { move.planningValues } returns listOf(monday, doctorId)

        filter.accept(sd, move).shouldBeTrue()
    }

    @Test
    fun `accept returns false when full-day clinic closure`() {
        val closure = ClinicClosureRecord(clinicId = clinicId, closureDate = monday, isFullDay = true)
        val solution = solutionWith(closures = listOf(closure))
        every { sd.workingSolution } returns solution
        every { move.planningValues } returns listOf(monday, doctorId)

        filter.accept(sd, move).shouldBeFalse()
    }

    @Test
    fun `accept returns true when partial-day closure (isFullDay=false)`() {
        val closure = ClinicClosureRecord(clinicId = clinicId, closureDate = monday, isFullDay = false, startTime = LocalTime.of(14, 0))
        val solution = solutionWith(closures = listOf(closure))
        every { sd.workingSolution } returns solution
        every { move.planningValues } returns listOf(monday, doctorId)

        filter.accept(sd, move).shouldBeTrue()
    }

    @Test
    fun `accept returns false when no operating hours for day of week`() {
        val solution = solutionWith(operatingHoursList = emptyList())
        every { sd.workingSolution } returns solution
        every { move.planningValues } returns listOf(monday, doctorId)

        filter.accept(sd, move).shouldBeFalse()
    }

    @Test
    fun `accept returns false on holiday when openOnHolidays is false`() {
        val holiday = HolidayRecord(holidayDate = monday, name = "Holiday")
        val solution = solutionWith(holidays = listOf(holiday), openOnHolidays = false)
        every { sd.workingSolution } returns solution
        every { move.planningValues } returns listOf(monday, doctorId)

        filter.accept(sd, move).shouldBeFalse()
    }

    @Test
    fun `accept returns true on holiday when openOnHolidays is true`() {
        val holiday = HolidayRecord(holidayDate = monday, name = "Holiday")
        val solution = solutionWith(holidays = listOf(holiday), openOnHolidays = true)
        every { sd.workingSolution } returns solution
        every { move.planningValues } returns listOf(monday, doctorId)

        filter.accept(sd, move).shouldBeTrue()
    }

    @Test
    fun `accept returns false when doctor has full-day absence`() {
        val absence = DoctorAbsenceRecord(doctorId = doctorId, absenceDate = monday, startTime = null)
        val solution = solutionWith(doctorAbsences = listOf(absence))
        every { sd.workingSolution } returns solution
        every { move.planningValues } returns listOf(monday, doctorId)

        filter.accept(sd, move).shouldBeFalse()
    }

    @Test
    fun `accept returns true when doctor has partial absence (startTime not null)`() {
        val absence = DoctorAbsenceRecord(doctorId = doctorId, absenceDate = monday, startTime = LocalTime.of(14, 0))
        val solution = solutionWith(doctorAbsences = listOf(absence))
        every { sd.workingSolution } returns solution
        every { move.planningValues } returns listOf(monday, doctorId)

        filter.accept(sd, move).shouldBeTrue()
    }

    @Test
    fun `accept returns false when doctor belongs to different clinic`() {
        val wrongClinicDoctor = DoctorFact(id = doctorId, clinicId = 999L, providerType = "DOCTOR", maxConcurrentPatients = 1)
        val solution = solutionWith(doctors = listOf(wrongClinicDoctor))
        every { sd.workingSolution } returns solution
        every { move.planningValues } returns listOf(monday, doctorId)

        filter.accept(sd, move).shouldBeFalse()
    }

    @Test
    fun `accept returns false when doctor provider type mismatches`() {
        val nurseDoctor = DoctorFact(id = doctorId, clinicId = clinicId, providerType = "NURSE", maxConcurrentPatients = 1)
        val solution = solutionWith(doctors = listOf(nurseDoctor))
        every { sd.workingSolution } returns solution
        every { move.planningValues } returns listOf(monday, doctorId)

        filter.accept(sd, move).shouldBeFalse()
    }

    @Test
    fun `accept returns false when doctor not found in solution`() {
        val solution = solutionWith(doctors = emptyList())
        every { sd.workingSolution } returns solution
        every { move.planningValues } returns listOf(monday, doctorId)

        filter.accept(sd, move).shouldBeFalse()
    }

    @Test
    fun `accept returns true when planning values are empty (uses entity values)`() {
        entity.doctorId = doctorId
        entity.appointmentDate = monday
        val solution = solutionWith()
        every { sd.workingSolution } returns solution
        every { move.planningValues } returns emptyList<Any>()

        filter.accept(sd, move).shouldBeTrue()
    }
}
