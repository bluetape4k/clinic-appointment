package io.bluetape4k.clinic.appointment.model.plan

import java.io.Serializable
import java.time.Instant

/**
 * 구매로 생성된 예약 계획의 수명주기 상태입니다.
 */
enum class AppointmentPlanStatus {
    /** 예약 가능하거나 이미 예약된 시술 의무가 하나 이상 남아 있습니다. */
    ACTIVE,

    /** 일부 의무는 완료되었고 취소되지 않은 의무가 하나 이상 남아 있습니다. */
    PARTIALLY_FULFILLED,

    /** 취소되지 않은 모든 시술 의무가 완료되었습니다. */
    FULFILLED,

    /** 환불 이벤트 등으로 남은 시술 의무가 취소되었습니다. */
    CANCELLED,
}

/**
 * 계획 안의 시술 의무 하나가 가지는 수명주기 상태입니다.
 */
enum class PlannedTreatmentStatus {
    /** 의무는 존재하지만 아직 예약에 배정되지 않았습니다. */
    PLANNED,

    /** 의무가 가예약 또는 확정 예약에 배정되었습니다. */
    SCHEDULED,

    /** 임상 작업이 시작되어 일반 고객 변경 요청으로는 더 이상 조정할 수 없습니다. */
    IN_PROGRESS,

    /** 권위 있는 외부 임상 증거가 이행 완료를 확인했습니다. */
    COMPLETED,

    /** 권위 있는 비즈니스 이벤트가 남은 의무를 취소했습니다. */
    CANCELLED,

    /** 제약 조건상 사람의 검토가 필요해 자동 스케줄링을 멈춘 상태입니다. */
    BLOCKED_REVIEW,
}

/**
 * 불변 구매 계획의 테넌트 범위 read view입니다.
 *
 * @property id 양수 영속 계획 식별자입니다.
 * @property tenantGroupId 양수 SaaS 테넌트 권한 경계입니다.
 * @property clinicId 양수 병원 권한 경계입니다.
 * @property sourcePurchaseAuthority 구매 사실을 소유한 서비스입니다.
 * @property sourcePurchaseId 범위 안에서 안정적인 원본 구매 식별자입니다.
 * @property catalogSourceAuthority 스냅샷된 상품 정의의 소유자입니다.
 * @property productId 상품 계보 식별자입니다.
 * @property catalogVersion 계획 생성에 사용한 정확한 카탈로그 리비전입니다.
 * @property catalogPayloadHash replay 일관성을 증명하는 정규 스냅샷 hash입니다. 환자나
 * 구매 식별자가 아닙니다.
 * @property productName 이력 표시명입니다.
 * @property bookingPreference 불변 계획 선호 정보입니다. 확정 예약이 아니며, 확정 예약
 * 변경에 대한 동의도 아닙니다.
 * @property status aggregate 수명주기 상태입니다.
 * @property treatments 스냅샷에서 복사한 정렬된 시술 의무입니다.
 * @property dependencies [treatments] 사이의 방향성 스케줄 edge입니다.
 */
data class AppointmentPlanView(
    val id: Long,
    val tenantGroupId: Long,
    val clinicId: Long,
    val sourcePurchaseAuthority: String,
    val sourcePurchaseId: String,
    val catalogSourceAuthority: String,
    val productId: String,
    val catalogVersion: Long,
    val catalogPayloadHash: String,
    val productName: String,
    val bookingPreference: BookingPreferenceSnapshot,
    val status: AppointmentPlanStatus,
    val treatments: List<PlannedTreatmentView>,
    val dependencies: List<TreatmentDependencyView>,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 카탈로그 스냅샷에서 복사된 시술 의무 하나의 read view입니다.
 *
 * @property id 양수 영속 시술 식별자입니다.
 * @property bomItemId 안정적인 카탈로그 BOM 항목 식별자입니다.
 * @property sequenceNo 1부터 시작하는 회차 번호입니다.
 * @property bomOrder 0부터 시작하는 결정적 BOM 확장 순서입니다.
 * @property representativeTreatmentName 묶음 진료의 표시명입니다.
 * @property detailedTreatmentCodes 순서 있는 세부 임상 활동 코드입니다.
 * @property durationMinutes 양수 분 단위의 예상 capacity 사용량입니다.
 * @property minimumIntervalDays 정수 calendar day 단위의 선택적 내재 hard 하한
 * 간격입니다. `null`이면 없습니다.
 * @property preferredIntervalDays 선택적 내재 soft 목표 간격입니다.
 * @property maximumIntervalDays 선택적 내재 hard 상한 간격입니다.
 * @property practitionerQualifications 필요한 담당자 역량입니다.
 * @property equipmentTypes 필요한 장비 역량입니다.
 * @property roomTypes 허용되는 room 역량입니다.
 * @property earliestStartAt 선택적 포괄 UTC 예약 하한입니다.
 * @property latestStartAt 선택적 포괄 UTC 예약 상한입니다.
 * @property status 현재 예약 및 이행 수명주기입니다.
 */
data class PlannedTreatmentView(
    val id: Long,
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
}

/**
 * 두 계획 시술 사이의 방향성 의존 관계 read view입니다.
 *
 * @property predecessorTreatmentId 먼저 완료되어야 하는 영속 시술입니다.
 * @property successorTreatmentId edge에 의해 제약되는 영속 시술입니다.
 * @property minimumIntervalDays 선행 완료 후 필요한 hard 하한 간격입니다. 정수 calendar
 * day 단위입니다.
 * @property preferredIntervalDays 정수 calendar day 단위의 soft 목표 간격입니다.
 * @property maximumIntervalDays 정수 calendar day 단위의 hard 상한 간격입니다.
 */
data class TreatmentDependencyView(
    val predecessorTreatmentId: Long,
    val successorTreatmentId: Long,
    val minimumIntervalDays: Int,
    val preferredIntervalDays: Int,
    val maximumIntervalDays: Int,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
