package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.clinic.appointment.model.plan.ExecutionDependency
import io.bluetape4k.clinic.appointment.model.plan.ExecutionDependencyType
import io.bluetape4k.support.requireNotBlank
import java.util.ArrayDeque

/**
 * 외부 완료·부분 이행·환불 사실 이후 증분 재계산할 Plan 항목을 찾습니다.
 *
 * 변경된 시작 항목과 전이적으로 연결된 `BLOCKING` 후속 항목만 반환합니다.
 * `NON_BLOCKING` 관계는 독립 진행을 허용하므로 그 edge를 건너 dirty-set을 확장하지
 * 않습니다.
 */
class PlanDirtySetResolver {

    /**
     * 변경 시작점과 `BLOCKING` 후속 경로의 폐포를 반환합니다.
     *
     * cycle이 포함돼도 각 항목을 한 번만 방문합니다. 이 성질은 외부에서 잘못된 과거
     * 데이터를 진단할 때 무한 순회를 막기 위한 방어이며, 실행 BOM 수신 시 cycle
     * 검증을 생략해도 된다는 뜻은 아닙니다.
     */
    fun resolve(
        changedTreatmentKeys: Set<String>,
        dependencies: List<ExecutionDependency>,
    ): Set<String> {
        changedTreatmentKeys.forEach { it.requireNotBlank("changedTreatmentKey") }
        val blockingSuccessors = dependencies.asSequence()
            .filter { it.type == ExecutionDependencyType.BLOCKING }
            .groupBy(
                keySelector = ExecutionDependency::predecessorTreatmentKey,
                valueTransform = ExecutionDependency::successorTreatmentKey,
            )
        val dirty = linkedSetOf<String>()
        val queue = ArrayDeque(changedTreatmentKeys)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!dirty.add(current)) {
                continue
            }
            blockingSuccessors[current].orEmpty().forEach(queue::addLast)
        }
        return dirty
    }
}
