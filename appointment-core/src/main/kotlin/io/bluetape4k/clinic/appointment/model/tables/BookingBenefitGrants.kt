package io.bluetape4k.clinic.appointment.model.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/** booking reliability 예외와 benefit grant를 waitlist delivery scope에 고정합니다. */
object BookingBenefitGrants : LongIdTable("scheduling_booking_benefit_grants") {
    val tenantGroupId = reference("tenant_group_id", TenantGroups, onDelete = ReferenceOption.RESTRICT)
    val clinicId = reference("clinic_id", Clinics, onDelete = ReferenceOption.CASCADE)
    val memberId = varchar("member_id", 255)
    val approvalReference = varchar("approval_reference", 160)
    val benefitType = varchar("benefit_type", 64)
    val benefitCap = integer("benefit_cap")
    val grantDigest = varchar("grant_digest", 64)
    val policyVersion = long("policy_version")
    val startsAt = timestamp("starts_at")
    val expiresAt = timestamp("expires_at").nullable()
    val consumedAt = timestamp("consumed_at").nullable()
    val revokedBy = varchar("revoked_by", 160).nullable()
    val revokedAt = timestamp("revoked_at").nullable()
    val revokeVersion = long("revoke_version").nullable()
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex("uq_booking_benefit_grant", tenantGroupId, clinicId, grantDigest)
        index("idx_booking_benefit_grant_active", false, tenantGroupId, clinicId, memberId, startsAt, expiresAt, id)
    }
}
