package io.bluetape4k.clinic.appointment.model.dto

import io.bluetape4k.clinic.appointment.model.plan.AppointmentPlanRevision
import io.bluetape4k.clinic.appointment.model.plan.ExecutionDependencyType
import io.bluetape4k.clinic.appointment.model.plan.PlanTreatmentStatus
import io.bluetape4k.clinic.appointment.model.plan.VisitGroupingType
import java.io.Serializable

/**
 * 새 Plan revision과 child row를 함께 저장하는 aggregate 입력입니다.
 */
data class AppointmentPlanRevisionAggregateRecord(
    val revision: AppointmentPlanRevision,
    val treatments: List<PlanRevisionTreatmentRecord>,
    val dependencies: List<PlanRevisionDependencyRecord>,
    val groupingConstraints: List<PlanRevisionGroupingConstraintRecord>,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 저장된 Plan revision과 child row의 read aggregate입니다.
 */
data class PersistedAppointmentPlanRevisionAggregateRecord(
    val revision: PersistedAppointmentPlanRevisionRecord,
    val treatments: List<PlanRevisionTreatmentRecord>,
    val dependencies: List<PlanRevisionDependencyRecord>,
    val groupingConstraints: List<PlanRevisionGroupingConstraintRecord>,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 영속 Plan revision header입니다.
 *
 * @property id 양수 revision row 식별자입니다.
 * @property planId 동일 구매를 나타내는 Plan 식별자입니다.
 * @property revision Plan 안에서 단조 증가하고 중복되지 않는 업무 revision입니다.
 * @property active 새 제안 계산의 현재 기준인지 나타냅니다. 과거 row는 삭제하지 않습니다.
 */
data class PersistedAppointmentPlanRevisionRecord(
    val id: Long,
    val planId: Long,
    val revision: Long,
    val productVersionId: String,
    val snapshotHash: String,
    val active: Boolean,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * revision 안의 미래 또는 완료 진료 provenance입니다.
 *
 * @property treatmentKey revision 안에서 중복되지 않는 안정적인 진료 키입니다.
 * @property componentProductId 구성 상품 계보입니다.
 * @property componentProductVersionId 구매 또는 전환 당시 고정된 구성 상품 version입니다.
 * @property productVersionId 이 row의 의무를 정의한 패키지/단일 상품 version입니다.
 * 완료 항목은 새 revision에 복사하지 않고 원래 version에 남깁니다.
 * @property status 상품 전환 관점의 완료 여부입니다.
 * @property sourceBomItemId 원본 구성 상품 version 안의 BOM 항목 ID입니다.
 * @property sequence 반복 시술의 1부터 시작하는 실제 회차입니다.
 * @property representativeTreatmentName 여러 세부 진료를 묶는 고객 표시명입니다.
 * @property detailedTreatmentCodes 한 번의 실행에서 시도할 순서 있는 세부 진료 코드입니다.
 * @property preparationMinutes 준비 시간, [treatmentMinutes] 실제 진료 시간,
 * [recoveryMinutes] 회복 시간입니다. 패키지 합계로 덮어쓰지 않습니다.
 * @property practitionerQualifications 필요한 의료진 역량 코드입니다.
 * @property equipmentTypes 필요한 장비 capability 코드입니다.
 * @property spaceCapabilities 필요한 실제 진료 공간 capability 코드입니다.
 */
data class PlanRevisionTreatmentRecord(
    val treatmentKey: String,
    val componentProductId: String,
    val componentProductVersionId: String,
    val productVersionId: String,
    val status: PlanTreatmentStatus,
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
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 같은 revision의 treatment key 사이 실행 관계입니다.
 *
 * @property type `BLOCKING`만 dirty-set을 전파하며 `NON_BLOCKING`은 독립 진행합니다.
 * @property minimumIntervalDays 선행 완료 뒤 허용하는 최소 calendar day 간격입니다.
 * @property preferredIntervalDays 선택적인 soft 목표 간격입니다.
 * @property maximumIntervalDays 선택적인 hard 상한입니다.
 */
data class PlanRevisionDependencyRecord(
    val predecessorTreatmentKey: String,
    val successorTreatmentKey: String,
    val type: ExecutionDependencyType,
    val minimumIntervalDays: Int,
    val preferredIntervalDays: Int?,
    val maximumIntervalDays: Int?,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 실행 의존성과 별도로 두 진료 항목을 같은 방문에 묶거나 분리하는 불변 제약입니다.
 *
 * [firstTreatmentKey]와 [secondTreatmentKey]는 같은 revision의 treatment를 참조하며
 * 방향성 의미가 없습니다. repository는 정규화된 쌍의 중복을 거부합니다.
 */
data class PlanRevisionGroupingConstraintRecord(
    val firstTreatmentKey: String,
    val secondTreatmentKey: String,
    val type: VisitGroupingType,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
