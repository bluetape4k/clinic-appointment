package io.bluetape4k.clinic.appointment.solver.service

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.clinic.appointment.model.dto.BreakTimeRecord
import io.bluetape4k.clinic.appointment.model.dto.ClinicClosureRecord
import io.bluetape4k.clinic.appointment.model.dto.ClinicDefaultBreakTimeRecord
import io.bluetape4k.clinic.appointment.model.dto.DoctorAbsenceRecord
import io.bluetape4k.clinic.appointment.model.dto.DoctorScheduleRecord
import io.bluetape4k.clinic.appointment.model.dto.HolidayRecord
import io.bluetape4k.clinic.appointment.model.dto.OperatingHoursRecord
import io.bluetape4k.clinic.appointment.model.dto.TreatmentEquipmentRecord
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import io.bluetape4k.clinic.appointment.solver.domain.ClinicFact
import io.bluetape4k.clinic.appointment.solver.domain.DoctorFact
import io.bluetape4k.clinic.appointment.solver.domain.EquipmentFact
import io.bluetape4k.clinic.appointment.solver.domain.ScheduleSolution
import io.bluetape4k.clinic.appointment.solver.domain.TreatmentFact
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class PlanningFactVersionHasherTest {

    @Test
    fun `같은 snapshot은 항상 같은 64자리 SHA-256을 만든다`() {
        val first = PlanningFactVersionHasher.hash(SCOPE, RANGE, solution())
        val second = PlanningFactVersionHasher.hash(SCOPE, RANGE, solution())

        first shouldBeEqualTo second
        first.matches(Regex("[0-9a-f]{64}")).shouldBeTrue()
    }

    @Test
    fun `fact collection 입력 순서가 달라도 digest는 같다`() {
        val ordered = solution(doctors = listOf(doctor(1), doctor(2)))
        val reversed = solution(doctors = listOf(doctor(2), doctor(1)))

        PlanningFactVersionHasher.hash(SCOPE, RANGE, ordered) shouldBeEqualTo
            PlanningFactVersionHasher.hash(SCOPE, RANGE, reversed)
    }

    @Test
    fun `nullable 값과 빈 문자열은 서로 다른 framing을 사용한다`() {
        val withNull = solution(doctorMaxConcurrentPatients = null)
        val withValue = solution(doctorMaxConcurrentPatients = 0)

        PlanningFactVersionHasher.hash(SCOPE, RANGE, withNull) shouldNotBeEqualTo
            PlanningFactVersionHasher.hash(SCOPE, RANGE, withValue)
    }

    @Test
    fun `problem fact field 변경은 digest를 변경한다`() {
        val baseline = PlanningFactVersionHasher.hash(SCOPE, RANGE, solution())
        val changed = PlanningFactVersionHasher.hash(SCOPE, RANGE, solution(clinicSlotMinutes = 60))

        changed shouldNotBeEqualTo baseline
    }

    private fun solution(
        clinicSlotMinutes: Int = 30,
        doctorMaxConcurrentPatients: Int? = 2,
        doctors: List<DoctorFact> = listOf(doctor(1), doctor(2)),
    ): ScheduleSolution = ScheduleSolution(
        clinic = ClinicFact(
            id = SCOPE.clinicId,
            slotDurationMinutes = clinicSlotMinutes,
            maxConcurrentPatients = 2,
            openOnHolidays = false,
        ),
        doctors = doctors.map { doctor ->
            doctor.copy(maxConcurrentPatients = if (doctor.id == 1L) doctorMaxConcurrentPatients else doctor.maxConcurrentPatients)
        },
        treatments = listOf(
            TreatmentFact(
                id = 11,
                defaultDurationMinutes = 30,
                requiredProviderType = "DOCTOR",
                requiresEquipment = true,
                maxConcurrentPatients = 1,
            ),
        ),
        equipments = listOf(EquipmentFact(id = 21, usageDurationMinutes = 30, quantity = 1)),
        operatingHours = listOf(
            OperatingHoursRecord(
                id = 31,
                clinicId = SCOPE.clinicId,
                dayOfWeek = DayOfWeek.MONDAY,
                openTime = LocalTime.of(9, 0),
                closeTime = LocalTime.of(18, 0),
            ),
        ),
        doctorSchedules = listOf(
            DoctorScheduleRecord(
                id = 41,
                doctorId = 1,
                dayOfWeek = DayOfWeek.MONDAY,
                startTime = LocalTime.of(9, 0),
                endTime = LocalTime.of(18, 0),
            ),
        ),
        doctorAbsences = listOf(
            DoctorAbsenceRecord(
                id = 51,
                doctorId = 1,
                absenceDate = RANGE.start,
                startTime = null,
                endTime = null,
                reason = null,
            ),
        ),
        breakTimes = listOf(
            BreakTimeRecord(
                id = 61,
                clinicId = SCOPE.clinicId,
                dayOfWeek = DayOfWeek.MONDAY,
                startTime = LocalTime.of(12, 0),
                endTime = LocalTime.of(13, 0),
            ),
        ),
        defaultBreakTimes = listOf(
            ClinicDefaultBreakTimeRecord(
                id = 71,
                clinicId = SCOPE.clinicId,
                name = "점심",
                startTime = LocalTime.of(12, 0),
                endTime = LocalTime.of(13, 0),
            ),
        ),
        closures = listOf(
            ClinicClosureRecord(
                id = 81,
                clinicId = SCOPE.clinicId,
                closureDate = RANGE.start,
                reason = "점검",
                isFullDay = true,
            ),
        ),
        holidays = listOf(
            HolidayRecord(
                id = 91,
                tenantGroupId = SCOPE.tenantGroupId,
                holidayDate = RANGE.start,
                name = "공휴일",
                recurring = false,
            ),
        ),
        treatmentEquipments = listOf(
            TreatmentEquipmentRecord(id = 101, treatmentTypeId = 11, equipmentId = 21),
        ),
        doctorIds = doctors.map { it.id },
        dateRange = listOf(RANGE.start, RANGE.endInclusive),
        timeSlots = listOf(LocalTime.of(9, 0), LocalTime.of(9, 30)),
    )

    private fun doctor(id: Long): DoctorFact = DoctorFact(
        id = id,
        clinicId = SCOPE.clinicId,
        providerType = "DOCTOR",
        maxConcurrentPatients = 2,
    )

    companion object {
        private val SCOPE = TenantClinicScope(tenantGroupId = 1, clinicId = 7)
        private val RANGE = LocalDate.of(2026, 3, 23)..LocalDate.of(2026, 3, 27)
    }
}
