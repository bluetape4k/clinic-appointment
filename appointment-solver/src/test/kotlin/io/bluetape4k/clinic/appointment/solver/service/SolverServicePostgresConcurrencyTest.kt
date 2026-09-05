package io.bluetape4k.clinic.appointment.solver.service

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import io.bluetape4k.clinic.appointment.model.tables.AppointmentNotes
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
import io.bluetape4k.clinic.appointment.model.tables.ProviderType
import io.bluetape4k.clinic.appointment.model.tables.RescheduleCandidates
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.TreatmentEquipments
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import io.bluetape4k.clinic.appointment.repository.AppointmentRepository
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@ResourceLock("exposed-default-database")
@Execution(ExecutionMode.SAME_THREAD)
class SolverServicePostgresConcurrencyTest {

    private fun scope(clinicId: Long) = TenantClinicScope(TenantGroups.DEFAULT_TENANT_GROUP_ID, clinicId)

    companion object {

        private const val H2_URL = "jdbc:h2:mem:solver_test;DB_CLOSE_DELAY=-1"

        private lateinit var database: Database
        private lateinit var postgres: PostgreSQLServer

        private val solverService = SolverService(
            solverFactory = AppointmentSolverConfig.createFactory(Duration.ofSeconds(2)),
        )

        private val MONDAY = LocalDate.of(2026, 3, 23)
        private val FRIDAY = LocalDate.of(2026, 3, 27)

        @JvmStatic
        @BeforeAll
        fun setup() {
            postgres = PostgreSQLServer.Launcher.postgres
            database = Database.connect(
                postgres.jdbcUrl,
                driver = "org.postgresql.Driver",
                user = postgres.username ?: PostgreSQLServer.USERNAME,
                password = postgres.password ?: PostgreSQLServer.PASSWORD,
            )
            TransactionManager.defaultDatabase = database
            transaction {
                SchemaUtils.createMissingTablesAndColumns(
                    TenantGroups,
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
                    RescheduleCandidates,
                )
            }
        }

        @JvmStatic
        @AfterAll
        fun restoreH2DefaultDatabase() {
            TransactionManager.defaultDatabase = Database.connect(H2_URL, driver = "org.h2.Driver")
        }
    }

    @BeforeEach
    fun cleanUp() {
        TransactionManager.defaultDatabase = database
        transaction {
            RescheduleCandidates.deleteAll()
            AppointmentNotes.deleteAll()
            Appointments.deleteAll()
            TreatmentEquipments.deleteAll()
            ConsultationTopics.deleteAll()
            TreatmentTypes.deleteAll()
            Equipments.deleteAll()
            DoctorAbsences.deleteAll()
            DoctorSchedules.deleteAll()
            Doctors.deleteAll()
            ClinicClosures.deleteAll()
            BreakTimes.deleteAll()
            ClinicDefaultBreakTimes.deleteAll()
            OperatingHoursTable.deleteAll()
            Clinics.deleteAll()
            Holidays.deleteAll()
            TenantGroups.deleteAll()
        }
    }

    private data class BaseData(
        val clinicId: Long,
        val doctorId1: Long,
        val doctorId2: Long,
        val treatmentTypeId: Long,
    )

    private fun insertBaseData(maxConcurrentPatients: Int = 1): BaseData = transaction {
        seedDefaultTenant()
        val clinicId = Clinics.insertAndGetId {
            it[name] = "PostgreSQL Test Clinic"
            it[slotDurationMinutes] = 30
            it[Clinics.maxConcurrentPatients] = maxConcurrentPatients
        }.value

        val weekdays = listOf(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY,
        )
        weekdays.forEach { day ->
            OperatingHoursTable.insert {
                it[OperatingHoursTable.clinicId] = clinicId
                it[dayOfWeek] = day
                it[openTime] = LocalTime.of(9, 0)
                it[closeTime] = LocalTime.of(18, 0)
                it[isActive] = true
            }
        }

        val doctorId1 = Doctors.insertAndGetId {
            it[Doctors.clinicId] = clinicId
            it[name] = "Dr. Kim"
            it[providerType] = ProviderType.DOCTOR
        }.value
        val doctorId2 = Doctors.insertAndGetId {
            it[Doctors.clinicId] = clinicId
            it[name] = "Dr. Park"
            it[providerType] = ProviderType.DOCTOR
        }.value

        weekdays.forEach { day ->
            DoctorSchedules.insert {
                it[DoctorSchedules.doctorId] = doctorId1
                it[dayOfWeek] = day
                it[startTime] = LocalTime.of(9, 0)
                it[endTime] = LocalTime.of(18, 0)
            }
            DoctorSchedules.insert {
                it[DoctorSchedules.doctorId] = doctorId2
                it[dayOfWeek] = day
                it[startTime] = LocalTime.of(9, 0)
                it[endTime] = LocalTime.of(18, 0)
            }
        }

        val treatmentTypeId = TreatmentTypes.insertAndGetId {
            it[TreatmentTypes.clinicId] = clinicId
            it[name] = "General Checkup"
            it[defaultDurationMinutes] = 30
            it[requiredProviderType] = ProviderType.DOCTOR
        }.value

        BaseData(clinicId, doctorId1, doctorId2, treatmentTypeId)
    }

    private fun seedDefaultTenant() {
        TenantGroups.insert {
            it[id] = EntityID(TenantGroups.DEFAULT_TENANT_GROUP_ID, TenantGroups)
            it[tenantCode] = TenantGroups.DEFAULT_TENANT_CODE
            it[displayName] = TenantGroups.DEFAULT_TENANT_NAME
            it[active] = true
        }
    }

    private fun insertAppointment(base: BaseData): Long = transaction {
        Appointments.insertAndGetId {
            it[Appointments.clinicId] = base.clinicId
            it[Appointments.doctorId] = base.doctorId1
            it[Appointments.treatmentTypeId] = base.treatmentTypeId
            it[patientName] = "PostgreSQL Planning Fact Patient"
            it[appointmentDate] = MONDAY
            it[startTime] = LocalTime.of(9, 0)
            it[endTime] = LocalTime.of(9, 30)
            it[status] = AppointmentState.REQUESTED
        }.value
    }

    private fun assertAppointmentVersionAndStatusUnchanged(base: BaseData, appointmentId: Long) {
        transaction {
            val current = checkNotNull(
                AppointmentRepository().findByIdAndScope(appointmentId, scope(base.clinicId)),
            )
            current.doctorId.shouldBeEqualTo(base.doctorId1)
            current.appointmentDate.shouldBeEqualTo(MONDAY)
            current.startTime.shouldBeEqualTo(LocalTime.of(9, 0))
            current.endTime.shouldBeEqualTo(LocalTime.of(9, 30))
            current.version.shouldBeEqualTo(0L)
            current.status.shouldBeEqualTo(AppointmentState.REQUESTED)
        }
    }

    @Test
    fun `PostgreSQL에서 solve와 apply 사이 clinic 변경은 결과를 거부한다`() {
        val base = insertBaseData()
        val appointmentId = insertAppointment(base)
        val result = solverService.optimize(scope(base.clinicId), MONDAY..FRIDAY, Duration.ofSeconds(2))

        transaction {
            Clinics.update({ Clinics.id eq base.clinicId }) {
                it[Clinics.maxConcurrentPatients] = 2
            }
        }

        solverService.isSourceVersionCurrentAdvisory(result).shouldBeFalse()
        solverService.applyOptimizedAssignments(result).shouldBeFalse()
        assertAppointmentVersionAndStatusUnchanged(base, appointmentId)
    }

    @Test
    fun `PostgreSQL appointment lock 경합은 assignment를 남기지 않는다`() {
        val base = insertBaseData()
        val appointmentId = insertAppointment(base)
        val result = solverService.optimize(scope(base.clinicId), MONDAY..FRIDAY, Duration.ofSeconds(2))
        val writerReady = CountDownLatch(1)
        val releaseWriter = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val writerFuture = executor.submit<Boolean> {
                transaction {
                    AppointmentRepository().lockLegacySourceVersions(
                        scope = scope(base.clinicId),
                        sourceVersions = result.sourceVersions,
                    ).also { locked ->
                        locked.shouldBeTrue()
                        writerReady.countDown()
                        AppointmentRepository().updateLegacyStatus(
                            scope = scope(base.clinicId),
                            appointmentId = appointmentId,
                            expectedVersion = 0L,
                            newStatus = AppointmentState.CONFIRMED,
                        ).shouldBeTrue()
                        releaseWriter.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    }
                }
            }
            writerReady.await(5, TimeUnit.SECONDS).shouldBeTrue()

            val applyFuture = executor.submit<Boolean> {
                solverService.applyOptimizedAssignments(result)
            }
            applyFuture.isDone.shouldBeFalse()

            transaction {
                Clinics.update({ Clinics.id eq base.clinicId }) {
                    it[Clinics.maxConcurrentPatients] = 2
                }
            }
            releaseWriter.countDown()

            applyFuture.get(5, TimeUnit.SECONDS).shouldBeFalse()
            writerFuture.get(5, TimeUnit.SECONDS).shouldBeTrue()
        } finally {
            releaseWriter.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS).shouldBeTrue()
        }

        transaction {
            val current = checkNotNull(
                AppointmentRepository().findByIdAndScope(appointmentId, scope(base.clinicId)),
            )
            current.version.shouldBeEqualTo(1L)
            current.status.shouldBeEqualTo(AppointmentState.CONFIRMED)
        }
    }

    @Test
    fun `PostgreSQL assignment CAS 하나가 실패하면 선행 assignment도 함께 rollback된다`() {
        val base = insertBaseData()
        val firstAppointmentId = insertAppointment(base)
        val secondAppointmentId = insertAppointment(base)
        val result = solverService.optimize(scope(base.clinicId), MONDAY..FRIDAY, Duration.ofSeconds(2))

        result.entityCount.shouldBeEqualTo(2)
        result.appointments.size.shouldBeEqualTo(2)
        val duplicatedResult = result.copy(
            appointments = result.appointments + result.appointments.first(),
        )

        solverService.applyOptimizedAssignments(duplicatedResult).shouldBeFalse()

        assertAppointmentVersionAndStatusUnchanged(base, firstAppointmentId)
        assertAppointmentVersionAndStatusUnchanged(base, secondAppointmentId)
    }
}
