package io.bluetape4k.clinic.appointment.model.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/** 운영 장애 후 대기 후보 우선순위에 반영되는 recovery credit ledger입니다. */
object DisruptionRecoveryCredits : LongIdTable("scheduling_disruption_recovery_credits") {
    val tenantGroupId = reference("tenant_group_id", TenantGroups, onDelete = ReferenceOption.RESTRICT)
    val clinicId = reference("clinic_id", Clinics, onDelete = ReferenceOption.CASCADE)
    val memberId = varchar("member_id", 255)
    val sourceAppointmentId = long("source_appointment_id").nullable()
    val creditDigest = varchar("credit_digest", 64)
    val priorityBoost = integer("priority_boost")
    val reasonCode = varchar("reason_code", 64)
    val grantedBy = varchar("granted_by", 160)
    val expiresAt = timestamp("expires_at")
    val consumedAt = timestamp("consumed_at").nullable()
    val reversedBy = varchar("reversed_by", 160).nullable()
    val reversedAt = timestamp("reversed_at").nullable()
    val reversalVersion = long("reversal_version").nullable()
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex("uq_disruption_recovery_credit", tenantGroupId, clinicId, creditDigest)
        index("idx_disruption_recovery_credit_active", false, tenantGroupId, clinicId, memberId, expiresAt, id)
    }
}
