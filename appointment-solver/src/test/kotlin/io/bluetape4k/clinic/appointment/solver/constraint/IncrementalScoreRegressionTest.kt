package io.bluetape4k.clinic.appointment.solver.constraint

import ai.timefold.solver.core.api.score.HardSoftScore
import ai.timefold.solver.core.api.score.stream.Constraint
import ai.timefold.solver.core.api.score.stream.ConstraintFactory
import ai.timefold.solver.core.api.score.stream.ConstraintProvider
import ai.timefold.solver.core.config.solver.SolverConfig
import ai.timefold.solver.core.impl.score.director.InnerScoreDirector
import ai.timefold.solver.core.impl.solver.DefaultSolverFactory
import ai.timefold.solver.core.preview.api.move.Move
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.model.dto.DoctorAbsenceRecord
import io.bluetape4k.clinic.appointment.solver.domain.AppointmentPlanning
import io.bluetape4k.clinic.appointment.solver.domain.ScheduleSolution
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import kotlin.reflect.KMutableProperty1

/**
 * 동일 계산기에 변경 이력을 누적하고, 매 단계 새 계산기의 전체 재계산과 비교한다.
 * 정적 ConstraintVerifier만으로 검증할 수 없는 튜플 갱신 경로를 테스트한다.
 * 내부 ScoreDirector API는 이 테스트 안에서만 사용하며 Timefold 전환 시 재검증한다.
 */
class IncrementalScoreRegressionTest {

    private val monday = LocalDate.of(2026, 3, 23)
    private val tuesday = monday.plusDays(1)
    private val factory = DefaultSolverFactory<ScheduleSolution>(
        SolverConfig()
            .withSolutionClass(ScheduleSolution::class.java)
            .withEntityClasses(AppointmentPlanning::class.java)
            .withConstraintProviderClass(RegressionConstraints::class.java),
    ).getScoreDirectorFactory<HardSoftScore>()

    @Test
    fun `시간 변경으로 ifExists 필터를 반복 출입해도 점수가 일치한다`() {
        val solution = solution()
        val appointment = solution.appointments.first()
        factory.buildScoreDirector().use { director ->
            director.setWorkingSolution(solution)
            verifyScore(director, HardSoftScore.ofSoft(-400))
            repeat(3) {
                change(director, appointment, AppointmentPlanning::startTime, LocalTime.of(9, 0))
                verifyScore(director, HardSoftScore.of(-1, -1000))
                change(director, appointment, AppointmentPlanning::startTime, LocalTime.of(9, 30))
                verifyScore(director, HardSoftScore.of(-1, -700))
                change(director, appointment, AppointmentPlanning::startTime, LocalTime.of(10, 0))
                verifyScore(director, HardSoftScore.ofSoft(-400))
            }
        }
    }

    @Test
    fun `의사와 날짜의 join 키를 변경하고 복원해도 점수가 일치한다`() {
        val solution = solution()
        val appointment = solution.appointments.first()
        factory.buildScoreDirector().use { director ->
            director.setWorkingSolution(solution)
            change(director, appointment, AppointmentPlanning::startTime, LocalTime.of(9, 0))
            verifyScore(director, HardSoftScore.of(-1, -1000))
            repeat(3) {
                change(director, appointment, AppointmentPlanning::doctorId, 200L)
                verifyScore(director, HardSoftScore.ZERO)
                change(director, appointment, AppointmentPlanning::appointmentDate, tuesday)
                verifyScore(director, HardSoftScore.ZERO)
                change(director, appointment, AppointmentPlanning::doctorId, 100L)
                verifyScore(director, HardSoftScore.ZERO)
                change(director, appointment, AppointmentPlanning::appointmentDate, monday)
                verifyScore(director, HardSoftScore.of(-1, -1000))
            }
        }
    }

    @Test
    fun `join 오른쪽 예약 변경과 시간 간격 필터 복원이 점수에 반영된다`() {
        val solution = solution()
        val appointment = solution.appointments.last()
        factory.buildScoreDirector().use { director ->
            director.setWorkingSolution(solution)
            verifyScore(director, HardSoftScore.ofSoft(-400))
            repeat(3) {
                change(director, appointment, AppointmentPlanning::doctorId, 200L)
                verifyScore(director, HardSoftScore.ZERO)
                change(director, appointment, AppointmentPlanning::doctorId, 100L)
                verifyScore(director, HardSoftScore.ofSoft(-400))
                change(director, appointment, AppointmentPlanning::startTime, LocalTime.of(10, 0))
                verifyScore(director, HardSoftScore.ofSoft(-100))
                change(director, appointment, AppointmentPlanning::startTime, LocalTime.of(11, 0))
                verifyScore(director, HardSoftScore.ofSoft(-400))
            }
        }
    }

    @Test
    fun `임시 move의 자동 undo 후 배정과 저장된 점수가 복원된다`() {
        val solution = solution()
        val appointment = solution.appointments.first()
        val timeVariable = factory.solutionDescriptor.metaModel
            .genuineEntity(AppointmentPlanning::class.java)
            .basicVariable("startTime", LocalTime::class.java)
        val move = Move<ScheduleSolution> { view ->
            view.changeVariable(timeVariable, appointment, LocalTime.of(9, 0))
        }
        factory.buildScoreDirector().use { director ->
            director.setWorkingSolution(solution)
            verifyScore(director, HardSoftScore.ofSoft(-400))
            repeat(3) {
                val temporary = director.executeTemporaryMove(move, { _ ->
                    appointment.startTime shouldBeEqualTo LocalTime.of(9, 0)
                }, true)
                temporary.raw() shouldBeEqualTo HardSoftScore.of(-1, -1000)
                appointment.startTime shouldBeEqualTo LocalTime.of(10, 0)
                // 재계산으로 저장 점수의 복원 누락을 덮기 전에 검사한다.
                solution.score shouldBeEqualTo HardSoftScore.ofSoft(-400)
                verifyScore(director, HardSoftScore.ofSoft(-400))
            }
        }
    }

    private fun <T> change(
        director: InnerScoreDirector<ScheduleSolution, HardSoftScore>,
        appointment: AppointmentPlanning,
        property: KMutableProperty1<AppointmentPlanning, T>,
        value: T,
    ) {
        director.beforeVariableChanged(appointment, property.name)
        property.set(appointment, value)
        director.afterVariableChanged(appointment, property.name)
        director.updateShadowVariables()
    }

    private fun verifyScore(
        director: InnerScoreDirector<ScheduleSolution, HardSoftScore>,
        expected: HardSoftScore,
    ) {
        val incremental = director.calculateScore()
        incremental.raw() shouldBeEqualTo expected
        incremental.unassignedCount() shouldBeEqualTo 0
        incremental.raw().isFeasible shouldBeEqualTo expected.isFeasible
        // 복제된 현재 상태로 새 계산기를 구성해 기존 튜플·점수 캐시를 공유하지 않는다.
        factory.buildScoreDirector().use { fresh ->
            fresh.setWorkingSolution(director.cloneWorkingSolution())
            fresh.calculateScore() shouldBeEqualTo incremental
        }
    }

    private fun solution() = ScheduleSolution(
        doctorAbsences = listOf(
            DoctorAbsenceRecord(
                doctorId = 100L,
                absenceDate = monday,
                startTime = LocalTime.of(9, 0),
                endTime = LocalTime.of(10, 0),
            ),
        ),
        doctorIds = listOf(100L, 200L),
        dateRange = listOf(monday, tuesday),
        timeSlots = listOf(9, 10, 11).map { LocalTime.of(it, 0) } + LocalTime.of(9, 30),
        appointments = listOf(10, 11).mapIndexed { index, hour ->
            AppointmentPlanning(
                id = index.toLong() + 1,
                clinicId = 10L,
                treatmentTypeId = 1L,
                durationMinutes = 30,
                doctorId = 100L,
                appointmentDate = monday,
                startTime = LocalTime.of(hour, 0),
            )
        },
    )

    /** 운영 제약을 그대로 사용하되 기대 점수를 명시할 수 있는 세 제약으로 한정한다. */
    class RegressionConstraints: ConstraintProvider {
        override fun defineConstraints(factory: ConstraintFactory): Array<Constraint> = arrayOf(
            HardConstraints.noDoctorAbsenceConflict(factory),
            SoftConstraints.doctorLoadBalance(factory),
            SoftConstraints.minimizeGaps(factory),
        )
    }
}
