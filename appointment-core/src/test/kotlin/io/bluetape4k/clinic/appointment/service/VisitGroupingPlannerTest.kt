package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.clinic.appointment.model.plan.ExecutionTreatment
import io.bluetape4k.clinic.appointment.model.plan.TreatmentPair
import io.bluetape4k.clinic.appointment.model.plan.VisitGroupingConstraint
import io.bluetape4k.clinic.appointment.model.plan.VisitGroupingType
import org.junit.jupiter.api.Test

class VisitGroupingPlannerTest {

    private val planner = VisitGroupingPlanner()

    @Test
    fun `같은 방문 필수 연결요소를 먼저 묶고 항목별 시간을 보존한다`() {
        val diagnosis = treatment("diagnosis", preparation = 5, treatment = 20, recovery = 0)
        val lifting = treatment("lifting", preparation = 10, treatment = 40, recovery = 30)

        val visits = planner.group(
            treatments = listOf(diagnosis, lifting),
            constraints = listOf(
                VisitGroupingConstraint(
                    firstTreatmentKey = "diagnosis",
                    secondTreatmentKey = "lifting",
                    type = VisitGroupingType.MUST_SAME_VISIT,
                ),
            ),
        )

        visits shouldHaveSize 1
        visits.single().treatments shouldBeEqualTo listOf(diagnosis, lifting)
        visits.single().totalDurationMinutes shouldBeEqualTo 105
    }

    @Test
    fun `같은 두 항목에 필수 묶음과 필수 분리를 함께 선언하면 거부한다`() {
        assertFailsWith<IllegalArgumentException> {
            planner.group(
                treatments = listOf(treatment("a"), treatment("b")),
                constraints = listOf(
                    VisitGroupingConstraint("a", "b", VisitGroupingType.MUST_SAME_VISIT),
                    VisitGroupingConstraint("a", "b", VisitGroupingType.MUST_SEPARATE_VISIT),
                ),
            )
        }
    }

    @Test
    fun `같은 방문 연결요소 안의 자원 비호환 항목은 거부한다`() {
        assertFailsWith<IllegalArgumentException> {
            planner.group(
                treatments = listOf(treatment("a"), treatment("b")),
                constraints = listOf(
                    VisitGroupingConstraint("a", "b", VisitGroupingType.MUST_SAME_VISIT),
                ),
                incompatiblePairs = setOf(TreatmentPair.of("a", "b")),
            )
        }
    }

    @Test
    fun `같은 방문 허용은 의미를 강제하지 않고 별도 방문 후보를 유지한다`() {
        val visits = planner.group(
            treatments = listOf(treatment("a"), treatment("b")),
            constraints = listOf(
                VisitGroupingConstraint("a", "b", VisitGroupingType.MAY_SAME_VISIT),
            ),
        )

        visits shouldHaveSize 2
    }

    private fun treatment(
        key: String,
        preparation: Int = 0,
        treatment: Int = 10,
        recovery: Int = 0,
    ): ExecutionTreatment =
        ExecutionTreatment(
            treatmentKey = key,
            componentProductId = key,
            componentProductVersionId = "$key-v1",
            sourceBomItemId = "$key-bom",
            sequence = 1,
            representativeTreatmentName = key,
            detailedTreatmentCodes = listOf(key),
            preparationMinutes = preparation,
            treatmentMinutes = treatment,
            recoveryMinutes = recovery,
            practitionerQualifications = emptyList(),
            equipmentTypes = emptyList(),
            spaceCapabilities = emptyList(),
        )
}
