package io.bluetape4k.clinic.appointment.model.dto

import io.bluetape4k.clinic.appointment.model.plan.AppointmentPlanRevision
import io.bluetape4k.clinic.appointment.model.plan.ExecutionDependencyType
import io.bluetape4k.clinic.appointment.model.plan.PlanTreatmentStatus
import java.io.Serializable

/**
 * 새 Plan revision과 child row를 함께 저장하는 aggregate 입력입니다.
 */
data class AppointmentPlanRevisionAggregateRecord(
    val revision: AppointmentPlanRevision,
    val treatments: List<PlanRevisionTreatmentRecord>,
    val dependencies: List<PlanRevisionDependencyRecord>,
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
 */
data class PlanRevisionTreatmentRecord(
    val treatmentKey: String,
    val componentProductId: String,
    val componentProductVersionId: String,
    val productVersionId: String,
    val status: PlanTreatmentStatus,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 같은 revision의 treatment key 사이 실행 관계입니다.
 *
 * @property type `BLOCKING`만 dirty-set을 전파하며 `NON_BLOCKING`은 독립 진행합니다.
 */
data class PlanRevisionDependencyRecord(
    val predecessorTreatmentKey: String,
    val successorTreatmentKey: String,
    val type: ExecutionDependencyType,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
