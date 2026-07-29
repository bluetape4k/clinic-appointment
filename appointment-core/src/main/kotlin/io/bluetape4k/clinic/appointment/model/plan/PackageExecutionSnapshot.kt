package io.bluetape4k.clinic.appointment.model.plan

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable

/**
 * 상품관리 또는 구매서비스가 구매 시점의 선택과 반복을 모두 전개해 발행한 실행 계약입니다.
 *
 * 예약서비스는 원본 상품 BOM을 재귀 조회하거나 상품 의미를 다시 해석하지 않습니다.
 * 이 snapshot의 구조·provenance·상한을 검증한 뒤 Plan revision에 그대로 복사합니다.
 *
 * @property packageProductId 구매한 단일 또는 패키지 상품의 안정적인 계보 식별자입니다.
 * 단일 상품도 quantity 1인 패키지 실행 형태로 정규화합니다.
 * @property packageProductVersionId 구매 당시 고정된 불변 상품 version입니다. 이후
 * 카탈로그 최신 version이 발행돼도 기존 구매에 자동 적용하지 않습니다.
 * @property selectedComponentVersions 실제 구매 선택에 포함된 구성 상품과 exact version,
 * 수량입니다. 선택되지 않은 후보 상품은 포함하지 않습니다.
 * @property componentSelections N개 중 M개 선택형의 구매 결과를 검증하는 선택군
 * 메타데이터입니다. 후보 수와 필수 선택 수는 상품 의미를 재계산하기 위한 값이 아니라
 * 발행자가 이미 전개한 결과가 완전한지 검증하는 증거입니다.
 * @property expandedTreatmentItems 반복 수량과 선택 결과를 반영해 완전히 전개된 진료
 * 항목입니다. 각 항목은 구성 상품 version과 준비·진료·회복 시간을 개별 보존합니다.
 * @property executionDependencies 실제 선행 항목 완료를 기준으로 하는 방향성 관계입니다.
 * @property visitGroupingConstraints 실행 의존성과 독립적인 같은 방문 필수·허용·분리
 * 관계입니다.
 * @property snapshotHash 위 모든 필드를 포함해 발행자가 계산한 canonical hash입니다.
 * 같은 source version에 다른 hash가 오면 replay가 아니라 계약 위반입니다.
 */
data class PackageExecutionSnapshot(
    val packageProductId: String,
    val packageProductVersionId: String,
    val selectedComponentVersions: List<ComponentVersionRef>,
    val componentSelections: List<ComponentSelection> = emptyList(),
    val expandedTreatmentItems: List<ExecutionTreatment>,
    val executionDependencies: List<ExecutionDependency>,
    val visitGroupingConstraints: List<VisitGroupingConstraint>,
    val snapshotHash: String,
) : Serializable {

    init {
        packageProductId.requireNotBlank("packageProductId")
        packageProductVersionId.requireNotBlank("packageProductVersionId")
        snapshotHash.requireNotBlank("snapshotHash")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 구매 결과에 포함된 구성 상품 version 참조입니다.
 *
 * @property componentProductId 구성 상품의 안정적인 계보 식별자입니다.
 * @property componentProductVersionId 구매 당시 선택된 정확한 불변 version입니다.
 * @property quantity 이 구성 상품에서 전개해야 하는 1 이상 100 이하 반복 수량입니다.
 * @property selectionGroupId N개 중 M개 선택 결과에 속하면 선택군 ID이며, 필수 또는
 * 일반 선택 구성이라면 `null`입니다.
 */
data class ComponentVersionRef(
    val componentProductId: String,
    val componentProductVersionId: String,
    val quantity: Int = 1,
    val selectionGroupId: String? = null,
) : Serializable {

    init {
        componentProductId.requireNotBlank("componentProductId")
        componentProductVersionId.requireNotBlank("componentProductVersionId")
        quantity.requirePositiveNumber("quantity")
        selectionGroupId?.requireNotBlank("selectionGroupId")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 구매 시 확정된 N개 중 M개 선택 결과의 검증 메타데이터입니다.
 *
 * @property selectionGroupId 패키지 version 안에서 안정적인 선택군 식별자입니다.
 * @property candidateCount 상품 version이 허용한 전체 후보 수입니다.
 * @property requiredSelectionCount 구매자가 반드시 선택해야 하는 수입니다. 실제 선택
 * 수는 같은 [selectionGroupId]를 가진 [ComponentVersionRef] 개수와 정확히 같아야 합니다.
 */
data class ComponentSelection(
    val selectionGroupId: String,
    val candidateCount: Int,
    val requiredSelectionCount: Int,
) : Serializable {

    init {
        selectionGroupId.requireNotBlank("selectionGroupId")
        candidateCount.requirePositiveNumber("candidateCount")
        requiredSelectionCount.requirePositiveNumber("requiredSelectionCount")
        require(requiredSelectionCount <= candidateCount) {
            "requiredSelectionCount must not exceed candidateCount"
        }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 실행 BOM에서 한 번 수행할 세부 진료 의무입니다.
 *
 * @property treatmentKey snapshot 안에서 유일하고 Plan revision까지 보존되는 안정적인 키입니다.
 * @property componentProductId 이 항목을 제공한 구성 상품 계보 식별자입니다.
 * @property componentProductVersionId 이 항목의 진료·시간·자원 정의를 제공한 exact
 * 구성 상품 version입니다.
 * @property sourceBomItemId 원본 구성 상품 version 안의 BOM 항목 식별자입니다.
 * @property sequence 반복 항목의 1부터 시작하는 실제 회차입니다.
 * @property representativeTreatmentName 여러 세부 진료를 포함할 수 있는 고객 표시명입니다.
 * @property detailedTreatmentCodes 한 방문에서 수행을 시도하는 순서 있는 세부 진료 코드입니다.
 * @property preparationMinutes 0 이상인 준비 시간입니다. 구성 상품별 값이 보존됩니다.
 * @property treatmentMinutes 양수인 실제 진료 예상 시간입니다.
 * @property recoveryMinutes 0 이상인 회복 시간입니다. 패키지 전체 합계로 덮어쓰지 않습니다.
 * @property practitionerQualifications 필요한 담당자 역량 코드입니다.
 * @property equipmentTypes 필요한 장비 역량 코드입니다.
 * @property spaceCapabilities 필요한 실제 진료 공간 capability 코드입니다.
 */
data class ExecutionTreatment(
    val treatmentKey: String,
    val componentProductId: String,
    val componentProductVersionId: String,
    val sourceBomItemId: String,
    val sequence: Int,
    val representativeTreatmentName: String,
    val detailedTreatmentCodes: List<String>,
    val preparationMinutes: Int,
    val treatmentMinutes: Int,
    val recoveryMinutes: Int,
    val practitionerQualifications: List<String>,
    val equipmentTypes: List<String>,
    val spaceCapabilities: List<String>,
) : Serializable {

    init {
        treatmentKey.requireNotBlank("treatmentKey")
        componentProductId.requireNotBlank("componentProductId")
        componentProductVersionId.requireNotBlank("componentProductVersionId")
        sourceBomItemId.requireNotBlank("sourceBomItemId")
        sequence.requirePositiveNumber("sequence")
        representativeTreatmentName.requireNotBlank("representativeTreatmentName")
        require(preparationMinutes >= 0) { "preparationMinutes must not be negative" }
        treatmentMinutes.requirePositiveNumber("treatmentMinutes")
        require(recoveryMinutes >= 0) { "recoveryMinutes must not be negative" }
    }

    /** 준비·진료·회복을 순차 수행할 때의 전체 분 단위 시간입니다. */
    val totalDurationMinutes: Int
        get() = preparationMinutes + treatmentMinutes + recoveryMinutes

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 두 실행 항목 사이의 실제 완료 기준 방향성 관계입니다.
 *
 * @property predecessorTreatmentKey 먼저 수행되는 항목 키입니다.
 * @property successorTreatmentKey 관계의 영향을 받는 후속 항목 키입니다.
 * @property type [ExecutionDependencyType.BLOCKING]만 완료 사실 이후 dirty-set을
 * 전이적으로 확장합니다.
 * @property minimumIntervalDays 선행 완료 후 필요한 0 이상 calendar day 하한입니다.
 * @property preferredIntervalDays 선택적인 soft 목표 간격입니다.
 * @property maximumIntervalDays 선택적인 hard 상한입니다.
 */
data class ExecutionDependency(
    val predecessorTreatmentKey: String,
    val successorTreatmentKey: String,
    val type: ExecutionDependencyType,
    val minimumIntervalDays: Int = 0,
    val preferredIntervalDays: Int? = null,
    val maximumIntervalDays: Int? = null,
) : Serializable {

    init {
        predecessorTreatmentKey.requireNotBlank("predecessorTreatmentKey")
        successorTreatmentKey.requireNotBlank("successorTreatmentKey")
        require(predecessorTreatmentKey != successorTreatmentKey) {
            "dependency must connect different treatments"
        }
        require(minimumIntervalDays >= 0) { "minimumIntervalDays must not be negative" }
        preferredIntervalDays?.let {
            require(it >= minimumIntervalDays) {
                "preferredIntervalDays must not be less than minimumIntervalDays"
            }
        }
        maximumIntervalDays?.let {
            require(it >= minimumIntervalDays) {
                "maximumIntervalDays must not be less than minimumIntervalDays"
            }
        }
        if (preferredIntervalDays != null && maximumIntervalDays != null) {
            require(preferredIntervalDays <= maximumIntervalDays) {
                "preferredIntervalDays must not exceed maximumIntervalDays"
            }
        }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 실행 의존성이 일정 재계산을 전파하는 방식입니다.
 */
enum class ExecutionDependencyType {
    /** 선행 완료 시각이 후속 예약 가능 범위를 결정하므로 dirty-set이 전파됩니다. */
    BLOCKING,

    /** 관계를 기록하지만 선행 변경만으로 후속 항목을 자동 보류하거나 재계산하지 않습니다. */
    NON_BLOCKING,
}

/**
 * 두 실행 항목의 방문 묶음 제약입니다.
 *
 * @property firstTreatmentKey 제약의 첫 항목입니다. 방향성 의미는 없습니다.
 * @property secondTreatmentKey 제약의 둘째 항목입니다.
 * @property type 반드시 같은 방문, 최적화상 같은 방문 허용, 반드시 다른 방문 중 하나입니다.
 */
data class VisitGroupingConstraint(
    val firstTreatmentKey: String,
    val secondTreatmentKey: String,
    val type: VisitGroupingType,
) : Serializable {

    init {
        firstTreatmentKey.requireNotBlank("firstTreatmentKey")
        secondTreatmentKey.requireNotBlank("secondTreatmentKey")
        require(firstTreatmentKey != secondTreatmentKey) {
            "visit grouping constraint must connect different treatments"
        }
    }

    /** 방향과 무관한 비교에 사용할 정규화된 항목 쌍입니다. */
    val pair: TreatmentPair
        get() = TreatmentPair.of(firstTreatmentKey, secondTreatmentKey)

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 두 항목을 한 방문에 배치할 수 있는지에 대한 관계입니다.
 */
enum class VisitGroupingType {
    /** 자원 양립성이 충족되는 한 반드시 같은 방문에 배치해야 합니다. */
    MUST_SAME_VISIT,

    /** 같은 방문 배치를 최적화 후보로 허용하지만 별도 방문의 의미도 유효합니다. */
    MAY_SAME_VISIT,

    /** 장비 고장 후 단계 분리 등 어떤 이유에서든 반드시 다른 방문에 배치해야 합니다. */
    MUST_SEPARATE_VISIT,
}

/**
 * 방향이 없는 두 treatment key의 canonical 쌍입니다.
 */
@ConsistentCopyVisibility
data class TreatmentPair private constructor(
    val firstTreatmentKey: String,
    val secondTreatmentKey: String,
) : Serializable {

    companion object {
        private const val serialVersionUID = 1L

        /** 두 키를 사전순으로 정렬해 방향과 무관한 동일 쌍을 만듭니다. */
        fun of(firstTreatmentKey: String, secondTreatmentKey: String): TreatmentPair {
            firstTreatmentKey.requireNotBlank("firstTreatmentKey")
            secondTreatmentKey.requireNotBlank("secondTreatmentKey")
            require(firstTreatmentKey != secondTreatmentKey) {
                "treatment pair must contain different treatments"
            }
            return if (firstTreatmentKey < secondTreatmentKey) {
                TreatmentPair(firstTreatmentKey, secondTreatmentKey)
            } else {
                TreatmentPair(secondTreatmentKey, firstTreatmentKey)
            }
        }
    }
}
