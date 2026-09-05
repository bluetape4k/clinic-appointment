package io.bluetape4k.clinic.appointment.solver.service

import ai.timefold.solver.core.api.solver.Solver
import ai.timefold.solver.core.api.solver.SolverFactory
import io.bluetape4k.assertions.assertFailsWith
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
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import io.bluetape4k.clinic.appointment.repository.AppointmentRepository
import io.bluetape4k.clinic.appointment.solver.domain.ScheduleSolution
import io.bluetape4k.logging.KLogging
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.mockk.every
import io.mockk.mockk
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
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
import java.io.Serializable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@ResourceLock("exposed-default-database")
@Execution(ExecutionMode.SAME_THREAD)
// 동일 DB fixture에서 기존 stale 검증과 새 scope/pinned 검증을 함께 유지한다.
@Suppress("LargeClass")
class SolverServiceTest {

    private fun scope(clinicId: Long) = TenantClinicScope(TenantGroups.DEFAULT_TENANT_GROUP_ID, clinicId)

    private fun scope(base: BaseData) = TenantClinicScope(base.tenantGroupId, base.clinicId)

    companion object: KLogging() {

        private lateinit var db: Database

        private val solverFactory = AppointmentSolverConfig.createFactory(Duration.ofSeconds(5))
        private val solverService = SolverService(solverFactory = solverFactory)

        private val MONDAY = LocalDate.of(2026, 3, 23)
        private val FRIDAY = LocalDate.of(2026, 3, 27)
    }

    @BeforeAll
    fun setup() {
        db = Database.connect(
            "jdbc:h2:mem:solver_test;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )
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

    @BeforeEach
    fun cleanUp() {
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

    /**
     * 기본 데이터 삽입: tenant에 병원 1개, 의사 2명, 영업시간 월~금, 진료유형 1개
     * 반환: (clinicId, doctorId1, doctorId2, treatmentTypeId, tenantGroupId)
     */
    private data class BaseData(
        val clinicId: Long,
        val doctorId1: Long,
        val doctorId2: Long,
        val treatmentTypeId: Long,
        val tenantGroupId: Long,
    ) : Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    private fun insertBaseData(
        maxConcurrentPatients: Int = 1,
        tenantGroupId: Long = TenantGroups.DEFAULT_TENANT_GROUP_ID,
        seedTenantGroup: Boolean = true,
        tenantCode: String = TenantGroups.DEFAULT_TENANT_CODE,
        tenantName: String = TenantGroups.DEFAULT_TENANT_NAME,
    ): BaseData = transaction {
        if (seedTenantGroup) {
            seedTenant(tenantGroupId, tenantCode, tenantName)
        }
        val clinicId = Clinics.insertAndGetId {
            it[Clinics.tenantGroupId] = tenantGroupId
            it[name] = "Test Clinic"
            it[slotDurationMinutes] = 30
            it[Clinics.maxConcurrentPatients] = maxConcurrentPatients
        }.value

        val weekdays = listOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY
        )
        for (day in weekdays) {
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

        for (day in weekdays) {
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

        BaseData(clinicId, doctorId1, doctorId2, treatmentTypeId, tenantGroupId)
    }

    private fun seedTenant(
        tenantGroupId: Long,
        tenantCode: String,
        tenantName: String,
    ) {
        TenantGroups.insert {
            it[id] = EntityID(tenantGroupId, TenantGroups)
            it[TenantGroups.tenantCode] = tenantCode
            it[displayName] = tenantName
            it[active] = true
        }
    }

    private fun insertAppointment(base: BaseData): Long = transaction {
        Appointments.insertAndGetId {
            it[Appointments.clinicId] = base.clinicId
            it[Appointments.doctorId] = base.doctorId1
            it[Appointments.treatmentTypeId] = base.treatmentTypeId
            it[patientName] = "Planning Fact Patient"
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
            current.version.shouldBeEqualTo(0L)
            current.status.shouldBeEqualTo(AppointmentState.REQUESTED)
        }
    }

    private fun assertAppointmentAssignmentAndVersionUnchanged(
        base: BaseData,
        appointmentId: Long,
        status: AppointmentState = AppointmentState.REQUESTED,
    ) {
        transaction {
            val current = checkNotNull(
                AppointmentRepository().findByIdAndScope(appointmentId, scope(base)),
            )
            current.doctorId.shouldBeEqualTo(base.doctorId1)
            current.appointmentDate.shouldBeEqualTo(MONDAY)
            current.startTime.shouldBeEqualTo(LocalTime.of(9, 0))
            current.endTime.shouldBeEqualTo(LocalTime.of(9, 30))
            current.status.shouldBeEqualTo(status)
            current.version.shouldBeEqualTo(0L)
        }
    }

    private fun assertApplyRejectsAfterPlanningFactChange(change: (BaseData) -> Unit) {
        val base = insertBaseData()
        val appointmentId = insertAppointment(base)
        val result = solverService.optimize(scope(base.clinicId), MONDAY..FRIDAY, Duration.ofSeconds(2))

        change(base)

        solverService.isSourceVersionCurrentAdvisory(result).shouldBeFalse()
        solverService.applyOptimizedAssignments(result).shouldBeFalse()
        assertAppointmentVersionAndStatusUnchanged(base, appointmentId)
    }

    @Test
    fun `1 - Solver가 feasible 해를 반환한다`() {
        val (clinicId, doctorId1, _, treatmentTypeId) = insertBaseData()

        transaction {
            Appointments.insert {
                it[Appointments.clinicId] = clinicId
                it[Appointments.doctorId] = doctorId1
                it[Appointments.treatmentTypeId] = treatmentTypeId
                it[patientName] = "Patient A"
                it[appointmentDate] = MONDAY
                it[startTime] = LocalTime.of(9, 0)
                it[endTime] = LocalTime.of(9, 30)
                it[status] = AppointmentState.REQUESTED
            }
            Appointments.insert {
                it[Appointments.clinicId] = clinicId
                it[Appointments.doctorId] = doctorId1
                it[Appointments.treatmentTypeId] = treatmentTypeId
                it[patientName] = "Patient B"
                it[appointmentDate] = MONDAY
                it[startTime] = LocalTime.of(9, 30)
                it[endTime] = LocalTime.of(10, 0)
                it[status] = AppointmentState.REQUESTED
            }
        }

        val result = solverService.optimize(scope(clinicId), MONDAY..FRIDAY, Duration.ofSeconds(5))

        result.isFeasible.shouldBeTrue()
        result.appointments shouldHaveSize 2
        result.dateRange?.start shouldBeEqualTo MONDAY
        result.dateRange?.endInclusive shouldBeEqualTo FRIDAY
        result.planningFactVersion.matches(Regex("[0-9a-f]{64}")).shouldBeTrue()
    }

    @Test
    fun `2 - 동시 환자 수 초과 시 Solver가 재배치한다`() {
        val (clinicId, doctorId1, _, treatmentTypeId) = insertBaseData(maxConcurrentPatients = 1)

        transaction {
            repeat(3) { i ->
                Appointments.insert {
                    it[Appointments.clinicId] = clinicId
                    it[Appointments.doctorId] = doctorId1
                    it[Appointments.treatmentTypeId] = treatmentTypeId
                    it[patientName] = "Patient $i"
                    it[appointmentDate] = MONDAY
                    it[startTime] = LocalTime.of(9, 0)
                    it[endTime] = LocalTime.of(9, 30)
                    it[status] = AppointmentState.REQUESTED
                }
            }
        }

        val result = solverService.optimize(scope(clinicId), MONDAY..FRIDAY, Duration.ofSeconds(5))

        result.isFeasible.shouldBeTrue()
        val startTimes = result.appointments.map { it.startTime }.toSet()
        startTimes.size shouldBeEqualTo result.appointments.size
    }

    @Test
    fun `3 - optimizeReschedule로 휴진 재배정을 수행한다`() {
        val (clinicId, doctorId1, _, treatmentTypeId) = insertBaseData()

        transaction {
            Appointments.insert {
                it[Appointments.clinicId] = clinicId
                it[Appointments.doctorId] = doctorId1
                it[Appointments.treatmentTypeId] = treatmentTypeId
                it[patientName] = "Reschedule Patient"
                it[appointmentDate] = MONDAY
                it[startTime] = LocalTime.of(9, 0)
                it[endTime] = LocalTime.of(9, 30)
                it[status] = AppointmentState.PENDING_RESCHEDULE
            }
        }

        val result = solverService.optimizeReschedule(
            scope = scope(clinicId),
            closureDate = MONDAY,
            searchDays = 5,
            timeLimit = Duration.ofSeconds(5),
        )

        result.isFeasible.shouldBeTrue()
        val rescheduled = result.appointments
        rescheduled.all { it.appointmentDate >= MONDAY }.shouldBeTrue()
    }

    @Test
    fun `실제 solve 뒤 pinned 예약의 의사 날짜 시간은 변하지 않는다`() {
        val base = insertBaseData()
        val pinnedAppointmentId = transaction {
            Appointments.insertAndGetId {
                it[Appointments.clinicId] = base.clinicId
                it[Appointments.doctorId] = base.doctorId1
                it[Appointments.treatmentTypeId] = base.treatmentTypeId
                it[patientName] = "Pinned Patient"
                it[appointmentDate] = MONDAY
                it[startTime] = LocalTime.of(9, 0)
                it[endTime] = LocalTime.of(9, 30)
                it[status] = AppointmentState.CONFIRMED
            }.value
        }

        val movableAppointmentId = insertAppointment(base)
        val actualSolver = solverFactory.buildSolver()
        lateinit var solved: ScheduleSolution
        val observingSolver = mockk<Solver<ScheduleSolution>>()
        every { observingSolver.solve(any()) } answers {
            actualSolver.solve(firstArg()).also { solved = it }
        }
        val observingFactory = mockk<SolverFactory<ScheduleSolution>>()
        every { observingFactory.buildSolver() } returns observingSolver
        val service = SolverService(solverFactory = observingFactory)
        // timeLimit을 생략해야 주입한 factory의 실제 Solver를 관찰할 수 있다.
        val result = service.optimize(scope(base), MONDAY..FRIDAY)

        result.entityCount.shouldBeEqualTo(2)
        result.pinnedCount.shouldBeEqualTo(1)
        result.appointments.single().id.shouldBeEqualTo(movableAppointmentId)
        result.isFeasible.shouldBeTrue()
        val pinned = solved.appointments.single { it.id == pinnedAppointmentId }
        pinned.pinned.shouldBeTrue()
        pinned.doctorId.shouldBeEqualTo(base.doctorId1)
        pinned.appointmentDate.shouldBeEqualTo(MONDAY)
        pinned.startTime.shouldBeEqualTo(LocalTime.of(9, 0))
        pinned.endTime.shouldBeEqualTo(LocalTime.of(9, 30))
        service.applyOptimizedAssignments(result).shouldBeTrue()
        assertAppointmentAssignmentAndVersionUnchanged(base, pinnedAppointmentId, AppointmentState.CONFIRMED)
    }

    @Test
    fun `같은 tenant의 다른 clinic 예약은 optimize와 apply에서 격리된다`() {
        val target = insertBaseData()
        val otherClinic = insertBaseData(seedTenantGroup = false)
        val targetAppointmentId = insertAppointment(target)
        val otherAppointmentId = insertAppointment(otherClinic)

        val result = solverService.optimize(scope(target), MONDAY..FRIDAY, Duration.ofSeconds(5))

        result.entityCount.shouldBeEqualTo(1)
        result.appointments.mapNotNull { it.id }.shouldHaveSize(1)
        result.appointments.single().id.shouldBeEqualTo(targetAppointmentId)

        val otherAppointment = transaction {
            checkNotNull(
                AppointmentRepository().findByIdAndScope(otherAppointmentId, scope(otherClinic)),
            )
        }
        val forgedResult = result.copy(
            appointments = result.appointments + otherAppointment,
            sourceVersions = result.sourceVersions + (otherAppointmentId to otherAppointment.version),
        )

        solverService.applyOptimizedAssignments(forgedResult).shouldBeFalse()
        assertAppointmentAssignmentAndVersionUnchanged(target, targetAppointmentId)
        assertAppointmentAssignmentAndVersionUnchanged(otherClinic, otherAppointmentId)
    }

    @Test
    fun `다른 tenant의 clinic 예약은 optimize와 apply에서 격리된다`() {
        val target = insertBaseData()
        val otherTenant = insertBaseData(
            tenantGroupId = 2L,
            tenantCode = "tenant-other",
            tenantName = "Other Tenant",
        )
        val targetAppointmentId = insertAppointment(target)
        val otherAppointmentId = insertAppointment(otherTenant)

        val result = solverService.optimize(scope(target), MONDAY..FRIDAY, Duration.ofSeconds(5))

        result.entityCount.shouldBeEqualTo(1)
        result.appointments.mapNotNull { it.id }.shouldHaveSize(1)
        result.appointments.single().id.shouldBeEqualTo(targetAppointmentId)

        val otherAppointment = transaction {
            checkNotNull(
                AppointmentRepository().findByIdAndScope(otherAppointmentId, scope(otherTenant)),
            )
        }
        val forgedResult = result.copy(
            appointments = result.appointments + otherAppointment,
            sourceVersions = result.sourceVersions + (otherAppointmentId to otherAppointment.version),
        )

        solverService.applyOptimizedAssignments(forgedResult).shouldBeFalse()
        assertAppointmentAssignmentAndVersionUnchanged(target, targetAppointmentId)
        assertAppointmentAssignmentAndVersionUnchanged(otherTenant, otherAppointmentId)
    }

    @Test
    fun `최적화 결과는 원본 version이 바뀌면 적용 전에 stale로 판정된다`() {
        val (clinicId, doctorId, _, treatmentTypeId) = insertBaseData()
        val appointmentId = transaction {
            Appointments.insertAndGetId {
                it[Appointments.clinicId] = clinicId
                it[Appointments.doctorId] = doctorId
                it[Appointments.treatmentTypeId] = treatmentTypeId
                it[patientName] = "Versioned Patient"
                it[appointmentDate] = MONDAY
                it[startTime] = LocalTime.of(9, 0)
                it[endTime] = LocalTime.of(9, 30)
                it[status] = AppointmentState.REQUESTED
            }.value
        }

        val result = solverService.optimize(scope(clinicId), MONDAY..FRIDAY, Duration.ofSeconds(5))
        solverService.isSourceVersionCurrentAdvisory(result).shouldBeTrue()

        transaction {
            AppointmentRepository().updateLegacyStatus(
                scope = scope(clinicId),
                appointmentId = appointmentId,
                expectedVersion = 0L,
                newStatus = AppointmentState.CONFIRMED,
            ).shouldBeTrue()
        }

        solverService.isSourceVersionCurrentAdvisory(result).shouldBeFalse()
    }

    @Test
    fun `legacy result metadata가 advisory와 apply에서 안전하게 거부된다`() {
        val (clinicId, _, _, _) = insertBaseData()
        val result = solverService.optimize(scope(clinicId), MONDAY..FRIDAY, Duration.ofSeconds(5))
        val legacyResult = result.copy(dateRange = null, planningFactVersion = "")

        solverService.isSourceVersionCurrentAdvisory(legacyResult).shouldBeFalse()
        solverService.applyOptimizedAssignments(legacyResult).shouldBeFalse()
    }

    @Test
    fun `clinic 변경은 stale result를 거부한다`() {
        assertApplyRejectsAfterPlanningFactChange { base ->
            transaction {
                Clinics.update({ Clinics.id eq base.clinicId }) {
                    it[Clinics.maxConcurrentPatients] = 2
                }
            }
        }
    }

    @Test
    fun `doctor 변경은 stale result를 거부한다`() {
        assertApplyRejectsAfterPlanningFactChange { base ->
            transaction {
                Doctors.update({ Doctors.id eq base.doctorId1 }) {
                    it[Doctors.providerType] = ProviderType.CONSULTANT
                }
            }
        }
    }

    @Test
    fun `treatment 변경은 stale result를 거부한다`() {
        assertApplyRejectsAfterPlanningFactChange { base ->
            transaction {
                TreatmentTypes.update({ TreatmentTypes.id eq base.treatmentTypeId }) {
                    it[TreatmentTypes.defaultDurationMinutes] = 60
                }
            }
        }
    }

    @Test
    fun `equipment 추가는 stale result를 거부한다`() {
        assertApplyRejectsAfterPlanningFactChange { base ->
            transaction {
                Equipments.insert {
                    it[Equipments.clinicId] = base.clinicId
                    it[Equipments.name] = "MRI"
                    it[Equipments.usageDurationMinutes] = 30
                }
            }
        }
    }

    @Test
    fun `operating hour 삭제는 stale result를 거부한다`() {
        assertApplyRejectsAfterPlanningFactChange { base ->
            transaction {
                OperatingHoursTable.deleteWhere { OperatingHoursTable.clinicId eq base.clinicId }
            }
        }
    }

    @Test
    fun `doctor schedule 변경은 stale result를 거부한다`() {
        assertApplyRejectsAfterPlanningFactChange { base ->
            transaction {
                DoctorSchedules.update({ DoctorSchedules.doctorId eq base.doctorId1 }) {
                    it[DoctorSchedules.startTime] = LocalTime.of(10, 0)
                }
            }
        }
    }

    @Test
    fun `doctor absence 추가는 stale result를 거부한다`() {
        assertApplyRejectsAfterPlanningFactChange { base ->
            transaction {
                DoctorAbsences.insert {
                    it[DoctorAbsences.doctorId] = base.doctorId1
                    it[DoctorAbsences.absenceDate] = MONDAY
                    it[DoctorAbsences.reason] = "회의"
                }
            }
        }
    }

    @Test
    fun `break 변경은 stale result를 거부한다`() {
        assertApplyRejectsAfterPlanningFactChange { base ->
            transaction {
                BreakTimes.insert {
                    it[BreakTimes.clinicId] = base.clinicId
                    it[BreakTimes.dayOfWeek] = DayOfWeek.MONDAY
                    it[BreakTimes.startTime] = LocalTime.of(12, 0)
                    it[BreakTimes.endTime] = LocalTime.of(13, 0)
                }
            }
        }
    }

    @Test
    fun `default break 변경은 stale result를 거부한다`() {
        assertApplyRejectsAfterPlanningFactChange { base ->
            transaction {
                ClinicDefaultBreakTimes.insert {
                    it[ClinicDefaultBreakTimes.clinicId] = base.clinicId
                    it[ClinicDefaultBreakTimes.name] = "점심"
                    it[ClinicDefaultBreakTimes.startTime] = LocalTime.of(12, 0)
                    it[ClinicDefaultBreakTimes.endTime] = LocalTime.of(13, 0)
                }
            }
        }
    }

    @Test
    fun `closure 추가는 stale result를 거부한다`() {
        assertApplyRejectsAfterPlanningFactChange { base ->
            transaction {
                ClinicClosures.insert {
                    it[ClinicClosures.clinicId] = base.clinicId
                    it[ClinicClosures.closureDate] = MONDAY
                    it[ClinicClosures.reason] = "점검"
                }
            }
        }
    }

    @Test
    fun `holiday 추가는 stale result를 거부한다`() {
        assertApplyRejectsAfterPlanningFactChange { _ ->
            transaction {
                Holidays.insert {
                    it[Holidays.tenantGroupId] = EntityID(TenantGroups.DEFAULT_TENANT_GROUP_ID, TenantGroups)
                    it[Holidays.holidayDate] = MONDAY
                    it[Holidays.name] = "임시 휴일"
                }
            }
        }
    }

    @Test
    fun `treatment equipment 연결 추가는 stale result를 거부한다`() {
        assertApplyRejectsAfterPlanningFactChange { base ->
            transaction {
                val equipmentId = Equipments.insertAndGetId {
                    it[Equipments.clinicId] = base.clinicId
                    it[Equipments.name] = "MRI"
                    it[Equipments.usageDurationMinutes] = 30
                }.value
                TreatmentEquipments.insert {
                    it[TreatmentEquipments.treatmentTypeId] = base.treatmentTypeId
                    it[TreatmentEquipments.equipmentId] = equipmentId
                }
            }
        }
    }

    @Test
    fun `원자적 assignment 적용은 source version CAS와 함께 성공한다`() {
        val (clinicId, doctorId, _, treatmentTypeId) = insertBaseData()
        val appointmentId = transaction {
            Appointments.insertAndGetId {
                it[Appointments.clinicId] = clinicId
                it[Appointments.doctorId] = doctorId
                it[Appointments.treatmentTypeId] = treatmentTypeId
                it[patientName] = "Atomic Patient"
                it[appointmentDate] = MONDAY
                it[startTime] = LocalTime.of(9, 0)
                it[endTime] = LocalTime.of(9, 30)
                it[status] = AppointmentState.REQUESTED
            }.value
        }

        val result = solverService.optimize(scope(clinicId), MONDAY..FRIDAY, Duration.ofSeconds(5))

        solverService.applyOptimizedAssignments(result).shouldBeTrue()

        transaction {
            val applied = checkNotNull(
                AppointmentRepository().findByIdAndScope(appointmentId, scope(clinicId)),
            )
            applied.version.shouldBeEqualTo(1L)
            applied.doctorId.shouldBeEqualTo(result.appointments.single().doctorId)
            applied.appointmentDate.shouldBeEqualTo(result.appointments.single().appointmentDate)
            applied.startTime.shouldBeEqualTo(result.appointments.single().startTime)
            applied.endTime.shouldBeEqualTo(result.appointments.single().endTime)
        }
    }

    @Test
    fun `advisory 확인 뒤 동시 writer가 version을 소비하면 원자적 적용은 stale을 거부한다`() {
        val (clinicId, doctorId, _, treatmentTypeId) = insertBaseData()
        val appointmentId = transaction {
            Appointments.insertAndGetId {
                it[Appointments.clinicId] = clinicId
                it[Appointments.doctorId] = doctorId
                it[Appointments.treatmentTypeId] = treatmentTypeId
                it[patientName] = "Concurrent Patient"
                it[appointmentDate] = MONDAY
                it[startTime] = LocalTime.of(9, 0)
                it[endTime] = LocalTime.of(9, 30)
                it[status] = AppointmentState.REQUESTED
            }.value
        }

        val result = solverService.optimize(scope(clinicId), MONDAY..FRIDAY, Duration.ofSeconds(5))
        solverService.isSourceVersionCurrentAdvisory(result).shouldBeTrue()

        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit<Boolean> {
                transaction {
                    AppointmentRepository().updateLegacyStatus(
                        scope = scope(clinicId),
                        appointmentId = appointmentId,
                        expectedVersion = 0L,
                        newStatus = AppointmentState.CONFIRMED,
                    )
                }
            }.get(5, TimeUnit.SECONDS).shouldBeTrue()

            solverService.applyOptimizedAssignments(result).shouldBeFalse()
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }

        transaction {
            val current = checkNotNull(
                AppointmentRepository().findByIdAndScope(appointmentId, scope(clinicId)),
            )
            current.status.shouldBeEqualTo(AppointmentState.CONFIRMED)
            current.version.shouldBeEqualTo(1L)
        }
    }

    @Test
    fun `assignment CAS 하나가 실패하면 선행 assignment도 함께 rollback된다`() {
        val (clinicId, doctorId, _, treatmentTypeId) = insertBaseData()
        val appointmentId = transaction {
            Appointments.insertAndGetId {
                it[Appointments.clinicId] = clinicId
                it[Appointments.doctorId] = doctorId
                it[Appointments.treatmentTypeId] = treatmentTypeId
                it[patientName] = "Rollback Patient"
                it[appointmentDate] = MONDAY
                it[startTime] = LocalTime.of(9, 0)
                it[endTime] = LocalTime.of(9, 30)
                it[status] = AppointmentState.REQUESTED
            }.value
        }

        val result = solverService.optimize(scope(clinicId), MONDAY..FRIDAY, Duration.ofSeconds(5))
        val duplicatedResult = result.copy(appointments = result.appointments + result.appointments)

        solverService.applyOptimizedAssignments(duplicatedResult).shouldBeFalse()

        transaction {
            val current = checkNotNull(
                AppointmentRepository().findByIdAndScope(appointmentId, scope(clinicId)),
            )
            current.version.shouldBeEqualTo(0L)
            current.status.shouldBeEqualTo(AppointmentState.REQUESTED)
        }
    }

    @Test
    fun `4 - 예약이 없으면 빈 결과 반환`() {
        val (clinicId, _, _, _) = insertBaseData()

        val result = solverService.optimize(scope(clinicId), MONDAY..FRIDAY, Duration.ofSeconds(5))

        result.appointments.shouldBeEmpty()
        result.isFeasible.shouldBeTrue()
    }

    @Test
    fun `optimize fails explicitly when solver returns no score`() {
        val (clinicId, _, _, _) = insertBaseData()
        val factory = mockk<SolverFactory<ScheduleSolution>>()
        val solver = mockk<Solver<ScheduleSolution>>()
        every { factory.buildSolver() } returns solver
        every { solver.solve(any()) } returns ScheduleSolution()

        val service = SolverService(solverFactory = factory)

        assertFailsWith<IllegalStateException> {
            service.optimize(scope(clinicId), MONDAY..FRIDAY)
        }
    }
}
