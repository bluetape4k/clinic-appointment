package io.bluetape4k.clinic.appointment.model.reliability

import java.io.Serializable

/**
 * 예약 신뢰성 정책 평가의 최종 판정입니다.
 *
 * 판정은 고객 라벨이 아니라 특정 정책 version과 관찰된 예약 이력에 대한 운영 결과입니다.
 * downstream offer 생성기는 [RESTRICTED]와 [REQUIRES_STAFF_APPROVAL]을 자동 제안 제한이나
 * 직원 승인 요구로 해석할 수 있습니다.
 */
enum class BookingReliabilityVerdict {
    /** 자동 제안 제한 없이 후보로 사용할 수 있습니다. */
    ELIGIBLE,

    /** 자동 제안 전에 직원 승인이 필요합니다. */
    REQUIRES_STAFF_APPROVAL,

    /** 자동 당일 제안에서 제외해야 합니다. */
    RESTRICTED,

    /** 직원 override가 기본 정책 결정을 대체한 결과입니다. */
    OVERRIDDEN,

    /** 정책 threshold가 명시적으로 비활성화되어 제한하지 않습니다. */
    POLICY_DISABLED,

    /** 읽기 중 관찰한 정책 또는 이력 version이 오래되어 평가하지 않습니다. */
    STALE,

    /** 정책 또는 이력 저장소를 신뢰 가능하게 읽을 수 없어 평가하지 않습니다. */
    UNAVAILABLE,
}

/**
 * threshold 충족 시 적용할 운영 제한 방식입니다.
 */
enum class BookingReliabilityRestrictionMode {
    /** 자동 당일 제안 후보에서 제외합니다. */
    EXCLUDE_AUTOMATIC_SAME_DAY_OFFERS,

    /** 자동 제외 대신 직원 승인 경로로 보냅니다. */
    REQUIRE_STAFF_APPROVAL,
}

/**
 * 예약 이력 사건의 닫힌 종류입니다.
 */
enum class BookingReliabilityEventType {
    /** 예약 시간에 내원하지 않은 상태 전이입니다. */
    NO_SHOW,

    /** 예약 취소 상태 전이입니다. */
    CANCELLED,
}

/** 사건을 발행한 신뢰 경계의 닫힌 종류입니다. */
enum class BookingReliabilityEventSource {
    /** 예약 aggregate가 발행한 결과입니다. */
    APPOINTMENT,

    /** 병원 운영 시스템이 발행한 결과입니다. */
    CLINIC_OPERATION,

    /** 승인된 직원 보정 경로가 발행한 결과입니다. */
    STAFF_OVERRIDE,

    /** 이관·복구 작업이 발행한 결과입니다. */
    IMPORT,
}

/**
 * 취소 또는 no-show가 누구의 책임으로 분류되었는지 나타내는 닫힌 집합입니다.
 */
enum class BookingReliabilityResponsibility {
    /** 고객 책임으로 집계할 수 있는 사건입니다. */
    PATIENT,

    /** 병원 사유 취소나 reschedule입니다. */
    CLINIC,

    /** 휴진, 담당의/장비 사용불가 등 운영 예외입니다. */
    OPERATIONAL_EXCEPTION,

    /** 데이터 보정이나 migration으로 발생한 상태 정정입니다. */
    DATA_CORRECTION,

    /** 책임을 판정할 수 없어 고객 책임으로 집계하지 않습니다. */
    UNKNOWN,
}

/**
 * 결정 사유의 닫힌 machine-readable code입니다.
 */
enum class BookingReliabilityReasonCode {
    /** 정책 threshold가 비활성화되어 제한하지 않습니다. */
    POLICY_DISABLED,

    /** 고객 책임으로 집계할 trigger가 없습니다. */
    NO_PATIENT_RESPONSIBLE_TRIGGER,

    /** 고객 책임 no-show 누적 기준을 충족했습니다. */
    NO_SHOW_THRESHOLD_REACHED,

    /** 고객 책임 late cancellation 누적 기준을 충족했습니다. */
    LATE_CANCELLATION_THRESHOLD_REACHED,

    /** 활성 staff override가 정책 평가보다 우선 적용되었습니다. */
    STAFF_OVERRIDE_ACTIVE,

    /** 정책 또는 이력 snapshot이 오래되어 평가하지 않았습니다. */
    POLICY_OR_HISTORY_STALE,

    /** 정책 또는 이력 snapshot을 사용할 수 없어 평가하지 않았습니다. */
    POLICY_OR_HISTORY_UNAVAILABLE,

    /** lookback 안 고객 책임 no-show가 기준 이상입니다. */
    NO_SHOW_THRESHOLD_EXCEEDED,

    /** lookback 안 고객 책임 late cancellation이 기준 이상입니다. */
    LATE_CANCELLATION_THRESHOLD_EXCEEDED,

    /** 제한 결정의 cooling-off가 아직 유효합니다. */
    COOLING_OFF_ACTIVE,

    /** 이전 제한의 cooling-off가 끝났고 새 책임 사건이 없어 제한을 재발행하지 않습니다. */
    COOLING_OFF_EXPIRED,

    /** 책임이 확정되지 않아 사건을 계산에서 제외했습니다. */
    UNATTRIBUTED_EVENT_EXCLUDED,

    /** 직원 override가 기본 정책 결정을 대체했습니다. */
    MANUAL_OVERRIDE,

    /** 직원이 활성 제한을 해제했습니다. */
    MANUAL_CLEAR,

    /** 요청 snapshot이 현재 정책과 달라졌습니다. */
    POLICY_SNAPSHOT_STALE,

    /** 결정 저장소를 사용할 수 없습니다. */
    DECISION_UNAVAILABLE,
}

/**
 * 제한 결정에 포함되는 trigger 종류입니다.
 */
enum class BookingReliabilityTriggerType {
    /** 고객 책임 no-show입니다. */
    NO_SHOW,

    /** 고객 책임 late cancellation입니다. */
    LATE_CANCELLATION,
}

/**
 * 특정 예약 이력이 threshold에 반영된 감사용 trigger입니다.
 *
 * @property appointmentId 예약 도메인의 내부 식별자입니다.
 * @property type bounded trigger type입니다.
 */
data class BookingReliabilityTrigger(
    val appointmentId: Long,
    val type: BookingReliabilityTriggerType,
) : Serializable {
    init {
        require(appointmentId > 0) { "appointmentId must be positive" }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}
