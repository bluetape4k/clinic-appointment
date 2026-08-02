package io.bluetape4k.clinic.appointment.model.tables

import io.bluetape4k.clinic.appointment.model.commitment.ResourceType
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistOfferState
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.regexp
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/** 후보 선택 결과와 제안 시점의 불변 슬롯·신뢰성 snapshot을 저장합니다. */
object WaitlistOffers : LongIdTable("scheduling_waitlist_offers") {
    val tenantGroupId = reference("tenant_group_id", TenantGroups, onDelete = ReferenceOption.RESTRICT)
    val clinicId = reference("clinic_id", Clinics, onDelete = ReferenceOption.CASCADE)
    val memberId = varchar("member_id", 255)
    val waitlistEntryId = reference("waitlist_entry_id", WaitlistEntries, onDelete = ReferenceOption.CASCADE)
    val vacancyKey = varchar("vacancy_key", 128)
    val activeEntryKey = varchar("active_entry_key", 128).nullable()
    val activeVacancyKey = varchar("active_vacancy_key", 128).nullable()
    val resourceType = enumerationByName<ResourceType>("resource_type", 32)
    val resourceId = varchar("resource_id", 128)
    val capacityUnits = integer("capacity_units")
    val maximumCapacity = integer("maximum_capacity")
    val doctorId = long("doctor_id").nullable()
    val treatmentTypeId = long("treatment_type_id")
    val startsAt = timestamp("starts_at")
    val endsAt = timestamp("ends_at")
    val expiresAt = timestamp("expires_at")
    val status = enumerationByName<WaitlistOfferState>("status", 32)
        .check("ck_waitlist_offer_status") { it inList WaitlistOfferState.entries }
    val bookingReliabilityDecisionId = long("booking_reliability_decision_id")
    val bookingReliabilityPolicyVersionId = long("booking_reliability_policy_version_id")
    val bookingReliabilityPolicyHash = varchar("booking_reliability_policy_hash", 64)
    val bookingReliabilityEvaluationDigest = varchar("booking_reliability_evaluation_digest", 64)
    val bookingReliabilityExpiresAt = timestamp("booking_reliability_expires_at").nullable()
    val candidateRank = integer("candidate_rank")
    val selectionReasonCode = varchar("selection_reason_code", 64)
    val version = long("version").default(0L)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)

    init {
        check("ck_waitlist_offer_time_range") { startsAt less endsAt }
        check("ck_waitlist_offer_expiry") { expiresAt lessEq endsAt }
        check("ck_waitlist_offer_units") {
            (capacityUnits greater 0) and
                (maximumCapacity greater 0) and
                (capacityUnits lessEq maximumCapacity)
        }
        check("ck_waitlist_offer_policy_hash") {
            bookingReliabilityPolicyHash regexp "^[0-9a-f]{64}$"
        }
        check("ck_waitlist_offer_evaluation_digest") {
            bookingReliabilityEvaluationDigest regexp "^[0-9a-f]{64}$"
        }
        uniqueIndex("uq_waitlist_offer_active_entry", tenantGroupId, clinicId, activeEntryKey)
        uniqueIndex("uq_waitlist_offer_active_vacancy", tenantGroupId, clinicId, activeVacancyKey)
        index("idx_waitlist_offer_entry_status", false, tenantGroupId, clinicId, waitlistEntryId, status, id)
        index("idx_waitlist_offer_expiry", false, tenantGroupId, clinicId, status, expiresAt, id)
    }
}
