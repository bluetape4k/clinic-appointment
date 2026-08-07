package io.bluetape4k.clinic.appointment.event.integration

import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * 신뢰된 inbound scheduling event의 내구성·비식별 수렴 기록입니다.
 */
object SchedulingInboxEvents : LongIdTable("scheduling_inbox_events") {
    val eventId = varchar("event_id", 128)
    val eventType = varchar("event_type", 128)
    val producer = varchar("producer", 128)
    val sourceAuthority = varchar("source_authority", 128)
    val sourceAggregateId = varchar("source_aggregate_id", 128)
    val sourceAggregateVersion = long("source_aggregate_version")
    val tenantGroupId = reference("tenant_group_id", TenantGroups, onDelete = ReferenceOption.RESTRICT)
    val clinicId = reference("clinic_id", Clinics, onDelete = ReferenceOption.RESTRICT)
    val payloadHash = varchar("payload_hash", 64)
    val status = enumerationByName<SchedulingInboxStatus>("status", 32)
    val replayAfter = timestamp("replay_after").nullable()
    val failureCode = varchar("failure_code", 128).nullable()
    val attemptCount = integer("attempt_count").default(0)
    val occurredAt = timestamp("occurred_at")
    val receivedAt = timestamp("received_at")
    val processedAt = timestamp("processed_at").nullable()

    init {
        uniqueIndex("uq_inbox_event_id", eventId)
        index("idx_inbox_status_replay_after_received", false, status, replayAfter, receivedAt)
        index(
            "idx_inbox_retention",
            false,
            tenantGroupId,
            clinicId,
            status,
            receivedAt,
            id,
        )
        index(
            "idx_inbox_source_version",
            false,
            tenantGroupId,
            clinicId,
            producer,
            sourceAuthority,
            sourceAggregateId,
            status,
            sourceAggregateVersion,
        )
    }
}

enum class SchedulingInboxStatus {
    RECEIVED,
    WAITING_GAP,
    PROCESSED,
    QUARANTINED,
}
