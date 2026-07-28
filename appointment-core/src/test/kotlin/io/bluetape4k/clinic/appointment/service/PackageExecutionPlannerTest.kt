package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.clinic.appointment.model.plan.ComponentSelection
import io.bluetape4k.clinic.appointment.model.plan.ComponentVersionRef
import io.bluetape4k.clinic.appointment.model.plan.ExecutionDependency
import io.bluetape4k.clinic.appointment.model.plan.ExecutionDependencyType
import io.bluetape4k.clinic.appointment.model.plan.ExecutionTreatment
import io.bluetape4k.clinic.appointment.model.plan.PackageExecutionSnapshot
import org.junit.jupiter.api.Test

class PackageExecutionPlannerTest {

    private val planner = PackageExecutionPlanner()

    @Test
    fun `미백치료 5회권의 전개 결과와 구성 상품 version을 그대로 보존한다`() {
        val treatments = (1..5).map { sequence ->
            treatment(
                key = "whitening-$sequence",
                componentId = "whitening",
                versionId = "whitening-v2",
                sequence = sequence,
            )
        }
        val snapshot = snapshot(
            components = listOf(ComponentVersionRef("whitening", "whitening-v2", quantity = 5)),
            treatments = treatments,
        )

        val revision = planner.plan(snapshot)

        revision.treatments shouldHaveSize 5
        revision.treatments.map { it.componentProductVersionId }.distinct() shouldBeEqualTo
            listOf("whitening-v2")
        revision.sourceSnapshotHash shouldBeEqualTo "snapshot-hash"
    }

    @Test
    fun `N개 중 M개 선택 결과가 부족하면 거부한다`() {
        val snapshot = snapshot(
            components = listOf(
                ComponentVersionRef("laser", "laser-v1", selectionGroupId = "care"),
            ),
            treatments = listOf(treatment("laser-1", "laser", "laser-v1", 1)),
            selections = listOf(
                ComponentSelection(
                    selectionGroupId = "care",
                    candidateCount = 3,
                    requiredSelectionCount = 2,
                ),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            planner.plan(snapshot)
        }
    }

    @Test
    fun `선택되지 않은 구성 상품을 출처로 가진 진료는 거부한다`() {
        val snapshot = snapshot(
            components = listOf(ComponentVersionRef("laser", "laser-v1")),
            treatments = listOf(treatment("peeling-1", "peeling", "peeling-v4", 1)),
        )

        assertFailsWith<IllegalArgumentException> {
            planner.plan(snapshot)
        }
    }

    @Test
    fun `반복 횟수와 전체 진료 및 edge 안전 상한을 초과하면 거부한다`() {
        assertFailsWith<IllegalArgumentException> {
            planner.plan(
                snapshot(
                    components = listOf(ComponentVersionRef("repeat", "v1", quantity = 101)),
                    treatments = listOf(treatment("repeat-1", "repeat", "v1", 1)),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            planner.plan(
                snapshot(
                    components = listOf(ComponentVersionRef("bulk", "v1", quantity = 100)),
                    treatments = (1..501).map { treatment("bulk-$it", "bulk", "v1", it) },
                ),
            )
        }
        val treatments = (1..91).map { treatment("edge-$it", "edge", "v1", it) }
        val dependencies = buildList {
            for (from in treatments.indices) {
                for (to in (from + 1) until treatments.size) {
                    add(
                        ExecutionDependency(
                            predecessorTreatmentKey = treatments[from].treatmentKey,
                            successorTreatmentKey = treatments[to].treatmentKey,
                            type = ExecutionDependencyType.NON_BLOCKING,
                        ),
                    )
                }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            planner.plan(
                snapshot(
                    components = listOf(ComponentVersionRef("edge", "v1", quantity = 91)),
                    treatments = treatments,
                    dependencies = dependencies,
                ),
            )
        }
    }

    @Test
    fun `실행 의존 그래프에 cycle이 있으면 거부한다`() {
        val snapshot = snapshot(
            components = listOf(ComponentVersionRef("care", "v1", quantity = 2)),
            treatments = listOf(
                treatment("care-1", "care", "v1", 1),
                treatment("care-2", "care", "v1", 2),
            ),
            dependencies = listOf(
                ExecutionDependency("care-1", "care-2", ExecutionDependencyType.BLOCKING),
                ExecutionDependency("care-2", "care-1", ExecutionDependencyType.BLOCKING),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            planner.plan(snapshot)
        }
    }

    @Test
    fun `candidate slot과 반환 proposal 안전 상한을 초과하면 거부한다`() {
        assertFailsWith<IllegalArgumentException> {
            planner.validateSearchBounds(candidateSlotCount = 2_001, proposalCount = 20)
        }
        assertFailsWith<IllegalArgumentException> {
            planner.validateSearchBounds(candidateSlotCount = 2_000, proposalCount = 21)
        }
    }

    private fun snapshot(
        components: List<ComponentVersionRef>,
        treatments: List<ExecutionTreatment>,
        dependencies: List<ExecutionDependency> = emptyList(),
        selections: List<ComponentSelection> = emptyList(),
    ): PackageExecutionSnapshot =
        PackageExecutionSnapshot(
            packageProductId = "package",
            packageProductVersionId = "package-v3",
            selectedComponentVersions = components,
            componentSelections = selections,
            expandedTreatmentItems = treatments,
            executionDependencies = dependencies,
            visitGroupingConstraints = emptyList(),
            snapshotHash = "snapshot-hash",
        )

    private fun treatment(
        key: String,
        componentId: String,
        versionId: String,
        sequence: Int,
    ): ExecutionTreatment =
        ExecutionTreatment(
            treatmentKey = key,
            componentProductId = componentId,
            componentProductVersionId = versionId,
            sourceBomItemId = "bom-$componentId",
            sequence = sequence,
            representativeTreatmentName = "대표 진료",
            detailedTreatmentCodes = listOf("detail"),
            preparationMinutes = 10,
            treatmentMinutes = 30,
            recoveryMinutes = 20,
            practitionerQualifications = listOf("doctor"),
            equipmentTypes = emptyList(),
            spaceCapabilities = listOf("treatment-room"),
        )
}
