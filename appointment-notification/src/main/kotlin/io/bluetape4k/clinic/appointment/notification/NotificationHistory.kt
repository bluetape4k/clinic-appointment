package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.clinic.appointment.model.tables.Appointments
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp
import java.io.Serializable
import java.time.Instant

/**
 * 알림 발송 이력 테이블.
 */
object NotificationHistoryTable : LongIdTable("clinic_notification_history") {
    val appointmentId = reference("appointment_id", Appointments, onDelete = ReferenceOption.CASCADE)
    val channelType = varchar("channel_type", 30)
    val eventType = varchar("event_type", 50)
    val recipient = varchar("recipient", 255).nullable()
    val payloadJson = text("payload_json")
    val status = varchar("status", 20).default(NotificationStatus.SUCCESS)
    val errorMessage = text("error_message").nullable()
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
}

/**
 * 알림 발송 상태.
 */
object NotificationStatus {
    const val SUCCESS = "SUCCESS"
    const val FAILED = "FAILED"
}

/**
 * 알림 이벤트 타입.
 */
object NotificationEventType {
    const val CREATED = "CREATED"
    const val CONFIRMED = "CONFIRMED"
    const val CANCELLED = "CANCELLED"
    const val RESCHEDULED = "RESCHEDULED"
    const val REMINDER_DAY_BEFORE = "REMINDER_DAY_BEFORE"
    const val REMINDER_SAME_DAY = "REMINDER_SAME_DAY"
}

/**
 * 알림 발송 이력 레코드.
 *
 * @property id 알림 이력 ID
 * @property appointmentId 예약 ID
 * @property channelType 알림 채널 유형
 * @property eventType 알림 이벤트 유형
 * @property recipient 수신자
 * @property payloadJson 알림 페이로드 JSON
 * @property status 발송 상태
 * @property errorMessage 실패 오류 메시지
 * @property createdAt 생성 시각
 */
data class NotificationHistoryRecord(
    val id: Long? = null,
    val appointmentId: Long,
    val channelType: String,
    val eventType: String,
    val recipient: String? = null,
    val payloadJson: String,
    val status: String = NotificationStatus.SUCCESS,
    val errorMessage: String? = null,
    val createdAt: Instant? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
