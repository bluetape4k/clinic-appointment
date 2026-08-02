package io.bluetape4k.clinic.appointment.model.tables

import io.bluetape4k.clinic.appointment.model.commitment.ResourceType
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistCapacityHoldState
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/** 제안부터 replacement consume까지 자원 capacity를 보존하는 durable hold입니다. */
object WaitlistCapacityHolds : LongIdTable("scheduling_waitlist_capacity_holds") {
    val tenantGroupId = reference("tenant_group_id", TenantGroups, onDelete = ReferenceOption.RESTRICT)
    val clinicId = reference("clinic_id", Clinics, onDelete = ReferenceOption.CASCADE)
    val memberId = varchar("member_id", 255)
    val offerId = reference("offer_id", WaitlistOffers, onDelete = ReferenceOption.CASCADE)
    val vacancyKey = varchar("vacancy_key", 128)
    val activeVacancyKey = varchar("active_vacancy_key", 128).nullable()
    val resourceType = enumerationByName<ResourceType>("resource_type", 32)
    val resourceId = varchar("resource_id", 128)
    val startsAt = timestamp("starts_at")
    val endsAt = timestamp("ends_at")
    val capacityUnits = integer("capacity_units")
    val maximumCapacity = integer("maximum_capacity")
    val status = enumerationByName<WaitlistCapacityHoldState>("status", 32)
        .check("ck_waitlist_capacity_hold_status") { it inList WaitlistCapacityHoldState.entries }
    val holdExpiresAt = timestamp("hold_expires_at")
    val version = long("version").default(0L)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    val releasedAt = timestamp("released_at").nullable()
    val consumedAt = timestamp("consumed_at").nullable()

    init {
        check("ck_waitlist_capacity_hold_time_range") { startsAt less endsAt }
        check("ck_waitlist_capacity_hold_units") {
            (capacityUnits greater 0) and
                (maximumCapacity greater 0) and
                (capacityUnits lessEq maximumCapacity)
        }
        uniqueIndex("uq_waitlist_capacity_hold_offer", offerId)
        uniqueIndex("uq_waitlist_capacity_hold_active_vacancy", tenantGroupId, clinicId, activeVacancyKey)
        index(
            "idx_waitlist_capacity_hold_overlap",
            false,
            tenantGroupId,
            clinicId,
            resourceType,
            resourceId,
            status,
            startsAt,
            endsAt,
            id,
        )
        index("idx_waitlist_capacity_hold_expiry", false, tenantGroupId, clinicId, status, holdExpiresAt, id)
    }
}
