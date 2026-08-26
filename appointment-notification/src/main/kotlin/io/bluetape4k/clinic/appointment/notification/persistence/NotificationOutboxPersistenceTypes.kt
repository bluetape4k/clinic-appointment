package io.bluetape4k.clinic.appointment.notification.persistence

import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** 알림 outbox row의 처리 계열을 persistence 모듈이 소유한다. */
enum class NotificationOutboxRowKind {
    SENDABLE,
    LEGACY_SUPPRESSION,
}

/** 알림 outbox row의 발송 생명주기 상태를 persistence 모듈이 소유한다. */
enum class NotificationOutboxStatus {
    PENDING,
    PROCESSING,
    RETRY_WAIT,
    SENT,
    SUPPRESSED,
    EXHAUSTED,
}

/** delivery attempt가 닫힌 persistence 결과다. */
enum class NotificationDeliveryAttemptOutcome {
    SUCCESS,
    RETRY_SCHEDULED,
    SUPPRESSED,
    EXHAUSTED,
    LEASE_LOST,
}

/** JDBC driver별 `CURRENT_TIMESTAMP` 반환형을 UTC [Instant] 정책으로 정규화한다. */
internal fun Any?.toNotificationDbInstant(): Instant =
    when (this) {
        is Instant -> this
        is Timestamp -> toInstant()
        is OffsetDateTime -> toInstant()
        is ZonedDateTime -> toInstant()
        is LocalDateTime -> toInstant(ZoneOffset.UTC)
        else -> error("Unsupported CURRENT_TIMESTAMP type: ${this?.javaClass?.name}")
    }
