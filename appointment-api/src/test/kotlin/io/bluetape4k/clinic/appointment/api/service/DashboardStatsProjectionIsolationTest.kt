package io.bluetape4k.clinic.appointment.api.service

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.api.stats.AppointmentStatsProjectionEventTable
import io.bluetape4k.clinic.appointment.api.stats.AppointmentStatsProjectionRepository
import io.bluetape4k.clinic.appointment.api.stats.AppointmentStatsProjectionTable
import io.bluetape4k.clinic.appointment.api.tenant.TenantContext
import io.bluetape4k.clinic.appointment.api.tenant.TenantInfo
import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.ConsultationTopics
import io.bluetape4k.clinic.appointment.model.tables.Doctors
import io.bluetape4k.clinic.appointment.model.tables.Equipments
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import io.bluetape4k.clinic.appointment.repository.AppointmentStatsRepository
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class DashboardStatsProjectionIsolationTest {
    private val database = Database.connect(
        url = "jdbc:h2:mem:dashboard-stats-projection-isolation;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        driver = "org.h2.Driver",
    )
    private val dashboardStatsService = DashboardStatsService(
        statsRepository = AppointmentStatsRepository(),
        projectionRepository = AppointmentStatsProjectionRepository(),
    )

    private var clinicId: Long = 0L
    private var doctorId: Long = 0L
    private var treatmentTypeId: Long = 0L

    @BeforeEach
    fun setUp() {
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(
                TenantGroups,
                Clinics,
                Doctors,
                Equipments,
                TreatmentTypes,
                ConsultationTopics,
                Appointments,
                AppointmentStatsProjectionTable,
                AppointmentStatsProjectionEventTable,
            )
            AppointmentStatsProjectionTable.deleteAll()
            AppointmentStatsProjectionEventTable.deleteAll()
            Appointments.deleteAll()
            ConsultationTopics.deleteAll()
            TreatmentTypes.deleteAll()
            Equipments.deleteAll()
            Doctors.deleteAll()
            Clinics.deleteAll()
            TenantGroups.deleteAll()

            TenantGroups.insert {
                it[id] = EntityID(TenantGroups.DEFAULT_TENANT_GROUP_ID, TenantGroups)
                it[tenantCode] = TenantGroups.DEFAULT_TENANT_CODE
                it[displayName] = TenantGroups.DEFAULT_TENANT_NAME
                it[active] = true
            }

            val cId = Clinics.insertAndGetId {
                it[name] = "Stats Clinic"
                it[slotDurationMinutes] = 30
                it[maxConcurrentPatients] = 3
            }.value
            clinicId = cId

            doctorId = Doctors.insertAndGetId {
                it[clinicId] = cId
                it[name] = "Dr. Stats"
            }.value

            treatmentTypeId = TreatmentTypes.insertAndGetId {
                it[clinicId] = cId
                it[name] = "General"
                it[defaultDurationMinutes] = 30
            }.value
        }
    }

    @Test
    fun `dashboard keeps current appointment counts when event projection has rows`() {
        val date = LocalDate.of(2026, 8, 6)
        transaction(database) {
            Appointments.insert {
                it[clinicId] = this@DashboardStatsProjectionIsolationTest.clinicId
                it[doctorId] = this@DashboardStatsProjectionIsolationTest.doctorId
                it[treatmentTypeId] = this@DashboardStatsProjectionIsolationTest.treatmentTypeId
                it[patientName] = "Current appointment"
                it[appointmentDate] = date
                it[startTime] = LocalTime.of(9, 0)
                it[endTime] = LocalTime.of(9, 30)
                it[Appointments.status] = AppointmentState.CONFIRMED
            }
            AppointmentStatsProjectionTable.insert {
                it[tenantGroupId] = TENANT_ID
                it[clinicId] = this@DashboardStatsProjectionIsolationTest.clinicId
                it[eventDate] = date
                it[status] = AppointmentState.CANCELLED
                it[appointmentCount] = 99L
                it[lastEventVersion] = 2L
                it[lastEventId] = "projection-event"
            }
        }

        val result = TenantContext.withTenant(
            TenantInfo(TENANT_ID, "stats-tenant", "Stats Tenant"),
        ) {
            dashboardStatsService.getAppointmentStats(clinicId, date, date)
        }

        result.totals shouldBeEqualTo mapOf(AppointmentState.CONFIRMED.name to 1L)
    }

    companion object {
        private const val TENANT_ID = 11L
    }
}
