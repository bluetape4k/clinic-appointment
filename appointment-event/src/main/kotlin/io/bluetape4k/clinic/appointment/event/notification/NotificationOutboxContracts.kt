package io.bluetape4k.clinic.appointment.event.notification

import io.bluetape4k.clinic.appointment.model.identity.MemberId
import java.io.Serializable
import java.time.Instant

/**
 * 알림 outbox row의 처리 계열이다.
 *
 * `SENDABLE`은 실제 발송 후보이며, `LEGACY_SUPPRESSION`은 과거 데이터 보정이나
 * 마이그레이션 중 발송하지 않을 억제 기록을 나타낸다.
 */
enum class NotificationOutboxRowKind {
    SENDABLE,
    LEGACY_SUPPRESSION,
}

/**
 * 알림 outbox row의 발송 생명주기 상태다.
 */
enum class NotificationOutboxStatus {
    PENDING,
    PROCESSING,
    RETRY_WAIT,
    SENT,
    SUPPRESSED,
    EXHAUSTED,
}

/**
 * 알림 provider로 전달되는 채널 유형이다.
 */
enum class NotificationChannelType {
    DUMMY,
    SMS,
    EMAIL,
    PUSH,
}

/**
 * 예약 도메인 이벤트를 알림 계약에서 분류한 유형이다.
 */
enum class NotificationEventType {
    CREATED,
    CONFIRMED,
    CANCELLED,
    RESCHEDULED,
    REMINDER,
}

/**
 * 알림 template과 중복 방지 범위를 결정하는 슬롯이다.
 */
enum class NotificationSlot {
    CREATED,
    CONFIRMED,
    CANCELLED,
    RESCHEDULED,
    REMINDER_24H,
    REMINDER_SAME_DAY,
}

/**
 * 재시도 가능한 알림 실패 원인이다.
 */
enum class NotificationFailureCode {
    MEMBER_DIRECTORY_UNAVAILABLE,
    PROVIDER_RATE_LIMITED,
    PROVIDER_UNAVAILABLE,
    CIRCUIT_OPEN,
    DELIVERY_RESULT_UNKNOWN,
    TEMPLATE_NOT_FOUND,
    TEMPLATE_PARAMETER_INVALID,
    LEASE_LOST,
    HMAC_KEY_UNAVAILABLE,
}

/**
 * 발송하지 않는 것으로 종결되는 억제 원인이다.
 */
enum class NotificationSuppressionReasonCode {
    MEMBER_NOT_AVAILABLE,
    DESTINATION_UNAVAILABLE,
    CONSENT_DENIED,
    MEMBER_SCOPE_MISMATCH,
    MEMBER_ID_MISSING_LEGACY,
    APPOINTMENT_CHANGED,
    REMINDER_WINDOW_MISSED,
}

/**
 * 알림 계약 위반을 표현하는 domain exception이다.
 */
class NotificationContractException(
    val failureCode: NotificationFailureCode,
    message: String = failureCode.name,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    companion object {
        private const val serialVersionUID = 1L
    }
}

internal fun validateDurableOpaqueString(
    value: String,
    fieldName: String,
    maxLength: Int,
): String {
    require(value.isNotBlank()) { "$fieldName must not be blank" }
    require(value.length <= maxLength) { "$fieldName must not exceed $maxLength characters" }
    require(value.none { it.isISOControl() }) { "$fieldName must not contain control characters" }
    return value
}

/**
 * 알림 이벤트의 불투명 식별자다.
 */
@JvmInline
value class NotificationEventId(val value: String) : Serializable {
    init {
        validateDurableOpaqueString(value, "eventId", MAX_LENGTH)
    }

    companion object {
        private const val serialVersionUID = 1L
        private const val MAX_LENGTH = 128
    }
}

/**
 * 알림 중복 방지 범위의 불투명 key다.
 */
@JvmInline
value class NotificationIdempotencyKey(val value: String) : Serializable {
    init {
        validateDurableOpaqueString(value, "idempotencyKey", MAX_LENGTH)
    }

    companion object {
        private const val serialVersionUID = 1L
        private const val MAX_LENGTH = 128
    }
}

/**
 * tenant group 경계 식별자다.
 */
@JvmInline
value class TenantGroupId(val value: Long) : Serializable {
    init {
        require(value > 0) { "value must be positive" }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * clinic 경계 식별자다.
 */
@JvmInline
value class ClinicId(val value: Long) : Serializable {
    init {
        require(value > 0) { "value must be positive" }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 예약 aggregate 식별자다.
 */
@JvmInline
value class AppointmentId(val value: Long) : Serializable {
    init {
        require(value > 0) { "value must be positive" }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 알림 template key다.
 */
@JvmInline
value class NotificationTemplateKey(val value: String) : Serializable {
    init {
        validateDurableOpaqueString(value, "templateKey", MAX_LENGTH)
    }

    companion object {
        private const val serialVersionUID = 1L
        private const val MAX_LENGTH = 128
    }
}

/**
 * 알림 template version이다.
 */
@JvmInline
value class NotificationTemplateVersion(val value: Int) : Serializable {
    init {
        require(value > 0) { "value must be positive" }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 알림 outbox에 저장되는 privacy-safe durable envelope다.
 *
 * envelope은 예약/회원 식별자와 template parameter allow-list만 포함한다. 환자명,
 * 전화번호, 회원명, 렌더링된 메시지는 provider 직전 단계에서만 해석해야 하며 durable
 * payload에는 저장하지 않는다.
 */
data class NotificationOutboxEnvelope(
    val schemaVersion: Int,
    val eventId: NotificationEventId,
    val idempotencyKey: NotificationIdempotencyKey,
    val tenantGroupId: TenantGroupId,
    val clinicId: ClinicId,
    val appointmentId: AppointmentId,
    val memberId: MemberId,
    val channel: NotificationChannelType,
    val eventType: NotificationEventType,
    val notificationSlot: NotificationSlot,
    val templateKey: NotificationTemplateKey,
    val templateVersion: NotificationTemplateVersion,
    val parameterType: NotificationParameterType,
    val parameters: NotificationTemplateParameters,
    val occurredAt: Instant,
    val availableAt: Instant,
) : Serializable {

    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "schemaVersion must be $CURRENT_SCHEMA_VERSION" }
        require(parameterType == parameters.parameterType) { "parameterType must match parameters" }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        private const val serialVersionUID = 1L
    }
}
