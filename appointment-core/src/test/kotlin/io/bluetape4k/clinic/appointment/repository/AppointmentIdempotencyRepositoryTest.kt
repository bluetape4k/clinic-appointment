package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.clinic.appointment.model.dto.AppointmentIdempotencyRecord
import io.bluetape4k.clinic.appointment.model.tables.AppointmentIdempotencies
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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class AppointmentIdempotencyRepositoryTest : AbstractExposedTest() {

    companion object {
        private val allTables = arrayOf(
            Clinics,
            Doctors,
            TreatmentTypes,
            Equipments,
            ConsultationTopics,
            Appointments,
            AppointmentIdempotencies,
        )
    }

    private val repository = AppointmentIdempotencyRepository()

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `persists scoped idempotency record and deletes only expired records`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, appointmentId) = setupAppointment()
            val now = Instant.parse("2026-07-24T00:00:00Z")
            val saved = repository.save(
                AppointmentIdempotencyRecord(
                    tenantGroupId = 1L,
                    clinicId = clinicId,
                    idempotencyKey = "retry-001",
                    requestFingerprint = "f".repeat(64),
                    appointmentId = appointmentId,
                    expiresAt = now.plusSeconds(3600),
                )
            )

            saved.id.shouldNotBeNull().shouldBeGreaterThan(0L)
            val found = repository.findByTenantGroupAndClinicAndKey(1L, clinicId, "retry-001")
            found.shouldNotBeNull()
            found.appointmentId shouldBeEqualTo appointmentId
            found.requestFingerprint shouldBeEqualTo "f".repeat(64)

            val (otherClinicId, otherAppointmentId) = setupAppointment()
            repository.save(
                AppointmentIdempotencyRecord(
                    tenantGroupId = 1L,
                    clinicId = otherClinicId,
                    idempotencyKey = "retry-001",
                    requestFingerprint = "a".repeat(64),
                    appointmentId = otherAppointmentId,
                    expiresAt = now.plusSeconds(7200),
                )
            )
            repository.findByTenantGroupAndClinicAndKey(1L, otherClinicId, "retry-001")
                .shouldNotBeNull()
                .appointmentId shouldBeEqualTo otherAppointmentId

            repository.deleteExpired(1L, clinicId, "retry-001", now) shouldBeEqualTo 0
            repository.deleteExpired(1L, clinicId, "retry-001", now.plusSeconds(3601)) shouldBeEqualTo 1
            repository.findByTenantGroupAndClinicAndKey(1L, clinicId, "retry-001").shouldBeNull()
            repository.findByTenantGroupAndClinicAndKey(1L, otherClinicId, "retry-001").shouldNotBeNull()
        }
    }

    private fun JdbcTransaction.setupAppointment(): Pair<Long, Long> {
        val clinicId = Clinics.insertAndGetId {
            it[name] = "Test Clinic"
            it[slotDurationMinutes] = 30
            it[maxConcurrentPatients] = 1
        }.value
        val doctorId = Doctors.insertAndGetId {
            it[Doctors.clinicId] = clinicId
            it[name] = "Test Doctor"
        }.value
        val treatmentTypeId = TreatmentTypes.insertAndGetId {
            it[TreatmentTypes.clinicId] = clinicId
            it[name] = "Test Treatment"
            it[defaultDurationMinutes] = 30
        }.value
        val appointmentId = Appointments.insertAndGetId {
            it[Appointments.clinicId] = clinicId
            it[Appointments.doctorId] = doctorId
            it[Appointments.treatmentTypeId] = treatmentTypeId
            it[patientName] = "Test Patient"
            it[appointmentDate] = LocalDate.of(2026, 8, 1)
            it[startTime] = LocalTime.of(10, 0)
            it[endTime] = LocalTime.of(10, 30)
            it[status] = AppointmentState.REQUESTED
        }.value
        return clinicId to appointmentId
    }
}
