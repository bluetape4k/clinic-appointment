package io.bluetape4k.clinic.appointment.model.policy

import java.io.Serializable
import java.time.Instant

/**
 * Identifies the organizational level at which a policy definition applies.
 *
 * A tenant default is the mandatory baseline shared by every clinic in the
 * tenant group. A clinic override may only narrow or replace fields explicitly
 * exposed as [OverrideValue]; it cannot weaken tenant or platform safety
 * ceilings.
 */
enum class PolicyScope {
    /** Tenant-wide baseline. A definition in this scope must have no clinic ID. */
    TENANT_DEFAULT,

    /** Clinic-specific override. A definition in this scope requires a positive clinic ID. */
    CLINIC_OVERRIDE,
}

/**
 * Closed set of independently versioned scheduling-policy areas.
 *
 * Keeping this set closed makes payload decoding, validation, hashing, and
 * compilation explicit. Adding a kind therefore requires a schema, validator,
 * canonical hash writer, compiler contribution, and compatibility tests.
 */
enum class SchedulingPolicyKind {
    /** Booking origin, provisional lifetime, capacity hold, approval, and consent rules. */
    BOOKING_COMMITMENT,

    /** Consent evidence retention and validity rules used during a hold. */
    HOLD_AND_CONSENT,

    /** Nominal capacity, deliberate overbooking, and hard booking ceilings. */
    CAPACITY_AND_OVERBOOKING,

    /** Objective customer reliability inputs and their scheduling weights. */
    PRIORITY_AND_RELIABILITY,

    /** Reconfirmation timing and retry limits before a scheduled visit. */
    RECONFIRMATION,

    /** Recovery proposal behavior after closures, absences, or equipment failures. */
    DISRUPTION_RECOVERY,

    /** Controlled extension of clinic operating time beyond the normal schedule. */
    OPERATING_EXTENSION,

    /** Notification channels and mandatory disruption-response service levels. */
    NOTIFICATION_AND_SLA,
}

/**
 * Administrative lifecycle of an immutable policy version.
 *
 * Mutating a published version is forbidden. A changed payload is represented
 * as a new version or draft revision and is activated separately.
 */
enum class PolicyLifecycle {
    /** Editable proposal that is not used for scheduling decisions. */
    DRAFT,

    /** Approved version waiting for its effective time. */
    SCHEDULED,

    /** Version eligible for effective-policy compilation. */
    ACTIVE,

    /** Historical version retained for audit and snapshot reproducibility. */
    RETIRED,
}

/**
 * Stable actor category recorded in policy audit evidence.
 *
 * The role is derived from trusted gateway authentication and must not be
 * accepted from policy request bodies.
 */
enum class ActorRole {
    /** Tenant or clinic administrator allowed to manage policy definitions. */
    ADMIN,

    /** Operational staff member who may approve or coordinate appointments. */
    STAFF,

    /** Medical practitioner whose availability can constrain appointments. */
    DOCTOR,

    /** Customer receiving care; never an administrative policy author. */
    PATIENT,

    /** Authenticated service identity executing an automated transition. */
    SYSTEM,
}

/**
 * Minimal immutable actor reference stored with policy audit records.
 *
 * @property actorId Stable, non-secret subject identifier obtained from the
 * trusted gateway principal. It must not contain a display name, credential,
 * token, or mutable authorization claim.
 * @property actorRole Normalized role used for authorization and separation-of-
 * duties checks at the time of the command.
 */
data class ActorAuditRef(
    val actorId: String,
    val actorRole: ActorRole,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * Marker for a closed, serializable scheduling-policy payload.
 *
 * Implementations represent either a complete tenant policy or a clinic
 * override for exactly one [kind]. The envelope kind and payload kind must
 * always match.
 */
sealed interface SchedulingPolicyPayload : Serializable {
    /** Policy area whose schema, validator, hash writer, and compiler handle this payload. */
    val kind: SchedulingPolicyKind
}

/**
 * Immutable, versioned scheduling-policy definition and its audit metadata.
 *
 * Validation is intentionally owned by `SchedulingPolicyValidator` so decoded
 * payloads and copied data classes cannot bypass the same boundary checks.
 *
 * @property id Database identity. `null` means the definition has not been
 * persisted yet; it is deliberately excluded from canonical payload hashes.
 * @property tenantGroupId Positive tenant boundary supplied by the trusted
 * command context. It is never inferred from the payload.
 * @property scope Whether this is a tenant baseline or clinic override.
 * @property clinicId Positive clinic identity for [PolicyScope.CLINIC_OVERRIDE],
 * and strictly `null` for [PolicyScope.TENANT_DEFAULT].
 * @property kind Closed policy area. It must equal [SchedulingPolicyPayload.kind].
 * @property version Monotonic immutable publication version, starting at `1`.
 * A new effective contract increments the version rather than editing history.
 * @property schemaVersion Wire-schema version for [payload]. Schema `1` is the
 * only version accepted by this foundation.
 * @property lifecycle Administrative state. Only active definitions contribute
 * to an effective snapshot; lifecycle transition rules are enforced by the
 * command service.
 * @property effectiveFrom Inclusive UTC instant from which this version may be
 * selected for compilation.
 * @property effectiveUntil Exclusive UTC instant after which this version is no
 * longer eligible, or `null` for an open-ended interval. When present it must
 * be strictly after [effectiveFrom].
 * @property revision Optimistic-concurrency revision of this definition,
 * starting at `1`. Approval evidence is bound to this exact revision.
 * @property payloadHash Lowercase 64-character SHA-256 of the canonical payload.
 * Database IDs, actor data, and timestamps are excluded.
 * @property payload Typed tenant policy or clinic override selected by [kind]
 * and [scope].
 * @property createdBy Trusted actor reference captured when this revision was
 * created. Authorization still uses the current gateway principal.
 * @property changeReason Human-readable audit rationale, from 1 to 1,000
 * non-blank characters; it must not contain secrets or raw authentication data.
 */
data class SchedulingPolicyDefinition(
    val id: Long?,
    val tenantGroupId: Long,
    val scope: PolicyScope,
    val clinicId: Long?,
    val kind: SchedulingPolicyKind,
    val version: Long,
    val schemaVersion: Int,
    val lifecycle: PolicyLifecycle,
    val effectiveFrom: Instant,
    val effectiveUntil: Instant?,
    val revision: Long,
    val payloadHash: String,
    val payload: SchedulingPolicyPayload,
    val createdBy: ActorAuditRef,
    val changeReason: String,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
