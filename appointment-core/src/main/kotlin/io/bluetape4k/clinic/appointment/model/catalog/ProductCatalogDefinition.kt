package io.bluetape4k.clinic.appointment.model.catalog

import java.io.Serializable
import java.time.Instant

/**
 * 상품관리 서비스에서 전달받아 예약 서비스가 복사해 두는 불변 상품 정의입니다.
 *
 * 예약 서비스는 이 객체를 버전이 고정된 원본 스냅샷으로 취급합니다. 이후 상품
 * 카탈로그 변경 이벤트가 들어와도 이미 진행된 시술을 새 정의로 다시 해석하지
 * 않고, 아직 수행되지 않은 향후 작업에만 명시적인 반영 절차를 적용할 수 있습니다.
 * 구매를 예약 계획으로 확장하기 전에 전체 정의를 검증하고 영속화합니다.
 *
 * 이 계약에는 상품과 리소스 메타데이터만 포함합니다. 환자 식별자, 구매 결제
 * 정보, 환불 정보, 진료 기록 같은 민감하거나 다른 서비스가 소유한 정보는 담지
 * 않습니다.
 *
 * @property tenantGroupId 상품관리 서비스가 제공한 양수 SaaS 테넌트 그룹 식별자입니다.
 * 모든 유일성, 권한, 조회 범위에 포함되며 병원의 테넌트 그룹과 일치해야 합니다.
 * @property clinicId 이 상품 버전이 유효한 양수 병원 식별자입니다. 카탈로그 버전은
 * 병원 사이에서 암묵적으로 공유되지 않습니다.
 * @property sourceAuthority 카탈로그 원본을 소유한 서비스의 안정적인 논리 이름입니다.
 * 예를 들어 `product-management`가 될 수 있으며 네트워크 위치나 사용자 표시명이
 * 아닙니다.
 * @property productId [tenantGroupId]/[clinicId]/[sourceAuthority] 범위 안에서 안정적인
 * 상품 식별자입니다. 상품 계보를 나타내며, [catalogVersion]은 그 계보의 특정 불변
 * 리비전을 나타냅니다.
 * @property catalogVersion 양수이며 단조 증가하는 원본 리비전입니다. 같은 버전은
 * 바이트 기준 정규 payload가 같아야 하며, 같은 버전을 다른 내용에 재사용하면 원본
 * 서비스 계약 위반으로 거부됩니다.
 * @property productName 계획 생성 시점에 이력 표시용으로 캡처한 상품명입니다. 설명
 * 정보일 뿐 식별 키로 사용하지 않습니다.
 * @property schemaVersion 이 payload를 해석할 호환 decoder를 고르는 양수 schema
 * 버전입니다. [catalogVersion]과는 별도의 축입니다.
 * @property sourceUpdatedAt 원본 서비스가 이 리비전을 커밋한 UTC 시각입니다. 감사
 * 메타데이터이며 시술 진행일이나 예약일이 아닙니다.
 * @property status projection 수명주기 상태입니다. [CatalogProjectionStatus.ACTIVE]는
 * 새 계획 생성에 사용할 수 있고, [CatalogProjectionStatus.RETIRED]는 과거 계획
 * 조회에는 남지만 새 계획 생성에는 사용할 수 없습니다.
 * @property items 시술 회차로 확장되는 정렬된 상품 BOM 항목입니다. 순서는
 * `bomOrder`로 보존하고, 식별은 각 `bomItemId`에서 옵니다.
 * @property dependencies [items]에서 확장된 시술 회차 사이의 방향성 스케줄 제약입니다.
 * sequence 기본값을 해석한 뒤 유효한 비순환 그래프여야 합니다.
 * @property initialBookingRule 구매 이벤트가
 * [io.bluetape4k.clinic.appointment.model.plan.BookingPreferenceSnapshot.NotProvided]를
 * 담고 있을 때만 사용하는 fallback 규칙입니다. `null`이면 구매 선호도와 상품
 * 정책 모두 자동 가예약 날짜 산출을 허용하지 않는다는 뜻입니다.
 */
data class ProductCatalogDefinition(
    val tenantGroupId: Long,
    val clinicId: Long,
    val sourceAuthority: String,
    val productId: String,
    val catalogVersion: Long,
    val productName: String,
    val schemaVersion: Int,
    val sourceUpdatedAt: Instant,
    val status: CatalogProjectionStatus = CatalogProjectionStatus.ACTIVE,
    val items: List<CatalogBomItem>,
    val dependencies: List<CatalogBomDependency>,
    val initialBookingRule: InitialBookingRule?,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 카탈로그 projection이 새 예약 계획 생성에 사용될 수 있는지 나타냅니다.
 */
enum class CatalogProjectionStatus {
    /** 새로 관측한 구매를 예약 계획으로 확장하는 데 사용할 수 있는 정의입니다. */
    ACTIVE,

    /** 기존 계획의 스냅샷 조회용으로만 남으며 새 계획 생성에는 사용할 수 없습니다. */
    RETIRED,
}

/**
 * 상품 BOM 안의 반복 가능한 시술 정의 한 항목입니다.
 *
 * @property bomItemId 하나의 상품 계보 안에서 안정적인 BOM 항목 식별자입니다. 회차
 * sequence 번호와 결합해 계획 시술 키를 만듭니다.
 * @property representativeTreatmentName 여러 [detailedTreatmentCodes]를 포함할 수 있는
 * 예약 1건의 고객 표시용 대표 진료명입니다. 설명 정보이며 카탈로그 식별자가 아닙니다.
 * @property detailedTreatmentCodes 이 회차에서 보통 함께 수행되는 개별 진료/시술 활동
 * 코드의 순서 있는 목록입니다. 예약 당일 일부만 수행되면 남은 항목은 이후 예약으로
 * 분리될 수 있습니다.
 * @property repeatCount 이 항목에서 생성되는 회차 수입니다. 양수여야 하며 sequence
 * 번호는 1부터 이 값까지 포함합니다.
 * @property durationMinutes 회차별 예상 리소스 점유 시간입니다. 양수 분 단위이며,
 * 계획 입력값일 뿐 환자 대기 시간이나 임상 완료 시간을 보장하지 않습니다.
 * @property minimumIntervalDays 같은 반복 항목의 연속 회차 사이에 필요한 하한입니다.
 * 정수 calendar day 단위이며 `null`이면 항목 자체 반복 간격은 없다는 뜻입니다. 별도
 * dependency edge가 하한을 부여할 수는 있습니다.
 * @property preferredIntervalDays 같은 반복 항목의 연속 회차 사이에 선호하는 목표
 * 간격입니다. 정수 calendar day 단위이며 `null`이면 항목 자체 선호 간격이 없습니다.
 * @property maximumIntervalDays 같은 반복 항목의 연속 회차 사이에 허용되는 상한입니다.
 * 정수 calendar day 단위이며 `null`이면 항목 자체 상한이 없습니다.
 * @property practitionerQualifications 배정된 담당자 중 최소 1명이 만족해야 하는 정규화된
 * 자격/역량 코드입니다. 빈 목록은 상품 BOM이 제한을 선언하지 않았다는 뜻이지 담당자
 * 배정을 생략해도 된다는 뜻이 아닙니다.
 * @property equipmentTypes 회차 동안 필요한 정규화된 장비 역량 코드입니다. 빈 목록은
 * 상품이 장비 요구를 선언하지 않았다는 뜻입니다.
 * @property roomTypes 회차에 허용되는 정규화된 room 유형 코드입니다. 빈 목록은 상품이
 * room 유형 제한을 선언하지 않았다는 뜻입니다.
 */
data class CatalogBomItem(
    val bomItemId: String,
    val representativeTreatmentName: String,
    val detailedTreatmentCodes: List<String>,
    val repeatCount: Int,
    val durationMinutes: Int,
    val minimumIntervalDays: Int?,
    val preferredIntervalDays: Int?,
    val maximumIntervalDays: Int?,
    val practitionerQualifications: List<String>,
    val equipmentTypes: List<String>,
    val roomTypes: List<String>,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 두 BOM 항목 회차 사이의 방향성 의존 관계입니다.
 *
 * 선행 sequence가 `null`이면 선행 항목의 마지막 회차를 선택하고, 후행 sequence가
 * `null`이면 후행 항목의 첫 회차를 선택합니다.
 *
 * @property predecessorBomItemId 후행 회차가 가능해지기 전에 완료되어야 하는 BOM
 * 항목입니다. 같은 정의 안의 항목을 참조해야 합니다.
 * @property predecessorSequenceNo 1부터 시작하는 선행 회차 번호입니다. `null`은 의도적으로
 * [predecessorBomItemId]의 마지막 회차를 의미하며, 모든 회차나 알 수 없음이 아닙니다.
 * @property successorBomItemId 이 edge에 의해 제약되는 BOM 항목입니다. 같은 정의 안의
 * 항목을 참조해야 하며 cycle을 만들면 안 됩니다.
 * @property successorSequenceNo 1부터 시작하는 후행 회차 번호입니다. `null`은 의도적으로
 * [successorBomItemId]의 첫 회차를 의미합니다.
 * @property minimumIntervalDays 선행 회차 완료 후 후행 회차를 예약하기까지 필요한 하한
 * 간격입니다. 정수 calendar day 단위입니다.
 * @property preferredIntervalDays 선행 회차 완료 후 선호하는 목표 간격입니다. 정수
 * calendar day 단위이며 minimum/maximum 범위 안에 있어야 합니다.
 * @property maximumIntervalDays 선행 회차 완료 후 허용되는 상한 간격입니다. 정수
 * calendar day 단위입니다. 운영 차질 때문에 이 상한을 지킬 수 없으면 명시적인 검토가
 * 필요하며 조용히 버리면 안 됩니다.
 */
data class CatalogBomDependency(
    val predecessorBomItemId: String,
    val predecessorSequenceNo: Int? = null,
    val successorBomItemId: String,
    val successorSequenceNo: Int? = null,
    val minimumIntervalDays: Int,
    val preferredIntervalDays: Int,
    val maximumIntervalDays: Int,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 구매 이벤트에 고객 예약 희망 정보가 없을 때만 사용하는 규칙입니다.
 *
 * 구매일은 시술 과정의 milestone이 아니므로 시술 회차 사이의 의존성을 만들지 않습니다.
 * 이 규칙은 최초 가예약을 만들 수 있는 제한된 fallback 범위만 제공합니다.
 */
sealed interface InitialBookingRule : Serializable {

    /**
     * 구매 후 [maximumDays]일 이내에 최초 가예약 제안을 만들도록 요구합니다.
     *
     * @property maximumDays 원본 구매일 이후 가예약 제안을 만들 수 있는 양수 최대
     * calendar day 수입니다. 자동 확정을 허용하지 않으며 고객이 제공한 희망 일정을
     * 절대 덮어쓰지 않습니다.
     */
    data class WithinDaysAfterPurchase(
        val maximumDays: Int,
    ) : InitialBookingRule {
        companion object {
            private const val serialVersionUID = 1L
        }
    }
}
