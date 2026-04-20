package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.clinic.appointment.model.tables.AppointmentNotes
import io.bluetape4k.clinic.appointment.model.tables.AppointmentStateHistory
import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.tables.BreakTimes
import io.bluetape4k.clinic.appointment.model.tables.ClinicClosures
import io.bluetape4k.clinic.appointment.model.tables.ClinicDefaultBreakTimes
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.ConsultationTopics
import io.bluetape4k.clinic.appointment.model.tables.DoctorAbsences
import io.bluetape4k.clinic.appointment.model.tables.DoctorSchedules
import io.bluetape4k.clinic.appointment.model.tables.Doctors
import io.bluetape4k.clinic.appointment.model.tables.Equipments
import io.bluetape4k.clinic.appointment.model.tables.Holidays
import io.bluetape4k.clinic.appointment.model.tables.OperatingHoursTable
import io.bluetape4k.clinic.appointment.model.tables.RescheduleCandidates
import io.bluetape4k.clinic.appointment.model.tables.TreatmentEquipments
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import io.bluetape4k.clinic.appointment.test.AbstractExposedTest
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withTables
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotNull
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldHaveSize
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class ClosureRescheduleServiceTest : AbstractExposedTest() {

    companion object : KLogging() {
        private val slotService = SlotCalculationService()
        private val rescheduleService = ClosureRescheduleService(slotService)

        private val MONDAY = LocalDate.of(2026, 3, 23)
        private val TUESDAY = LocalDate.of(2026, 3, 24)

        private val allTables = arrayOf(
            Holidays,
            Clinics,
            ClinicDefaultBreakTimes,
            OperatingHoursTable,
            BreakTimes,
            ClinicClosures,
            Doctors,
            DoctorSchedules,
            DoctorAbsences,
            Equipments,
            TreatmentTypes,
            TreatmentEquipments,
            ConsultationTopics,
            Appointments,
            AppointmentNotes,
            AppointmentStateHistory,
            RescheduleCandidates,
        )
    }

    /**
     * 기본 데이터 삽입: 병원 + 의사 + 진료유형 + 월/화 스케줄 + 월요일 예약 1건
     */
    private fun JdbcTransaction.insertDataWithAppointment(): Triple<Long, Long, Long> {
        val clinicId = Clinics.insertAndGetId {
            it[name] = "Test Clinic"
            it[slotDurationMinutes] = 30
            it[maxConcurrentPatients] = 1
        }

        for (day in listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY)) {
            OperatingHoursTable.insert {
                it[OperatingHoursTable.clinicId] = clinicId
                it[dayOfWeek] = day
                it[openTime] = LocalTime.of(9, 0)
                it[closeTime] = LocalTime.of(18, 0)
                it[isActive] = true
            }
        }

        val doctorId = Doctors.insertAndGetId {
            it[Doctors.clinicId] = clinicId
            it[name] = "Dr. Kim"
        }

        for (day in listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY)) {
            DoctorSchedules.insert {
                it[DoctorSchedules.doctorId] = doctorId
                it[dayOfWeek] = day
                it[startTime] = LocalTime.of(9, 0)
                it[endTime] = LocalTime.of(18, 0)
            }
        }

        val treatmentTypeId = TreatmentTypes.insertAndGetId {
            it[TreatmentTypes.clinicId] = clinicId
            it[name] = "General Checkup"
            it[defaultDurationMinutes] = 30
        }

        Appointments.insert {
            it[Appointments.clinicId] = clinicId
            it[Appointments.doctorId] = doctorId
            it[Appointments.treatmentTypeId] = treatmentTypeId
            it[patientName] = "홍길동"
            it[patientPhone] = "010-1234-5678"
            it[appointmentDate] = MONDAY
            it[startTime] = LocalTime.of(9, 0)
            it[endTime] = LocalTime.of(9, 30)
            it[status] = AppointmentState.CONFIRMED
        }

        return Triple(clinicId.value, doctorId.value, treatmentTypeId.value)
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `1 - 임시휴진 시 활성 예약이 PENDING_RESCHEDULE로 전환된다`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, _, _) = insertDataWithAppointment()

            rescheduleService.processClosureReschedule(clinicId, MONDAY)

            val appointments = Appointments.selectAll()
                .where { Appointments.clinicId eq clinicId }
                .toList()

            appointments shouldHaveSize 1
            appointments[0][Appointments.status] shouldBeEqualTo AppointmentState.PENDING_RESCHEDULE
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `2 - 재배정 후보가 다음 날짜에서 탐색된다`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, _, _) = insertDataWithAppointment()

            val result = rescheduleService.processClosureReschedule(clinicId, MONDAY, searchDays = 1)

            result.size shouldBeEqualTo 1
            val candidates = result.values.first()
            candidates.isEmpty().shouldBeFalse()
            candidates.all { it.candidateDate == TUESDAY }.shouldBeTrue()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `3 - 관리자가 후보를 선택하면 새 예약 생성 및 원래 예약 RESCHEDULED`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, _, _) = insertDataWithAppointment()

            val result = rescheduleService.processClosureReschedule(clinicId, MONDAY, searchDays = 1)
            val firstCandidate = result.values.first().first()

            val newAppointmentId = rescheduleService.confirmReschedule(firstCandidate.id!!)

            val originalAppointment = Appointments.selectAll()
                .where { Appointments.status eq AppointmentState.RESCHEDULED }
                .firstOrNull()
            originalAppointment.shouldNotBeNull()

            val newAppointment = Appointments.selectAll()
                .where { Appointments.id eq newAppointmentId }
                .first()
            newAppointment[Appointments.status] shouldBeEqualTo AppointmentState.CONFIRMED
            newAppointment[Appointments.appointmentDate] shouldBeEqualTo TUESDAY
            newAppointment[Appointments.patientName] shouldBeEqualTo "홍길동"
            newAppointment[Appointments.rescheduleFromId].shouldNotBeNull()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `4 - 자동 재배정은 가장 높은 우선순위 후보를 선택한다`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, _, _) = insertDataWithAppointment()

            val result = rescheduleService.processClosureReschedule(clinicId, MONDAY, searchDays = 1)
            val originalAppointmentId = result.keys.first()

            val newAppointmentId = rescheduleService.autoReschedule(originalAppointmentId)

            newAppointmentId.shouldNotBeNull()

            val newAppointment = Appointments.selectAll()
                .where { Appointments.id eq newAppointmentId.requireNotNull("newAppointmentId") }
                .first()
            newAppointment[Appointments.appointmentDate] shouldBeEqualTo TUESDAY
            newAppointment[Appointments.startTime] shouldBeEqualTo LocalTime.of(9, 0)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `5 - 활성 예약이 없으면 빈 결과 반환`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, _, _) = insertDataWithAppointment()

            val result = rescheduleService.processClosureReschedule(clinicId, TUESDAY)

            result.shouldBeEmpty()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `6 - 후보가 없으면 autoReschedule은 null 반환`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val clinicId = Clinics.insertAndGetId {
                it[name] = "Empty Clinic"
                it[slotDurationMinutes] = 30
                it[maxConcurrentPatients] = 1
            }

            OperatingHoursTable.insert {
                it[OperatingHoursTable.clinicId] = clinicId
                it[dayOfWeek] = DayOfWeek.MONDAY
                it[openTime] = LocalTime.of(9, 0)
                it[closeTime] = LocalTime.of(18, 0)
                it[isActive] = true
            }

            val doctorId = Doctors.insertAndGetId {
                it[Doctors.clinicId] = clinicId
                it[name] = "Dr. Park"
            }

            DoctorSchedules.insert {
                it[DoctorSchedules.doctorId] = doctorId
                it[dayOfWeek] = DayOfWeek.MONDAY
                it[startTime] = LocalTime.of(9, 0)
                it[endTime] = LocalTime.of(18, 0)
            }

            val treatmentTypeId = TreatmentTypes.insertAndGetId {
                it[TreatmentTypes.clinicId] = clinicId
                it[name] = "Checkup"
                it[defaultDurationMinutes] = 30
            }

            val appointmentId = Appointments.insertAndGetId {
                it[Appointments.clinicId] = clinicId
                it[Appointments.doctorId] = doctorId
                it[Appointments.treatmentTypeId] = treatmentTypeId
                it[patientName] = "김환자"
                it[appointmentDate] = MONDAY
                it[startTime] = LocalTime.of(9, 0)
                it[endTime] = LocalTime.of(9, 30)
                it[status] = AppointmentState.PENDING_RESCHEDULE
            }

            val result = rescheduleService.autoReschedule(appointmentId.value)
            (result == null).shouldBeTrue()
        }
    }
}
