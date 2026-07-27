package io.bluetape4k.clinic.appointment.model.tables

import io.bluetape4k.clinic.appointment.model.dto.PolicyPreviewJobStatus
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Durable bounded impact-preview jobs with owner-fenced checkpoints.
 */
object SchedulingPolicyPreviewJobs : LongIdTable("scheduling_policy_preview_jobs") {
    /** Positive tenant boundary for every scanned row. */
    val tenantGroupId = long("tenant_group_id")

    /** Positive clinic boundary for every scanned row. */
    val clinicId = long("clinic_id")

    /** Draft definition being previewed. */
    val definitionId = long("definition_id")

    /** Exact draft revision; mismatch makes the job stale. */
    val draftRevision = long("draft_revision")

    /** Tenant generation expected at every resume. */
    val tenantGeneration = long("tenant_generation")

    /** Clinic generation expected at every resume. */
    val clinicGeneration = long("clinic_generation")

    /** Positive fixed partition count. */
    val partitionCount = integer("partition_count")

    /** Zero-based persisted partition cursor. */
    val cursorPartition = integer("cursor_partition").default(0)

    /** Last processed appointment ID in the current partition. */
    val cursorLastAppointmentId = long("cursor_last_appointment_id").nullable()

    /** Monotonic number of inspected appointments. */
    val scannedCount = long("scanned_count").default(0L)

    /** Monotonic affected count, never above scanned count. */
    val affectedCount = long("affected_count").default(0L)

    /** Current durable preview lifecycle. */
    val status = enumerationByName<PolicyPreviewJobStatus>("status", 24)

    /** UTC hard deadline after which partial evidence is unusable. */
    val deadlineAt = timestamp("deadline_at")

    /** Earliest UTC worker claim instant. */
    val nextAttemptAt = timestamp("next_attempt_at")

    /** Opaque current worker identity, or null while unclaimed. */
    val leaseOwner = varchar("lease_owner", 160).nullable()

    /** UTC lease expiry, or null while unclaimed. */
    val leaseUntil = timestamp("lease_until").nullable()

    /** Sanitized stable error code. */
    val lastErrorCode = varchar("last_error_code", 96).nullable()

    /** Database insertion instant. */
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)

    /** UTC instant of the latest transition or checkpoint. */
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)

    init {
        index("idx_policy_preview_due", false, status, nextAttemptAt, leaseUntil)
        index("idx_policy_preview_scope", false, tenantGroupId, clinicId, id)
    }
}
