package io.bluetape4k.clinic.appointment.api.policy

import io.bluetape4k.clinic.appointment.api.config.SchedulingPolicyApiException
import io.bluetape4k.clinic.appointment.api.config.SchedulingPolicyErrorCode
import io.bluetape4k.clinic.appointment.api.security.ActorContext
import io.bluetape4k.clinic.appointment.api.security.ActorType
import io.bluetape4k.clinic.appointment.api.security.AuthenticationAssurance
import io.bluetape4k.clinic.appointment.model.dto.PolicyActivationCommandStatus
import io.bluetape4k.clinic.appointment.model.dto.PolicyScopeRef
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyActivationCommandRecord
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyApprovalRecord
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyDefinitionRecord
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyScopeHeadRecord
import io.bluetape4k.clinic.appointment.model.policy.ActorAuditRef
import io.bluetape4k.clinic.appointment.model.policy.ActorRole
import io.bluetape4k.clinic.appointment.model.policy.PolicyGenerationVector
import io.bluetape4k.clinic.appointment.model.policy.PolicyLifecycle
import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import io.bluetape4k.clinic.appointment.model.policy.SchedulingPolicyKind
import io.bluetape4k.clinic.appointment.repository.PolicyScopeHeadConflictException
import io.bluetape4k.clinic.appointment.repository.SchedulingPolicyJobRepository
import io.bluetape4k.clinic.appointment.repository.SchedulingPolicyRepository
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Transactional application service for scheduling-policy administration.
 *
 * This service is the only layer allowed to combine lifecycle changes, approval
 * evidence, scope-head counters, durable activation commands, and an activation
 * outbox event. Repositories deliberately inherit the Exposed transaction
 * opened here. An exception from validation, CAS, command completion, or
 * [publisher] therefore rolls back the entire business decision.
 *
 * Trust and privacy boundaries:
 *
 * - [ActorContext] must come from the verified Gateway JWT and path-scoped
 *   `ActorContextResolver`; request payloads cannot supply identity or scope.
 * - typed payload validation and canonical hashing finish before draft commands
 *   reach this service.
 * - raw idempotency keys are validated and HMAC-hashed before a transaction is
 *   opened. Only the digest and a payload-free intent fingerprint are stored.
 * - preview evidence is checked by [previewVerifier] against the exact draft
 *   revision and generation vector. Partial or stale evidence fails closed.
 *
 * Serialization invariant:
 *
 * Every scope mutation locks its scope head. Clinic commands lock the tenant
 * head first and clinic head second even when only the clinic generation is
 * advanced. This prevents tenant/clinic lock inversion and makes interval
 * overlap, retirement, activation, generation, command result, and outbox
 * publication one atomic decision.
 *
 * @param policyRepository Caller-transaction definition/approval/head store.
 * @param jobRepository Caller-transaction activation-command store and keyed
 * idempotency hasher.
 * @param tenantBoundaryVerifier Rechecks the numeric tenant ID against trusted
 * request tenant resolution; it must fail closed outside request context.
 * @param previewVerifier Local, fail-closed verifier for completed preview
 * evidence. It must not perform remote I/O while scope locks are held.
 * @param publisher Caller-transaction redacted outbox publisher.
 * @param clock UTC business clock; tests inject a fixed clock.
 * @param activationLease Positive live-owner interval used only inside an
 * immediate activation transaction. The default is 30 seconds.
 */
class SchedulingPolicyCommandService(
    private val policyRepository: SchedulingPolicyRepository,
    private val jobRepository: SchedulingPolicyJobRepository,
    private val tenantBoundaryVerifier: PolicyTenantBoundaryVerifier,
    private val previewVerifier: PolicyPreviewEvidenceVerifier,
    private val publisher: PolicyActivationPublisher,
    private val clock: Clock = Clock.systemUTC(),
    private val activationLease: Duration = Duration.ofSeconds(30),
) {
    init {
        require(!activationLease.isNegative && !activationLease.isZero) {
            "activationLease must be positive"
        }
    }

    /**
     * Creates a new policy version at revision `1` and lifecycle `DRAFT`.
     *
     * Version allocation and the administrative head increment occur under the
     * same scope lock. The effective generation remains unchanged because a
     * draft cannot affect scheduling decisions.
     *
     * @param command Canonical payload, trusted scope/actor, and expected
     * non-negative scope revision.
     * @return Persisted draft plus the post-create scope head.
     * @throws SchedulingPolicyApiException for authority or stale-head failure.
     */
    fun createDraft(command: CreateSchedulingPolicyDraftCommand): SchedulingPolicyMutationResult {
        authorizeHumanWrite(command.actor, command.scope)
        validateExpectedRevision(command.expectedScopeRevision)
        return mapHeadConflict {
            transaction {
                val head = policyRepository.lockScopeHead(command.scope)
                requireHeadRevision(head, command.expectedScopeRevision)
                val definition = policyRepository.createDefinition(
                    SchedulingPolicyDefinitionRecord(
                        tenantGroupId = command.scope.tenantGroupId,
                        scope = command.scope.scope,
                        clinicId = command.scope.clinicId,
                        kind = command.kind,
                        version = policyRepository.nextDefinitionVersion(command.scope, command.kind),
                        schemaVersion = command.schemaVersion,
                        lifecycle = PolicyLifecycle.DRAFT,
                        effectiveFrom = command.effectiveFrom,
                        effectiveUntil = command.effectiveUntil,
                        revision = 1L,
                        payloadHash = command.payloadHash,
                        payloadJson = command.payloadJson,
                        createdByActorId = command.actor.actorId,
                        createdByActorRole = command.actor.auditRole(),
                        changeReason = command.changeReason,
                    )
                )
                SchedulingPolicyMutationResult(
                    definition,
                    policyRepository.compareAndIncrementRevision(command.scope, head.revision),
                )
            }
        }
    }

    /**
     * Revises editable draft data and invalidates old approvals/previews by revision.
     *
     * Evidence rows are retained for audit; no destructive cleanup is performed.
     * The successful definition revision is exactly
     * `command.expectedDraftRevision + 1`, while effective generation is
     * unchanged.
     *
     * @return Persisted revised draft and post-edit administrative head.
     */
    fun reviseDraft(command: ReviseSchedulingPolicyDraftCommand): SchedulingPolicyMutationResult {
        authorizeHumanWrite(command.actor, command.scope)
        validateExpectedRevision(command.expectedScopeRevision)
        return mapHeadConflict {
            transaction {
                val head = policyRepository.lockScopeHead(command.scope)
                requireHeadRevision(head, command.expectedScopeRevision)
                val current = requireDefinition(command.scope, command.definitionId)
                if (current.lifecycle != PolicyLifecycle.DRAFT ||
                    current.revision != command.expectedDraftRevision
                ) {
                    reject(
                        SchedulingPolicyErrorCode.POLICY_DRAFT_STALE,
                        "The policy draft revision is no longer editable.",
                    )
                }
                val revised = policyRepository.compareAndReviseDraft(
                    definitionId = command.definitionId,
                    expectedRevision = command.expectedDraftRevision,
                    schemaVersion = command.schemaVersion,
                    effectiveFrom = command.effectiveFrom,
                    effectiveUntil = command.effectiveUntil,
                    payloadHash = command.payloadHash,
                    payloadJson = command.payloadJson,
                    changeReason = command.changeReason,
                ) ?: reject(
                    SchedulingPolicyErrorCode.POLICY_DRAFT_STALE,
                    "The policy draft changed before the revision was saved.",
                )
                SchedulingPolicyMutationResult(
                    revised,
                    policyRepository.compareAndIncrementRevision(command.scope, head.revision),
                )
            }
        }
    }

    /**
     * Appends idempotent human approval for an exact draft revision.
     *
     * High-impact policy kinds require MFA and forbid the draft creator from
     * approving their own revision. A repeated approval by the same actor
     * returns the existing evidence rather than creating a second vote.
     *
     * @return Existing or newly appended approval for this actor and revision.
     * @throws SchedulingPolicyApiException when scope, role, revision, MFA, or
     * creator-separation rules are not satisfied.
     */
    fun approve(command: ApproveSchedulingPolicyCommand): SchedulingPolicyApprovalRecord {
        authorizeHumanWrite(command.actor, command.scope)
        return transaction {
            val definition = requireDefinition(command.scope, command.definitionId)
            if (definition.lifecycle != PolicyLifecycle.DRAFT ||
                definition.revision != command.expectedDraftRevision
            ) {
                reject(
                    SchedulingPolicyErrorCode.POLICY_DRAFT_STALE,
                    "Approval must target the current editable draft revision.",
                )
            }
            if (definition.kind.isSensitive()) {
                if (command.actor.assurance != AuthenticationAssurance.MFA ||
                    command.actor.actorId == definition.createdByActorId
                ) {
                    reject(
                        SchedulingPolicyErrorCode.POLICY_APPROVAL_INSUFFICIENT,
                        "This policy requires MFA approval by an actor other than its creator.",
                    )
                }
            }
            val definitionId = requireNotNull(definition.id)
            policyRepository.findApprovals(definitionId, definition.revision)
                .firstOrNull { it.actorId == command.actor.actorId }
                ?: policyRepository.addApproval(
                    SchedulingPolicyApprovalRecord(
                        definitionId = definitionId,
                        draftRevision = definition.revision,
                        actorId = command.actor.actorId,
                        actorRole = command.actor.auditRole(),
                        assuranceLevel = command.actor.assurance.name,
                        approvedAt = Instant.now(clock),
                    )
                )
        }
    }

    /**
     * Moves a draft to `SCHEDULED` and creates its deterministic pending command.
     *
     * The service derives its raw HMAC input from durable
     * `(definitionId, version, effectiveFrom)` values. A runner can therefore
     * execute by command ID without knowing a human request key. On an
     * identical retry, the existing scoped command is returned. Scheduling
     * advances only administrative revision; generation changes later when the
     * runner activates successfully.
     *
     * @return Durable `PENDING` activation command, or the same existing row on
     * a deterministic retry.
     */
    fun schedule(command: ScheduleSchedulingPolicyCommand): SchedulingPolicyActivationCommandRecord {
        authorizeHumanWrite(command.actor, command.scope)
        return try {
            mapHeadConflict {
                transaction {
                    val observedDefinition = requireDefinition(command.scope, command.definitionId)
                    val keyHash = hashKey(scheduledActivationKey(observedDefinition))
                    val fingerprint = activationFingerprint(
                        command.scope,
                        command.definitionId,
                        command.expectedDraftRevision,
                        command.expectedActiveRevision + 1L,
                        replayOfCommandId = null,
                    )
                    jobRepository.findActivation(command.scope, keyHash)?.let { existing ->
                        requireSameIntent(existing, fingerprint)
                        return@transaction existing
                    }
                    val heads = lockHeads(command.scope)
                    val scopeHead = heads.scopeHead(command.scope)
                    requireHeadRevision(scopeHead, command.expectedActiveRevision)
                    val definition = requireDefinition(command.scope, command.definitionId)
                    requireDraftRevision(definition, command.expectedDraftRevision, PolicyLifecycle.DRAFT)
                    val generation = heads.generation(command.scope)
                    verifyPreview(command.preview, definition, generation)
                    requireApprovals(definition, command.actor, activation = false)
                    val overlaps = policyRepository.findOverlappingPublishedDefinitions(
                        command.scope,
                        definition.kind,
                        definition.effectiveFrom,
                        definition.effectiveUntil,
                    )
                    if (overlaps.isNotEmpty()) {
                        reject(
                            SchedulingPolicyErrorCode.POLICY_ACTIVATION_CONFLICT,
                            "The scheduled interval overlaps an existing published policy.",
                        )
                    }
                    val scheduled = policyRepository.compareAndTransitionLifecycle(
                        definition.id!!,
                        definition.revision,
                        PolicyLifecycle.DRAFT,
                        PolicyLifecycle.SCHEDULED,
                    ) ?: reject(
                        SchedulingPolicyErrorCode.POLICY_DRAFT_STALE,
                        "The draft changed before it could be scheduled.",
                    )
                    val nextHead = policyRepository.compareAndIncrementRevision(command.scope, scopeHead.revision)
                    jobRepository.createActivation(
                        SchedulingPolicyActivationCommandRecord(
                            tenantGroupId = command.scope.tenantGroupId,
                            scope = command.scope.scope,
                            clinicId = command.scope.clinicId,
                            definitionId = scheduled.id!!,
                            expectedDraftRevision = scheduled.revision,
                            expectedActiveRevision = nextHead.revision,
                            idempotencyKeyHash = keyHash,
                            requestFingerprint = fingerprint,
                            effectiveFrom = scheduled.effectiveFrom,
                            nextAttemptAt = scheduled.effectiveFrom,
                        )
                    )
                }
            }
        } catch (ex: ExposedSQLException) {
            if (!ex.isPolicyIdempotencyUniqueViolation()) throw ex
            transaction {
                val definition = requireDefinition(command.scope, command.definitionId)
                val existing = jobRepository.findActivation(
                    command.scope,
                    hashKey(scheduledActivationKey(definition)),
                ) ?: throw ex
                requireSameIntent(
                    existing,
                    activationFingerprint(
                        command.scope,
                        command.definitionId,
                        command.expectedDraftRevision,
                        command.expectedActiveRevision + 1L,
                        replayOfCommandId = null,
                    ),
                )
                existing
            }
        }
    }

    /**
     * Activates a draft, scheduled command, or explicit replay atomically.
     *
     * The outbox publisher currently returns the deterministic event ID, so the
     * insert is performed immediately before durable command completion. Both
     * writes remain in the same transaction: either both commit with lifecycle
     * and generations, or neither is visible. Human immediate/replay commands
     * require a raw idempotency key; scheduled service execution requires a
     * command ID and forbids a raw key. Clinic activation additionally requires
     * a positive tenant generation.
     *
     * @return Newly committed activation or a stored completed idempotent result.
     * @throws SchedulingPolicyApiException for stale preview/approval/head,
     * overlap, authority, lease, missed-replay, or idempotency intent conflicts.
     */
    fun activate(command: ActivateSchedulingPolicyCommand): SchedulingPolicyActivationResult {
        authorizeActivation(command.actor, command.scope, command.scheduledCommandId)
        val keyHash =
            if (command.scheduledCommandId == null) {
                hashKey(command.idempotencyKey ?: reject(
                    SchedulingPolicyErrorCode.POLICY_PAYLOAD_INVALID,
                    "An immediate or replay activation requires an idempotency key.",
                ))
            } else {
                if (command.idempotencyKey != null) {
                    reject(
                        SchedulingPolicyErrorCode.POLICY_PAYLOAD_INVALID,
                        "A scheduled activation is identified by command ID, not a raw idempotency key.",
                    )
                }
                null
            }
        val fingerprint = activationFingerprint(
            command.scope,
            command.definitionId,
            command.expectedDraftRevision,
            command.expectedActiveRevision,
            command.replayOfCommandId,
        )
        val now = Instant.now(clock)
        return try {
            mapHeadConflict {
                transaction {
                    val durable = resolveActivationCommand(command, keyHash, fingerprint, now)
                    if (durable.status == PolicyActivationCommandStatus.COMPLETED) {
                        return@transaction completedResult(durable, idempotentReplay = true)
                    }
                    val commandId = durable.id!!
                    val owner = "policy-activation-$commandId"
                    if (!jobRepository.claimDueActivation(commandId, owner, now, now.plus(activationLease))) {
                        reject(
                            SchedulingPolicyErrorCode.POLICY_ACTIVATION_CONFLICT,
                            "The activation command is not eligible for this worker.",
                        )
                    }
                    val heads = lockHeads(command.scope)
                    val scopeHead = heads.scopeHead(command.scope)
                    requireHeadRevision(scopeHead, command.expectedActiveRevision)
                    val definition = requireDefinition(command.scope, command.definitionId)
                    val expectedLifecycle =
                        if (command.scheduledCommandId != null || command.replayOfCommandId != null) {
                            PolicyLifecycle.SCHEDULED
                        } else {
                            PolicyLifecycle.DRAFT
                        }
                    requireDraftRevision(definition, command.expectedDraftRevision, expectedLifecycle)
                    val currentGeneration = heads.generation(command.scope)
                    verifyPreview(command.preview, definition, currentGeneration)
                    requireApprovals(definition, command.actor, activation = true)
                    if (command.actor.actorType == ActorType.SYSTEM &&
                        (durable.status != PolicyActivationCommandStatus.PENDING &&
                            durable.status != PolicyActivationCommandStatus.CLAIMED)
                    ) {
                        reject(
                            SchedulingPolicyErrorCode.POLICY_ACTOR_FORBIDDEN,
                            "A service actor requires pending scheduled-command evidence.",
                        )
                    }
                    retireOverlappingActive(command.scope, definition)
                    val active = policyRepository.compareAndTransitionLifecycle(
                        definition.id!!,
                        definition.revision,
                        expectedLifecycle,
                        PolicyLifecycle.ACTIVE,
                    ) ?: reject(
                        SchedulingPolicyErrorCode.POLICY_ACTIVATION_CONFLICT,
                        "The policy lifecycle changed before activation.",
                    )
                    val changedHead =
                        policyRepository.compareAndIncrementGeneration(command.scope, scopeHead.revision)
                    val generation = heads.generationAfter(command.scope, changedHead)
                    val eventId = publisher.publish(
                        active,
                        generation,
                        ActorAuditRef(command.actor.actorId, command.actor.auditRole()),
                        command.actor.correlationId,
                    )
                    if (!jobRepository.completeActivation(commandId, owner, generation, eventId, now)) {
                        reject(
                            SchedulingPolicyErrorCode.POLICY_ACTIVATION_CONFLICT,
                            "The activation lease expired before command completion.",
                        )
                    }
                    SchedulingPolicyActivationResult(
                        command = requireNotNull(jobRepository.findActivation(commandId)),
                        definition = active,
                        generation = generation,
                        idempotentReplay = false,
                    )
                }
            }
        } catch (ex: ExposedSQLException) {
            if (keyHash == null || !ex.isPolicyIdempotencyUniqueViolation()) throw ex
            transaction {
                val existing = jobRepository.findActivation(command.scope, keyHash) ?: throw ex
                requireSameIntent(existing, fingerprint)
                if (existing.status != PolicyActivationCommandStatus.COMPLETED) {
                    reject(
                        SchedulingPolicyErrorCode.POLICY_ACTIVATION_CONFLICT,
                        "A concurrent activation with the same key is still in progress.",
                    )
                }
                completedResult(existing, idempotentReplay = true)
            }
        }
    }

    /**
     * Retires history without deleting definition, approval, command, or event rows.
     *
     * Retiring `ACTIVE` advances revision and generation because effective
     * behavior changed. Retiring `DRAFT` or `SCHEDULED` advances revision only.
     * The method never rewrites a `MISSED` or `COMPLETED` activation command.
     *
     * @return Retired immutable definition and post-retirement scope head.
     */
    fun retire(command: RetireSchedulingPolicyCommand): SchedulingPolicyMutationResult {
        authorizeHumanWrite(command.actor, command.scope)
        return mapHeadConflict {
            transaction {
                val heads = lockHeads(command.scope)
                val scopeHead = heads.scopeHead(command.scope)
                requireHeadRevision(scopeHead, command.expectedScopeRevision)
                val definition = requireDefinition(command.scope, command.definitionId)
                if (definition.revision != command.expectedDraftRevision ||
                    definition.lifecycle == PolicyLifecycle.RETIRED
                ) {
                    reject(
                        SchedulingPolicyErrorCode.POLICY_DRAFT_STALE,
                        "The policy revision or lifecycle changed before retirement.",
                    )
                }
                val retired = policyRepository.compareAndTransitionLifecycle(
                    definition.id!!,
                    definition.revision,
                    definition.lifecycle,
                    PolicyLifecycle.RETIRED,
                ) ?: reject(
                    SchedulingPolicyErrorCode.POLICY_ACTIVATION_CONFLICT,
                    "The policy lifecycle changed before retirement.",
                )
                val head =
                    if (definition.lifecycle == PolicyLifecycle.ACTIVE) {
                        policyRepository.compareAndIncrementGeneration(command.scope, scopeHead.revision)
                    } else {
                        policyRepository.compareAndIncrementRevision(command.scope, scopeHead.revision)
                    }
                SchedulingPolicyMutationResult(retired, head)
            }
        }
    }

    private fun resolveActivationCommand(
        command: ActivateSchedulingPolicyCommand,
        keyHash: String?,
        fingerprint: String,
        now: Instant,
    ): SchedulingPolicyActivationCommandRecord {
        val existing =
            command.scheduledCommandId?.let(jobRepository::findActivation)
                ?: keyHash?.let { jobRepository.findActivation(command.scope, it) }
        if (existing != null) {
            if (existing.tenantGroupId != command.scope.tenantGroupId ||
                existing.scope != command.scope.scope ||
                existing.clinicScopeKey != command.scope.clinicScopeKey
            ) {
                reject(
                    SchedulingPolicyErrorCode.POLICY_RESOURCE_NOT_FOUND,
                    "The scheduled activation command was not found in this scope.",
                )
            }
            requireSameIntent(existing, fingerprint)
            if (command.scheduledCommandId != null && existing.id != command.scheduledCommandId) {
                reject(
                    SchedulingPolicyErrorCode.POLICY_IDEMPOTENCY_CONFLICT,
                    "The idempotency key belongs to a different scheduled command.",
                )
            }
            return existing
        }
        if (command.scheduledCommandId != null) {
            reject(
                SchedulingPolicyErrorCode.POLICY_RESOURCE_NOT_FOUND,
                "The scheduled activation command was not found in this scope.",
            )
        }
        val definition = requireDefinition(command.scope, command.definitionId)
        val expectedLifecycle =
            if (command.replayOfCommandId == null) PolicyLifecycle.DRAFT else PolicyLifecycle.SCHEDULED
        requireDraftRevision(definition, command.expectedDraftRevision, expectedLifecycle)
        if (definition.effectiveFrom > now) {
            reject(
                SchedulingPolicyErrorCode.POLICY_ACTIVATION_CONFLICT,
                "A future effective boundary must be scheduled before activation.",
            )
        }
        return try {
            jobRepository.createActivation(
                SchedulingPolicyActivationCommandRecord(
                    tenantGroupId = command.scope.tenantGroupId,
                    scope = command.scope.scope,
                    clinicId = command.scope.clinicId,
                    definitionId = command.definitionId,
                    replayOfCommandId = command.replayOfCommandId,
                    expectedDraftRevision = command.expectedDraftRevision,
                    expectedActiveRevision = command.expectedActiveRevision,
                    idempotencyKeyHash = requireNotNull(keyHash),
                    requestFingerprint = fingerprint,
                    effectiveFrom = definition.effectiveFrom,
                    nextAttemptAt = now,
                )
            )
        } catch (_: IllegalArgumentException) {
            reject(
                if (command.replayOfCommandId == null) {
                    SchedulingPolicyErrorCode.POLICY_PAYLOAD_INVALID
                } else {
                    SchedulingPolicyErrorCode.POLICY_ACTIVATION_MISSED
                },
                "The activation command or replay source is invalid.",
            )
        }
    }

    private fun completedResult(
        command: SchedulingPolicyActivationCommandRecord,
        idempotentReplay: Boolean,
    ): SchedulingPolicyActivationResult {
        val definition = requireDefinition(
            PolicyScopeRef(command.tenantGroupId, command.scope, command.clinicId),
            command.definitionId,
        )
        val generation = PolicyGenerationVector(
            tenantGeneration = requireNotNull(command.resultTenantGeneration),
            clinicGeneration = requireNotNull(command.resultClinicGeneration),
        )
        return SchedulingPolicyActivationResult(command, definition, generation, idempotentReplay)
    }

    private fun retireOverlappingActive(
        scope: PolicyScopeRef,
        target: SchedulingPolicyDefinitionRecord,
    ) {
        policyRepository.findOverlappingPublishedDefinitions(
            scope,
            target.kind,
            target.effectiveFrom,
            target.effectiveUntil,
        ).filter { it.id != target.id }.forEach { overlap ->
            when (overlap.lifecycle) {
                PolicyLifecycle.ACTIVE ->
                    policyRepository.compareAndTransitionLifecycle(
                        overlap.id!!,
                        overlap.revision,
                        PolicyLifecycle.ACTIVE,
                        PolicyLifecycle.RETIRED,
                    ) ?: reject(
                        SchedulingPolicyErrorCode.POLICY_ACTIVATION_CONFLICT,
                        "An overlapping active policy changed before replacement.",
                    )
                PolicyLifecycle.SCHEDULED -> reject(
                    SchedulingPolicyErrorCode.POLICY_ACTIVATION_CONFLICT,
                    "Another scheduled policy overlaps the activation interval.",
                )
                else -> Unit
            }
        }
    }

    private fun requireApprovals(
        definition: SchedulingPolicyDefinitionRecord,
        actor: ActorContext,
        activation: Boolean,
    ) {
        val approvals = policyRepository.findApprovals(definition.id!!, definition.revision)
        val required = if (definition.kind.isSensitive()) 2 else 1
        val eligible = approvals
            .filter { it.actorRole == ActorRole.ADMIN || it.actorRole == ActorRole.STAFF }
            .filter { !definition.kind.isSensitive() || it.assuranceLevel == AuthenticationAssurance.MFA.name }
            .distinctBy { it.actorId }
        val separated =
            !activation ||
                !definition.kind.isSensitive() ||
                (actor.actorId != definition.createdByActorId &&
                    eligible.none { it.actorId == actor.actorId })
        if (eligible.size < required || !separated) {
            reject(
                SchedulingPolicyErrorCode.POLICY_APPROVAL_INSUFFICIENT,
                "The policy does not have sufficient distinct approval and activation authority.",
            )
        }
    }

    private fun verifyPreview(
        evidence: PolicyPreviewEvidence,
        definition: SchedulingPolicyDefinitionRecord,
        generation: PolicyGenerationVector,
    ) {
        val structurallyCurrent =
            evidence.definitionId == definition.id &&
                evidence.draftRevision == definition.revision &&
                evidence.tenantGeneration == generation.tenantGeneration &&
                evidence.clinicGeneration == generation.clinicGeneration &&
                evidence.evidenceId.isNotBlank() &&
                evidence.evidenceId.length <= 160
        if (!structurallyCurrent || !previewVerifier.verify(evidence, definition, generation)) {
            reject(
                SchedulingPolicyErrorCode.POLICY_PREVIEW_STALE,
                "The impact preview does not match the current draft and generations.",
            )
        }
    }

    private fun requireDefinition(
        scope: PolicyScopeRef,
        definitionId: Long,
    ): SchedulingPolicyDefinitionRecord {
        val definition = definitionId.takeIf { it > 0 }?.let(policyRepository::findDefinition)
        if (definition == null ||
            definition.tenantGroupId != scope.tenantGroupId ||
            definition.scope != scope.scope ||
            definition.clinicScopeKey != scope.clinicScopeKey
        ) {
            reject(
                SchedulingPolicyErrorCode.POLICY_RESOURCE_NOT_FOUND,
                "The policy resource was not found in the requested scope.",
            )
        }
        return definition
    }

    private fun requireDraftRevision(
        definition: SchedulingPolicyDefinitionRecord,
        expectedRevision: Long,
        expectedLifecycle: PolicyLifecycle,
    ) {
        if (definition.revision != expectedRevision || definition.lifecycle != expectedLifecycle) {
            reject(
                SchedulingPolicyErrorCode.POLICY_DRAFT_STALE,
                "The policy revision or lifecycle no longer matches the command.",
            )
        }
    }

    private fun authorizeHumanWrite(actor: ActorContext, scope: PolicyScopeRef) {
        authorizeScope(actor, scope)
        if (actor.actorType != ActorType.ADMIN && actor.actorType != ActorType.STAFF) {
            reject(
                SchedulingPolicyErrorCode.POLICY_ACTOR_FORBIDDEN,
                "Only an administrator or staff actor may manage policy definitions.",
            )
        }
        if (POLICY_WRITE_SCOPE !in actor.scopes) {
            reject(
                SchedulingPolicyErrorCode.POLICY_ACTOR_FORBIDDEN,
                "The actor does not have policy write authority.",
            )
        }
    }

    private fun authorizeActivation(
        actor: ActorContext,
        scope: PolicyScopeRef,
        scheduledCommandId: Long?,
    ) {
        authorizeScope(actor, scope)
        when (actor.actorType) {
            ActorType.ADMIN, ActorType.STAFF ->
                if (POLICY_WRITE_SCOPE !in actor.scopes) {
                    reject(
                        SchedulingPolicyErrorCode.POLICY_ACTOR_FORBIDDEN,
                        "The human actor does not have policy write authority.",
                    )
                }
            ActorType.SYSTEM ->
                if (actor.assurance != AuthenticationAssurance.SERVICE ||
                    scheduledCommandId == null ||
                    POLICY_SCHEDULED_ACTIVATION_SCOPE !in actor.scopes
                ) {
                    reject(
                        SchedulingPolicyErrorCode.POLICY_ACTOR_FORBIDDEN,
                        "A service actor requires scheduled-activation scope, service assurance, and command evidence.",
                    )
                }
            else -> reject(
                SchedulingPolicyErrorCode.POLICY_ACTOR_FORBIDDEN,
                "The actor type cannot activate scheduling policy.",
            )
        }
    }

    private fun authorizeScope(actor: ActorContext, scope: PolicyScopeRef) {
        if (!tenantBoundaryVerifier.isAuthorized(scope, actor)) {
            reject(
                SchedulingPolicyErrorCode.POLICY_ACTOR_FORBIDDEN,
                "The actor is not authorized for the requested tenant.",
            )
        }
        if (scope.scope == PolicyScope.CLINIC_OVERRIDE &&
            scope.clinicId !in actor.allowedClinicIds
        ) {
            reject(
                SchedulingPolicyErrorCode.POLICY_ACTOR_FORBIDDEN,
                "The actor is not authorized for the requested clinic.",
            )
        }
        actor.auditRole()
    }

    private fun ActorContext.auditRole(): ActorRole {
        val role = when (actorType) {
            ActorType.ADMIN -> ActorRole.ADMIN
            ActorType.STAFF -> ActorRole.STAFF
            ActorType.DOCTOR -> ActorRole.DOCTOR
            ActorType.PATIENT -> ActorRole.PATIENT
            ActorType.SYSTEM -> ActorRole.SYSTEM
        }
        if (role !in roles) {
            reject(
                SchedulingPolicyErrorCode.POLICY_ACTOR_FORBIDDEN,
                "The actor type and role evidence are inconsistent.",
            )
        }
        return role
    }

    private fun lockHeads(scope: PolicyScopeRef): LockedHeads {
        val tenant = PolicyScopeRef(scope.tenantGroupId, PolicyScope.TENANT_DEFAULT)
        val locked = policyRepository.lockScopeHeads(tenant, scope)
        val tenantHead = locked.first { it.scope == PolicyScope.TENANT_DEFAULT }
        val scopeHead = locked.first {
            it.scope == scope.scope && it.clinicScopeKey == scope.clinicScopeKey
        }
        return LockedHeads(tenantHead, scopeHead)
    }

    private fun LockedHeads.generation(scope: PolicyScopeRef): PolicyGenerationVector {
        if (scope.scope == PolicyScope.CLINIC_OVERRIDE && tenantHead.generation <= 0) {
            reject(
                SchedulingPolicyErrorCode.POLICY_ACTIVATION_CONFLICT,
                "A clinic override requires an active tenant policy baseline.",
            )
        }
        return PolicyGenerationVector(
            tenantGeneration = tenantHead.generation,
            clinicGeneration = if (scope.scope == PolicyScope.CLINIC_OVERRIDE) scopeHead.generation else 0L,
        )
    }

    private fun LockedHeads.generationAfter(
        scope: PolicyScopeRef,
        changedHead: SchedulingPolicyScopeHeadRecord,
    ): PolicyGenerationVector =
        if (scope.scope == PolicyScope.TENANT_DEFAULT) {
            PolicyGenerationVector(changedHead.generation, 0L)
        } else {
            if (tenantHead.generation <= 0) {
                reject(
                    SchedulingPolicyErrorCode.POLICY_ACTIVATION_CONFLICT,
                    "A clinic override requires an active tenant policy baseline.",
                )
            }
            PolicyGenerationVector(tenantHead.generation, changedHead.generation)
        }

    private fun LockedHeads.scopeHead(scope: PolicyScopeRef): SchedulingPolicyScopeHeadRecord =
        if (scope.scope == PolicyScope.TENANT_DEFAULT) tenantHead else scopeHead

    private fun requireHeadRevision(head: SchedulingPolicyScopeHeadRecord, expected: Long) {
        validateExpectedRevision(expected)
        if (head.revision != expected) {
            reject(
                SchedulingPolicyErrorCode.POLICY_ACTIVATION_CONFLICT,
                "The policy scope head revision changed.",
            )
        }
    }

    private fun requireSameIntent(
        existing: SchedulingPolicyActivationCommandRecord,
        fingerprint: String,
    ) {
        if (existing.requestFingerprint != fingerprint) {
            reject(
                SchedulingPolicyErrorCode.POLICY_IDEMPOTENCY_CONFLICT,
                "The idempotency key was already used for a different policy command.",
            )
        }
    }

    private fun hashKey(rawKey: String): String =
        try {
            jobRepository.hashIdempotencyKey(rawKey)
        } catch (_: IllegalArgumentException) {
            reject(
                SchedulingPolicyErrorCode.POLICY_PAYLOAD_INVALID,
                "The idempotency key format is invalid.",
            )
        }

    private fun activationFingerprint(
        scope: PolicyScopeRef,
        definitionId: Long,
        draftRevision: Long,
        activeRevision: Long,
        replayOfCommandId: Long?,
    ): String {
        val canonical = listOf(
            "activate-policy-v1",
            scope.tenantGroupId.toString(),
            scope.scope.name,
            scope.clinicScopeKey.toString(),
            definitionId.toString(),
            draftRevision.toString(),
            activeRevision.toString(),
            replayOfCommandId?.toString() ?: "-",
        ).joinToString("|")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private inline fun <T> mapHeadConflict(block: () -> T): T =
        try {
            block()
        } catch (_: PolicyScopeHeadConflictException) {
            reject(
                SchedulingPolicyErrorCode.POLICY_ACTIVATION_CONFLICT,
                "The policy scope head changed before the command committed.",
            )
        }

    private fun validateExpectedRevision(value: Long) {
        if (value < 0) {
            reject(
                SchedulingPolicyErrorCode.POLICY_PAYLOAD_INVALID,
                "Expected scope revision must be non-negative.",
            )
        }
    }

    private fun SchedulingPolicyKind.isSensitive(): Boolean =
        when (this) {
            SchedulingPolicyKind.CAPACITY_AND_OVERBOOKING,
            SchedulingPolicyKind.PRIORITY_AND_RELIABILITY,
            SchedulingPolicyKind.DISRUPTION_RECOVERY,
            SchedulingPolicyKind.OPERATING_EXTENSION,
            -> true

            SchedulingPolicyKind.BOOKING_COMMITMENT,
            SchedulingPolicyKind.HOLD_AND_CONSENT,
            SchedulingPolicyKind.RECONFIRMATION,
            SchedulingPolicyKind.NOTIFICATION_AND_SLA,
            -> false
        }

    private fun scheduledActivationKey(definition: SchedulingPolicyDefinitionRecord): String =
        "scheduled:${definition.id}:${definition.version}:${definition.effectiveFrom.toEpochMilli()}"

    private fun ExposedSQLException.isPolicyIdempotencyUniqueViolation(): Boolean =
        sqlState in UNIQUE_VIOLATION_SQL_STATES &&
            message.orEmpty().contains(POLICY_IDEMPOTENCY_CONSTRAINT, ignoreCase = true)

    private data class LockedHeads(
        val tenantHead: SchedulingPolicyScopeHeadRecord,
        val scopeHead: SchedulingPolicyScopeHeadRecord,
    )

    private companion object {
        const val POLICY_WRITE_SCOPE = "policy:write"
        const val POLICY_SCHEDULED_ACTIVATION_SCOPE = "policy:scheduled-activation"
        const val POLICY_IDEMPOTENCY_CONSTRAINT = "uq_policy_activation_idempotency"
        val UNIQUE_VIOLATION_SQL_STATES = setOf("23000", "23505")

        /**
         * Sensitivity is deliberately classified by the exhaustive `when` in
         * [isSensitive], so adding an enum value cannot silently inherit weak
         * approval. Capacity, priority, disruption recovery, and operating
         * extension can change promises or safety margins and require dual MFA
         * approval. Booking commitment, hold/consent, reconfirmation, and
         * notification/SLA remain standard only because this foundation limits
         * them to workflow/evidence timing; a schema that lets one weaken a
         * safety ceiling must reclassify the kind or carry versioned approval
         * policy before activation.
         *
         * This comment is a security decision record, not permission for future
         * kinds to default to standard sensitivity.
         */
    }
}

private fun reject(
    code: SchedulingPolicyErrorCode,
    detail: String,
): Nothing = throw SchedulingPolicyApiException(code, detail)
