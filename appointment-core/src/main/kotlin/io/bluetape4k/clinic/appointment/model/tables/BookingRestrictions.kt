package io.bluetape4k.clinic.appointment.model.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/** no-show, late-cancel 등으로 발생한 예약 제한을 waitlist delivery가 조회하는 projection입니다. */
object BookingRestrictions : LongIdTable("scheduling_booking_restrictions") {
    val tenantGroupId = reference("tenant_group_id", TenantGroups, onDelete = ReferenceOption.RESTRICT)
    val clinicId = reference("clinic_id", Clinics, onDelete = ReferenceOption.CASCADE)
    val memberId = varchar("member_id", 255)
    val reasonCode = varchar("reason_code", 64)
    val policyVersion = long("policy_version")
    val startsAt = timestamp("starts_at")
    val expiresAt = timestamp("expires_at").nullable()
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)

    init {
        index("idx_booking_restriction_active", false, tenantGroupId, clinicId, memberId, startsAt, expiresAt, id)
    }
}
