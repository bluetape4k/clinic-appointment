package io.bluetape4k.clinic.appointment.model.tables

import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistPolicyState
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/** waitlist delivery policy의 immutable publication version을 저장합니다. */
object WaitlistPolicyVersions : LongIdTable("scheduling_waitlist_policy_versions") {
    val tenantGroupId = reference("tenant_group_id", TenantGroups, onDelete = ReferenceOption.RESTRICT)
    val clinicId = reference("clinic_id", Clinics, onDelete = ReferenceOption.CASCADE)
    val generation = long("generation")
    val policyVersion = long("policy_version")
    val policyDigest = varchar("policy_digest", 64)
    val urgencyWeight = integer("urgency_weight").default(0)
    val recoveryWeight = integer("recovery_weight").default(0)
    val benefitWeight = integer("benefit_weight").default(0)
    val reliabilityWeight = integer("reliability_weight").default(0)
    val waitingAgeWeight = integer("waiting_age_weight").default(0)
    val slotFitWeight = integer("slot_fit_weight").default(0)
    val status = enumerationByName<WaitlistPolicyState>("status", 24)
        .check("ck_waitlist_policy_version_status") { it inList WaitlistPolicyState.entries }
    val effectiveFrom = timestamp("effective_from")
    val effectiveUntil = timestamp("effective_until").nullable()
    val canonicalPolicyJson = text("canonical_policy_json")
    val createdBy = varchar("created_by", 160)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val retiredBy = varchar("retired_by", 160).nullable()
    val retiredAt = timestamp("retired_at").nullable()

    init {
        check("ck_waitlist_policy_weights_bounded") {
            (urgencyWeight greaterEq 0) and
                (urgencyWeight lessEq 10_000) and
                (recoveryWeight greaterEq 0) and
                (recoveryWeight lessEq 10_000) and
                (benefitWeight greaterEq 0) and
                (benefitWeight lessEq 10_000) and
                (reliabilityWeight greaterEq 0) and
                (reliabilityWeight lessEq 10_000) and
                (waitingAgeWeight greaterEq 0) and
                (waitingAgeWeight lessEq 10_000) and
                (slotFitWeight greaterEq 0) and
                (slotFitWeight lessEq 10_000)
        }
        uniqueIndex("uq_waitlist_policy_generation", tenantGroupId, clinicId, generation)
        uniqueIndex("uq_waitlist_policy_version", tenantGroupId, clinicId, policyVersion)
        index("idx_waitlist_policy_active", false, tenantGroupId, clinicId, status, effectiveFrom)
    }
}
