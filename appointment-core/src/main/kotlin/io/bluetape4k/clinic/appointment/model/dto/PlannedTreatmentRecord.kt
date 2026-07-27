package io.bluetape4k.clinic.appointment.model.dto

import io.bluetape4k.clinic.appointment.model.plan.PlannedTreatmentStatus
import java.io.Serializable
import java.time.Instant

/**
 * 하나의 계획 안에서 시술 회차를 식별하는 안정적인 논리 키입니다.
 *
 * @property bomItemId 계획에 복사된 카탈로그 BOM 항목 식별자입니다.
 * @property sequenceNo `1..repeatCount` 범위의 1부터 시작하는 회차 번호입니다.
 */
data class PlannedTreatmentKey(
    val bomItemId: String,
    val sequenceNo: Int,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 카탈로그 스냅샷에서 복사해 영속화한 시술 의무입니다.
 *
 * 이 record는 계획이 환자에게 제공해야 하는 작업을 설명합니다. 특정 예약 1건과
 * 의도적으로 분리되어 있습니다. 예약 1건은 여러 시술 의무를 포함할 수 있고, 부분
 * 이행된 예약은 남은 의무를 다시 예약해야 할 수 있기 때문입니다.
 *
 * @property id 데이터베이스가 생성한 회차 식별자입니다. insert 전에는 `null`입니다.
 * @property planId 소유 계획의 영속 식별자입니다. 새 aggregate를 구성하는 동안에는
 * `null`이고, storage에서 읽은 뒤에는 양수입니다.
 * @property bomItemId 카탈로그 스냅샷에서 온 안정적인 BOM 항목 식별자입니다.
 * @property sequenceNo [bomItemId]에 대한 1부터 시작하는 회차 번호입니다.
 * @property bomOrder 원본 BOM 항목의 0부터 시작하는 표시 및 결정적 확장 순서입니다.
 * dependency가 다른 실행 순서를 만들 수 있으므로 실제 수행 순서가 아닙니다.
 * @property representativeTreatmentName 보통 함께 수행되는 [detailedTreatmentCodes]
 * 묶음의 고객 표시용 대표 진료명입니다.
 * @property detailedTreatmentCodes 이 의무에 속하는 순서 있는 임상 활동 코드입니다.
 * 일부만 이행된 뒤 새 구매 계획을 만들지 않고 남은 항목을 재예약할 수 있습니다.
 * @property durationMinutes 양수 분 단위의 예상 리소스 사용량입니다.
 * @property minimumIntervalDays 같은 BOM 항목의 직전 회차로부터 필요한 내재 hard 하한
 * 간격입니다. 정수 calendar day 단위이며 `null`이면 없습니다.
 * @property preferredIntervalDays 정수 calendar day 단위의 내재 soft 목표 간격입니다.
 * `null`이면 없습니다.
 * @property maximumIntervalDays 정수 calendar day 단위의 내재 hard 상한 간격입니다.
 * `null`이면 없다는 뜻이며 임의 sentinel 값으로 대체하면 안 됩니다.
 * @property practitionerQualifications 담당자 배정에 필요한 역량 코드입니다.
 * @property equipmentTypes 장비 배정에 필요한 장비 역량 코드입니다.
 * @property roomTypes 허용되는 room 역량 코드입니다.
 * @property earliestStartAt 계산된 포괄 UTC 예약 하한입니다. `null`이면 완료된 선행
 * 회차나 정책에 의해 아직 제약되지 않았다는 뜻입니다.
 * @property latestStartAt 계산된 포괄 UTC 예약 상한입니다. `null`이면 현재 상한이
 * 없다는 뜻입니다. [earliestStartAt]보다 앞설 수 없습니다.
 * @property status 예약/이행 수명주기입니다. 완료는 시간 경과로 추론하지 않고 다른
 * 서비스에서 받은 임상 증거로 확정합니다.
 */
data class PlannedTreatmentRecord(
    val id: Long? = null,
    val planId: Long? = null,
    val bomItemId: String,
    val sequenceNo: Int,
    val bomOrder: Int,
    val representativeTreatmentName: String,
    val detailedTreatmentCodes: List<String>,
    val durationMinutes: Int,
    val minimumIntervalDays: Int?,
    val preferredIntervalDays: Int?,
    val maximumIntervalDays: Int?,
    val practitionerQualifications: List<String>,
    val equipmentTypes: List<String>,
    val roomTypes: List<String>,
    val earliestStartAt: Instant?,
    val latestStartAt: Instant?,
    val status: PlannedTreatmentStatus,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    /**
     * 데이터베이스 식별자가 배정되기 전에 사용하는 계획 내부의 안정적인 키입니다.
     */
    val key: PlannedTreatmentKey
        get() = PlannedTreatmentKey(bomItemId, sequenceNo)
}

/**
 * 두 시술 회차 사이에 영속화된 방향성 edge입니다.
 *
 * @property id 데이터베이스가 생성한 edge 식별자입니다. insert 전에는 `null`입니다.
 * @property planId 소유 계획 식별자입니다. 아직 저장되지 않은 aggregate에서는 `null`입니다.
 * @property predecessorTreatmentId 영속화된 선행 시술 식별자입니다. atomic aggregate
 * insert 중 논리 키가 해석되기 전에는 `null`입니다.
 * @property successorTreatmentId 영속화된 후행 시술 식별자입니다. atomic aggregate
 * insert 중 논리 키가 해석되기 전에는 `null`입니다.
 * @property predecessor 같은 계획 안의 논리 선행 키입니다.
 * @property successor 같은 계획 안의 논리 후행 키입니다.
 * @property minimumIntervalDays 선행 회차 완료 후 필요한 hard 하한 간격입니다. 정수
 * calendar day 단위입니다.
 * @property preferredIntervalDays 선행 회차 완료 후 선호하는 soft 목표 간격입니다. 정수
 * calendar day 단위입니다.
 * @property maximumIntervalDays 선행 회차 완료 후 허용되는 hard 상한 간격입니다. 정수
 * calendar day 단위입니다.
 */
data class TreatmentDependencyRecord(
    val id: Long? = null,
    val planId: Long? = null,
    val predecessorTreatmentId: Long? = null,
    val successorTreatmentId: Long? = null,
    val predecessor: PlannedTreatmentKey,
    val successor: PlannedTreatmentKey,
    val minimumIntervalDays: Int,
    val preferredIntervalDays: Int,
    val maximumIntervalDays: Int,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
