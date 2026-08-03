package io.bluetape4k.clinic.appointment.model.tables

import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistPolicyState
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/** waitlist delivery policy의 immutable publication version을 저장합니다. */
object WaitlistPolicyVersions : LongIdTable("scheduling_waitlist_policy_versions") {
    val tenantGroupId = reference("tenant_group_id", TenantGroups, onDelete = ReferenceOption.RESTRICT)
    val clinicId = reference("clinic_id", Clinics, onDelete = ReferenceOption.CASCADE)
    val version = long("version")
    val status = enumerationByName<WaitlistPolicyState>("status", 24)
        .check("ck_waitlist_policy_version_status") { it inList WaitlistPolicyState.entries }
    val effectiveFrom = timestamp("effective_from")
    val effectiveUntil = timestamp("effective_until").nullable()
    val policyHash = varchar("policy_hash", 64)
    val payloadJson = text("payload_json")
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex("uq_waitlist_policy_version", tenantGroupId, clinicId, version)
        index("idx_waitlist_policy_active", false, tenantGroupId, clinicId, status, effectiveFrom)
    }
}
