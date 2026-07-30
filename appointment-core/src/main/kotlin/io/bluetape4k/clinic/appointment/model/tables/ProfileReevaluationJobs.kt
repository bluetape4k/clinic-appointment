package io.bluetape4k.clinic.appointment.model.tables

import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationPriorityClass
import io.bluetape4k.clinic.appointment.model.profile.ProfileReevaluationJobStatus
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * latest revision 하나를 처리하는 owner-fenced durable 재평가 작업입니다.
 */
object ProfileReevaluationJobs : LongIdTable("scheduling_profile_reevaluation_jobs") {
    val headId = reference(
        "head_id",
        ProfileReevaluationHeads,
        onDelete = ReferenceOption.CASCADE,
    )
    val tenantGroupId = long("tenant_group_id")
    val clinicId = long("clinic_id")
    val patientReferenceFingerprint = varchar("patient_reference_fingerprint", 64)
    val targetRevision = long("target_revision")
    val eventId = varchar("event_id", 160)
    val assessmentRef = varchar("assessment_ref", 512)
    val assessmentHash = varchar("assessment_hash", 64)
    val status = enumerationByName<ProfileReevaluationJobStatus>("status", 24)
        .check("ck_profile_reevaluation_job_status") {
            it inList ProfileReevaluationJobStatus.entries
        }
    val occurredAt = timestamp("occurred_at")
    val dueAt = timestamp("due_at")
    val targetDurationSeconds = long("target_duration_seconds")
    val heldTargetSeconds = long("held_target_seconds")
    val proposedTargetSeconds = long("proposed_target_seconds")
    val targetPolicyRef = varchar("target_policy_ref", 256)
    val targetPolicyGeneration = long("target_policy_generation")
    val nextAttemptAt = timestamp("next_attempt_at")
    val leaseOwner = varchar("lease_owner", 160).nullable()
    val leaseExpiresAt = timestamp("lease_expires_at").nullable()
    val attemptCount = integer("attempt_count").default(0)
    val firstAttemptAt = timestamp("first_attempt_at").nullable()
    val redriveCount = integer("redrive_count").default(0)
    val rootJobId = long("root_job_id").nullable()
    val redriveOfJobId = long("redrive_of_job_id").nullable()
    val redriveGeneration = integer("redrive_generation").default(0)
    val priorityClass = enumerationByName<ProfileReevaluationPriorityClass>("priority_class", 24)
        .check("ck_profile_reevaluation_priority_class") {
            it inList ProfileReevaluationPriorityClass.entries
        }
    val heldCursorAppointmentId = long("held_cursor_appointment_id").nullable()
    val proposedCursorAppointmentId = long("proposed_cursor_appointment_id").nullable()
    val scannedCount = long("scanned_count").default(0L)
    val proposalSupersededCount = long("proposal_superseded_count").default(0L)
    val holdKeptCount = long("hold_kept_count").default(0L)
    val holdReplacedCount = long("hold_replaced_count").default(0L)
    val fallbackToProposedCount = long("fallback_to_proposed_count").default(0L)
    val skippedIneligibleCount = long("skipped_ineligible_count").default(0L)
    val skippedUnchangedCount = long("skipped_unchanged_count").default(0L)
    val lastFailureCode = varchar("last_failure_code", 96).nullable()
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)

    init {
        index("idx_profile_reevaluation_due", false, status, nextAttemptAt, clinicId, id)
        index("idx_profile_reevaluation_lease", false, status, leaseExpiresAt, clinicId, id)
        index(
            "idx_profile_reevaluation_clinic_ready",
            false,
            tenantGroupId,
            clinicId,
            status,
            nextAttemptAt,
            dueAt,
            id,
        )
        index(
            "idx_profile_reevaluation_clinic_lease",
            false,
            tenantGroupId,
            clinicId,
            status,
            leaseExpiresAt,
            dueAt,
            id,
        )
        uniqueIndex(
            "uq_profile_reevaluation_job_lineage",
            rootJobId,
            targetRevision,
            redriveGeneration,
        )
    }
}
