package io.bluetape4k.clinic.appointment.model.dto

import io.bluetape4k.clinic.appointment.model.plan.AppointmentPlanStatus
import io.bluetape4k.clinic.appointment.model.plan.BookingPreferenceSnapshot
import java.io.Serializable
import java.time.Instant

/**
 * 하나의 구매에서 파생된 예약 계획의 영속화된 root입니다.
 *
 * 새 상품 구매 1건은 새 계획 1건을 만듭니다. 이미 다른 계획이 진행 중인 상태에서
 * 추가 상품을 구매한 경우에도 이는 기존 계획의 회차 증가가 아니라 별도 계획입니다.
 * 계획은 예약 자체가 아니라 수행해야 할 시술 의무를 소유합니다. 예약 1건은 여러
 * 세부진료를 수행할 수 있고, 당일 일부만 수행된 항목은 이후 예약으로 분리될 수
 * 있습니다.
 *
 * 원문 환자 식별자는 의도적으로 제외합니다. 암호화된 참조는 복호화 권한이 있는
 * 경로에서만 사용하고, 일반 상관관계 조회는 정확한 테넌트/병원 범위 안의 비가역
 * fingerprint로 처리합니다.
 *
 * @property id 데이터베이스가 생성한 식별자입니다. `null`이면 아직 저장되지 않은
 * aggregate이고, insert 이후에는 양수입니다.
 * @property tenantGroupId 카탈로그 스냅샷과 인증된 요청 context에서 복사한 양수 SaaS
 * 테넌트 경계입니다.
 * @property clinicId 카탈로그 스냅샷에서 복사한 양수 병원 경계입니다. 모든 repository
 * 조회는 [tenantGroupId]도 함께 제한해야 합니다.
 * @property catalogProjectionId 이 계획을 확장한 정확한 불변 카탈로그 projection의
 * 양수 외래 키입니다.
 * @property sourcePurchaseAuthority 구매 사실을 소유한 서비스의 안정적인 식별자입니다.
 * endpoint나 표시명이 아닙니다.
 * @property sourcePurchaseId [sourcePurchaseAuthority], [tenantGroupId], [clinicId] 범위
 * 안의 안정적인 구매 식별자입니다. 같은 구매 이벤트 replay는 새 계획을 만들지 않고
 * 같은 계획으로 수렴해야 합니다.
 * @property patientReferenceCiphertext 원본 환자 참조의 authenticated ciphertext입니다.
 * 민감 정보이므로 로그에 남기면 안 되며 [patientReferenceKeyId]가 가리키는 키로만
 * 복호화할 수 있습니다.
 * @property patientReferenceKeyId [patientReferenceCiphertext] 복호화에 필요한 비밀이
 * 아닌 key-version 식별자입니다. 구매 identity를 바꾸지 않고 키 회전을 지원합니다.
 * @property patientReferenceFingerprint 환자 정보를 복호화하지 않고 동등성/인덱싱에
 * 사용하는 결정적이고 비가역적인 범위 한정 fingerprint입니다. 상관관계 데이터로서
 * 여전히 민감하므로 로그나 URL에 노출하지 않습니다.
 * @property catalogSourceAuthority 카탈로그 스냅샷 원본의 안정적인 소유자입니다.
 * @property productId 스냅샷에서 복사한 상품 계보 식별자입니다.
 * @property catalogVersion 계획 생성에 사용한 정확한 양수 카탈로그 리비전입니다. 이후
 * 카탈로그 리비전은 명시적 계획 진화 절차로 향후 작업에만 반영할 수 있고, 완료된
 * 의무를 다시 쓰면 안 됩니다.
 * @property catalogPayloadHash 영속화된 카탈로그 payload의 정규 content hash입니다.
 * 같은 버전의 불법 content drift를 탐지하고 replay 증거를 제공합니다. 표시용
 * serialization에서 계산하면 안 됩니다.
 * @property productName 계획 생성 시점에 캡처한 이력 표시명입니다.
 * @property bookingPreference 구매와 함께 전달된 불변 고객 희망 일정, 또는 명시적인
 * `NotProvided` sentinel입니다. 계획 입력값일 뿐 확정 예약이나 이후 확정 예약 변경에
 * 대한 고객 동의가 아닙니다.
 * @property status 시술 의무 상태에서 파생된 aggregate 수명주기입니다. 환불과 임상
 * 완료 여부는 외부 이벤트로 들어오며, 이 서비스는 그 결과로 생기는 예약 계획 상태만
 * 소유합니다.
 * @property createdAt 데이터베이스 생성 UTC 시각입니다. insert 전에는 `null`입니다.
 * @property updatedAt 데이터베이스 마지막 수정 UTC 시각입니다. insert 전에는 `null`입니다.
 */
data class AppointmentPlanRecord(
    val id: Long? = null,
    val tenantGroupId: Long,
    val clinicId: Long,
    val catalogProjectionId: Long,
    val sourcePurchaseAuthority: String,
    val sourcePurchaseId: String,
    val patientReferenceCiphertext: String,
    val patientReferenceKeyId: String,
    val patientReferenceFingerprint: String,
    val catalogSourceAuthority: String,
    val productId: String,
    val catalogVersion: Long,
    val catalogPayloadHash: String,
    val productName: String,
    val bookingPreference: BookingPreferenceSnapshot,
    val status: AppointmentPlanStatus,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * repository 경계에서 사용하는 완전한 예약 계획 aggregate입니다.
 *
 * @property plan scope, 구매 출처, 보안 환자 참조, 불변 카탈로그 출처, 희망 일정,
 * 수명주기를 담은 aggregate root입니다.
 * @property treatments [plan]이 소유한 물리화된 시술 회차입니다. 논리 키는 유일해야
 * 하며 모든 시술은 이 계획에 속해야 합니다.
 * @property dependencies [treatments] 사이의 방향성 edge입니다. 알 수 없는 endpoint,
 * self-edge, cycle은 유효하지 않습니다.
 */
data class AppointmentPlanAggregateRecord(
    val plan: AppointmentPlanRecord,
    val treatments: List<PlannedTreatmentRecord>,
    val dependencies: List<TreatmentDependencyRecord>,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
