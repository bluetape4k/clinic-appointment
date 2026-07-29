package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.clinic.appointment.model.plan.AppointmentPlanRevisionDraft
import io.bluetape4k.clinic.appointment.model.plan.ExecutionDependency
import io.bluetape4k.clinic.appointment.model.plan.PackageExecutionSnapshot
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable

/**
 * 외부 서비스가 이미 전개한 실행 BOM을 검증해 Plan revision 초안으로 복사합니다.
 *
 * 이 planner는 상품 카탈로그를 조회하거나 반복·선택의 업무 의미를 재해석하지 않습니다.
 * 발행자가 제공한 exact 구성 상품 version, 전개 항목, 관계가 구조적으로 완전하고 계산
 * 상한 안에 있는지만 검증합니다.
 */
class PackageExecutionPlanner(
    private val limits: PackageExecutionLimits = PackageExecutionLimits(),
) {

    /**
     * [snapshot]을 원자적으로 검증하고 불변 revision 초안을 반환합니다.
     *
     * @throws IllegalArgumentException 반복 수량, 전체 항목, 관계 상한을 넘거나, 선택
     * provenance가 불완전하거나, 참조가 없거나, 의존 그래프에 cycle이 있으면 발생합니다.
     */
    fun plan(snapshot: PackageExecutionSnapshot): AppointmentPlanRevisionDraft {
        validateLimits(snapshot)
        validateComponents(snapshot)
        validateTreatments(snapshot)
        validateSelections(snapshot)
        validateRelations(snapshot)
        validateAcyclic(snapshot.executionDependencies)

        return AppointmentPlanRevisionDraft(
            packageProductId = snapshot.packageProductId,
            packageProductVersionId = snapshot.packageProductVersionId,
            sourceSnapshotHash = snapshot.snapshotHash,
            treatments = snapshot.expandedTreatmentItems.toList(),
            dependencies = snapshot.executionDependencies.toList(),
            visitGroupingConstraints = snapshot.visitGroupingConstraints.toList(),
        )
    }

    /**
     * 동기식 제안 탐색에서 평가·반환할 collection 크기가 안전 상한 안인지 검증합니다.
     *
     * 이 검증은 일부 결과를 잘라 성공으로 위장하지 않습니다. 상한을 넘은 요청은 전체를
     * 거부해 호출자가 탐색 범위를 줄이거나 비동기 계획 경로로 전환하게 합니다.
     *
     * @throws IllegalArgumentException 음수이거나 설정된 slot/proposal 상한을 넘으면
     * 발생합니다.
     */
    fun validateSearchBounds(
        candidateSlotCount: Int,
        proposalCount: Int,
    ) {
        candidateSlotCount.requireInRange(0, limits.maximumCandidateSlotCount, "candidateSlotCount")
        proposalCount.requireInRange(0, limits.maximumProposalCount, "proposalCount")
    }

    private fun validateLimits(snapshot: PackageExecutionSnapshot) {
        require(snapshot.selectedComponentVersions.all { it.quantity <= limits.maximumRepeatCount }) {
            "component quantity exceeds maximum repeat count ${limits.maximumRepeatCount}"
        }
        require(snapshot.expandedTreatmentItems.size <= limits.maximumTreatmentCount) {
            "expanded treatment count exceeds ${limits.maximumTreatmentCount}"
        }
        val edgeCount = snapshot.executionDependencies.size + snapshot.visitGroupingConstraints.size
        require(edgeCount <= limits.maximumEdgeCount) {
            "execution and grouping edge count exceeds ${limits.maximumEdgeCount}"
        }
    }

    private fun validateComponents(snapshot: PackageExecutionSnapshot) {
        val componentKeys = snapshot.selectedComponentVersions.map {
            it.componentProductId to it.componentProductVersionId
        }
        require(componentKeys.size == componentKeys.toSet().size) {
            "selected component product versions must be unique"
        }
    }

    private fun validateTreatments(snapshot: PackageExecutionSnapshot) {
        val treatmentKeys = snapshot.expandedTreatmentItems.map { it.treatmentKey }
        require(treatmentKeys.isNotEmpty()) { "expanded treatment items must not be empty" }
        require(treatmentKeys.size == treatmentKeys.toSet().size) {
            "expanded treatment keys must be unique"
        }
        val selectedComponents = snapshot.selectedComponentVersions
            .map { it.componentProductId to it.componentProductVersionId }
            .toSet()
        require(
            snapshot.expandedTreatmentItems.all {
                (it.componentProductId to it.componentProductVersionId) in selectedComponents
            },
        ) {
            "every treatment provenance must reference an exact selected component version"
        }
    }

    private fun validateSelections(snapshot: PackageExecutionSnapshot) {
        val selectionIds = snapshot.componentSelections.map { it.selectionGroupId }
        require(selectionIds.size == selectionIds.toSet().size) {
            "component selection group ids must be unique"
        }
        val declaredGroups = selectionIds.toSet()
        val selectedByGroup = snapshot.selectedComponentVersions
            .filter { it.selectionGroupId != null }
            .groupingBy { it.selectionGroupId }
            .eachCount()
        require(selectedByGroup.keys.all { it in declaredGroups }) {
            "selected component references an undeclared selection group"
        }
        snapshot.componentSelections.forEach { selection ->
            require(selectedByGroup[selection.selectionGroupId] == selection.requiredSelectionCount) {
                "selection group ${selection.selectionGroupId} must contain exactly " +
                    "${selection.requiredSelectionCount} selected components"
            }
        }
    }

    private fun validateRelations(snapshot: PackageExecutionSnapshot) {
        val treatmentKeys = snapshot.expandedTreatmentItems.mapTo(mutableSetOf()) { it.treatmentKey }
        require(
            snapshot.executionDependencies.all {
                it.predecessorTreatmentKey in treatmentKeys && it.successorTreatmentKey in treatmentKeys
            },
        ) {
            "execution dependency must reference existing treatments"
        }
        require(
            snapshot.visitGroupingConstraints.all {
                it.firstTreatmentKey in treatmentKeys && it.secondTreatmentKey in treatmentKeys
            },
        ) {
            "visit grouping constraint must reference existing treatments"
        }
    }

    private fun validateAcyclic(dependencies: List<ExecutionDependency>) {
        val successors = dependencies.groupBy(
            keySelector = ExecutionDependency::predecessorTreatmentKey,
            valueTransform = ExecutionDependency::successorTreatmentKey,
        )
        val states = mutableMapOf<String, VisitState>()

        fun visit(treatmentKey: String) {
            when (states[treatmentKey]) {
                VisitState.VISITING -> throw IllegalArgumentException("execution dependency graph contains a cycle")
                VisitState.VISITED -> return
                null -> Unit
            }
            states[treatmentKey] = VisitState.VISITING
            successors[treatmentKey].orEmpty().forEach(::visit)
            states[treatmentKey] = VisitState.VISITED
        }

        dependencies.asSequence()
            .flatMap { sequenceOf(it.predecessorTreatmentKey, it.successorTreatmentKey) }
            .distinct()
            .forEach(::visit)
    }

    private enum class VisitState {
        VISITING,
        VISITED,
    }
}

/**
 * 실행 BOM 하나를 동기식으로 검증·계산할 때의 안전 상한입니다.
 *
 * @property maximumRepeatCount 단일 구성 상품의 최대 반복 수량입니다.
 * @property maximumTreatmentCount 완전히 전개된 전체 진료 항목 상한입니다.
 * @property maximumEdgeCount 실행 의존성과 방문 묶음 관계를 합한 상한입니다.
 * @property maximumCandidateSlotCount 한 동기식 요청에서 평가할 candidate slot 상한입니다.
 * @property maximumResourcesPerSlot 한 candidate slot이 제공할 실제 병원 자원 상한입니다.
 * @property maximumCandidateResourceCount 한 요청의 모든 slot에 포함할 자원 entry 합계 상한입니다.
 * @property maximumProposalCount 한 요청에서 반환할 proposal 상한입니다.
 */
data class PackageExecutionLimits(
    val maximumRepeatCount: Int = 100,
    val maximumTreatmentCount: Int = 500,
    val maximumEdgeCount: Int = 4_000,
    val maximumCandidateSlotCount: Int = 2_000,
    val maximumResourcesPerSlot: Int = 200,
    val maximumCandidateResourceCount: Int = 10_000,
    val maximumProposalCount: Int = 20,
) : Serializable {

    init {
        maximumRepeatCount.requirePositiveNumber("maximumRepeatCount")
        maximumTreatmentCount.requirePositiveNumber("maximumTreatmentCount")
        maximumEdgeCount.requirePositiveNumber("maximumEdgeCount")
        maximumCandidateSlotCount.requirePositiveNumber("maximumCandidateSlotCount")
        maximumResourcesPerSlot.requirePositiveNumber("maximumResourcesPerSlot")
        maximumCandidateResourceCount.requirePositiveNumber("maximumCandidateResourceCount")
        maximumProposalCount.requirePositiveNumber("maximumProposalCount")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}
