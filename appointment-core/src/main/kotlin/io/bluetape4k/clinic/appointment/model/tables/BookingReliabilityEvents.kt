package io.bluetape4k.clinic.appointment.model.tables

import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityEventSource
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityEventType
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityResponsibility
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * 예약 결과를 고객 책임 여부로 정규화한 append-only 사건 ledger입니다.
 */
object BookingReliabilityEvents : LongIdTable("booking_reliability_events") {
    val tenantGroupId = long("tenant_group_id")
    val clinicId = long("clinic_id")
    val memberId = varchar("member_id", 255)
    val eventId = varchar("event_id", 160)
    val appointmentId = long("appointment_id")
    val eventType = enumerationByName<BookingReliabilityEventType>("event_type", 24)
    val responsibility = enumerationByName<BookingReliabilityResponsibility>("responsibility", 32)
    val scheduledStartAt = timestamp("scheduled_start_at")
    val occurredAt = timestamp("occurred_at")
    val sourceVersion = long("source_version")
    /** 동일 identity에 다른 bounded event payload가 들어왔는지 검증하는 canonical hash입니다. */
    val eventHash = varchar("event_hash", 64)
    val eventSource = enumerationByName<BookingReliabilityEventSource>("source", 32)
    val correlationId = varchar("correlation_id", 160).nullable()
    val retentionClass = varchar("retention_class", 32)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex(
            "ux_booking_reliability_event_identity",
            tenantGroupId,
            clinicId,
            memberId,
            eventId,
            sourceVersion,
        )
        index(
            "idx_booking_reliability_event_member_time",
            false,
            tenantGroupId,
            clinicId,
            memberId,
            occurredAt,
            eventId,
        )
    }
}
