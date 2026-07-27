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
 * 스케줄링 정책 관리를 담당하는 transactional application service이다.
 *
 * lifecycle 변경, 승인 증적, scope-head counter, durable activation command,
 * activation outbox event를 하나의 업무 결정으로 묶을 수 있는 유일한 계층이다.
 * repository는 여기서 열린 Exposed transaction을 의도적으로 상속한다. 따라서 검증,
 * CAS, command completion, [publisher]에서 예외가 발생하면 전체 업무 결정이 함께 rollback된다.
 *
 * 신뢰 및 privacy 경계:
 *
 * - [ActorContext]는 검증된 Gateway JWT와 path-scoped `ActorContextResolver`에서 와야 한다.
 *   request payload가 identity 또는 scope를 공급할 수 없다.
 * - typed payload validation과 canonical hashing은 draft command가 이 service에 도달하기 전에 끝난다.
 * - raw idempotency key는 transaction을 열기 전에 검증되고 HMAC hash로 변환된다.
 *   저장되는 값은 digest와 payload-free intent fingerprint뿐이다.
 * - preview evidence는 정확한 draft revision 및 generation vector를 기준으로 [previewVerifier]가 검증한다.
 *   partial 또는 stale evidence는 fail-closed로 거절된다.
 *
 * 직렬화 불변식:
 *
 * 모든 scope mutation은 해당 scope head를 잠근다. clinic command는 clinic generation만
 * 증가시키는 경우에도 tenant head를 먼저, clinic head를 두 번째로 잠근다. 이렇게 해야
 * tenant/clinic lock inversion을 피하고 interval overlap, retirement, activation,
 * generation, command result, outbox publication을 하나의 원자적 결정으로 만들 수 있다.
 *
 * @param policyRepository caller transaction을 사용하는 definition/approval/head 저장소.
 * @param jobRepository caller transaction을 사용하는 activation-command 저장소와 keyed idempotency hasher.
 * @param tenantBoundaryVerifier 숫자 tenant ID를 신뢰된 request tenant resolution과 다시 비교한다.
 * request context 밖에서는 fail-closed여야 한다.
 * @param previewVerifier completed preview evidence를 검증하는 local fail-closed verifier.
 * scope lock을 잡은 동안 remote I/O를 수행하면 안 된다.
 * @param publisher caller transaction을 사용하는 redacted outbox publisher.
 * @param clock UTC business clock. 테스트에서는 fixed clock을 주입한다.
 * @param activationLease immediate activation transaction 안에서만 사용하는 양수 live-owner interval.
 * 기본값은 30초이다.
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
     * revision `1`, lifecycle `DRAFT`인 새 정책 버전을 생성한다.
     *
     * version 할당과 administrative head 증가는 같은 scope lock 아래에서 일어난다.
     * draft는 스케줄링 결정에 영향을 줄 수 없으므로 effective generation은 변경하지 않는다.
     *
     * @param command canonical payload, 신뢰된 scope/actor, 기대하는 non-negative scope revision.
     * @return 영속화된 draft와 생성 이후 scope head.
     * @throws SchedulingPolicyApiException 권한 또는 stale head 검증에 실패한 경우.
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
     * 편집 가능한 draft 데이터를 수정하고 기존 approval/preview를 revision 기준으로 무효화한다.
     *
     * evidence row는 감사 목적으로 보존하며 destructive cleanup은 수행하지 않는다.
     * 성공한 definition revision은 정확히 `command.expectedDraftRevision + 1`이고,
     * effective generation은 변경하지 않는다.
     *
     * @return 영속화된 revised draft와 edit 이후 administrative head.
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
     * 정확한 draft revision에 대한 human approval을 idempotent하게 추가한다.
     *
     * 영향도가 큰 policy kind는 MFA를 요구하고 draft 생성자가 자기 revision을 직접 승인하는
     * 것을 금지한다. 같은 actor의 반복 승인은 두 번째 vote를 만들지 않고 기존 evidence를 반환한다.
     *
     * @return 이 actor/revision에 대한 기존 또는 신규 approval.
     * @throws SchedulingPolicyApiException scope, role, revision, MFA, creator-separation
     * 규칙을 만족하지 못한 경우.
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
     * draft를 `SCHEDULED`로 이동시키고 deterministic pending command를 생성한다.
     *
     * service는 durable `(definitionId, version, effectiveFrom)` 값에서 raw HMAC input을 만든다.
     * 따라서 runner는 human request key를 몰라도 command ID로 실행할 수 있다.
     * 동일 retry에서는 기존 scoped command를 반환한다. scheduling은 administrative revision만
     * 증가시키며, generation은 이후 runner가 성공적으로 activate할 때 변경된다.
     *
     * @return durable `PENDING` activation command. deterministic retry이면 같은 기존 row.
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
     * draft, scheduled command, explicit replay 중 하나를 원자적으로 activate한다.
     *
     * outbox publisher는 현재 deterministic event ID를 반환하므로, durable command completion
     * 직전에 insert를 수행한다. 두 write는 같은 transaction 안에 남는다. 즉 lifecycle과
     * generation이 함께 commit되거나, 둘 다 보이지 않아야 한다. human immediate/replay command는
     * raw idempotency key를 요구하고, scheduled service execution은 command ID를 요구하며 raw key를
     * 금지한다. clinic activation은 추가로 양수 tenant generation을 요구한다.
     *
     * @return 새로 commit된 activation 또는 저장되어 있던 completed idempotent result.
     * @throws SchedulingPolicyApiException stale preview/approval/head, interval overlap,
     * authority, lease, missed-replay, idempotency intent conflict가 발생한 경우.
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
     * definition, approval, command, event row를 삭제하지 않고 정책 이력을 retire한다.
     *
     * `ACTIVE`를 retire하면 effective behavior가 바뀌므로 revision과 generation을 모두
     * 증가시킨다. `DRAFT` 또는 `SCHEDULED`를 retire하면 revision만 증가시킨다.
     * 이 메서드는 `MISSED` 또는 `COMPLETED` activation command를 절대 다시 쓰지 않는다.
     *
     * @return retire된 불변 definition과 retirement 이후 scope head.
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
         * sensitivity는 [isSensitive]의 exhaustive `when`에서 의도적으로 분류한다.
         * 그래야 enum 값이 추가될 때 약한 approval을 조용히 상속하지 못한다.
         * capacity, priority, disruption recovery, operating extension은 약속이나 안전 여유를
         * 바꿀 수 있으므로 dual MFA approval이 필요하다. booking commitment, hold/consent,
         * reconfirmation, notification/SLA가 standard로 남는 이유는 이 foundation에서 이들을
         * workflow/evidence timing으로 제한하기 때문이다. 안전 상한을 약화할 수 있는 schema가
         * 추가되면 activation 전에 해당 kind를 재분류하거나 versioned approval policy를 가져야 한다.
         *
         * 이 주석은 security decision record이며, 향후 kind가 standard sensitivity를 기본값으로
         * 사용해도 된다는 허가가 아니다.
         */
    }
}

private fun reject(
    code: SchedulingPolicyErrorCode,
    detail: String,
): Nothing = throw SchedulingPolicyApiException(code, detail)
