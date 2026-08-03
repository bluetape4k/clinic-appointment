package io.bluetape4k.clinic.appointment.model.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/** waitlist policy 변경과 activation 결과를 append-only로 남기는 ledger입니다. */
object WaitlistPolicyEvents : LongIdTable("scheduling_waitlist_policy_events") {
    val tenantGroupId = reference("tenant_group_id", TenantGroups, onDelete = ReferenceOption.RESTRICT)
    val clinicId = reference("clinic_id", Clinics, onDelete = ReferenceOption.CASCADE)
    val policyVersion = long("policy_version")
    val eventType = varchar("event_type", 64)
    val eventDigest = varchar("event_digest", 64)
    val payloadJson = text("payload_json").nullable()
    val occurredAt = timestamp("occurred_at")
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex("uq_waitlist_policy_event_digest", tenantGroupId, clinicId, eventDigest)
        index("idx_waitlist_policy_event_scope", false, tenantGroupId, clinicId, policyVersion, occurredAt, id)
    }
}
