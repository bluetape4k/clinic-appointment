package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.clinic.appointment.model.plan.ExecutionTreatment
import io.bluetape4k.clinic.appointment.model.plan.TreatmentPair
import io.bluetape4k.clinic.appointment.model.plan.VisitCandidate
import io.bluetape4k.clinic.appointment.model.plan.VisitGroupingConstraint
import io.bluetape4k.clinic.appointment.model.plan.VisitGroupingType

/**
 * 실행 항목을 의미가 보존되는 방문 후보로 묶는 순수 계산기입니다.
 *
 * `MUST_SAME_VISIT` 연결요소만 필수 결합하고 `MAY_SAME_VISIT`는 후속 optimizer가
 * 선택할 수 있는 힌트로 남깁니다. 따라서 허용 관계만으로 항목을 강제 결합해 고객
 * 약정의 의미를 바꾸지 않습니다.
 */
class VisitGroupingPlanner {

    /**
     * 필수 같은 방문 연결요소를 계산하고 분리·자원 비호환 제약을 검증합니다.
     *
     * @param treatments 실행 BOM 순서를 보존할 진료 항목입니다.
     * @param constraints 같은 방문 필수·허용·분리 관계입니다.
     * @param incompatiblePairs 외부 capability 계산이 같은 방문에 둘 수 없다고 판정한
     * 방향 없는 항목 쌍입니다.
     * @throws IllegalArgumentException 알 수 없는 항목을 참조하거나, 같은 연결요소에
     * 필수 분리 또는 자원 비호환 관계가 있으면 발생합니다.
     */
    fun group(
        treatments: List<ExecutionTreatment>,
        constraints: List<VisitGroupingConstraint>,
        incompatiblePairs: Set<TreatmentPair> = emptySet(),
    ): List<VisitCandidate> {
        val keys = treatments.map { it.treatmentKey }
        require(keys.size == keys.toSet().size) { "treatment keys must be unique" }
        val knownKeys = keys.toSet()
        require(
            constraints.all {
                it.firstTreatmentKey in knownKeys && it.secondTreatmentKey in knownKeys
            },
        ) {
            "visit grouping constraint must reference existing treatments"
        }
        require(
            incompatiblePairs.all {
                it.firstTreatmentKey in knownKeys && it.secondTreatmentKey in knownKeys
            },
        ) {
            "incompatible treatment pair must reference existing treatments"
        }

        val disjointSet = DisjointSet(keys)
        constraints.asSequence()
            .filter { it.type == VisitGroupingType.MUST_SAME_VISIT }
            .forEach { disjointSet.union(it.firstTreatmentKey, it.secondTreatmentKey) }

        val separatedPairs = constraints.asSequence()
            .filter { it.type == VisitGroupingType.MUST_SEPARATE_VISIT }
            .map(VisitGroupingConstraint::pair)
            .toSet()
        val invalidPairs = separatedPairs + incompatiblePairs
        require(
            invalidPairs.none {
                disjointSet.root(it.firstTreatmentKey) == disjointSet.root(it.secondTreatmentKey)
            },
        ) {
            "MUST_SAME_VISIT component conflicts with separation or resource compatibility"
        }

        return treatments
            .groupBy { disjointSet.root(it.treatmentKey) }
            .values
            .map(::VisitCandidate)
    }

    private class DisjointSet(keys: List<String>) {
        private val parent = keys.associateWith { it }.toMutableMap()

        fun root(key: String): String {
            val currentParent = checkNotNull(parent[key]) { "unknown treatment key" }
            if (currentParent == key) {
                return key
            }
            val root = root(currentParent)
            parent[key] = root
            return root
        }

        fun union(first: String, second: String) {
            val firstRoot = root(first)
            val secondRoot = root(second)
            if (firstRoot != secondRoot) {
                parent[secondRoot] = firstRoot
            }
        }
    }
}
