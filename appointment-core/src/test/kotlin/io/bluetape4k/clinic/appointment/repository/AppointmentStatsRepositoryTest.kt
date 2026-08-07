package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.ConsultationTopics
import io.bluetape4k.clinic.appointment.model.tables.Doctors
import io.bluetape4k.clinic.appointment.model.tables.Equipments
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import io.bluetape4k.clinic.appointment.test.AbstractExposedTest
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withTables
import io.bluetape4k.logging.KLogging
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldHaveSize
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.LocalDate
import java.time.LocalTime

class AppointmentStatsRepositoryTest : AbstractExposedTest() {

    companion object : KLogging() {
        private val repo = AppointmentStatsRepository()

        private val allTables = arrayOf(
            Clinics,
            Doctors,
            Equipments,
            TreatmentTypes,
            ConsultationTopics,
            Appointments,
        )
    }

    private fun JdbcTransaction.setupBaseData(): Triple<Long, Long, Long> {
        val clinicId = Clinics.insertAndGetId {
            it[name] = "Test Clinic"
            it[slotDurationMinutes] = 30
            it[maxConcurrentPatients] = 3
        }.value

        val doctorId = Doctors.insertAndGetId {
            it[Doctors.clinicId] = clinicId
            it[name] = "Dr. Kim"
        }.value

        val treatmentTypeId = TreatmentTypes.insertAndGetId {
            it[TreatmentTypes.clinicId] = clinicId
            it[name] = "General Consultation"
            it[defaultDurationMinutes] = 30
        }.value

        return Triple(clinicId, doctorId, treatmentTypeId)
    }

    private fun JdbcTransaction.insertAppointment(
        clinicId: Long,
        doctorId: Long,
        treatmentTypeId: Long,
        date: LocalDate,
        status: AppointmentState,
    ): Long = Appointments.insertAndGetId {
        it[Appointments.clinicId] = clinicId
        it[Appointments.doctorId] = doctorId
        it[Appointments.treatmentTypeId] = treatmentTypeId
        it[patientName] = "Patient"
        it[appointmentDate] = date
        it[startTime] = LocalTime.of(9, 0)
        it[endTime] = LocalTime.of(9, 30)
        it[Appointments.status] = status
    }.value

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `countByDateAndStatus - 날짜별 상태 집계 반환`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, doctorId, treatmentTypeId) = setupBaseData()
            val date = LocalDate.of(2026, 5, 1)

            insertAppointment(clinicId, doctorId, treatmentTypeId, date, AppointmentState.CONFIRMED)
            insertAppointment(clinicId, doctorId, treatmentTypeId, date, AppointmentState.CONFIRMED)
            insertAppointment(clinicId, doctorId, treatmentTypeId, date, AppointmentState.CANCELLED)

            val results = repo.countByDateAndStatus(clinicId, date..date)

            results shouldHaveSize 2
            val confirmedCount = results.first { it.second == AppointmentState.CONFIRMED }.third
            confirmedCount shouldBeEqualTo 2L
            val cancelledCount = results.first { it.second == AppointmentState.CANCELLED }.third
            cancelledCount shouldBeEqualTo 1L
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `countByDateAndStatus - statuses 필터 적용`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, doctorId, treatmentTypeId) = setupBaseData()
            val date = LocalDate.of(2026, 5, 1)

            insertAppointment(clinicId, doctorId, treatmentTypeId, date, AppointmentState.CONFIRMED)
            insertAppointment(clinicId, doctorId, treatmentTypeId, date, AppointmentState.CANCELLED)

            val results = repo.countByDateAndStatus(
                clinicId, date..date,
                listOf(AppointmentState.CONFIRMED)
            )

            results shouldHaveSize 1
            results[0].second shouldBeEqualTo AppointmentState.CONFIRMED
            results[0].third shouldBeEqualTo 1L
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `countByDateAndStatus - 날짜 경계 검증`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, doctorId, treatmentTypeId) = setupBaseData()
            val from = LocalDate.of(2026, 5, 1)
            val to = LocalDate.of(2026, 5, 31)

            insertAppointment(clinicId, doctorId, treatmentTypeId, from, AppointmentState.CONFIRMED)
            insertAppointment(clinicId, doctorId, treatmentTypeId, to, AppointmentState.CONFIRMED)
            insertAppointment(clinicId, doctorId, treatmentTypeId, to.plusDays(1), AppointmentState.CONFIRMED)

            val results = repo.countByDateAndStatus(clinicId, from..to)

            results.sumOf { it.third } shouldBeEqualTo 2L
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `countByDateAndStatus - 데이터 없으면 빈 결과`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, _, _) = setupBaseData()
            val date = LocalDate.of(2026, 5, 1)

            val results = repo.countByDateAndStatus(clinicId, date..date)

            results.shouldBeEmpty()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `countByDateAndStatus - clinicId 격리 검증`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, doctorId, treatmentTypeId) = setupBaseData()
            val date = LocalDate.of(2026, 5, 1)

            val otherClinicId = Clinics.insertAndGetId {
                it[name] = "Other Clinic"
                it[slotDurationMinutes] = 30
                it[maxConcurrentPatients] = 3
            }.value
            val otherDoctorId = Doctors.insertAndGetId {
                it[Doctors.clinicId] = otherClinicId
                it[name] = "Dr. Lee"
            }.value
            val otherTreatmentTypeId = TreatmentTypes.insertAndGetId {
                it[TreatmentTypes.clinicId] = otherClinicId
                it[name] = "General"
                it[defaultDurationMinutes] = 30
            }.value

            insertAppointment(clinicId, doctorId, treatmentTypeId, date, AppointmentState.CONFIRMED)
            insertAppointment(otherClinicId, otherDoctorId, otherTreatmentTypeId, date, AppointmentState.CONFIRMED)
            insertAppointment(otherClinicId, otherDoctorId, otherTreatmentTypeId, date, AppointmentState.CONFIRMED)

            val results = repo.countByDateAndStatus(clinicId, date..date)

            results.sumOf { it.third } shouldBeEqualTo 1L
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `countByDoctorAndStatus - 의사 3명 x 상태 다종 집계`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, doctorId1, treatmentTypeId) = setupBaseData()

            val doctorId2 = Doctors.insertAndGetId {
                it[Doctors.clinicId] = clinicId
                it[name] = "Dr. Park"
            }.value
            val doctorId3 = Doctors.insertAndGetId {
                it[Doctors.clinicId] = clinicId
                it[name] = "Dr. Choi"
            }.value

            val date = LocalDate.of(2026, 5, 1)

// doc1: CONFIRMED×2, CANCELLED×1인 데이터
            insertAppointment(clinicId, doctorId1, treatmentTypeId, date, AppointmentState.CONFIRMED)
            insertAppointment(clinicId, doctorId1, treatmentTypeId, date, AppointmentState.CONFIRMED)
            insertAppointment(clinicId, doctorId1, treatmentTypeId, date, AppointmentState.CANCELLED)
// doc2: COMPLETED×1인 데이터
            insertAppointment(clinicId, doctorId2, treatmentTypeId, date, AppointmentState.COMPLETED)
// doc3: NO_SHOW×1인 데이터
            insertAppointment(clinicId, doctorId3, treatmentTypeId, date, AppointmentState.NO_SHOW)

            val results = repo.countByDoctorAndStatus(clinicId, date..date)

// (doc1,CONFIRMED,2), (doc1,CANCELLED,1), (doc2,COMPLETED,1), (doc3,NO_SHOW,1) 조합
            results shouldHaveSize 4
            val doc1Confirmed = results.first { it.doctorId == doctorId1 && it.status == AppointmentState.CONFIRMED }
            doc1Confirmed.count shouldBeEqualTo 2L
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `countByDoctorAndStatus - SQL LIMIT 없음 - 모든 의사 행 반환`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, _, treatmentTypeId) = setupBaseData()
            val date = LocalDate.of(2026, 5, 1)

            repeat(5) { i ->
                val doctorId = Doctors.insertAndGetId {
                    it[Doctors.clinicId] = clinicId
                    it[name] = "Dr. $i"
                }.value
                insertAppointment(clinicId, doctorId, treatmentTypeId, date, AppointmentState.CONFIRMED)
            }

            val results = repo.countByDoctorAndStatus(clinicId, date..date)

// doctor 5명 × status 1개씩 = 5행이며 SQL 수준에서는 limit을 적용하지 않는다.
            results shouldHaveSize 5
        }
    }
}
