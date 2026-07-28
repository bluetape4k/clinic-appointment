package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.clinic.appointment.model.plan.MigrationMapping
import io.bluetape4k.clinic.appointment.model.plan.MigrationMappingType
import io.bluetape4k.clinic.appointment.model.plan.PlanTreatment
import io.bluetape4k.clinic.appointment.model.plan.PlanTreatmentStatus
import io.bluetape4k.clinic.appointment.model.plan.ProductVersionMigrationResult
import io.bluetape4k.support.requireNotBlank

/**
 * 상품팀이 승인한 명시적 전환표로 동일 구매 Plan의 미래 항목을 새 revision에 계산합니다.
 *
 * 이름이나 코드 유사도로 항목 대응을 추측하지 않습니다. 완료 항목은 구 revision과
 * version에 남기고, 모든 미완료 source가 정확히 한 번 설명된 경우에만 미래 항목을
 * 생성합니다.
 */
class ProductVersionMigrationPlanner {

    /**
     * [mappings]을 검증하고 완료 항목과 새 revision의 미래 항목을 분리해 반환합니다.
     *
     * @throws IllegalArgumentException 완료 항목이 source에 포함되거나, 미완료 source가
     * 누락·중복되거나, mapping 유형의 source/target cardinality가 맞지 않거나, 새
     * treatment key가 중복되면 발생합니다.
     */
    fun migrate(
        currentTreatments: List<PlanTreatment>,
        mappings: List<MigrationMapping>,
        targetProductVersionId: String,
    ): ProductVersionMigrationResult {
        targetProductVersionId.requireNotBlank("targetProductVersionId")
        val currentByKey = currentTreatments.associateBy(PlanTreatment::treatmentKey)
        require(currentByKey.size == currentTreatments.size) {
            "current treatment keys must be unique"
        }

        mappings.forEach(::validateCardinality)
        val allSources = mappings.flatMap(MigrationMapping::sourceTreatmentKeys)
        allSources.forEach { it.requireNotBlank("sourceTreatmentKey") }
        require(allSources.all { it in currentByKey }) {
            "migration source must reference an existing treatment"
        }

        val completedKeys = currentTreatments.asSequence()
            .filter { it.status == PlanTreatmentStatus.COMPLETED }
            .mapTo(mutableSetOf(), PlanTreatment::treatmentKey)
        require(allSources.none { it in completedKeys }) {
            "completed treatment must not be changed by a migration mapping"
        }

        val pendingKeys = currentTreatments.asSequence()
            .filter { it.status == PlanTreatmentStatus.PENDING }
            .mapTo(mutableSetOf(), PlanTreatment::treatmentKey)
        val sourceCounts = allSources.groupingBy { it }.eachCount()
        require(pendingKeys.all { sourceCounts[it] == 1 } && sourceCounts.keys == pendingKeys) {
            "every pending source treatment must be explained exactly once"
        }

        val targetKeys = mappings.flatMap { mapping -> mapping.targets.map { it.treatmentKey } }
        require(targetKeys.size == targetKeys.toSet().size) {
            "migration target treatment keys must be unique"
        }

        val futureTreatments = targetKeys.map { treatmentKey ->
            PlanTreatment(
                treatmentKey = treatmentKey,
                productVersionId = targetProductVersionId,
                status = PlanTreatmentStatus.PENDING,
            )
        }
        return ProductVersionMigrationResult(
            retainedCompleted = currentTreatments.filter { it.status == PlanTreatmentStatus.COMPLETED },
            futureTreatments = futureTreatments,
        )
    }

    private fun validateCardinality(mapping: MigrationMapping) {
        val sourceCount = mapping.sourceTreatmentKeys.size
        val targetCount = mapping.targets.size
        val valid = when (mapping.type) {
            MigrationMappingType.KEEP,
            MigrationMappingType.REPLACE,
            -> sourceCount == 1 && targetCount == 1

            MigrationMappingType.SPLIT -> sourceCount == 1 && targetCount >= 2
            MigrationMappingType.MERGE -> sourceCount >= 2 && targetCount == 1
            MigrationMappingType.REMOVE -> sourceCount >= 1 && targetCount == 0
            MigrationMappingType.ADD -> sourceCount == 0 && targetCount >= 1
        }
        require(valid) {
            "migration mapping ${mapping.type} has invalid source/target cardinality"
        }
    }
}
