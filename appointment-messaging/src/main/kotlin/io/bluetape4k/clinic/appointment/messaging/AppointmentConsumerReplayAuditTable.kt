package io.bluetape4k.clinic.appointment.messaging

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/** approved replay의 scope와 상태만 보존하는 metadata-only audit table입니다. */
object AppointmentConsumerReplayAuditTable : LongIdTable("scheduling_appointment_consumer_replay_audit") {
    val requestId = varchar("request_id", 128)
    val logicalConsumerId = varchar("logical_consumer_id", 128)
    val logicalStreamId = varchar("logical_stream_id", 128)
    val tenantGroupId = long("tenant_group_id")
    val clinicId = long("clinic_id")
    val fromOffset = long("from_offset")
    val toOffset = long("to_offset")
    val dryRun = bool("dry_run")
    val approvedBy = varchar("approved_by", 128)
    val status = enumerationByName("status", 32, AppointmentReplayAuditStatus::class)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val completedAt = timestamp("completed_at").nullable()

    init {
        uniqueIndex("uq_appointment_consumer_replay_request", requestId)
        index("idx_appointment_consumer_replay_audit_scope_created", false, tenantGroupId, clinicId, createdAt)
    }
}
