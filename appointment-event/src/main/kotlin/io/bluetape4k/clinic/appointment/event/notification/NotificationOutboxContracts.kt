package io.bluetape4k.clinic.appointment.event.notification

import io.bluetape4k.clinic.appointment.model.identity.MemberId
import java.io.Serializable
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

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
    WAITLIST_OFFER_EXPIRED,
    WAITLIST_OFFER_NOT_ACTIVE,
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
 * JDBC driver별 `CURRENT_TIMESTAMP` 반환형을 UTC [Instant] 정책으로 정규화한다.
 */
internal fun Any?.toNotificationDbInstant(): Instant =
    when (this) {
        is Instant -> this
        is Timestamp -> toInstant()
        is OffsetDateTime -> toInstant()
        is ZonedDateTime -> toInstant()
        is LocalDateTime -> toInstant(ZoneOffset.UTC)
        else -> error("Unsupported CURRENT_TIMESTAMP type: ${this?.javaClass?.name}")
    }

private val durableMetadataPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
private val destinationFingerprintPattern = Regex("^v[1-9][0-9]*:hmac-sha256:[0-9a-f]{64}$")
private val emailLikePattern = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
private val rawErrorLikePattern = Regex("(?i).*(exception|error|stacktrace|caused[ -]by|failed:|failure:).*")

private fun validateLowRiskMetadata(value: String, fieldName: String): String {
    validateDurableOpaqueString(value, fieldName, 128)
    require(durableMetadataPattern.matches(value)) { "$fieldName must use low-risk opaque characters" }
    require(!emailLikePattern.containsMatchIn(value)) { "$fieldName must not contain email-like values" }
    require(!value.isPhoneLike()) { "$fieldName must not contain phone-like values" }
    require(!rawErrorLikePattern.matches(value)) { "$fieldName must not contain raw error text" }
    return value
}

private fun String.isPhoneLike(): Boolean {
    val trimmed = trim()
    val digitCount = trimmed.count { it.isDigit() }
    if (digitCount < 8) return false
    if (trimmed.all { it.isDigit() || it in setOf('+', '-', '.', '(', ')') }) return true
    return Regex(".*(?:^|[^A-Za-z0-9])(?:\\+?\\d[\\d().-]{7,}\\d)(?:$|[^A-Za-z0-9]).*").matches(trimmed)
}

/** provider adapter가 검증한 낮은 cardinality message reference다. */
@JvmInline
value class NotificationProviderMessageReference(val value: String) : Serializable {
    init {
        validateLowRiskMetadata(value, "providerMessageReference")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** 실제 수신자가 아닌 versioned HMAC destination digest다. */
@JvmInline
value class NotificationDestinationFingerprint(val value: String) : Serializable {
    init {
        validateDurableOpaqueString(value, "destinationFingerprint", 128)
        require(value.none { it.isWhitespace() }) { "destinationFingerprint must not contain whitespace" }
        require(!emailLikePattern.containsMatchIn(value)) { "destinationFingerprint must not contain email-like values" }
        require(!rawErrorLikePattern.matches(value)) { "destinationFingerprint must not contain raw error text" }
        require(destinationFingerprintPattern.matches(value)) {
            "destinationFingerprint must be versioned hmac-sha256 digest"
        }
        require(value.substringAfterLast(':').any { it in 'a'..'f' }) {
            "destinationFingerprint digest must not be digit-only"
        }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** 알림 delivery workflow를 묶는 제한된 correlation id다. */
@JvmInline
value class NotificationCorrelationId(val value: String) : Serializable {
    init {
        validateLowRiskMetadata(value, "correlationId")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** 외부 tracing system에 전달 가능한 제한된 trace id다. */
@JvmInline
value class NotificationTraceId(val value: String) : Serializable {
    init {
        validateLowRiskMetadata(value, "traceId")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
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
        require(schemaVersion in SUPPORTED_SCHEMA_VERSIONS) {
            "schemaVersion must be one of $SUPPORTED_SCHEMA_VERSIONS"
        }
        require(parameterType == parameters.parameterType) { "parameterType must match parameters" }
    }

    companion object {
        const val LEGACY_SCHEMA_VERSION = 1
        const val CURRENT_SCHEMA_VERSION = 2
        val SUPPORTED_SCHEMA_VERSIONS: Set<Int> = setOf(LEGACY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
        private const val serialVersionUID = 1L
    }
}
