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

    /**
     * 환불로 직접 취소된 항목과 함께 취소해야 하는 후속 의무를 반환합니다.
     *
     * `BLOCKING`은 선행 의무가 사라지면 후속 의무를 임상적으로 시작할 수 없다는
     * 계약이므로 전이적으로 취소합니다. `NON_BLOCKING` 후속 의무는 독립적으로
     * 수행할 수 있으므로 결과에 포함하지 않습니다. 이 메서드는 환불 금액이나 환불
     * 가능 여부를 판단하지 않으며, 외부 환불 서비스가 확정한 사실을 예약 Plan에
     * 반영할 범위만 계산합니다.
     *
     * @param refundedTreatmentKeys 환불 서비스가 취소를 확정한 Plan treatment key입니다.
     * @param dependencies 현재 활성 Plan revision의 실행 의존성입니다.
     * @return 직접 환불된 항목과 `BLOCKING` 후속 경로의 폐포입니다.
     */
    fun resolveCancellationSet(
        refundedTreatmentKeys: Set<String>,
        dependencies: List<ExecutionDependency>,
    ): Set<String> = resolve(refundedTreatmentKeys, dependencies)
}
