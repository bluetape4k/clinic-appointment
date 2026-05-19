package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.ConsultationTopics
import io.bluetape4k.clinic.appointment.model.tables.Doctors
import io.bluetape4k.clinic.appointment.model.tables.EquipmentUnavailabilities
import io.bluetape4k.clinic.appointment.model.tables.Equipments
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import io.bluetape4k.clinic.appointment.test.AbstractExposedTest
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withTables
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.LocalDate
import java.time.LocalTime

class TenantGuardRepositoryTest : AbstractExposedTest() {

    companion object {
        private const val TENANT_A = TenantGroups.DEFAULT_TENANT_GROUP_ID
        private const val TENANT_B = 2L

        private val allTables = arrayOf(
            TenantGroups,
            Clinics,
            Doctors,
            TreatmentTypes,
            Equipments,
            ConsultationTopics,
            Appointments,
            EquipmentUnavailabilities,
        )
    }

    private val clinicRepository = ClinicRepository()
    private val doctorRepository = DoctorRepository()
    private val treatmentTypeRepository = TreatmentTypeRepository()
    private val equipmentRepository = EquipmentRepository()
    private val appointmentRepository = AppointmentRepository()
    private val equipmentUnavailabilityRepository = EquipmentUnavailabilityRepository()

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `tenant guarded repository lookups hide rows from other tenants`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val tenantRows = setupTenantRows()

            clinicRepository.findByIdAndTenant(tenantRows.tenantA.clinicId, TENANT_A)
                .shouldNotBeNull()
                .tenantGroupId shouldBeEqualTo TENANT_A
            clinicRepository.findByIdAndTenant(tenantRows.tenantB.clinicId, TENANT_A).shouldBeNull()

            doctorRepository.findByIdAndTenant(tenantRows.tenantA.doctorId, TENANT_A).shouldNotBeNull()
            doctorRepository.findByIdAndTenant(tenantRows.tenantB.doctorId, TENANT_A).shouldBeNull()

            treatmentTypeRepository.findByIdAndTenant(tenantRows.tenantA.treatmentTypeId, TENANT_A).shouldNotBeNull()
            treatmentTypeRepository.findByIdAndTenant(tenantRows.tenantB.treatmentTypeId, TENANT_A).shouldBeNull()

            equipmentRepository.findByIdAndTenant(tenantRows.tenantA.equipmentId, TENANT_A).shouldNotBeNull()
            equipmentRepository.findByIdAndTenant(tenantRows.tenantB.equipmentId, TENANT_A).shouldBeNull()

            appointmentRepository.findByIdAndTenant(tenantRows.tenantA.appointmentId, TENANT_A).shouldNotBeNull()
            appointmentRepository.findByIdAndTenant(tenantRows.tenantB.appointmentId, TENANT_A).shouldBeNull()

            equipmentUnavailabilityRepository.findByIdAndTenant(tenantRows.tenantA.unavailabilityId, TENANT_A)
                .shouldNotBeNull()
            equipmentUnavailabilityRepository.findByIdAndTenant(tenantRows.tenantB.unavailabilityId, TENANT_A)
                .shouldBeNull()
        }
    }

    private fun JdbcTransaction.setupTenantRows(): TenantRows {
        TenantGroups.insert {
            it[id] = EntityID(TENANT_B, TenantGroups)
            it[tenantCode] = "tenant-b"
            it[displayName] = "Tenant B"
            it[active] = true
        }

        val tenantA = insertOwnedRows(
            tenantGroupId = TENANT_A,
            clinicName = "Tenant A Clinic",
            patientName = "Alice",
        )
        val tenantB = insertOwnedRows(
            tenantGroupId = TENANT_B,
            clinicName = "Tenant B Clinic",
            patientName = "Bob",
        )

        return TenantRows(tenantA = tenantA, tenantB = tenantB)
    }

    private fun JdbcTransaction.insertOwnedRows(
        tenantGroupId: Long,
        clinicName: String,
        patientName: String,
    ): OwnedRows {
        val clinicId = Clinics.insertAndGetId {
            it[Clinics.tenantGroupId] = EntityID(tenantGroupId, TenantGroups)
            it[name] = clinicName
            it[slotDurationMinutes] = 30
            it[maxConcurrentPatients] = 2
        }.value

        val doctorId = Doctors.insertAndGetId {
            it[Doctors.clinicId] = clinicId
            it[name] = "$clinicName Doctor"
        }.value

        val treatmentTypeId = TreatmentTypes.insertAndGetId {
            it[TreatmentTypes.clinicId] = clinicId
            it[name] = "$clinicName Treatment"
            it[defaultDurationMinutes] = 30
        }.value

        val equipmentId = Equipments.insertAndGetId {
            it[Equipments.clinicId] = clinicId
            it[name] = "$clinicName Equipment"
            it[usageDurationMinutes] = 30
            it[quantity] = 1
        }.value

        val appointmentId = Appointments.insertAndGetId {
            it[Appointments.clinicId] = clinicId
            it[Appointments.doctorId] = doctorId
            it[Appointments.treatmentTypeId] = treatmentTypeId
            it[Appointments.equipmentId] = equipmentId
            it[Appointments.patientName] = patientName
            it[Appointments.appointmentDate] = LocalDate.of(2026, 5, 20)
            it[Appointments.startTime] = LocalTime.of(9, 0)
            it[Appointments.endTime] = LocalTime.of(9, 30)
            it[Appointments.status] = AppointmentState.CONFIRMED
        }.value

        val unavailability = equipmentUnavailabilityRepository.create(
            equipmentId = equipmentId,
            clinicId = clinicId,
            unavailableDate = LocalDate.of(2026, 5, 21),
            isRecurring = false,
            recurringDayOfWeek = null,
            effectiveFrom = LocalDate.of(2026, 5, 21),
            effectiveUntil = LocalDate.of(2026, 5, 21),
            startTime = LocalTime.of(10, 0),
            endTime = LocalTime.of(11, 0),
            reason = "$clinicName maintenance",
        )

        return OwnedRows(
            clinicId = clinicId,
            doctorId = doctorId,
            treatmentTypeId = treatmentTypeId,
            equipmentId = equipmentId,
            appointmentId = appointmentId,
            unavailabilityId = unavailability.id,
        )
    }

    private data class TenantRows(
        val tenantA: OwnedRows,
        val tenantB: OwnedRows,
    )

    private data class OwnedRows(
        val clinicId: Long,
        val doctorId: Long,
        val treatmentTypeId: Long,
        val equipmentId: Long,
        val appointmentId: Long,
        val unavailabilityId: Long,
    )
}
