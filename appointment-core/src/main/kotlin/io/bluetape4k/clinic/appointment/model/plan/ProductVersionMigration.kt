package io.bluetape4k.clinic.appointment.model.plan

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

/**
 * 상품 version 전환 계산에 사용하는 최소 Plan 항목입니다.
 *
 * @property treatmentKey 기존 Plan revision 안에서 안정적인 항목 키입니다.
 * @property productVersionId 이 항목의 의무와 provenance를 정의한 상품 version입니다.
 * 완료 항목은 전환 뒤에도 이 값을 유지합니다.
 * @property status 완료 여부와 미래 전환 가능성을 나타냅니다.
 */
data class PlanTreatment(
    val treatmentKey: String,
    val productVersionId: String,
    val status: PlanTreatmentStatus,
) : Serializable {

    init {
        treatmentKey.requireNotBlank("treatmentKey")
        productVersionId.requireNotBlank("productVersionId")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 상품 전환 관점의 Plan 항목 상태입니다.
 */
enum class PlanTreatmentStatus {
    /** 아직 임상 완료되지 않아 승인된 전환표로 새 revision에 승계할 수 있습니다. */
    PENDING,

    /** 권위 있는 완료 사실이 확정되어 구 revision과 version에 영구히 남습니다. */
    COMPLETED,

    /**
     * 권위 있는 환불·취소 사실로 미래 시술 의무가 소멸했습니다.
     *
     * 완료 이력을 되돌리는 상태가 아니며, `BLOCKING` 의존성 때문에 함께 수행할 수
     * 없게 된 후속 의무에도 적용할 수 있습니다.
     */
    CANCELLED,
}

/**
 * 상품팀이 승인해 발행한 한 개의 version 전환 규칙입니다.
 *
 * @property type source와 target cardinality의 의미를 명시합니다.
 * @property sourceTreatmentKeys 현재 revision의 미완료 항목 키입니다. `ADD`만 비어야 하며
 * 나머지 유형은 하나 이상이어야 합니다.
 * @property targets 새 revision에 생성할 미래 항목입니다. `REMOVE`만 비어야 합니다.
 */
data class MigrationMapping(
    val type: MigrationMappingType,
    val sourceTreatmentKeys: Set<String>,
    val targets: List<MigrationTarget>,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 전환표가 새 revision에 만드는 미래 항목입니다.
 *
 * @property treatmentKey 새 상품 version의 실행 BOM 안에서 안정적인 항목 키입니다.
 */
data class MigrationTarget(
    val treatmentKey: String,
) : Serializable {

    init {
        treatmentKey.requireNotBlank("treatmentKey")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 상품 version 사이의 명시적인 BOM 항목 대응 방식입니다.
 */
enum class MigrationMappingType {
    /** 같은 진료 의무를 새 version 항목으로 유지합니다. */
    KEEP,

    /** 기존 미완료 항목을 다른 새 version 항목으로 교체합니다. */
    REPLACE,

    /** 하나의 미완료 항목을 여러 새 항목으로 분리합니다. */
    SPLIT,

    /** 여러 미완료 항목을 하나의 새 항목으로 통합합니다. */
    MERGE,

    /** 미완료 의무를 새 revision에서 제거합니다. */
    REMOVE,

    /** 기존 source 없이 새 진료 의무를 추가합니다. */
    ADD,
}

/**
 * 승인된 상품 version 전환 계산 결과입니다.
 *
 * @property retainedCompleted 구 revision과 product version provenance를 그대로 유지할
 * 완료 항목입니다.
 * @property futureTreatments 전환표가 설명한 미완료 항목과 추가 항목의 새 revision 값입니다.
 */
data class ProductVersionMigrationResult(
    val retainedCompleted: List<PlanTreatment>,
    val futureTreatments: List<PlanTreatment>,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
