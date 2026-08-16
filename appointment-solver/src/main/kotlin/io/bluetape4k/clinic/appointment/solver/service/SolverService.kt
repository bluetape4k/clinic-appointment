package io.bluetape4k.clinic.appointment.solver.service

import ai.timefold.solver.core.api.solver.SolverFactory
import io.bluetape4k.logging.KLogging
import io.bluetape4k.clinic.appointment.model.dto.AppointmentRecord
import io.bluetape4k.clinic.appointment.repository.AppointmentRepository
import io.bluetape4k.clinic.appointment.repository.ClinicRepository
import io.bluetape4k.clinic.appointment.repository.DoctorRepository
import io.bluetape4k.clinic.appointment.repository.EquipmentRepository
import io.bluetape4k.clinic.appointment.repository.HolidayRepository
import io.bluetape4k.clinic.appointment.repository.TreatmentTypeRepository
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import io.bluetape4k.clinic.appointment.solver.converter.SolutionConverter
import io.bluetape4k.clinic.appointment.solver.domain.ScheduleSolution
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.time.LocalDate
import java.util.ArrayDeque

/**
 * Solver 실행 진입점.
 *
 * Repository에서 데이터를 로딩하고 Solver를 실행하여 최적화된 예약 배치를 반환합니다.
 *
 * @param clinicRepository 병원 데이터 조회 Repository
 * @param doctorRepository 의사 데이터 조회 Repository
 * @param appointmentRepository 예약 데이터 조회 Repository
 * @param treatmentTypeRepository 진료 유형 데이터 조회 Repository
 * @param holidayRepository 휴일 데이터 조회 Repository
 * @param solverFactory 기본 SolverFactory
 */
class SolverService(
    private val clinicRepository: ClinicRepository = ClinicRepository(),
    private val doctorRepository: DoctorRepository = DoctorRepository(),
    private val appointmentRepository: AppointmentRepository = AppointmentRepository(),
    private val treatmentTypeRepository: TreatmentTypeRepository = TreatmentTypeRepository(),
    private val equipmentRepository: EquipmentRepository = EquipmentRepository(),
    private val holidayRepository: HolidayRepository = HolidayRepository(),
    private val solverFactory: SolverFactory<ScheduleSolution> = AppointmentSolverConfig.createFactory(),
) {
    companion object: KLogging()

    /**
     * 특정 클리닉의 주어진 날짜 범위에 대해 배치 최적화를 실행합니다.
     *
     * @param clinicId 대상 클리닉
     * @param dateRange 최적화 대상 날짜 범위
     * @param timeLimit 최대 Solver 실행 시간
     * @return 최적화된 예약 배치 결과
     */
    fun optimize(
        scope: TenantClinicScope,
        dateRange: ClosedRange<LocalDate>,
        timeLimit: Duration = Duration.ofSeconds(30),
    ): SolverResult {
        val snapshot = loadSnapshot(scope, dateRange)
        val solution = snapshot.solution
        val factory = if (timeLimit != Duration.ofSeconds(30)) {
            AppointmentSolverConfig.createFactory(timeLimit)
        } else {
            solverFactory
        }

        val entityCount = solution.appointments.size
        val pinnedCount = solution.appointments.count { it.pinned }

        log.info("Solver 시작: scope=${scope.cacheKey()}, dateRange=$dateRange, entities=$entityCount, pinned=$pinnedCount")

        val startMillis = System.currentTimeMillis()
        val solver = factory.buildSolver()
        val result = solver.solve(solution)
        val solveTimeMillis = System.currentTimeMillis() - startMillis

        val originalMap = snapshot.originalAppointments

        val optimizedAppointments = SolutionConverter.extractResults(result, originalMap)
        val score = checkNotNull(result.score) {
            "Solver returned no score: scope=${scope.cacheKey()}, dateRange=$dateRange"
        }

        log.info("Solver 완료: score=$score, feasible=${score.isFeasible}, time=${solveTimeMillis}ms")

        return SolverResult(
            score = score,
            appointments = optimizedAppointments,
            isFeasible = score.isFeasible,
            solveTimeMillis = solveTimeMillis,
            entityCount = entityCount,
            pinnedCount = pinnedCount,
            scope = scope,
            sourceVersions = originalMap.mapNotNull { (id, record) -> record.version.let { id to it } }.toMap(),
            dateRange = dateRange,
            planningFactVersion = snapshot.planningFactVersion,
        )
    }

    /**
     * 임시휴진에 의한 재스케줄을 전역 최적화로 수행합니다.
     *
     * @param clinicId 대상 클리닉
     * @param closureDate 휴진 날짜
     * @param searchDays 후보 탐색 일수
     * @param timeLimit 최대 Solver 실행 시간
     * @return 최적화된 예약 배치 결과
     */
    fun optimizeReschedule(
        scope: TenantClinicScope,
        closureDate: LocalDate,
        searchDays: Int = 7,
        timeLimit: Duration = Duration.ofSeconds(30),
    ): SolverResult {
        val dateRange = closureDate..closureDate.plusDays(searchDays.toLong())
        return optimize(scope, dateRange, timeLimit)
    }

    /**
     * 원본 snapshot version을 조회 시점에 advisory 방식으로 확인합니다.
     *
     * 이 결과만으로 assignment를 반영하면 확인 직후 다른 writer가 version을 소비할 수
     * 있으므로 실제 반영에는 반드시 [applyOptimizedAssignments]를 사용해야 합니다.
     */
    fun isSourceVersionCurrentAdvisory(result: SolverResult): Boolean {
        if (result.dateRange == null || result.planningFactVersion.isBlank()) return false

        val resultScope = result.scope
        return transaction {
            val snapshot = loadSnapshotInCurrentTransaction(resultScope, checkNotNull(result.dateRange))
            if (snapshot.planningFactVersion != result.planningFactVersion) return@transaction false

            result.sourceVersions.all { (appointmentId, version) ->
                appointmentRepository.findByIdAndScope(appointmentId, resultScope)?.version == version
            }
        }
    }

    /**
     * 기존 호출자 호환용 alias입니다. 실제 결과 반영에는 사용하지 말고
     * [applyOptimizedAssignments]를 사용해야 합니다.
     */
    @Deprecated(
        message = "Use isSourceVersionCurrentAdvisory for an advisory check or applyOptimizedAssignments for atomic application",
        replaceWith = ReplaceWith("isSourceVersionCurrentAdvisory(result)"),
    )
    fun verifySourceVersions(result: SolverResult): Boolean = isSourceVersionCurrentAdvisory(result)

    /**
     * 최적화 결과를 source version fence와 함께 원자적으로 반영합니다.
     *
     * `isSourceVersionCurrentAdvisory`는 호출자에게 최신성만 알려주는 advisory check입니다. 실제
     * 반영은 이 메서드를 사용해야 하며, source rows를 잠근 같은 transaction 안에서 각
     * assignment를 version CAS로 갱신합니다. stale 결과이거나 CAS 하나라도 실패하면
     * transaction 전체를 rollback하고 `false`를 반환합니다.
     */
    fun applyOptimizedAssignments(result: SolverResult): Boolean {
        return try {
            transaction(transactionIsolation = Connection.TRANSACTION_SERIALIZABLE) {
                val resultDateRange = result.dateRange ?: throw StaleSolverResultException
                if (result.planningFactVersion.isBlank()) throw StaleSolverResultException

                val current = loadSnapshotInCurrentTransaction(result.scope, resultDateRange)
                if (current.planningFactVersion != result.planningFactVersion) {
                    throw StaleSolverResultException
                }

                if (!appointmentRepository.lockLegacySourceVersions(result.scope, result.sourceVersions)) {
                    throw StaleSolverResultException
                }

                val lockedSnapshot = loadSnapshotInCurrentTransaction(result.scope, resultDateRange)
                if (lockedSnapshot.planningFactVersion != result.planningFactVersion) {
                    throw StaleSolverResultException
                }

                result.appointments.forEach { appointment ->
                    val appointmentId = checkNotNull(appointment.id) {
                        "Solver result appointment is missing id"
                    }
                    val expectedVersion = result.sourceVersions[appointmentId]
                        ?: throw StaleSolverResultException
                    val applied = appointmentRepository.updateLegacyAssignment(
                        scope = result.scope,
                        appointmentId = appointmentId,
                        expectedVersion = expectedVersion,
                        doctorId = appointment.doctorId,
                        appointmentDate = appointment.appointmentDate,
                        startTime = appointment.startTime,
                        endTime = appointment.endTime,
                    )
                    if (!applied) throw StaleSolverResultException
                }
                true
            }
        } catch (_: StaleSolverResultException) {
            false
        } catch (failure: Exception) {
            if (failure.isSerializationConflict()) false else throw failure
        }
    }

    private object StaleSolverResultException : RuntimeException()

    private data class SolverSnapshot(
        val solution: ScheduleSolution,
        val originalAppointments: Map<Long, AppointmentRecord>,
        val planningFactVersion: String,
    )

    private fun loadSnapshot(scope: TenantClinicScope, dateRange: ClosedRange<LocalDate>): SolverSnapshot =
        transaction {
            loadSnapshotInCurrentTransaction(scope, dateRange)
        }

    private fun loadSnapshotInCurrentTransaction(
        scope: TenantClinicScope,
        dateRange: ClosedRange<LocalDate>,
    ): SolverSnapshot {
        val clinic = clinicRepository.findByIdAndTenant(scope.clinicId, scope.tenantGroupId)
            ?: throw IllegalArgumentException("Clinic not found: ${scope.clinicId}")

        val doctors = doctorRepository.findByScope(scope)
        val appointments = appointmentRepository.findByClinicAndDateRange(scope, dateRange)
        val treatments = treatmentTypeRepository.findByScope(scope)
        val equipments = equipmentRepository.findByScope(scope)
        val operatingHours = clinicRepository.findAllOperatingHours(scope)
        val doctorSchedules = doctors.flatMap { doctor ->
            val doctorId = checkNotNull(doctor.id) {
                "Doctor record is missing id: clinicId=${doctor.clinicId}"
            }
            doctorRepository.findAllSchedules(scope, doctorId)
        }
        val doctorAbsences = doctors.flatMap { doctor ->
            val doctorId = checkNotNull(doctor.id) {
                "Doctor record is missing id: clinicId=${doctor.clinicId}"
            }
            doctorRepository.findAbsencesByDateRange(scope, doctorId, dateRange)
        }
        val breakTimes = clinicRepository.findAllBreakTimes(scope)
        val defaultBreakTimes = clinicRepository.findDefaultBreakTimes(scope)
        val closures = clinicRepository.findClosuresByDateRange(scope, dateRange)
        val holidays = holidayRepository.findByDateRange(scope, dateRange)
        val treatmentEquipments = treatmentTypeRepository.findAllTreatmentEquipments(scope)

        val solution = SolutionConverter.buildSolution(
            clinic = clinic,
            doctors = doctors,
            appointments = appointments,
            treatments = treatments,
            equipments = equipments,
            operatingHours = operatingHours,
            doctorSchedules = doctorSchedules,
            doctorAbsences = doctorAbsences,
            breakTimes = breakTimes,
            defaultBreakTimes = defaultBreakTimes,
            closures = closures,
            holidays = holidays,
            treatmentEquipments = treatmentEquipments,
            dateRange = dateRange,
        )
        return SolverSnapshot(
            solution = solution,
            originalAppointments = appointments.associateBy { record ->
                checkNotNull(record.id) { "Appointment record is missing id: clinicId=${record.clinicId}" }
            },
            planningFactVersion = PlanningFactVersionHasher.hash(scope, dateRange, solution),
        )
    }

    private fun Throwable.isSerializationConflict(): Boolean = sqlExceptions().any { exception ->
        exception.sqlState == "40001" || exception.sqlState == "40P01"
    }

    private fun Throwable.sqlExceptions(): Sequence<SQLException> = sequence {
        val seen = mutableSetOf<Throwable>()
        val pending = ArrayDeque<Throwable>()
        pending.add(this@sqlExceptions)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!seen.add(current)) continue
            if (current is SQLException) {
                yield(current)
                current.nextException?.let(pending::addLast)
            }
            current.cause?.let(pending::addLast)
        }
    }

}
