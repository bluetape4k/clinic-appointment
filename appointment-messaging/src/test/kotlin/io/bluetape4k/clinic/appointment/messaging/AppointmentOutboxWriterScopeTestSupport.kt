package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxEvents
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlans
import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.ConsultationTopics
import io.bluetape4k.clinic.appointment.model.tables.Doctors
import io.bluetape4k.clinic.appointment.model.tables.Equipments
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogProjections
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import io.bluetape4k.clinic.appointment.repository.AppointmentRepository
import io.bluetape4k.clinic.appointment.service.AppointmentCommandContext
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** 하나의 tenant에 clinic 두 개와 다른 tenant에 clinic 하나를 둔 H2 fixture. */
internal object AppointmentOutboxWriterScopeTestSupport {
    const val TENANT_ONE = 1L
    const val TENANT_TWO = 2L
    const val CLINIC_ONE = 31L
    const val CLINIC_TWO = 32L
    const val CLINIC_OTHER_TENANT = 41L
    const val ORIGINAL_APPOINTMENT = 924L
    const val REPLACEMENT_APPOINTMENT = 925L
    const val OTHER_TENANT_APPOINTMENT = 926L

    val validScope = TenantClinicScope(TENANT_ONE, CLINIC_ONE)
    val repository = AppointmentRepository()

    fun connectAndCreateFixture() {
        Database.connect(
            "jdbc:h2:mem:appointment_outbox_writer_scope_${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver",
        )
        transaction {
            SchemaUtils.create(
                TenantGroups,
                Clinics,
                Doctors,
                TreatmentTypes,
                Equipments,
                ConsultationTopics,
                Appointments,
                ProductCatalogProjections,
                AppointmentPlans,
                SchedulingOutboxEvents,
            )
            insertTenant(TENANT_ONE, "tenant-one")
            insertTenant(TENANT_TWO, "tenant-two")
            insertClinic(CLINIC_ONE, TENANT_ONE, "Clinic One")
            insertClinic(CLINIC_TWO, TENANT_ONE, "Clinic Two")
            insertClinic(CLINIC_OTHER_TENANT, TENANT_TWO, "Clinic Other Tenant")
            insertProvider(41L, CLINIC_ONE)
            insertProvider(42L, CLINIC_TWO)
            insertProvider(43L, CLINIC_OTHER_TENANT)
            insertTreatment(51L, CLINIC_ONE)
            insertTreatment(52L, CLINIC_TWO)
            insertTreatment(53L, CLINIC_OTHER_TENANT)
            insertAppointment(ORIGINAL_APPOINTMENT, CLINIC_ONE, 41L, 51L, "Original")
            insertAppointment(REPLACEMENT_APPOINTMENT, CLINIC_TWO, 42L, 52L, "Replacement")
            insertAppointment(OTHER_TENANT_APPOINTMENT, CLINIC_OTHER_TENANT, 43L, 53L, "Other Tenant")
        }
    }

    fun appointment(id: Long, scope: TenantClinicScope): io.bluetape4k.clinic.appointment.model.dto.AppointmentRecord =
        transaction {
            requireNotNull(repository.findByIdAndScope(id, scope)) {
                "fixture appointment $id is not readable in scope $scope"
            }
        }

    fun context(label: String = "writer-scope-test"): AppointmentMessagingContext =
        AppointmentMessagingContext.from(AppointmentCommandContext.root(label))

    fun writer(eventId: String = "writer-scope-event"): DefaultAppointmentOutboxWriter =
        DefaultAppointmentOutboxWriter(
            databaseClock = AppointmentDatabaseClock { Instant.parse("2026-08-05T08:30:00Z") },
            eventIdFactory = { AppointmentEventId(eventId) },
        )

    fun outboxCount(): Long = transaction { SchedulingOutboxEvents.selectAll().count() }

    private fun insertTenant(id: Long, code: String) {
        TenantGroups.insert {
            it[TenantGroups.id] = EntityID(id, TenantGroups)
            it[tenantCode] = code
            it[displayName] = code.replace('-', ' ').replaceFirstChar(Char::uppercase)
            it[active] = true
        }
    }

    private fun insertClinic(id: Long, tenantId: Long, name: String) {
        Clinics.insert {
            it[Clinics.id] = EntityID(id, Clinics)
            it[tenantGroupId] = tenantId
            it[Clinics.name] = name
        }
    }

    private fun insertProvider(id: Long, clinicId: Long) {
        Doctors.insert {
            it[Doctors.id] = EntityID(id, Doctors)
            it[Doctors.clinicId] = clinicId
            it[name] = "Doctor $id"
        }
    }

    private fun insertTreatment(id: Long, clinicId: Long) {
        TreatmentTypes.insert {
            it[TreatmentTypes.id] = EntityID(id, TreatmentTypes)
            it[TreatmentTypes.clinicId] = clinicId
            it[name] = "Treatment $id"
            it[defaultDurationMinutes] = 30
        }
    }

    private fun insertAppointment(
        id: Long,
        clinicId: Long,
        doctorId: Long,
        treatmentTypeId: Long,
        patientName: String,
    ) {
        Appointments.insert {
            it[Appointments.id] = EntityID(id, Appointments)
            it[Appointments.clinicId] = clinicId
            it[Appointments.doctorId] = doctorId
            it[Appointments.treatmentTypeId] = treatmentTypeId
            it[Appointments.patientName] = patientName
            it[appointmentDate] = LocalDate.of(2026, 8, 5)
            it[startTime] = LocalTime.of(10, 0)
            it[endTime] = LocalTime.of(10, 30)
            it[status] = AppointmentState.CONFIRMED
        }
    }
}
