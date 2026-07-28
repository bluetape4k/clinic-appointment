package io.bluetape4k.clinic.appointment.model.plan

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable

/**
 * 실행 BOM에서 계산했지만 아직 영속화하지 않은 Plan revision입니다.
 *
 * 같은 구매에서 상품 version을 전환해도 새 Plan을 만들지 않고 기존 Plan 아래 새
 * revision을 만듭니다. 완료된 항목은 이전 revision provenance를 유지하고, 이 초안에는
 * 검증된 실행 snapshot에서 승계할 미래 항목과 관계만 들어갑니다.
 *
 * @property packageProductId 구매한 단일 또는 패키지 상품의 안정적인 계보 식별자입니다.
 * @property packageProductVersionId 구매 또는 승인된 전환이 고정한 불변 상품
 * version입니다. 상품 카탈로그의 최신값을 동적으로 따라가지 않습니다.
 * @property sourceSnapshotHash 이 revision을 재현하는 실행 BOM 전체의 canonical
 * hash입니다. 동일 event replay의 내용 일치 검증에 사용합니다.
 * @property treatments 반복과 구매 시 선택이 이미 반영된 세부 진료 의무 목록입니다.
 * @property dependencies [treatments] 사이의 완료 기준 실행 의존성입니다.
 * @property visitGroupingConstraints 같은 방문 필수·허용·분리 관계입니다. 실행 의존성과
 * 별도의 제약 축입니다.
 */
data class AppointmentPlanRevisionDraft(
    val packageProductId: String,
    val packageProductVersionId: String,
    val sourceSnapshotHash: String,
    val treatments: List<ExecutionTreatment>,
    val dependencies: List<ExecutionDependency>,
    val visitGroupingConstraints: List<VisitGroupingConstraint>,
) : Serializable {

    init {
        packageProductId.requireNotBlank("packageProductId")
        packageProductVersionId.requireNotBlank("packageProductVersionId")
        sourceSnapshotHash.requireNotBlank("sourceSnapshotHash")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 같은 방문에 배치될 실행 항목 후보입니다.
 *
 * @property treatments 원본 실행 BOM 순서를 유지한 항목입니다. 각 항목의 준비·진료·회복
 * 시간과 자원 provenance가 그대로 보존됩니다.
 */
data class VisitCandidate(
    val treatments: List<ExecutionTreatment>,
) : Serializable {

    init {
        require(treatments.isNotEmpty()) { "visit candidate must contain at least one treatment" }
    }

    /** 모든 항목을 순차 실행한다고 가정한 준비·진료·회복 시간의 합계입니다. */
    val totalDurationMinutes: Int
        get() = treatments.sumOf(ExecutionTreatment::totalDurationMinutes)

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 일정 변경 전후의 불변 Plan revision read model입니다.
 *
 * @property planId 같은 구매를 대표하는 양수 Plan 식별자입니다.
 * @property revision 1부터 단조 증가하는 업무 revision입니다.
 * @property productVersionId 이 revision이 고정한 상품 version입니다.
 * @property snapshotHash 이 revision의 실행 항목과 관계 전체를 검증하는 canonical hash입니다.
 * @property active 새 제안 계산에 사용하는 활성 revision인지 나타냅니다. 과거 revision은
 * 감사와 완료 항목 provenance를 위해 계속 남습니다.
 */
data class AppointmentPlanRevision(
    val planId: Long,
    val revision: Long,
    val productVersionId: String,
    val snapshotHash: String,
    val active: Boolean,
) : Serializable {

    init {
        planId.requirePositiveNumber("planId")
        revision.requirePositiveNumber("revision")
        productVersionId.requireNotBlank("productVersionId")
        snapshotHash.requireNotBlank("snapshotHash")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}
