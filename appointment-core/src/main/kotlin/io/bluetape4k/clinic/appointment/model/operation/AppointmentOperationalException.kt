package io.bluetape4k.clinic.appointment.model.operation

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable
import java.time.Instant

/**
 * 자동 예약 계산으로 안전하게 해결할 수 없어 상담·운영 handoff가 필요한 예외입니다.
 *
 * 이 기록은 append-only 업무 사실이며 예약 상태 자체가 아닙니다. 예를 들어 고객이
 * 변경 제안을 거부해도 기존 확정 예약은 유지하고 별도의 운영 예외를 열 수 있습니다.
 *
 * @property appointmentPlanId 영향을 받는 동일 구매 Plan의 양수 식별자입니다.
 * @property appointmentId 특정 방문 문제라면 해당 양수 예약 식별자이며, 아직 방문이
 * 생성되지 않은 Plan 수준 문제라면 `null`입니다.
 * @property type 기계 고장, 고객 변경 거부, 상품 전환 검토 등 안정적인 원인 분류입니다.
 * @property reasonCode 개인정보나 자유 형식 원문을 포함하지 않는 안정적인 운영 코드입니다.
 * @property status 운영자가 인지·해결했는지를 나타내는 수명주기입니다.
 * @property openedAt 예외가 최초 기록된 UTC 시각입니다.
 * @property resolvedAt 해결 전에는 `null`, 해결된 경우 [openedAt]보다 빠르지 않은 UTC
 * 시각입니다.
 */
data class AppointmentOperationalException(
    val appointmentPlanId: Long,
    val appointmentId: Long?,
    val type: AppointmentOperationalExceptionType,
    val reasonCode: String,
    val status: AppointmentOperationalExceptionStatus,
    val openedAt: Instant,
    val resolvedAt: Instant?,
) : Serializable {

    init {
        appointmentPlanId.requirePositiveNumber("appointmentPlanId")
        appointmentId?.requirePositiveNumber("appointmentId")
        reasonCode.requireNotBlank("reasonCode")
        require((status == AppointmentOperationalExceptionStatus.RESOLVED) == (resolvedAt != null)) {
            "resolvedAt must exist exactly when status is RESOLVED"
        }
        resolvedAt?.let {
            require(it >= openedAt) { "resolvedAt must not be before openedAt" }
        }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 운영 개입이 필요한 원인 분류입니다.
 */
enum class AppointmentOperationalExceptionType {
    /** 장비 고장이나 공간 폐쇄로 일부 진료를 다른 방문으로 분리해야 합니다. */
    RESOURCE_DISRUPTION,

    /** 고객이 확정 일정 변경 제안을 거부해 상담팀 판단이 필요합니다. */
    CUSTOMER_DECLINED_RESCHEDULE,

    /** 상품 version 전환표 또는 동의 증빙을 자동 적용할 수 없습니다. */
    PRODUCT_MIGRATION_REVIEW,

    /** 자동 분류되지 않은 예외이며 안정적인 reason code로 상세 원인을 구분합니다. */
    OTHER,
}

/**
 * 운영 예외 처리 상태입니다.
 */
enum class AppointmentOperationalExceptionStatus {
    /** 운영자가 아직 인지하지 않은 열린 예외입니다. */
    OPEN,

    /** 담당자가 확인했지만 해결 결과는 아직 기록되지 않았습니다. */
    ACKNOWLEDGED,

    /** 외부 상담·환불·재예약 등 결과를 연결하고 닫은 예외입니다. */
    RESOLVED,
}
