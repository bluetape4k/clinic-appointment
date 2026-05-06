package io.bluetape4k.clinic.appointment.solver.converter

import io.bluetape4k.clinic.appointment.model.dto.AppointmentRecord
import io.bluetape4k.clinic.appointment.model.dto.ClinicRecord
import io.bluetape4k.clinic.appointment.model.dto.DoctorRecord
import io.bluetape4k.clinic.appointment.model.dto.EquipmentRecord
import io.bluetape4k.clinic.appointment.model.dto.OperatingHoursRecord
import io.bluetape4k.clinic.appointment.model.dto.TreatmentTypeRecord
import io.bluetape4k.clinic.appointment.solver.domain.AppointmentPlanning
import io.bluetape4k.clinic.appointment.solver.domain.ScheduleSolution
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldHaveSize
import org.amshove.kluent.shouldNotBeEmpty
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class SolutionConverterTest {

    private val clinicId = 1L
    private val doctorId = 10L
    private val treatmentId = 100L

    private val clinic = ClinicRecord(
        id = clinicId,
        name = "Test Clinic",
        slotDurationMinutes = 30,
        timezone = "Asia/Seoul",
        locale = "ko-KR",
        maxConcurrentPatients = 3,
        openOnHolidays = false,
    )

    private val doctor = DoctorRecord(
        id = doctorId,
        clinicId = clinicId,
        name = "Dr. Test",
        providerType = "DOCTOR",
    )

    private val treatment = TreatmentTypeRecord(
        id = treatmentId,
        clinicId = clinicId,
        name = "General Checkup",
        defaultDurationMinutes = 30,
        requiredProviderType = "DOCTOR",
        requiresEquipment = false,
    )

    private val operatingHours = OperatingHoursRecord(
        clinicId = clinicId,
        dayOfWeek = DayOfWeek.MONDAY,
        openTime = LocalTime.of(9, 0),
        closeTime = LocalTime.of(18, 0),
        isActive = true,
    )

    private val appointment = AppointmentRecord(
        id = 1L,
        clinicId = clinicId,
        doctorId = doctorId,
        treatmentTypeId = treatmentId,
        patientName = "John Doe",
        appointmentDate = LocalDate.of(2026, 4, 6),
        startTime = LocalTime.of(10, 0),
        endTime = LocalTime.of(10, 30),
        status = AppointmentState.REQUESTED,
    )

    private val dateRange = LocalDate.of(2026, 4, 6)..LocalDate.of(2026, 4, 12)

    @Test
    fun `buildSolution creates solution with clinic fact`() {
        val solution = SolutionConverter.buildSolution(
            clinic = clinic,
            doctors = listOf(doctor),
            appointments = listOf(appointment),
            treatments = listOf(treatment),
            equipments = emptyList(),
            operatingHours = listOf(operatingHours),
            doctorSchedules = emptyList(),
            doctorAbsences = emptyList(),
            breakTimes = emptyList(),
            defaultBreakTimes = emptyList(),
            closures = emptyList(),
            holidays = emptyList(),
            treatmentEquipments = emptyList(),
            dateRange = dateRange,
        )

        solution.clinic.id shouldBeEqualTo clinicId
        solution.clinic.slotDurationMinutes shouldBeEqualTo 30
        solution.clinic.openOnHolidays shouldBeEqualTo false
    }

    @Test
    fun `buildSolution maps doctors correctly`() {
        val solution = SolutionConverter.buildSolution(
            clinic = clinic,
            doctors = listOf(doctor),
            appointments = emptyList(),
            treatments = listOf(treatment),
            equipments = emptyList(),
            operatingHours = listOf(operatingHours),
            doctorSchedules = emptyList(),
            doctorAbsences = emptyList(),
            breakTimes = emptyList(),
            defaultBreakTimes = emptyList(),
            closures = emptyList(),
            holidays = emptyList(),
            treatmentEquipments = emptyList(),
            dateRange = dateRange,
        )

        solution.doctors shouldHaveSize 1
        solution.doctors[0].id shouldBeEqualTo doctorId
        solution.doctors[0].clinicId shouldBeEqualTo clinicId
    }

    @Test
    fun `buildSolution creates planning entity for appointment`() {
        val solution = SolutionConverter.buildSolution(
            clinic = clinic,
            doctors = listOf(doctor),
            appointments = listOf(appointment),
            treatments = listOf(treatment),
            equipments = emptyList(),
            operatingHours = listOf(operatingHours),
            doctorSchedules = emptyList(),
            doctorAbsences = emptyList(),
            breakTimes = emptyList(),
            defaultBreakTimes = emptyList(),
            closures = emptyList(),
            holidays = emptyList(),
            treatmentEquipments = emptyList(),
            dateRange = dateRange,
        )

        solution.appointments shouldHaveSize 1
        val planning = solution.appointments[0]
        planning.id shouldBeEqualTo 1L
        planning.durationMinutes shouldBeEqualTo 30
        planning.pinned shouldBeEqualTo false
    }

    @Test
    fun `buildSolution pins confirmed appointments`() {
        val confirmedAppointment = appointment.copy(status = AppointmentState.CONFIRMED)

        val solution = SolutionConverter.buildSolution(
            clinic = clinic,
            doctors = listOf(doctor),
            appointments = listOf(confirmedAppointment),
            treatments = listOf(treatment),
            equipments = emptyList(),
            operatingHours = listOf(operatingHours),
            doctorSchedules = emptyList(),
            doctorAbsences = emptyList(),
            breakTimes = emptyList(),
            defaultBreakTimes = emptyList(),
            closures = emptyList(),
            holidays = emptyList(),
            treatmentEquipments = emptyList(),
            dateRange = dateRange,
        )

        solution.appointments[0].pinned shouldBeEqualTo true
    }

    @Test
    fun `buildSolution generates time slots from operating hours`() {
        val solution = SolutionConverter.buildSolution(
            clinic = clinic,
            doctors = listOf(doctor),
            appointments = emptyList(),
            treatments = listOf(treatment),
            equipments = emptyList(),
            operatingHours = listOf(operatingHours),
            doctorSchedules = emptyList(),
            doctorAbsences = emptyList(),
            breakTimes = emptyList(),
            defaultBreakTimes = emptyList(),
            closures = emptyList(),
            holidays = emptyList(),
            treatmentEquipments = emptyList(),
            dateRange = dateRange,
        )

        solution.timeSlots.shouldNotBeEmpty()
        solution.timeSlots shouldContain LocalTime.of(9, 0)
    }

    @Test
    fun `buildSolution generates date range`() {
        val solution = SolutionConverter.buildSolution(
            clinic = clinic,
            doctors = listOf(doctor),
            appointments = emptyList(),
            treatments = listOf(treatment),
            equipments = emptyList(),
            operatingHours = listOf(operatingHours),
            doctorSchedules = emptyList(),
            doctorAbsences = emptyList(),
            breakTimes = emptyList(),
            defaultBreakTimes = emptyList(),
            closures = emptyList(),
            holidays = emptyList(),
            treatmentEquipments = emptyList(),
            dateRange = dateRange,
        )

        solution.dateRange shouldHaveSize 7
        solution.dateRange shouldContain LocalDate.of(2026, 4, 6)
        solution.dateRange shouldContain LocalDate.of(2026, 4, 12)
    }

    @Test
    fun `extractResults returns results for solved appointments`() {
        val solvedPlanning = AppointmentPlanning(
            id = 1L,
            clinicId = clinicId,
            patientName = "John Doe",
            durationMinutes = 30,
            doctorId = doctorId,
            appointmentDate = LocalDate.of(2026, 4, 6),
            startTime = LocalTime.of(10, 0),
            pinned = false,
        )

        val solution = ScheduleSolution(
            appointments = listOf(solvedPlanning),
        )

        val results = SolutionConverter.extractResults(solution, mapOf(appointment.id!! to appointment))

        results shouldHaveSize 1
        results[0].doctorId shouldBeEqualTo doctorId
        results[0].patientName shouldBeEqualTo "John Doe"
    }

    @Test
    fun `extractResults skips appointments without doctor assigned`() {
        val unassigned = AppointmentPlanning(
            id = 1L,
            clinicId = clinicId,
            patientName = "Jane Doe",
            durationMinutes = 30,
            doctorId = null,
            appointmentDate = LocalDate.of(2026, 4, 6),
            startTime = LocalTime.of(10, 0),
        )

        val solution = ScheduleSolution(appointments = listOf(unassigned))
        val results = SolutionConverter.extractResults(solution, mapOf(appointment.id!! to appointment))

        results shouldHaveSize 0
    }

    @Test
    fun `extractResults skips appointments without date or time`() {
        val noDate = AppointmentPlanning(
            id = 1L,
            clinicId = clinicId,
            patientName = "Jane Doe",
            durationMinutes = 30,
            doctorId = doctorId,
            appointmentDate = null,
            startTime = null,
        )

        val solution = ScheduleSolution(appointments = listOf(noDate))
        val results = SolutionConverter.extractResults(solution, mapOf(appointment.id!! to appointment))

        results shouldHaveSize 0
    }

    @Test
    fun `buildSolution handles equipments`() {
        val equipment = EquipmentRecord(
            id = 200L,
            clinicId = clinicId,
            name = "MRI",
            usageDurationMinutes = 60,
            quantity = 1,
        )

        val solution = SolutionConverter.buildSolution(
            clinic = clinic,
            doctors = listOf(doctor),
            appointments = emptyList(),
            treatments = listOf(treatment),
            equipments = listOf(equipment),
            operatingHours = listOf(operatingHours),
            doctorSchedules = emptyList(),
            doctorAbsences = emptyList(),
            breakTimes = emptyList(),
            defaultBreakTimes = emptyList(),
            closures = emptyList(),
            holidays = emptyList(),
            treatmentEquipments = emptyList(),
            dateRange = dateRange,
        )

        solution.equipments shouldHaveSize 1
        solution.equipments[0].id shouldBeEqualTo 200L
    }
}
