package io.bluetape4k.clinic.appointment.model.tables

import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistCommandState
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/** waitlist command의 scoped HMAC idempotency와 replay 결과를 저장합니다. */
object WaitlistCommandRecords : LongIdTable("scheduling_waitlist_command_records") {
    val tenantGroupId = reference("tenant_group_id", TenantGroups, onDelete = ReferenceOption.RESTRICT)
    val clinicId = reference("clinic_id", Clinics, onDelete = ReferenceOption.CASCADE)
    val commandType = varchar("command_type", 64)
    val keyDigest = varchar("key_digest", 76)
    val requestDigest = varchar("request_digest", 64)
    val status = enumerationByName<WaitlistCommandState>("status", 24)
        .check("ck_waitlist_command_record_status") { it inList WaitlistCommandState.entries }
    val resultType = varchar("result_type", 64).nullable()
    val resultId = long("result_id").nullable()
    val responseDigest = varchar("response_digest", 64).nullable()
    val failureCode = varchar("failure_code", 96).nullable()
    val expiresAt = timestamp("expires_at")
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex("uq_waitlist_command_idempotency", tenantGroupId, clinicId, commandType, keyDigest)
        index("idx_waitlist_command_retention", false, tenantGroupId, clinicId, expiresAt, id)
    }
}
