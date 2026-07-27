package io.bluetape4k.clinic.appointment.model.dto

import io.bluetape4k.clinic.appointment.model.policy.ActorRole
import io.bluetape4k.clinic.appointment.model.policy.PolicyLifecycle
import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import io.bluetape4k.clinic.appointment.model.policy.SchedulingPolicyKind
import java.io.Serializable
import java.time.Instant

/**
 * Canonical database boundary for one tenant or clinic policy scope.
 *
 * @property tenantGroupId Positive tenant identity that owns the policy.
 * @property scope Tenant baseline or clinic override boundary.
 * @property clinicId Positive clinic identity for a clinic override and `null`
 * for a tenant baseline. Persistence converts the tenant `null` to the
 * non-null sentinel `0` only in `clinic_scope_key`.
 */
data class PolicyScopeRef(
    val tenantGroupId: Long,
    val scope: PolicyScope,
    val clinicId: Long? = null,
) : Serializable {
    /** Non-null key used by cross-dialect unique constraints. */
    val clinicScopeKey: Long
        get() = when (scope) {
            PolicyScope.TENANT_DEFAULT -> 0L
            PolicyScope.CLINIC_OVERRIDE -> requireNotNull(clinicId) {
                "clinicId is required for CLINIC_OVERRIDE"
            }
        }

    init {
        require(tenantGroupId > 0) { "tenantGroupId must be positive" }
        when (scope) {
            PolicyScope.TENANT_DEFAULT -> require(clinicId == null) {
                "clinicId must be null for TENANT_DEFAULT"
            }
            PolicyScope.CLINIC_OVERRIDE -> require(clinicId != null && clinicId > 0) {
                "clinicId must be positive for CLINIC_OVERRIDE"
            }
        }
    }
}

/**
 * Persistence projection of an immutable scheduling-policy definition.
 *
 * @property id Database identity, or `null` before insertion.
 * @property tenantGroupId Positive tenant owner.
 * @property scope Tenant default or clinic override.
 * @property clinicId Clinic identity for an override; `null` for a tenant default.
 * @property clinicScopeKey Stored non-null scope discriminator. `0` means tenant
 * scope; a positive value equals [clinicId].
 * @property kind Closed policy area whose payload is stored.
 * @property version Positive immutable publication version within scope and kind.
 * @property schemaVersion Positive payload wire-schema version.
 * @property lifecycle Current administrative lifecycle. Published payload rows
 * remain immutable even after retirement.
 * @property effectiveFrom Inclusive UTC boundary for selection.
 * @property effectiveUntil Exclusive UTC boundary, or `null` when open ended.
 * @property revision Positive draft revision to which approvals bind.
 * @property payloadHash Lowercase 64-character canonical payload SHA-256.
 * @property payloadJson Canonical, schema-versioned JSON. It must not contain
 * actor credentials or an idempotency key.
 * @property createdByActorId Stable trusted Gateway subject, never a display
 * name, access token, or request-body identity.
 * @property createdByActorRole Role captured for audit at creation time.
 * @property changeReason Non-secret operator rationale for the revision.
 * @property createdAt Database creation instant in UTC.
 */
data class SchedulingPolicyDefinitionRecord(
    val id: Long? = null,
    val tenantGroupId: Long,
    val scope: PolicyScope,
    val clinicId: Long? = null,
    val clinicScopeKey: Long = if (scope == PolicyScope.TENANT_DEFAULT) 0L else requireNotNull(clinicId),
    val kind: SchedulingPolicyKind,
    val version: Long,
    val schemaVersion: Int,
    val lifecycle: PolicyLifecycle,
    val effectiveFrom: Instant,
    val effectiveUntil: Instant?,
    val revision: Long,
    val payloadHash: String,
    val payloadJson: String,
    val createdByActorId: String,
    val createdByActorRole: ActorRole,
    val changeReason: String,
    val createdAt: Instant = Instant.EPOCH,
) : Serializable

/**
 * Approval evidence bound to one exact draft revision.
 *
 * @property id Database identity, or `null` before insertion.
 * @property definitionId Policy definition being approved.
 * @property draftRevision Exact revision reviewed by the actor. A later draft
 * revision leaves this row for audit but makes it unusable for activation.
 * @property actorId Stable trusted Gateway subject of the approver.
 * @property actorRole Role used when approval authority was evaluated.
 * @property assuranceLevel Bounded, non-secret authentication assurance label
 * such as `MFA`; it is evidence metadata, not a credential.
 * @property approvedAt UTC instant at which approval was recorded.
 */
data class SchedulingPolicyApprovalRecord(
    val id: Long? = null,
    val definitionId: Long,
    val draftRevision: Long,
    val actorId: String,
    val actorRole: ActorRole,
    val assuranceLevel: String,
    val approvedAt: Instant,
) : Serializable

/**
 * Serialization point for all policy-kind activations inside one scope.
 *
 * @property id Database identity.
 * @property tenantGroupId Tenant that owns the scope.
 * @property scope Tenant baseline or clinic override.
 * @property clinicScopeKey `0` for tenant scope or the positive clinic ID.
 * @property revision Optimistic command revision. It advances once for every
 * successful scope mutation.
 * @property generation Monotonic freshness counter. It advances together with
 * [revision] when any policy kind becomes active.
 * @property updatedAt UTC instant of the last successful mutation.
 */
data class SchedulingPolicyScopeHeadRecord(
    val id: Long,
    val tenantGroupId: Long,
    val scope: PolicyScope,
    val clinicScopeKey: Long,
    val revision: Long,
    val generation: Long,
    val updatedAt: Instant,
) : Serializable

/**
 * Immutable compiled policy snapshot for one clinic.
 *
 * @property id Database identity.
 * @property tenantGroupId Tenant boundary of the compiled result.
 * @property clinicId Clinic for which the policy was resolved.
 * @property decisionAt UTC policy decision instant.
 * @property serviceAt UTC planned service instant.
 * @property tenantGeneration Tenant head generation observed and rechecked.
 * @property clinicGeneration Clinic head generation observed and rechecked.
 * @property sourceVersionsJson Canonical source-version map JSON.
 * @property sourceByPathJson Canonical leaf-source map JSON.
 * @property disabledFeaturesJson Canonical sorted disabled-path array JSON.
 * @property warningsJson Ordered, customer-safe warning array JSON.
 * @property payloadJson Canonical compiled-policy JSON.
 * @property snapshotHash Lowercase 64-character SHA-256 over the complete
 * compiled contract. Identity is unique only within tenant and clinic scope.
 * @property createdAt Database creation instant. Existing snapshots are never
 * updated when newer policies become active.
 */
data class EffectiveSchedulingPolicySnapshotRecord(
    val id: Long,
    val tenantGroupId: Long,
    val clinicId: Long,
    val decisionAt: Instant,
    val serviceAt: Instant,
    val tenantGeneration: Long,
    val clinicGeneration: Long,
    val sourceVersionsJson: String,
    val sourceByPathJson: String,
    val disabledFeaturesJson: String,
    val warningsJson: String,
    val payloadJson: String,
    val snapshotHash: String,
    val createdAt: Instant,
) : Serializable

/** Durable state of a scheduled policy activation command. */
enum class PolicyActivationCommandStatus {
    /** Ready for the first eligible worker claim. */
    PENDING,
    /** Temporarily owned by the worker named in the lease columns. */
    CLAIMED,
    /** Previous attempt failed and may run again at `nextAttemptAt`. */
    RETRY_WAIT,
    /** Activation committed and its result metadata is immutable. */
    COMPLETED,
    /** Effective deadline passed and policy deliberately did not activate. */
    MISSED,
}

/**
 * Durable activation command with privacy-safe keyed idempotency.
 *
 * @property id Database identity, or `null` before insertion.
 * @property tenantGroupId Tenant that owns the command.
 * @property scope Tenant baseline or clinic override.
 * @property clinicId Positive clinic for override scope, otherwise `null`.
 * @property clinicScopeKey Non-null uniqueness sentinel derived from scope.
 * @property definitionId Definition selected for activation.
 * @property expectedDraftRevision Draft revision validated by the caller.
 * @property expectedActiveRevision Scope-head revision expected at activation.
 * @property idempotencyKeyHash Lowercase HMAC-SHA-256 of the validated raw key.
 * The raw key is never persisted, logged, or returned.
 * @property requestFingerprint Lowercase SHA-256 of the canonical request. A
 * repeated key with a different fingerprint is a conflict.
 * @property status Current worker lifecycle.
 * @property effectiveFrom UTC activation boundary.
 * @property nextAttemptAt Earliest UTC instant at which a worker may claim.
 * @property leaseOwner Opaque bounded worker ID while claimed.
 * @property leaseUntil UTC lease expiry; stale owners lose write authority.
 * @property attempt Number of successful worker claims.
 * @property resultTenantGeneration Tenant generation produced by completion.
 * It is `null` for every non-completed state and must be populated atomically
 * with [resultClinicGeneration] and [eventId] when status becomes `COMPLETED`.
 * @property resultClinicGeneration Clinic generation produced by completion.
 * It is `null` for every non-completed state; `0` is valid after completion
 * when no clinic override generation exists.
 * @property eventId Deterministic outbox event identity produced atomically.
 * It is `null` until completion. Consumers must require `COMPLETED` plus this
 * value and both result generations before treating activation as published.
 * @property lastErrorCode Stable sanitized retry or terminal error code, or
 * `null` when no failure is recorded. It never contains a raw exception,
 * request JSON, idempotency key, actor data, credential, or claim.
 * @property createdAt Database creation instant.
 * @property updatedAt Database last-transition instant.
 */
data class SchedulingPolicyActivationCommandRecord(
    val id: Long? = null,
    val tenantGroupId: Long,
    val scope: PolicyScope,
    val clinicId: Long? = null,
    val clinicScopeKey: Long = if (scope == PolicyScope.TENANT_DEFAULT) 0L else requireNotNull(clinicId),
    val definitionId: Long,
    val expectedDraftRevision: Long,
    val expectedActiveRevision: Long,
    val idempotencyKeyHash: String,
    val requestFingerprint: String,
    val status: PolicyActivationCommandStatus = PolicyActivationCommandStatus.PENDING,
    val effectiveFrom: Instant,
    val nextAttemptAt: Instant,
    val leaseOwner: String? = null,
    val leaseUntil: Instant? = null,
    val attempt: Int = 0,
    val resultTenantGeneration: Long? = null,
    val resultClinicGeneration: Long? = null,
    val eventId: String? = null,
    val lastErrorCode: String? = null,
    val createdAt: Instant = Instant.EPOCH,
    val updatedAt: Instant = Instant.EPOCH,
) : Serializable

/** Durable state of an asynchronous scheduling-policy impact preview. */
enum class PolicyPreviewJobStatus {
    /** Waiting for a worker claim. */
    PENDING,
    /** Actively scanned by the current lease owner. */
    RUNNING,
    /** Full bounded scan completed successfully. */
    COMPLETED,
    /** Definition revision or generation changed during the scan. */
    STALE,
    /** Scan failed after its retry policy was exhausted. */
    FAILED,
    /** Explicitly cancelled before completion. */
    CANCELLED,
}

/**
 * Keyset cursor used to resume a bounded preview scan.
 *
 * @property partition Zero-based partition number currently being scanned.
 * @property lastAppointmentId Last processed appointment ID, or `null` before
 * the first row in the partition.
 */
data class PolicyPreviewCursor(
    val partition: Int,
    val lastAppointmentId: Long?,
) : Serializable

/**
 * Monotonic counters recorded with a preview checkpoint.
 *
 * @property scannedCount Total future appointments inspected so far.
 * @property affectedCount Subset whose effective policy or schedule may change.
 */
data class PolicyPreviewProgress(
    val scannedCount: Long,
    val affectedCount: Long,
) : Serializable

/**
 * Durable asynchronous preview job.
 *
 * @property id Database identity, or `null` before insertion.
 * @property tenantGroupId Tenant boundary for every scanned appointment.
 * @property clinicId Clinic boundary for the preview.
 * @property definitionId Draft definition being evaluated.
 * @property draftRevision Exact draft revision; mismatch makes the job stale.
 * @property tenantGeneration Expected tenant generation at each resume.
 * @property clinicGeneration Expected clinic generation at each resume.
 * @property partitionCount Positive fixed partition count for deterministic resume.
 * @property cursorPartition Zero-based persisted partition cursor.
 * @property cursorLastAppointmentId Last processed positive appointment ID in
 * the partition. `null` means no row has yet been processed in that partition,
 * including immediately after advancing [cursorPartition].
 * @property scannedCount Monotonic total rows inspected.
 * @property affectedCount Monotonic affected-row count, never above scanned.
 * @property status Current preview lifecycle.
 * @property deadlineAt UTC hard deadline after which partial results are unusable.
 * @property nextAttemptAt Earliest UTC worker claim instant.
 * @property leaseOwner Opaque current worker ID. It is non-null only while
 * [status] is `RUNNING` and must be paired with [leaseUntil].
 * @property leaseUntil Exclusive UTC fencing deadline. It is non-null only
 * while [status] is `RUNNING`; at or after this instant the owner is stale.
 * @property lastErrorCode Stable sanitized retry or terminal error code, or
 * `null` when no failure is recorded. It never contains raw exceptions,
 * appointment data, request/policy payloads, credentials, or claims.
 * @property createdAt Database creation instant.
 * @property updatedAt Database last-transition/checkpoint instant.
 */
data class SchedulingPolicyPreviewJobRecord(
    val id: Long? = null,
    val tenantGroupId: Long,
    val clinicId: Long,
    val definitionId: Long,
    val draftRevision: Long,
    val tenantGeneration: Long,
    val clinicGeneration: Long,
    val partitionCount: Int,
    val cursorPartition: Int = 0,
    val cursorLastAppointmentId: Long? = null,
    val scannedCount: Long = 0,
    val affectedCount: Long = 0,
    val status: PolicyPreviewJobStatus = PolicyPreviewJobStatus.PENDING,
    val deadlineAt: Instant,
    val nextAttemptAt: Instant,
    val leaseOwner: String? = null,
    val leaseUntil: Instant? = null,
    val lastErrorCode: String? = null,
    val createdAt: Instant = Instant.EPOCH,
    val updatedAt: Instant = Instant.EPOCH,
) : Serializable
