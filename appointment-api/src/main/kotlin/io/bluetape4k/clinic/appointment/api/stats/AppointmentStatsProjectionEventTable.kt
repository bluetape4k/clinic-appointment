package io.bluetape4k.clinic.appointment.api.stats

import io.bluetape4k.clinic.appointment.model.tables.appointmentState
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestamp

/** aggregate/event 단위로 이미 투영한 이벤트를 기억하는 metadata-only ledger입니다. */
object AppointmentStatsProjectionEventTable : Table("scheduling_appointment_stats_projection_events") {
    val tenantGroupId = long("tenant_group_id")
    val clinicId = long("clinic_id")
    val aggregateId = varchar("aggregate_id", 128)
    val eventId = varchar("event_id", 128)
    val eventVersion = long("event_version")
    val eventDate = date("event_date")
    val status = appointmentState("status")
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)

    override val primaryKey = PrimaryKey(
        tenantGroupId,
        clinicId,
        aggregateId,
        eventId,
        name = "pk_appointment_stats_projection_events",
    )
}
