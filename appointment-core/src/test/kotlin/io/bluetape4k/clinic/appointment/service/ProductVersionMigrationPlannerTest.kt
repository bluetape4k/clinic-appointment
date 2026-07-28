package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.model.plan.MigrationMapping
import io.bluetape4k.clinic.appointment.model.plan.MigrationMappingType
import io.bluetape4k.clinic.appointment.model.plan.MigrationTarget
import io.bluetape4k.clinic.appointment.model.plan.PlanTreatment
import io.bluetape4k.clinic.appointment.model.plan.PlanTreatmentStatus
import org.junit.jupiter.api.Test

class ProductVersionMigrationPlannerTest {

    private val planner = ProductVersionMigrationPlanner()

    @Test
    fun `모든 전환 유형으로 미래 항목을 새 revision에 계산하고 완료 항목은 유지한다`() {
        val current = listOf(
            treatment("completed", PlanTreatmentStatus.COMPLETED),
            treatment("keep"),
            treatment("replace"),
            treatment("split"),
            treatment("merge-a"),
            treatment("merge-b"),
            treatment("remove"),
        )
        val mappings = listOf(
            mapping(MigrationMappingType.KEEP, setOf("keep"), "keep-v2"),
            mapping(MigrationMappingType.REPLACE, setOf("replace"), "replacement"),
            MigrationMapping(
                type = MigrationMappingType.SPLIT,
                sourceTreatmentKeys = setOf("split"),
                targets = listOf(MigrationTarget("split-a"), MigrationTarget("split-b")),
            ),
            mapping(MigrationMappingType.MERGE, setOf("merge-a", "merge-b"), "merged"),
            MigrationMapping(
                type = MigrationMappingType.REMOVE,
                sourceTreatmentKeys = setOf("remove"),
                targets = emptyList(),
            ),
            mapping(MigrationMappingType.ADD, emptySet(), "added"),
        )

        val result = planner.migrate(
            currentTreatments = current,
            mappings = mappings,
            targetProductVersionId = "product-v2",
        )

        result.retainedCompleted.map { it.treatmentKey } shouldBeEqualTo listOf("completed")
        result.futureTreatments.map { it.treatmentKey }.toSet() shouldBeEqualTo
            setOf("keep-v2", "replacement", "split-a", "split-b", "merged", "added")
        result.futureTreatments.map { it.productVersionId }.distinct() shouldBeEqualTo
            listOf("product-v2")
    }

    @Test
    fun `미완료 source가 누락되거나 중복 설명되면 거부한다`() {
        val current = listOf(treatment("a"), treatment("b"))

        assertFailsWith<IllegalArgumentException> {
            planner.migrate(
                currentTreatments = current,
                mappings = listOf(mapping(MigrationMappingType.KEEP, setOf("a"), "a-v2")),
                targetProductVersionId = "product-v2",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            planner.migrate(
                currentTreatments = current,
                mappings = listOf(
                    mapping(MigrationMappingType.KEEP, setOf("a"), "a-v2"),
                    mapping(MigrationMappingType.REPLACE, setOf("a", "b"), "replacement"),
                ),
                targetProductVersionId = "product-v2",
            )
        }
    }

    @Test
    fun `완료 항목은 전환 mapping의 source가 될 수 없다`() {
        assertFailsWith<IllegalArgumentException> {
            planner.migrate(
                currentTreatments = listOf(treatment("done", PlanTreatmentStatus.COMPLETED)),
                mappings = listOf(mapping(MigrationMappingType.REPLACE, setOf("done"), "new")),
                targetProductVersionId = "product-v2",
            )
        }
    }

    private fun treatment(
        key: String,
        status: PlanTreatmentStatus = PlanTreatmentStatus.PENDING,
    ): PlanTreatment =
        PlanTreatment(
            treatmentKey = key,
            productVersionId = "product-v1",
            status = status,
        )

    private fun mapping(
        type: MigrationMappingType,
        sources: Set<String>,
        target: String,
    ): MigrationMapping =
        MigrationMapping(
            type = type,
            sourceTreatmentKeys = sources,
            targets = listOf(MigrationTarget(target)),
        )
}
