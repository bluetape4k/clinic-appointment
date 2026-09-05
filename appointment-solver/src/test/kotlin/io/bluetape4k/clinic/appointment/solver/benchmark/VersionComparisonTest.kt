package io.bluetape4k.clinic.appointment.solver.benchmark

import ai.timefold.solver.core.api.score.HardSoftScore
import ai.timefold.solver.core.api.solver.SolutionManager
import ai.timefold.solver.core.api.solver.SolverFactory
import ai.timefold.solver.core.config.constructionheuristic.ConstructionHeuristicPhaseConfig
import ai.timefold.solver.core.config.constructionheuristic.ConstructionHeuristicType
import ai.timefold.solver.core.config.localsearch.LocalSearchPhaseConfig
import ai.timefold.solver.core.config.localsearch.LocalSearchType
import ai.timefold.solver.core.config.solver.SolverConfig
import ai.timefold.solver.core.config.solver.termination.TerminationConfig
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.clinic.appointment.solver.constraint.AppointmentConstraintProvider
import io.bluetape4k.clinic.appointment.solver.domain.AppointmentPlanning
import io.bluetape4k.clinic.appointment.solver.domain.ScheduleSolution
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * #455 버전 전환 검증용 실험. 같은 fixture와 seed로 Local Search 200 step을 실행한다.
 * 일반 테스트와 분리하여 `timefoldVersionComparison` 태스크로 명시적으로 실행한다.
 * 실행 시간은 예열 이후 반복 표본이며, 운영 성능이나 통계적 우월성을 보장하지 않는다.
 */
@Tag("version-comparison")
class VersionComparisonTest {

    @Test
    fun `동일 데이터 seed step의 최종 점수는 재계산과 반복 실행에서 일치한다`() {
        val version = System.getProperty("issue455.timefoldVersion").shouldNotBeNull()
        val output = Path.of(System.getProperty("issue455.output").shouldNotBeNull())
        Files.createDirectories(output.parent)
        Files.newBufferedWriter(output).use { writer ->
            writer.appendLine(
                "version,doctors,appointments,days,seed,steps,phase,iteration,hard,soft,feasible,elapsedNanos",
            )
            for ((doctorCount, appointmentCount) in listOf(2 to 10, 5 to 30)) {
                val factory = factory()
                val scores = mutableListOf<HardSoftScore>()
                repeat(7) { iteration ->
                    val input = BenchmarkTest().buildSolution(doctorCount, appointmentCount, days = 5)
                    input.appointments.shouldHaveSize(appointmentCount)
                    val solver = factory.buildSolver()
                    val started = System.nanoTime()
                    val result = solver.solve(input)
                    val elapsed = System.nanoTime() - started
                    val score = result.score.shouldNotBeNull()
                    score.isFeasible.shouldBeTrue()
                    // 기존 점수와 증분 상태를 재사용하지 않는 새 ScoreDirector로 확인한다.
                    result.score = null
                    SolutionManager.create<ScheduleSolution, HardSoftScore>(factory)
                        .update(result).shouldBeEqualTo(score)
                    val phase = if (iteration < 2) "warmup" else "measurement"
                    if (iteration >= 2) scores.add(score)
                    writer.appendLine(
                        "$version,$doctorCount,$appointmentCount,5,37,200,$phase,$iteration," +
                            "${score.hardScore()},${score.softScore()},${score.isFeasible},$elapsed",
                    )
                    writer.flush()
                }
                scores.shouldHaveSize(5)
                scores.distinct().shouldHaveSize(1)
            }
        }
    }

    private fun factory(): SolverFactory<ScheduleSolution> = SolverFactory.create(
        SolverConfig()
            .withSolutionClass(ScheduleSolution::class.java)
            .withEntityClasses(AppointmentPlanning::class.java)
            .withConstraintProviderClass(AppointmentConstraintProvider::class.java)
            .withRandomSeed(37L)
            .withMoveThreadCount("NONE")
            .withPhases(
                ConstructionHeuristicPhaseConfig()
                    .withConstructionHeuristicType(ConstructionHeuristicType.FIRST_FIT_DECREASING),
                LocalSearchPhaseConfig()
                    .withLocalSearchType(LocalSearchType.LATE_ACCEPTANCE)
                    .withTerminationConfig(TerminationConfig().withStepCountLimit(200)),
            ),
    )
}
