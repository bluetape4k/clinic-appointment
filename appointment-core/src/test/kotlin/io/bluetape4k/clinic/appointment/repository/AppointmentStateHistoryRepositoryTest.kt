package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.clinic.appointment.model.tables.AppointmentStateHistory
import io.bluetape4k.clinic.appointment.model.tables.AppointmentStateHistoryRecord
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
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.LocalDate
import java.time.LocalTime

class AppointmentStateHistoryRepositoryTest : AbstractExposedTest() {

    private val repository = AppointmentStateHistoryRepository()

    companion object {
        private val allTables = arrayOf(
            Clinics,
            Doctors,
            TreatmentTypes,
            Equipments,
            ConsultationTopics,
            Appointments,
            AppointmentStateHistory,
        )
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `상태 이력은 저장되고 최신 변경 시각과 ID 역순으로 조회된다`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val appointmentId = setupAppointment()
            val first = repository.save(
                AppointmentStateHistoryRecord(
                    appointmentId = appointmentId,
                    fromState = AppointmentState.REQUESTED,
                    toState = AppointmentState.CONFIRMED,
                    reason = "confirmed",
                ),
            )
            val second = repository.save(
                AppointmentStateHistoryRecord(
                    appointmentId = appointmentId,
                    fromState = AppointmentState.CONFIRMED,
                    toState = AppointmentState.CANCELLED,
                    reason = "cancelled",
                ),
            )

            first.id.shouldNotBeNull().shouldBeGreaterThan(0L)
            second.id.shouldNotBeNull().shouldBeGreaterThan(0L)
            val histories = repository.findByAppointmentId(appointmentId)
            histories.shouldHaveSize(2)
            histories.map { it.id } shouldBeEqualTo listOf(second.id, first.id)
            repository.findLatestByAppointmentId(appointmentId)?.id shouldBeEqualTo second.id
        }
    }

    private fun JdbcTransaction.setupAppointment(): Long {
        val clinicId = Clinics.insertAndGetId {
            it[name] = "History Clinic"
            it[slotDurationMinutes] = 30
            it[maxConcurrentPatients] = 1
        }.value
        val doctorId = Doctors.insertAndGetId {
            it[Doctors.clinicId] = clinicId
            it[name] = "History Doctor"
        }.value
        val treatmentTypeId = TreatmentTypes.insertAndGetId {
            it[TreatmentTypes.clinicId] = clinicId
            it[name] = "History Treatment"
            it[defaultDurationMinutes] = 30
        }.value
        return Appointments.insertAndGetId {
            it[Appointments.clinicId] = clinicId
            it[Appointments.doctorId] = doctorId
            it[Appointments.treatmentTypeId] = treatmentTypeId
            it[patientName] = "History Patient"
            it[appointmentDate] = LocalDate.of(2026, 8, 19)
            it[startTime] = LocalTime.of(10, 0)
            it[endTime] = LocalTime.of(10, 30)
            it[status] = AppointmentState.REQUESTED
        }.value
    }
}
