package io.bluetape4k.clinic.appointment.api.policy

import io.bluetape4k.clinic.appointment.api.security.ActorContext
import io.bluetape4k.clinic.appointment.model.dto.PolicyScopeRef
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyActivationCommandRecord
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyDefinitionRecord
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyScopeHeadRecord
import io.bluetape4k.clinic.appointment.model.policy.ActorAuditRef
import io.bluetape4k.clinic.appointment.model.policy.PolicyGenerationVector
import io.bluetape4k.clinic.appointment.model.policy.SchedulingPolicyKind
import java.time.Instant

/**
 * Creates a new immutable policy version in `DRAFT`.
 *
 * Payload decoding, typed validation, canonicalization, and SHA-256 calculation
 * happen before this command is constructed. Consequently [payloadJson] and
 * [payloadHash] are trusted only as output of the application codec, never as
 * arbitrary request-body fields passed through unchanged.
 *
 * @property scope Numeric persistence boundary resolved from the authenticated
 * request path. `clinicId` is null only for a tenant default.
 * @property kind Closed payload schema and compilation area.
 * @property schemaVersion Positive wire-schema version understood by the codec.
 * @property effectiveFrom Inclusive UTC eligibility boundary.
 * @property effectiveUntil Exclusive UTC boundary, or `null` for no planned end.
 * @property payloadHash Lowercase SHA-256 of canonical UTF-8 [payloadJson].
 * @property payloadJson Canonical schema-versioned JSON, at most 256 KiB UTF-8.
 * It must contain no credentials, claims, idempotency key, or actor identity.
 * @property changeReason Non-secret operator rationale of 1..1000 characters.
 * @property expectedScopeRevision Non-negative revision read from the locked
 * scope head. A mismatch rejects the command instead of silently rebasing it.
 * @property actor Gateway-derived current actor. Request bodies cannot supply
 * or override it.
 */
data class CreateSchedulingPolicyDraftCommand(
    val scope: PolicyScopeRef,
    val kind: SchedulingPolicyKind,
    val schemaVersion: Int,
    val effectiveFrom: Instant,
    val effectiveUntil: Instant?,
    val payloadHash: String,
    val payloadJson: String,
    val changeReason: String,
    val expectedScopeRevision: Long,
    val actor: ActorContext,
)

/**
 * Replaces editable content of one exact draft revision.
 *
 * A successful edit increments both the definition revision and administrative
 * scope revision. Approval rows and preview evidence remain immutable audit
 * records but become unusable because they are pinned to [expectedDraftRevision].
 *
 * @property definitionId Positive database identity resolved within [scope].
 * @property expectedDraftRevision Positive optimistic revision being replaced.
 * @property expectedScopeRevision Non-negative administrative head revision.
 * Other fields have the same canonical and time-bound meanings as
 * [CreateSchedulingPolicyDraftCommand].
 */
data class ReviseSchedulingPolicyDraftCommand(
    /** Trusted numeric tenant/clinic boundary resolved from the request path. */
    val scope: PolicyScopeRef,
    /** Positive database identity that must belong to [scope]. */
    val definitionId: Long,
    /** Positive editable revision; a mismatch returns `POLICY_DRAFT_STALE`. */
    val expectedDraftRevision: Long,
    /** Non-negative scope-head revision protecting the administrative edit. */
    val expectedScopeRevision: Long,
    /** Positive wire schema understood by the typed payload codec. */
    val schemaVersion: Int,
    /** Inclusive UTC eligibility boundary for the revised definition. */
    val effectiveFrom: Instant,
    /** Exclusive UTC end, or `null` for an open-ended interval. */
    val effectiveUntil: Instant?,
    /** Lowercase SHA-256 of canonical UTF-8 [payloadJson]. */
    val payloadHash: String,
    /** Canonical JSON of at most 256 KiB with no credentials or actor data. */
    val payloadJson: String,
    /** Non-secret audit rationale containing 1..1000 characters. */
    val changeReason: String,
    /** Current Gateway-derived actor; never accepted from the request body. */
    val actor: ActorContext,
)

/**
 * Records approval evidence for one exact draft revision.
 *
 * Approval is not a lifecycle state. Editing the draft does not delete this
 * evidence; the revision mismatch makes it stale. [actor] is both the authority
 * evaluated now and the audit subject stored on success.
 */
data class ApproveSchedulingPolicyCommand(
    /** Trusted tenant/clinic boundary against which ownership is rechecked. */
    val scope: PolicyScopeRef,
    /** Positive draft identity that must belong to [scope]. */
    val definitionId: Long,
    /** Positive exact revision reviewed by the approver. */
    val expectedDraftRevision: Long,
    /** Gateway-derived current approver and authentication assurance evidence. */
    val actor: ActorContext,
)

/**
 * Evidence that a complete impact preview was evaluated against exact inputs.
 *
 * This value is an application boundary, not self-authenticating proof. The
 * injected [PolicyPreviewEvidenceVerifier] must verify its durable job/token
 * source. A stale or partial preview must never produce an accepted instance.
 *
 * @property definitionId Draft evaluated by the preview.
 * @property draftRevision Exact definition revision evaluated.
 * @property tenantGeneration Tenant effective generation observed by the scan.
 * It is positive once a tenant baseline exists.
 * @property clinicGeneration Clinic generation observed by the scan; `0` means
 * no clinic override has yet been activated.
 * @property evidenceId Bounded opaque preview job or signed-token identity. It
 * is safe metadata and must not embed patient or appointment details.
 */
data class PolicyPreviewEvidence(
    val definitionId: Long,
    val draftRevision: Long,
    val tenantGeneration: Long,
    val clinicGeneration: Long,
    val evidenceId: String,
)

/**
 * Verifies preview evidence against the definition and locked generation vector.
 *
 * Implementations must fail closed and perform no network call while database
 * scope-head locks are held. The production preview service can satisfy this
 * contract with locally persisted evidence in a later task.
 */
fun interface PolicyPreviewEvidenceVerifier {
    /**
     * Returns `true` only for complete durable evidence matching all inputs.
     *
     * [generation] uses the tenant generation first and clinic generation
     * second; `clinicGeneration=0` is the no-override sentinel.
     */
    fun verify(
        evidence: PolicyPreviewEvidence,
        definition: SchedulingPolicyDefinitionRecord,
        generation: PolicyGenerationVector,
    ): Boolean
}

/**
 * Rechecks the numeric persistence tenant against trusted request context.
 *
 * [ActorContext.allowedTenantCodes] contains Gateway codes rather than database
 * IDs. A production verifier must combine those codes with the already resolved
 * request `TenantContext` and require its numeric ID to equal [PolicyScopeRef.tenantGroupId].
 * It must also fail when invoked outside a tenant-scoped request.
 */
fun interface PolicyTenantBoundaryVerifier {
    /**
     * Returns `true` only when [scope]'s numeric tenant equals trusted request
     * resolution and [actor] carries the matching Gateway tenant code.
     */
    fun isAuthorized(
        scope: PolicyScopeRef,
        actor: ActorContext,
    ): Boolean
}

/**
 * Schedules a validated draft for later activation.
 *
 * @property expectedActiveRevision Scope-head revision before scheduling.
 * The persisted activation command stores the post-schedule revision because
 * that is the revision it must observe when a worker activates.
 * @property preview Completed evidence pinned to this draft and current heads.
 */
data class ScheduleSchedulingPolicyCommand(
    /** Trusted numeric tenant/clinic boundary resolved from the request path. */
    val scope: PolicyScopeRef,
    /** Positive draft identity that must belong to [scope]. */
    val definitionId: Long,
    /** Positive revision approved and previewed for scheduling. */
    val expectedDraftRevision: Long,
    /** Non-negative scope-head revision before the schedule mutation. */
    val expectedActiveRevision: Long,
    /** Complete preview pinned to the pre-schedule generation vector. */
    val preview: PolicyPreviewEvidence,
    /** Current Gateway-derived human actor; service actors cannot schedule. */
    val actor: ActorContext,
)

/**
 * Activates a draft immediately or executes a persisted scheduled command.
 *
 * @property scheduledCommandId Positive durable command identity for a
 * scheduled worker execution or manual replay; `null` means a human immediate
 * activation and is forbidden for a `SYSTEM` actor.
 * @property replayOfCommandId Terminal `MISSED` source for manual recovery, or
 * `null` for an original command. Replay creates a new durable row.
 * @property idempotencyKey Raw bounded human key transformed before the
 * transaction, or `null` only when [scheduledCommandId] identifies a durable
 * scheduled command. A runner needs the command ID, not the original human
 * scheduling request key.
 * @property expectedActiveRevision Exact scope-head revision protected by CAS.
 */
data class ActivateSchedulingPolicyCommand(
    /** Trusted numeric tenant/clinic boundary resolved from request context. */
    val scope: PolicyScopeRef,
    /** Positive definition identity that must belong to [scope]. */
    val definitionId: Long,
    /** Positive revision approved and previewed for activation. */
    val expectedDraftRevision: Long,
    /** Non-negative exact scope-head revision protected by activation CAS. */
    val expectedActiveRevision: Long,
    /** Raw human idempotency key, or `null` for durable scheduled execution. */
    val idempotencyKey: String?,
    /** Complete preview evidence pinned to definition and generations. */
    val preview: PolicyPreviewEvidence,
    /** Gateway-derived human or service activation authority. */
    val actor: ActorContext,
    /** Durable scheduled command ID, or `null` for immediate/manual activation. */
    val scheduledCommandId: Long? = null,
    /** Immutable terminal `MISSED` source, or `null` for an original command. */
    val replayOfCommandId: Long? = null,
)

/**
 * Retires one active or not-yet-active definition without deleting history.
 *
 * Retirement advances the scope generation only when an `ACTIVE` definition
 * stops contributing to effective scheduling. Retiring `DRAFT` or `SCHEDULED`
 * advances only the administrative revision.
 */
data class RetireSchedulingPolicyCommand(
    /** Trusted numeric tenant/clinic boundary resolved from request context. */
    val scope: PolicyScopeRef,
    /** Positive definition identity retained permanently after retirement. */
    val definitionId: Long,
    /** Positive exact definition revision being retired. */
    val expectedDraftRevision: Long,
    /** Non-negative scope-head revision protecting the retirement CAS. */
    val expectedScopeRevision: Long,
    /** Gateway-derived human administrator or staff actor. */
    val actor: ActorContext,
)

/**
 * Result of an activation or an idempotent replay.
 *
 * @property command Durable activation command. Successful results are always
 * `COMPLETED` and contain generations plus event identity.
 * @property definition Newly active immutable definition.
 * @property generation Fresh generation vector committed atomically with the
 * definition, command result, and outbox event.
 * @property idempotentReplay `true` when no write occurred and a prior
 * completed command with the same scoped key and fingerprint was returned.
 */
data class SchedulingPolicyActivationResult(
    val command: SchedulingPolicyActivationCommandRecord,
    val definition: SchedulingPolicyDefinitionRecord,
    val generation: PolicyGenerationVector,
    val idempotentReplay: Boolean,
)

/**
 * Appends the redacted activation integration event inside the caller transaction.
 *
 * Implementations must not open or commit a transaction. Throwing rolls back
 * lifecycle, head counters, durable command, and outbox together.
 */
fun interface PolicyActivationPublisher {
    /**
     * Inserts one redacted outbox event and returns its deterministic ID.
     *
     * [definition] must already be `ACTIVE`; [generation] must be the
     * post-increment vector; [actor] contains only stable audit identity; and
     * [correlationId] is bounded non-secret trace metadata.
     */
    fun publish(
        definition: SchedulingPolicyDefinitionRecord,
        generation: PolicyGenerationVector,
        actor: ActorAuditRef,
        correlationId: String,
    ): String
}

/**
 * Combined result of a definition mutation and its serialized scope head.
 *
 * [head] is the post-commit logical value read inside the transaction and lets
 * callers form their next optimistic command without an extra unlocked read.
 *
 * @property definition Persisted post-mutation definition, never a detached
 * request-body projection.
 * @property head Post-mutation serialization head. Revision is non-negative;
 * generation changes only when effective scheduling behavior changes.
 */
data class SchedulingPolicyMutationResult(
    val definition: SchedulingPolicyDefinitionRecord,
    val head: SchedulingPolicyScopeHeadRecord,
)
