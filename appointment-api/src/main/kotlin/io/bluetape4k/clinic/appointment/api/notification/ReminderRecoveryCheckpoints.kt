package io.bluetape4k.clinic.appointment.api.notification

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * reminder catch-up 순회 위치를 프로세스 재시작과 leader 교체 사이에 보존합니다.
 *
 * 예약·회원 정보는 저장하지 않고, 현재 순회의 불투명 ID와 마지막 예약 PK만 기록합니다.
 */
object ReminderRecoveryCheckpoints : Table("clinic_notification_reminder_checkpoint") {
    val scope = varchar("scope", 64)
    val runId = varchar("run_id", 64)
    val lastAppointmentId = long("last_appointment_id").default(0L)
    val active = bool("active").default(true)
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)

    override val primaryKey = PrimaryKey(scope, name = "pk_notification_reminder_checkpoint")

    const val GLOBAL_SCOPE = "appointment-reminders"
}
