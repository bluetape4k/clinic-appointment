package io.bluetape4k.clinic.appointment.api.policy

import io.bluetape4k.clinic.appointment.api.config.SchedulingPolicyApiException
import io.bluetape4k.clinic.appointment.api.config.SchedulingPolicyErrorCode
import io.bluetape4k.clinic.appointment.api.config.SchedulingPolicyProperties
import io.bluetape4k.clinic.appointment.api.dto.ActivateSchedulingPolicyRequest
import io.bluetape4k.clinic.appointment.api.dto.ApproveSchedulingPolicyRequest
import io.bluetape4k.clinic.appointment.api.dto.CreateSchedulingPolicyDraftRequest
import io.bluetape4k.clinic.appointment.api.dto.EffectiveSchedulingPolicyResponse
import io.bluetape4k.clinic.appointment.api.dto.PolicyGenerationRequest
import io.bluetape4k.clinic.appointment.api.dto.PolicyGenerationResponse
import io.bluetape4k.clinic.appointment.api.dto.PreviewSchedulingPolicyRequest
import io.bluetape4k.clinic.appointment.api.dto.ReplaySchedulingPolicyRequest
import io.bluetape4k.clinic.appointment.api.dto.RetireSchedulingPolicyRequest
import io.bluetape4k.clinic.appointment.api.dto.ScheduleSchedulingPolicyRequest
import io.bluetape4k.clinic.appointment.api.dto.SchedulingPolicyActivationResponse
import io.bluetape4k.clinic.appointment.api.dto.SchedulingPolicyApprovalResponse
import io.bluetape4k.clinic.appointment.api.dto.SchedulingPolicyMutationResponse
import io.bluetape4k.clinic.appointment.api.dto.SchedulingPolicyPreviewResponse
import io.bluetape4k.clinic.appointment.api.dto.ValidateSchedulingPolicyRequest
import io.bluetape4k.clinic.appointment.api.security.ActorContext
import io.bluetape4k.clinic.appointment.api.security.ActorType
import io.bluetape4k.clinic.appointment.model.dto.PolicyActivationCommandStatus
import io.bluetape4k.clinic.appointment.model.dto.PolicyPreviewJobStatus
import io.bluetape4k.clinic.appointment.model.dto.PolicyScopeRef
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyDefinitionRecord
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyPreviewJobRecord
import io.bluetape4k.clinic.appointment.model.policy.PolicyGenerationVector
import io.bluetape4k.clinic.appointment.model.policy.PolicyLifecycle
import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import io.bluetape4k.clinic.appointment.repository.SchedulingPolicyJobRepository
import io.bluetape4k.clinic.appointment.repository.SchedulingPolicyRepository
import io.bluetape4k.clinic.appointment.service.SchedulingPolicyHasher
import io.bluetape4k.clinic.appointment.service.SchedulingPolicyPayloadCodec
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * tenant/clinic 정책 HTTP 계약을 기존 transactional 명령 서비스에 연결하는 application facade다.
 *
 * controller는 path scope와 Gateway [ActorContext]만 구성하고 이 서비스에 위임한다. 이 계층은
 * strict payload decode/hash, revision·generation 입력 검증, preview 범위 고정, 완료 증거
 * 검증, replay 복원을 담당한다. Exposed transaction은 짧은 권위 조회에만 사용하고 명령
 * transaction을 controller 또는 facade가 중첩해서 감싸지 않는다.
 *
 * request의 `changeReason`은 현재 명령의 사람이 읽는 의도 확인값이며 draft 생성에서는
 * definition 감사 행에 영속화된다. 승인·schedule·activate·retire·replay는 기존 domain
 * aggregate가 정의한 immutable evidence와 lifecycle event를 권위 감사기록으로 사용한다.
 * 이들 요청의 사유도 비어 있거나 1000자를 넘으면 거부하지만 payload, token, actor 정보와
 * 함께 로그로 남기지 않는다.
 *
 * @property commandService scope lock, approval, lifecycle, generation, outbox를 원자적으로 처리한다.
 * @property previewService bounded scan과 durable async 전환을 처리한다.
 * @property previewStore polling을 scope-aware primary-key lookup으로 수행한다.
 * @property previewVerifier 완료 token이 exact revision·generation에 고정됐는지 검증한다.
 * @property policyRepository definition과 scope head를 짧은 transaction에서 조회한다.
 * @property jobRepository 완료 preview token과 MISSED activation command를 로컬 DB에서 복원한다.
 * @property tenantEffectiveService clinic sentinel 없는 tenant baseline 조회 서비스.
 * @property clinicEffectiveService clinic override까지 resolve하는 권위 snapshot 서비스.
 * @property metrics 작업·결과·scope 종류만 기록하는 낮은 cardinality 관리 API 지표다.
 * @property properties 공개 순서, preview horizon, polling 간격을 정의하는 fail-closed 설정.
 * @property clock preview 입력과 HTTP 응답에서 사용할 UTC application clock.
 */
@Suppress("TooManyFunctions")
class SchedulingPolicyAdministrationService(
    private val commandService: SchedulingPolicyCommandService,
    private val previewService: SchedulingPolicyPreviewService,
    private val previewStore: SchedulingPolicyPreviewStore,
    private val previewVerifier: PolicyPreviewEvidenceVerifier,
    private val policyRepository: SchedulingPolicyRepository,
    private val jobRepository: SchedulingPolicyJobRepository,
    private val tenantEffectiveService: TenantEffectiveSchedulingPolicyService,
    private val clinicEffectiveService: EffectiveSchedulingPolicyService,
    private val metrics: SchedulingPolicyMetrics,
    private val properties: SchedulingPolicyProperties,
    private val clock: Clock = Clock.systemUTC(),
    private val pollingLimiter: SchedulingPolicyPollingLimiter =
        SchedulingPolicyPollingLimiter(properties),
    private val payloadCodec: SchedulingPolicyPayloadCodec = SchedulingPolicyPayloadCodec(),
    private val payloadMapper: JsonMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .build(),
) {

    /** strict decode와 canonical semantic hash를 통과한 새 draft를 생성한다. */
    fun createDraft(
        scope: PolicyScopeRef,
        actor: ActorContext,
        request: CreateSchedulingPolicyDraftRequest,
    ): SchedulingPolicyMutationResponse = observe(
        PolicyAdministrationMetricOperation.CREATE_DRAFT,
        scope,
    ) {
        requireAdminWrite(actor, scope)
        require(request.schemaVersion > 0) { "schemaVersion must be positive" }
        require(request.expectedScopeRevision >= 0) { "expectedScopeRevision must be non-negative" }
        requireChangeReason(request.changeReason)
        require(request.effectiveUntil == null || request.effectiveUntil > request.effectiveFrom) {
            "effectiveUntil must be later than effectiveFrom"
        }
        val payloadJson = payloadMapper.writeValueAsString(request.payload)
        val payload = payloadCodec.decode(
            kind = request.kind,
            scope = scope.scope,
            schemaVersion = request.schemaVersion,
            json = payloadJson,
        )
        val result = commandService.createDraft(
            CreateSchedulingPolicyDraftCommand(
                scope = scope,
                kind = request.kind,
                schemaVersion = request.schemaVersion,
                effectiveFrom = request.effectiveFrom,
                effectiveUntil = request.effectiveUntil,
                expectedScopeRevision = request.expectedScopeRevision,
                payloadHash = SchedulingPolicyHasher.payloadHash(payload),
                payloadJson = payloadJson,
                changeReason = request.changeReason,
                actor = actor,
            )
        )
        SchedulingPolicyMutationResponse.from(result, currentGeneration(scope), actor.correlationId)
    }

    /** 현재 draft row가 path scope, revision, strict payload schema를 계속 만족하는지 확인한다. */
    fun validate(
        scope: PolicyScopeRef,
        actor: ActorContext,
        definitionId: Long,
        request: ValidateSchedulingPolicyRequest,
    ): SchedulingPolicyMutationResponse = observe(
        PolicyAdministrationMetricOperation.VALIDATE,
        scope,
    ) {
        requireAdminRead(actor, scope)
        val definition = requireDraft(scope, definitionId, request.expectedDraftRevision)
        payloadCodec.decode(
            definition.kind,
            definition.scope,
            definition.schemaVersion,
            definition.payloadJson,
        )
        val head = requireScopeHead(scope)
        SchedulingPolicyMutationResponse.validated(
            definition = definition,
            generation = currentGeneration(scope),
            scopeRevision = head.revision,
            correlationId = actor.correlationId,
        )
    }

    /** 서버가 정한 bounded horizon으로 영향도 preview를 제출한다. */
    fun preview(
        scope: PolicyScopeRef,
        actor: ActorContext,
        definitionId: Long,
        request: PreviewSchedulingPolicyRequest,
    ): SchedulingPolicyPreviewSubmission = observe(
        PolicyAdministrationMetricOperation.PREVIEW,
        scope,
    ) {
        requireAdminWrite(actor, scope)
        if (!properties.previewWorkerEnabled) notFound()
        requireDraft(scope, definitionId, request.expectedDraftRevision)
        val generation = request.expectedGeneration.requireMatches(scope)
        requireCurrentGeneration(scope, generation)
        val requestedAt = Instant.now(clock)
        val result = previewService.submit(
            CreateSchedulingPolicyPreviewCommand(
                scope = scope,
                definitionId = definitionId,
                draftRevision = request.expectedDraftRevision,
                generation = generation,
                horizonFrom = requestedAt,
                horizonUntil = requestedAt.plus(properties.previewHorizon),
                requestedAt = requestedAt,
            )
        )
        SchedulingPolicyPreviewSubmission(
            response = SchedulingPolicyPreviewResponse.from(result.job, actor.correlationId),
            asynchronous = result.disposition == SchedulingPolicyPreviewDisposition.ACCEPTED_ASYNC,
        )
    }

    /** 완료 preview token을 확인한 뒤 exact draft revision에 human approval을 기록한다. */
    fun approve(
        scope: PolicyScopeRef,
        actor: ActorContext,
        definitionId: Long,
        request: ApproveSchedulingPolicyRequest,
    ): SchedulingPolicyApprovalResponse = observe(
        PolicyAdministrationMetricOperation.APPROVE,
        scope,
    ) {
        requireAdminWrite(actor, scope)
        requireChangeReason(request.changeReason)
        val definition = requireDraft(scope, definitionId, request.expectedDraftRevision)
        requireCompletedEvidence(scope, definition, request.previewEvidenceToken)
        SchedulingPolicyApprovalResponse.from(
            commandService.approve(
                ApproveSchedulingPolicyCommand(
                    scope = scope,
                    definitionId = definitionId,
                    expectedDraftRevision = request.expectedDraftRevision,
                    actor = actor,
                )
            ),
            actor.correlationId,
        )
    }

    /** 미래 effective boundary와 완료 preview가 일치할 때 durable activation command를 만든다. */
    fun schedule(
        scope: PolicyScopeRef,
        actor: ActorContext,
        definitionId: Long,
        request: ScheduleSchedulingPolicyRequest,
    ): SchedulingPolicyActivationResponse = observe(
        PolicyAdministrationMetricOperation.SCHEDULE,
        scope,
    ) {
        requireAdminWrite(actor, scope)
        requireChangeReason(request.changeReason)
        val definition = requireDraft(scope, definitionId, request.expectedDraftRevision)
        require(definition.effectiveFrom == request.effectiveFrom) {
            "effectiveFrom must match the immutable draft boundary"
        }
        val preview = request.previewEvidence(scope, definitionId)
        SchedulingPolicyActivationResponse.scheduled(
            commandService.schedule(
                ScheduleSchedulingPolicyCommand(
                    scope = scope,
                    definitionId = definitionId,
                    expectedDraftRevision = request.expectedDraftRevision,
                    expectedActiveRevision = request.expectedActiveRevision,
                    preview = preview,
                    actor = actor,
                )
            ),
            actor.correlationId,
        )
    }

    /** raw header key와 완료 preview evidence로 즉시 activation을 수행한다. */
    fun activate(
        scope: PolicyScopeRef,
        actor: ActorContext,
        definitionId: Long,
        idempotencyKey: String?,
        request: ActivateSchedulingPolicyRequest,
    ): SchedulingPolicyActivationResponse = observe(
        PolicyAdministrationMetricOperation.ACTIVATE,
        scope,
    ) {
        requireAdminWrite(actor, scope)
        requireChangeReason(request.changeReason)
        val key = requireIdempotencyKey(idempotencyKey)
        SchedulingPolicyActivationResponse.activated(
            commandService.activate(
                ActivateSchedulingPolicyCommand(
                    scope = scope,
                    definitionId = definitionId,
                    expectedDraftRevision = request.expectedDraftRevision,
                    expectedActiveRevision = request.expectedActiveRevision,
                    idempotencyKey = key,
                    preview = request.previewEvidence(scope, definitionId),
                    actor = actor,
                )
            ),
            actor.correlationId,
        )
    }

    /** 현재 scope head와 generation을 CAS 입력으로 사용해 definition 이력을 retire한다. */
    fun retire(
        scope: PolicyScopeRef,
        actor: ActorContext,
        definitionId: Long,
        request: RetireSchedulingPolicyRequest,
    ): SchedulingPolicyMutationResponse = observe(
        PolicyAdministrationMetricOperation.RETIRE,
        scope,
    ) {
        requireAdminWrite(actor, scope)
        requireChangeReason(request.changeReason)
        requireCurrentGeneration(scope, request.expectedGeneration.requireMatches(scope))
        val definition = requireDefinition(scope, definitionId)
        val result = commandService.retire(
            RetireSchedulingPolicyCommand(
                scope = scope,
                definitionId = definitionId,
                expectedDraftRevision = definition.revision,
                expectedScopeRevision = request.expectedActiveRevision,
                actor = actor,
            )
        )
        SchedulingPolicyMutationResponse.from(result, currentGeneration(scope), actor.correlationId)
    }

    /**
     * MISSED source를 변경하지 않고 새 keyed command로 replay한다.
     *
     * 원본 command의 scope, definition, revision, preview token은 durable row에서만 복원한다.
     * request body는 이 값을 덮어쓸 수 없다.
     */
    fun replay(
        scope: PolicyScopeRef,
        actor: ActorContext,
        commandId: Long,
        idempotencyKey: String?,
        request: ReplaySchedulingPolicyRequest,
    ): SchedulingPolicyActivationResponse = observe(
        PolicyAdministrationMetricOperation.REPLAY,
        scope,
    ) {
        requireAdminWrite(actor, scope)
        requireChangeReason(request.changeReason)
        val generation = request.expectedGeneration.requireMatches(scope)
        requireCurrentGeneration(scope, generation)
        val source = transaction { jobRepository.findActivation(commandId) }
            ?.takeIf {
                it.tenantGroupId == scope.tenantGroupId &&
                    it.scope == scope.scope &&
                    it.clinicScopeKey == scope.clinicScopeKey
            }
            ?: notFound()
        if (source.status != PolicyActivationCommandStatus.MISSED) {
            reject(
                SchedulingPolicyErrorCode.POLICY_ACTIVATION_MISSED,
                "Only a terminal MISSED activation command can be replayed.",
            )
        }
        val head = requireScopeHead(scope)
        SchedulingPolicyActivationResponse.activated(
            commandService.activate(
                ActivateSchedulingPolicyCommand(
                    scope = scope,
                    definitionId = source.definitionId,
                    expectedDraftRevision = source.expectedDraftRevision,
                    expectedActiveRevision = head.revision,
                    idempotencyKey = requireIdempotencyKey(idempotencyKey),
                    preview = PolicyPreviewEvidence(
                        definitionId = source.definitionId,
                        draftRevision = source.expectedDraftRevision,
                        tenantGeneration = generation.tenantGeneration,
                        clinicGeneration = generation.clinicGeneration,
                        evidenceId = source.previewEvidenceToken,
                    ),
                    actor = actor,
                    replayOfCommandId = commandId,
                )
            ),
            actor.correlationId,
        )
    }

    /** scope-aware primary-key query 하나로 preview polling projection을 반환한다. */
    fun previewJob(
        scope: PolicyScopeRef,
        actor: ActorContext,
        jobId: Long,
    ): SchedulingPolicyPreviewResponse = observe(
        PolicyAdministrationMetricOperation.PREVIEW_JOB,
        scope,
    ) {
        requireAdminRead(actor, scope)
        require(jobId > 0) { "jobId must be positive" }
        val job = previewStore.find(scope, jobId) ?: notFound()
        pollingLimiter.requireAllowed(scope, job)
        SchedulingPolicyPreviewResponse.from(job, actor.correlationId)
    }

    /** clinic sentinel 없이 tenant baseline effective projection을 반환한다. */
    fun tenantEffective(
        scope: PolicyScopeRef,
        actor: ActorContext,
        decisionAt: Instant,
        serviceAt: Instant,
    ): EffectiveSchedulingPolicyResponse = observe(
        PolicyAdministrationMetricOperation.TENANT_EFFECTIVE,
        scope,
    ) {
        require(scope.scope == PolicyScope.TENANT_DEFAULT) { "tenant effective route requires tenant scope" }
        requireEffectiveRead(actor, scope)
        EffectiveSchedulingPolicyResponse.from(
            tenantEffectiveService.getEffective(scope.tenantGroupId, decisionAt, serviceAt),
            actor.correlationId,
        )
    }

    /** exact clinic scope에 resolve된 immutable effective snapshot을 반환한다. */
    fun clinicEffective(
        scope: PolicyScopeRef,
        actor: ActorContext,
        decisionAt: Instant,
        serviceAt: Instant,
    ): EffectiveSchedulingPolicyResponse = observe(
        PolicyAdministrationMetricOperation.CLINIC_EFFECTIVE,
        scope,
    ) {
        require(scope.scope == PolicyScope.CLINIC_OVERRIDE) { "clinic effective route requires clinic scope" }
        requireEffectiveRead(actor, scope)
        EffectiveSchedulingPolicyResponse.from(
            clinicEffectiveService.getEffective(
                scope.tenantGroupId,
                requireNotNull(scope.clinicId),
                decisionAt,
                serviceAt,
            ),
            actor.correlationId,
        )
    }

    /**
     * 관리 facade 결과를 성공/거부로 닫아 기록하고 원래 결과 또는 예외를 그대로 보존한다.
     *
     * 이 경계는 입력값이나 예외 상세를 meter에 전달하지 않는다. JVM [Error]는 업무 거부가
     * 아니므로 포착하지 않으며, 모든 application 예외는 같은 원본 instance로 재전파한다.
     */
    internal fun <T> observe(
        operation: PolicyAdministrationMetricOperation,
        scope: PolicyScopeRef,
        block: () -> T,
    ): T =
        try {
            block().also {
                recordAdministrationSafely(
                    PolicyAdministrationMetricResult.SUCCEEDED,
                    operation,
                    scope.scope,
                )
            }
        } catch (error: Exception) {
            recordAdministrationSafely(
                PolicyAdministrationMetricResult.REJECTED,
                operation,
                scope.scope,
            )
            throw error
        }

    /**
     * meter registry 장애를 업무 결과와 격리한다.
     *
     * Micrometer registry는 동적 교체, backend 종료, custom registry 구현 오류로
     * `register` 또는 `increment`에서 예외를 던질 수 있다. 관리 명령은 이 시점에 이미
     * commit됐을 수 있으므로 관측 실패를 호출자에게 노출하면 성공한 명령의 재시도를
     * 유발한다. 따라서 log message에는 닫힌 enum만 남기고 registry [Exception]의 class와
     * stack은 로컬 진단용 throwable로만 첨부한다. JVM [Error]는 정상적인 복구 대상이
     * 아니므로 포착하지 않는다.
     */
    private fun recordAdministrationSafely(
        result: PolicyAdministrationMetricResult,
        operation: PolicyAdministrationMetricOperation,
        scope: PolicyScope,
    ) {
        try {
            metrics.recordAdministration(result, operation, scope)
        } catch (error: Exception) {
            log.warn(error) {
                "Scheduling policy administration metric failed: " +
                    "result=${result.name.lowercase()}, operation=${operation.name.lowercase()}, " +
                    "scope_type=${scope.name.lowercase()}"
            }
        }
    }

    private fun requireCompletedEvidence(
        scope: PolicyScopeRef,
        definition: SchedulingPolicyDefinitionRecord,
        token: String,
    ): PolicyPreviewEvidence {
        val job = try {
            transaction { jobRepository.findCompletedPreviewByToken(scope, token) }
        } catch (_: IllegalArgumentException) {
            reject(SchedulingPolicyErrorCode.POLICY_PAYLOAD_INVALID, "Preview evidence token is invalid.")
        } ?: reject(
            SchedulingPolicyErrorCode.POLICY_PREVIEW_STALE,
            "Completed preview evidence was not found.",
        )
        if (job.tenantGroupId != scope.tenantGroupId ||
            job.scope != scope.scope ||
            job.clinicScopeKey != scope.clinicScopeKey ||
            job.definitionId != definition.id ||
            job.draftRevision != definition.revision
        ) {
            reject(
                SchedulingPolicyErrorCode.POLICY_PREVIEW_STALE,
                "Preview evidence does not belong to the requested draft scope.",
            )
        }
        val evidence = PolicyPreviewEvidence(
            definitionId = job.definitionId,
            draftRevision = job.draftRevision,
            tenantGeneration = job.tenantGeneration,
            clinicGeneration = job.clinicGeneration,
            evidenceId = token,
        )
        val generation = currentGenerationVector(scope)
        if (!previewVerifier.verify(evidence, definition, generation)) {
            reject(SchedulingPolicyErrorCode.POLICY_PREVIEW_STALE, "Preview evidence is no longer current.")
        }
        return evidence
    }

    private fun requireDraft(
        scope: PolicyScopeRef,
        definitionId: Long,
        expectedDraftRevision: Long,
    ): SchedulingPolicyDefinitionRecord {
        require(expectedDraftRevision > 0) { "expectedDraftRevision must be positive" }
        val definition = requireDefinition(scope, definitionId)
        if (definition.lifecycle != PolicyLifecycle.DRAFT ||
            definition.revision != expectedDraftRevision
        ) {
            reject(SchedulingPolicyErrorCode.POLICY_DRAFT_STALE, "Draft revision is stale.")
        }
        return definition
    }

    private fun requireDefinition(
        scope: PolicyScopeRef,
        definitionId: Long,
    ): SchedulingPolicyDefinitionRecord {
        require(definitionId > 0) { "definitionId must be positive" }
        return transaction { policyRepository.findDefinition(definitionId) }
            ?.takeIf {
                it.tenantGroupId == scope.tenantGroupId &&
                    it.scope == scope.scope &&
                    it.clinicScopeKey == scope.clinicScopeKey
            }
            ?: notFound()
    }

    private fun requireScopeHead(scope: PolicyScopeRef) =
        transaction { policyRepository.findScopeHead(scope) } ?: notFound()

    private fun currentGeneration(scope: PolicyScopeRef): PolicyGenerationResponse {
        val generation = currentGenerationVector(scope)
        return PolicyGenerationResponse(generation.tenantGeneration, generation.clinicGeneration)
    }

    private fun currentGenerationVector(scope: PolicyScopeRef): PolicyGenerationVector =
        transaction {
            val tenantGeneration = policyRepository.findScopeHead(
                PolicyScopeRef(scope.tenantGroupId, PolicyScope.TENANT_DEFAULT)
            )?.generation ?: 0L
            val clinicGeneration =
                if (scope.scope == PolicyScope.CLINIC_OVERRIDE) {
                    policyRepository.findScopeHead(scope)?.generation ?: 0L
                } else {
                    0L
                }
            PolicyGenerationVector(tenantGeneration, clinicGeneration)
        }

    private fun requireCurrentGeneration(
        scope: PolicyScopeRef,
        expected: PolicyGenerationVector,
    ) {
        if (currentGenerationVector(scope) != expected) {
            reject(SchedulingPolicyErrorCode.POLICY_PREVIEW_STALE, "Policy generation is stale.")
        }
    }

    private fun PolicyGenerationRequest.requireMatches(scope: PolicyScopeRef): PolicyGenerationVector {
        require(tenantGeneration >= 0) { "tenantGeneration must be non-negative" }
        require(clinicGeneration >= 0) { "clinicGeneration must be non-negative" }
        require(scope.scope != PolicyScope.TENANT_DEFAULT || clinicGeneration == 0L) {
            "tenant policy generation requires clinicGeneration zero"
        }
        return PolicyGenerationVector(tenantGeneration, clinicGeneration)
    }

    private fun ScheduleSchedulingPolicyRequest.previewEvidence(
        scope: PolicyScopeRef,
        definitionId: Long,
    ) = PolicyPreviewEvidence(
        definitionId = definitionId,
        draftRevision = expectedDraftRevision,
        tenantGeneration = expectedGeneration.requireMatches(scope).tenantGeneration,
        clinicGeneration = expectedGeneration.requireMatches(scope).clinicGeneration,
        evidenceId = previewEvidenceToken,
    )

    private fun ActivateSchedulingPolicyRequest.previewEvidence(
        scope: PolicyScopeRef,
        definitionId: Long,
    ) = PolicyPreviewEvidence(
        definitionId = definitionId,
        draftRevision = expectedDraftRevision,
        tenantGeneration = expectedGeneration.requireMatches(scope).tenantGeneration,
        clinicGeneration = expectedGeneration.requireMatches(scope).clinicGeneration,
        evidenceId = previewEvidenceToken,
    )

    private fun requireAdminWrite(actor: ActorContext, scope: PolicyScopeRef) {
        if (!properties.adminWriteEnabled) notFound()
        requireAdminRead(actor, scope)
    }

    private fun requireAdminRead(actor: ActorContext, scope: PolicyScopeRef) {
        if (!properties.adminWriteEnabled) notFound()
        requireActorScope(actor, scope)
        requirePolicyAuthority(actor)
    }

    private fun requireEffectiveRead(actor: ActorContext, scope: PolicyScopeRef) {
        if (!properties.effectiveReadEnabled) notFound()
        requireActorScope(actor, scope)
        requirePolicyAuthority(actor)
    }

    /** 모든 공개 policy admin/read route가 요구하는 explicit Gateway capability다. */
    private fun requirePolicyAuthority(actor: ActorContext) {
        if (POLICY_WRITE_SCOPE !in actor.scopes) {
            reject(SchedulingPolicyErrorCode.POLICY_ACTOR_FORBIDDEN, "Policy authority is required.")
        }
    }

    private fun requireActorScope(actor: ActorContext, scope: PolicyScopeRef) {
        if ((actor.actorType != ActorType.ADMIN && actor.actorType != ActorType.STAFF) ||
            scope.clinicId?.let { it !in actor.allowedClinicIds } == true
        ) {
            reject(SchedulingPolicyErrorCode.POLICY_ACTOR_FORBIDDEN, "Policy actor scope is forbidden.")
        }
    }

    private fun requireChangeReason(reason: String) {
        require(reason.isNotBlank() && reason.length <= 1_000) {
            "changeReason must contain 1..1000 characters"
        }
    }

    private fun requireIdempotencyKey(value: String?): String =
        value?.takeIf { it.isNotBlank() }
            ?: reject(
                SchedulingPolicyErrorCode.POLICY_PAYLOAD_INVALID,
                "Idempotency-Key header is required.",
            )

    private fun notFound(): Nothing =
        reject(SchedulingPolicyErrorCode.POLICY_RESOURCE_NOT_FOUND, "Policy resource was not found.")

    private fun reject(
        errorCode: SchedulingPolicyErrorCode,
        detail: String,
    ): Nothing = throw SchedulingPolicyApiException(errorCode, detail)

    private companion object : KLogging() {
        const val POLICY_WRITE_SCOPE = "policy:write"
    }
}

/** preview submit의 body와 HTTP `200|202` 선택을 함께 반환한다. */
data class SchedulingPolicyPreviewSubmission(
    val response: SchedulingPolicyPreviewResponse,
    val asynchronous: Boolean,
)

/**
 * 같은 scope/job에 대한 비종결 polling을 설정 간격으로 제한한다.
 *
 * terminal job은 즉시 항목을 제거해 반복 결과 조회를 허용한다. 비종결 항목은
 * [SchedulingPolicyProperties.previewPollInterval]이 지나면 다시 허용하며, opportunistic
 * cleanup과 hard entry cap으로 abandon된 key가 무한히 쌓이지 않게 한다. 이 limiter는
 * 한 API process 안의 편의 부하 제어일 뿐 SaaS 전체 rate limit이 아니다. 다중 instance
 * 배포는 API Gateway 또는 분산 rate limiter를 별도로 적용해야 한다. 권위 scope fence는
 * [SchedulingPolicyPreviewStore.find]의 SQL predicate가 담당한다.
 */
class SchedulingPolicyPollingLimiter(
    private val properties: SchedulingPolicyProperties,
    private val monotonicNanos: () -> Long = System::nanoTime,
) {
    private val nextAllowedNanos = ConcurrentHashMap<PollKey, Long>()

    fun requireAllowed(
        scope: PolicyScopeRef,
        job: SchedulingPolicyPreviewJobRecord,
    ) {
        val key = PollKey(scope.tenantGroupId, scope.scope, scope.clinicScopeKey, requireNotNull(job.id))
        if (job.status in TERMINAL_STATUSES) {
            nextAllowedNanos.remove(key)
            return
        }
        val now = monotonicNanos()
        val interval = properties.previewPollInterval.toNanos()
        var allowed = false
        var nextAllowed = now
        nextAllowedNanos.compute(key) { _, previous ->
            if (previous == null || now >= previous) {
                allowed = true
                Math.addExact(now, interval).also { nextAllowed = it }
            } else {
                previous
            }
        }
        if (!allowed) {
            limited()
        }
        if (nextAllowedNanos.size > MAX_TRACKED_JOBS) {
            nextAllowedNanos.entries.removeIf { it.value <= now }
            if (nextAllowedNanos.size > MAX_TRACKED_JOBS &&
                nextAllowedNanos.remove(key, nextAllowed)
            ) {
                limited()
            }
        }
    }

    private fun limited(): Nothing =
        throw SchedulingPolicyApiException(
            SchedulingPolicyErrorCode.POLICY_PREVIEW_LIMITED,
            "Preview polling interval has not elapsed.",
        )

    private data class PollKey(
        val tenantGroupId: Long,
        val scope: PolicyScope,
        val clinicScopeKey: Long,
        val jobId: Long,
    )

    private companion object {
        const val MAX_TRACKED_JOBS = 10_000
        val TERMINAL_STATUSES = setOf(
            PolicyPreviewJobStatus.COMPLETED,
            PolicyPreviewJobStatus.STALE,
            PolicyPreviewJobStatus.FAILED,
            PolicyPreviewJobStatus.CANCELLED,
        )
    }
}
