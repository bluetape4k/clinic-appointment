package io.bluetape4k.clinic.appointment.event.notification

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

    /** attempt가 열린 UTC 시각이다. */
    val startedAt = timestamp("started_at").defaultExpression(CurrentTimestamp)

    /** attempt 종료 UTC 시각이다. 열려 있으면 null이다. */
    val finishedAt = timestamp("finished_at").nullable()

    /** 성공 여부다. 열려 있으면 null이다. */
    val succeeded = bool("succeeded").nullable()

    /** 실패 원인 code다. raw exception message는 저장하지 않는다. */
    val failureCode = varchar("failure_code", 64).nullable()

    init {
        uniqueIndex("uk_notification_delivery_attempt_number", outboxId, attemptNumber)
    }
}
