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
import java.time.Duration
import java.time.LocalDate

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
     * 최적화 결과를 적용하기 직전에 원본 snapshot version을 다시 확인합니다.
     * 결과 자체는 read-only이므로 이 검사는 caller의 apply transaction과 분리해 사용할 수
     * 있으며, 하나라도 변경됐으면 false를 반환해 stale 결과 적용을 막습니다.
     */
    fun verifySourceVersions(result: SolverResult): Boolean {
        val resultScope = result.scope
        return transaction {
            result.sourceVersions.all { (appointmentId, version) ->
                appointmentRepository.findByIdAndScope(appointmentId, resultScope)?.version == version
            }
        }
    }

    private data class SolverSnapshot(
        val solution: ScheduleSolution,
        val originalAppointments: Map<Long, AppointmentRecord>,
    )

    private fun loadSnapshot(scope: TenantClinicScope, dateRange: ClosedRange<LocalDate>): SolverSnapshot =
        transaction {
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
            SolverSnapshot(
                solution = solution,
                originalAppointments = appointments.associateBy { record ->
                    checkNotNull(record.id) { "Appointment record is missing id: clinicId=${record.clinicId}" }
                },
            )
        }
}
