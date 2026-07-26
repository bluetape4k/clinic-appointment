package io.bluetape4k.clinic.appointment.event.integration

import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlans
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Durable redacted identifiers waiting for downstream publication.
 */
object SchedulingOutboxEvents : LongIdTable("scheduling_outbox_events") {
    val eventId = varchar("event_id", 128)
    val eventType = varchar("event_type", 128)
    val tenantGroupId = reference("tenant_group_id", TenantGroups, onDelete = ReferenceOption.RESTRICT)
    val clinicId = reference("clinic_id", Clinics, onDelete = ReferenceOption.RESTRICT)
    val planId = reference("plan_id", AppointmentPlans, onDelete = ReferenceOption.RESTRICT)
    val schemaVersion = integer("schema_version")
    val payloadJson = text("payload_json")
    val status = enumerationByName<SchedulingOutboxStatus>("status", 32)
    val attemptCount = integer("attempt_count").default(0)
    val nextAttemptAt = timestamp("next_attempt_at").nullable()
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val publishedAt = timestamp("published_at").nullable()

    init {
        uniqueIndex("uq_outbox_event_id", eventId)
        index("idx_outbox_status_created_at", false, status, createdAt)
        index("idx_outbox_status_next_attempt", false, status, nextAttemptAt)
    }
}

enum class SchedulingOutboxStatus {
    PENDING,
    PUBLISHED,
    FAILED,
}
