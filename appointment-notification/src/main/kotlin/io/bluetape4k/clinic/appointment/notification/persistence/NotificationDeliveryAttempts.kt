package io.bluetape4k.clinic.appointment.notification.persistence

import io.bluetape4k.clinic.appointment.event.notification.NotificationChannelType
import io.bluetape4k.clinic.appointment.event.notification.NotificationEventType
import io.bluetape4k.clinic.appointment.event.notification.NotificationFailureCode
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * 알림 delivery attempt의 durable audit 테이블이다.
 *
 * recipient, payload, rendered message, raw error message는 저장하지 않는다. 실패는 닫힌
 * [NotificationFailureCode]와 낮은 cardinality metadata만 저장한다.
 */
object NotificationDeliveryAttempts : LongIdTable("clinic_notification_delivery_attempts") {
    /** 대상 outbox row다. */
    val outboxId = reference(
        "outbox_id",
        NotificationOutboxEvents,
        onDelete = ReferenceOption.RESTRICT,
    )

    /** outbox별 단조 증가 attempt 번호다. */
    val attemptNumber = integer("attempt_number")

    /** claim worker owner다. */
    val owner = varchar("owner", 128)

    /** claim fencing token이다. */
    val token = varchar("token", 128)

    /** claim 당시 channel metadata다. */
    val channel = enumerationByName<NotificationChannelType>("channel", 32)

    /** claim 당시 event type metadata다. */
    val eventType = enumerationByName<NotificationEventType>("event_type", 32)

    /** claim 당시 template key metadata다. */
    val templateKey = varchar("template_key", 128)

    /** claim 당시 template version metadata다. */
    val templateVersion = integer("template_version")

    /** attempt가 열린 UTC 시각이다. */
    val startedAt = timestamp("started_at").defaultExpression(CurrentTimestamp)

    /** attempt 종료 UTC 시각이다. 열려 있으면 null이다. */
    val completedAt = timestamp("completed_at").nullable()

    /** attempt 처리 시간이다. 열려 있으면 null이다. */
    val durationMillis = long("duration_millis").nullable()

    /** 닫힌 attempt outcome이다. 열려 있으면 null이다. */
    val outcome = enumerationByName<NotificationDeliveryAttemptOutcome>("outcome", 32).nullable()

    /** 실패 원인 code다. raw exception message는 저장하지 않는다. */
    val failureCode = varchar("failure_code", 64).nullable()

    /** provider가 검증해 반환한 낮은 cardinality message reference다. */
    val providerMessageReference = varchar("provider_message_reference", 128).nullable()

    /** 실제 수신자가 아닌 비식별 destination fingerprint다. */
    val destinationFingerprint = varchar("destination_fingerprint", 128).nullable()

    /** workflow correlation metadata다. */
    val correlationId = varchar("correlation_id", 128).nullable()

    /** distributed trace metadata다. */
    val traceId = varchar("trace_id", 128).nullable()

    init {
        uniqueIndex("uk_notification_delivery_attempt_number", outboxId, attemptNumber)
        index("idx_notification_delivery_attempt_completed_retention", false, completedAt, id)
    }
}
