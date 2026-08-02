package io.bluetape4k.clinic.appointment.api.policy

import com.fasterxml.jackson.databind.ObjectMapper
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.clinic.appointment.api.config.GlobalExceptionHandler
import io.bluetape4k.clinic.appointment.api.config.SchedulingPolicyApiException
import io.bluetape4k.clinic.appointment.api.config.SchedulingPolicyErrorCode
import io.bluetape4k.clinic.appointment.api.dto.SchedulingApiErrorResponse
import io.bluetape4k.clinic.appointment.api.security.ActorContext
import io.bluetape4k.clinic.appointment.api.security.ActorType
import io.bluetape4k.clinic.appointment.api.security.AuthenticationAssurance
import io.bluetape4k.clinic.appointment.api.security.CorrelationIdFilter
import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxEvents
import io.bluetape4k.clinic.appointment.event.policy.SchedulingPolicyEventRepository
import io.bluetape4k.clinic.appointment.model.dto.PolicyActivationCommandStatus
import io.bluetape4k.clinic.appointment.model.dto.PolicyScopeRef
import io.bluetape4k.clinic.appointment.model.policy.ActorRole
import io.bluetape4k.clinic.appointment.model.policy.PolicyLifecycle
import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import io.bluetape4k.clinic.appointment.model.policy.SchedulingPolicyKind
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlans
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogProjections
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyActivationCommands
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyApprovals
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyDefinitions
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyScopeHeads
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.repository.SchedulingPolicyJobRepository
import io.bluetape4k.clinic.appointment.repository.SchedulingPolicyRepository
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.mock.web.MockHttpServletRequest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * 예약 정책 관리 명령의 권한·수명 주기·멱등성·감사 불변식을 검증한다.
 *
 * Gateway에서 파생된 [ActorContext]만 권한 판단에 사용하고, 초안 revision과 스코프 헤드
 * revision이 어긋나면 자동 병합하지 않는지 확인한다. 또한 승인·preview·활성화가 한
 * 트랜잭션 경계에서 연결되고, 동일 멱등 명령은 같은 결과를 재사용하지만 다른 payload는
 * 충돌하는지 증명한다.
 */
@ExtendWith(OutputCaptureExtension::class)
class SchedulingPolicyCommandServiceTest {
    private val now = Instant.parse("2026-07-27T00:00:00Z")
    private val scope = PolicyScopeRef(tenantGroupId = 1L, scope = PolicyScope.TENANT_DEFAULT)
    private val policyRepository = SchedulingPolicyRepository()
    private val jobRepository = SchedulingPolicyJobRepository("test-policy-secret-32-bytes-value".toByteArray())
    private val eventRepository = SchedulingPolicyEventRepository()
    private val previewVerifier = PolicyPreviewEvidenceVerifier { _, _, _ -> true }

    @BeforeEach
    fun setup() {
        Database.connect(
            "jdbc:h2:mem:policy_command_${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver",
        )
        transaction {
            SchemaUtils.createMissingTablesAndColumns(*ALL_TABLES)
            ALL_TABLES.reversed().forEach { it.deleteAll() }
            TenantGroups.insert {
                it[id] = EntityID(1L, TenantGroups)
                it[tenantCode] = "tenant-one"
                it[displayName] = "Tenant One"
                it[active] = true
            }
        }
    }

    @Test
    fun `stable error registry preserves approved status retry and action contract`() {
        val expectedStatus = mapOf(
            SchedulingPolicyErrorCode.POLICY_PAYLOAD_INVALID to 400,
            SchedulingPolicyErrorCode.POLICY_OVERRIDE_FORBIDDEN to 400,
            SchedulingPolicyErrorCode.POLICY_ACTOR_FORBIDDEN to 403,
            SchedulingPolicyErrorCode.POLICY_RESOURCE_NOT_FOUND to 404,
            SchedulingPolicyErrorCode.POLICY_DRAFT_STALE to 409,
            SchedulingPolicyErrorCode.POLICY_PREVIEW_STALE to 409,
            SchedulingPolicyErrorCode.POLICY_ACTIVATION_CONFLICT to 409,
            SchedulingPolicyErrorCode.POLICY_IDEMPOTENCY_CONFLICT to 409,
            SchedulingPolicyErrorCode.POLICY_ACTIVATION_MISSED to 409,
            SchedulingPolicyErrorCode.POLICY_APPROVAL_INSUFFICIENT to 422,
            SchedulingPolicyErrorCode.POLICY_PREVIEW_LIMITED to 429,
            SchedulingPolicyErrorCode.POLICY_EFFECTIVE_READ_CONFLICT to 409,
            SchedulingPolicyErrorCode.POLICY_EFFECTIVE_READ_UNAVAILABLE to 503,
            SchedulingPolicyErrorCode.POLICY_INTERNAL_ERROR to 500,
        )
        val retryableErrors = setOf(
            SchedulingPolicyErrorCode.POLICY_PREVIEW_LIMITED,
            SchedulingPolicyErrorCode.POLICY_EFFECTIVE_READ_CONFLICT,
            SchedulingPolicyErrorCode.POLICY_EFFECTIVE_READ_UNAVAILABLE,
        )

        SchedulingPolicyErrorCode.entries.toSet() shouldBeEqualTo expectedStatus.keys
        expectedStatus.forEach { (error, status) ->
            error.httpStatus.value() shouldBeEqualTo status
            error.retryable shouldBeEqualTo (error in retryableErrors)
            error.safeMessage.isNotBlank().shouldBeTrue()
            error.action.isNotBlank().shouldBeTrue()
        }
        val nonRetryableJson = ObjectMapper().writeValueAsString(
            SchedulingApiErrorResponse(
                error = "Stale draft.",
                errorCode = "POLICY_DRAFT_STALE",
                correlationId = "correlation-false",
                retryable = false,
                action = SchedulingPolicyErrorCode.POLICY_DRAFT_STALE.action,
            )
        )
        nonRetryableJson.contains("\"retryable\":false").shouldBeTrue()
    }

    @Test
    fun `policy exception handler emits stable correlation retry and action fields`() {
        val request = MockHttpServletRequest().apply {
            setAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE, "correlation-policy-error")
        }
        val error = SchedulingPolicyApiException(
            SchedulingPolicyErrorCode.POLICY_PREVIEW_LIMITED,
            "internal SQL detail that must never be reflected",
        )

        val response = GlobalExceptionHandler().handleSchedulingPolicy(error, request)

        response.statusCode.value() shouldBeEqualTo 429
        val body = response.body.shouldNotBeNull()
        body.errorCode shouldBeEqualTo "POLICY_PREVIEW_LIMITED"
        body.correlationId shouldBeEqualTo "correlation-policy-error"
        body.retryable.shouldBeTrue()
        body.action shouldBeEqualTo SchedulingPolicyErrorCode.POLICY_PREVIEW_LIMITED.action
        body.error shouldBeEqualTo SchedulingPolicyErrorCode.POLICY_PREVIEW_LIMITED.safeMessage
        body.error.contains("internal SQL detail").shouldBeFalse()
    }

    @Test
    fun `unexpected policy failure keeps internal detail out of the stable error envelope`(output: CapturedOutput) {
        val secretMarker = "secret-sql-marker"
        val request = MockHttpServletRequest(
            "POST",
            "/api/tenant-one/admin/scheduling-policies/validate",
        ).apply {
            setAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE, "correlation-policy-internal")
        }

        val response = GlobalExceptionHandler().handleGeneral(
            IllegalStateException(secretMarker),
            request,
        )
        val body = response.body as SchedulingApiErrorResponse

        response.statusCode.value() shouldBeEqualTo 500
        body.errorCode shouldBeEqualTo "POLICY_INTERNAL_ERROR"
        body.correlationId shouldBeEqualTo "correlation-policy-internal"
        body.retryable shouldBeEqualTo false
        body.error.contains(secretMarker) shouldBeEqualTo false
        output.out.shouldContain("Scheduling policy request failed with an internal error")
        output.out.shouldNotContain(secretMarker)
    }

    @Test
    fun `effective read handlers preserve stable conflict and unavailable contracts`() {
        val request = MockHttpServletRequest().apply {
            setAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE, "correlation-effective-read")
        }
        val handler = GlobalExceptionHandler()

        val conflict = handler.handleEffectivePolicyGenerationConflict(
            EffectivePolicyGenerationConflictException(3),
            request,
        )
        val unavailable = handler.handleEffectivePolicyReadUnavailable(
            EffectivePolicyReadUnavailableException(IllegalStateException("secret database detail")),
            request,
        )

        conflict.statusCode.value() shouldBeEqualTo 409
        val conflictBody = conflict.body.shouldNotBeNull()
        conflictBody.errorCode shouldBeEqualTo "POLICY_EFFECTIVE_READ_CONFLICT"
        conflictBody.retryable.shouldBeTrue()
        conflictBody.correlationId shouldBeEqualTo "correlation-effective-read"
        unavailable.statusCode.value() shouldBeEqualTo 503
        val unavailableBody = unavailable.body.shouldNotBeNull()
        unavailableBody.errorCode shouldBeEqualTo "POLICY_EFFECTIVE_READ_UNAVAILABLE"
        unavailableBody.retryable.shouldBeTrue()
        unavailableBody.error.contains("secret database detail").shouldBeFalse()
    }

    @Test
    fun `draft approval activation idempotency and retirement preserve lifecycle history`() {
        val service = service()
        val admin = actor("admin-a", ActorType.ADMIN, ActorRole.ADMIN)
        val draft = service.createDraft(createDraft(admin))
        draft.definition.lifecycle shouldBeEqualTo PolicyLifecycle.DRAFT
        draft.definition.revision shouldBeEqualTo 1L
        draft.head.revision shouldBeEqualTo 1L
        draft.head.generation shouldBeEqualTo 0L

        val definitionId = draft.definition.id.shouldNotBeNull()
        service.approve(ApproveSchedulingPolicyCommand(scope, definitionId, 1L, admin))
        val activation = activationCommand(
            definitionId = definitionId,
            actor = admin,
            expectedHeadRevision = 1L,
            idempotencyKey = "activate-basic",
        )
        val first = service.activate(activation)
        first.definition.lifecycle shouldBeEqualTo PolicyLifecycle.ACTIVE
        first.command.status shouldBeEqualTo PolicyActivationCommandStatus.COMPLETED
        first.generation.tenantGeneration shouldBeEqualTo 1L
        first.idempotentReplay.shouldBeFalse()

        val replay = service.activate(activation)
        replay.idempotentReplay.shouldBeTrue()
        replay.command.id shouldBeEqualTo first.command.id
        transaction {
            SchedulingOutboxEvents.selectAll().count() shouldBeEqualTo 1L
        }

        val retired = service.retire(
            RetireSchedulingPolicyCommand(
                scope = scope,
                definitionId = definitionId,
                expectedDraftRevision = 1L,
                expectedScopeRevision = 2L,
                actor = admin,
            )
        )
        retired.definition.lifecycle shouldBeEqualTo PolicyLifecycle.RETIRED
        retired.head.generation shouldBeEqualTo 2L
        transaction {
            policyRepository.findDefinition(definitionId).shouldNotBeNull()
            SchedulingOutboxEvents.selectAll().count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `sensitive policy requires two MFA approvers and separate activator`() {
        val service = service()
        val creator = actor("creator", ActorType.ADMIN, ActorRole.ADMIN, AuthenticationAssurance.MFA)
        val draft = service.createDraft(
            createDraft(creator, SchedulingPolicyKind.CAPACITY_AND_OVERBOOKING)
        )
        val definitionId = draft.definition.id.shouldNotBeNull()

        val creatorApproval = assertFailsWith<SchedulingPolicyApiException> {
            service.approve(
                ApproveSchedulingPolicyCommand(scope, definitionId, 1L, creator)
            )
        }
        creatorApproval.errorCode shouldBeEqualTo SchedulingPolicyErrorCode.POLICY_APPROVAL_INSUFFICIENT

        val approverOne = actor("approver-1", ActorType.ADMIN, ActorRole.ADMIN, AuthenticationAssurance.MFA)
        val approverTwo = actor("approver-2", ActorType.STAFF, ActorRole.STAFF, AuthenticationAssurance.MFA)
        service.approve(ApproveSchedulingPolicyCommand(scope, definitionId, 1L, approverOne))
        service.approve(ApproveSchedulingPolicyCommand(scope, definitionId, 1L, approverTwo))

        val separated = actor("activator", ActorType.ADMIN, ActorRole.ADMIN, AuthenticationAssurance.MFA)
        val result = service.activate(
            activationCommand(
                definitionId,
                separated,
                expectedHeadRevision = 1L,
                idempotencyKey = "activate-sensitive",
            )
        )
        result.definition.lifecycle shouldBeEqualTo PolicyLifecycle.ACTIVE
    }

    @Test
    fun `malformed preview evidence fails with stable stale contract before repository lookup`() {
        val service = service()
        val admin = actor("admin", ActorType.ADMIN, ActorRole.ADMIN)
        val draft = service.createDraft(createDraft(admin))
        val definitionId = draft.definition.id.shouldNotBeNull()
        service.approve(ApproveSchedulingPolicyCommand(scope, definitionId, 1L, admin))

        val error = assertFailsWith<SchedulingPolicyApiException> {
            service.activate(
                activationCommand(
                    definitionId = definitionId,
                    actor = admin,
                    expectedHeadRevision = 1L,
                    idempotencyKey = "malformed-preview",
                ).copy(
                    preview = preview(definitionId, tenantGeneration = 0L)
                        .copy(evidenceId = "contains unsafe whitespace"),
                )
            )
        }

        error.errorCode shouldBeEqualTo SchedulingPolicyErrorCode.POLICY_PREVIEW_STALE
    }

    @Test
    fun `scheduled activation requires durable command evidence for service actor`() {
        val service = service()
        val admin = actor("admin", ActorType.ADMIN, ActorRole.ADMIN)
        val draft = service.createDraft(createDraft(admin))
        val definitionId = draft.definition.id.shouldNotBeNull()
        service.approve(ApproveSchedulingPolicyCommand(scope, definitionId, 1L, admin))

        val scheduled = service.schedule(
            ScheduleSchedulingPolicyCommand(
                scope = scope,
                definitionId = definitionId,
                expectedDraftRevision = 1L,
                expectedActiveRevision = 1L,
                preview = preview(definitionId, tenantGeneration = 0L),
                actor = admin,
            )
        )
        scheduled.status shouldBeEqualTo PolicyActivationCommandStatus.PENDING
        scheduled.expectedActiveRevision shouldBeEqualTo 2L
        scheduled.expectedTenantGeneration shouldBeEqualTo 0L
        scheduled.expectedClinicGeneration shouldBeEqualTo 0L
        scheduled.previewEvidenceToken shouldBeEqualTo "preview-$definitionId-0"
        scheduled.idempotencyKeyHash shouldBeEqualTo jobRepository.hashIdempotencyKey(
            "scheduled:${draft.definition.id}:${draft.definition.version}:${draft.definition.effectiveFrom.toEpochMilli()}"
        )

        val system = actor(
            "policy-runner",
            ActorType.SYSTEM,
            ActorRole.SYSTEM,
            AuthenticationAssurance.SERVICE,
        )
        val missingServiceScope = assertFailsWith<SchedulingPolicyApiException> {
            service.activate(
                activationCommand(
                    definitionId = definitionId,
                    actor = system.copy(scopes = setOf("policy:write")),
                    expectedHeadRevision = 2L,
                    idempotencyKey = "unused-for-scheduled",
                ).copy(idempotencyKey = null, scheduledCommandId = scheduled.id)
            )
        }
        missingServiceScope.errorCode shouldBeEqualTo SchedulingPolicyErrorCode.POLICY_ACTOR_FORBIDDEN

        val missingSystemRole = assertFailsWith<SchedulingPolicyApiException> {
            service.activate(
                activationCommand(
                    definitionId = definitionId,
                    actor = system.copy(roles = emptySet()),
                    expectedHeadRevision = 2L,
                    idempotencyKey = "unused-for-scheduled",
                ).copy(idempotencyKey = null, scheduledCommandId = scheduled.id)
            )
        }
        missingSystemRole.errorCode shouldBeEqualTo SchedulingPolicyErrorCode.POLICY_ACTOR_FORBIDDEN

        val publicSystemExecution = assertFailsWith<SchedulingPolicyApiException> {
            service.activate(
                activationCommand(
                    definitionId = definitionId,
                    actor = system,
                    expectedHeadRevision = 2L,
                    idempotencyKey = "unused-for-scheduled",
                ).copy(idempotencyKey = null, scheduledCommandId = scheduled.id)
            )
        }
        publicSystemExecution.errorCode shouldBeEqualTo SchedulingPolicyErrorCode.POLICY_ACTOR_FORBIDDEN

        val forbidden = assertFailsWith<SchedulingPolicyApiException> {
            service.activate(
                activationCommand(
                    definitionId = definitionId,
                    actor = system,
                    expectedHeadRevision = 3L,
                    idempotencyKey = "system-without-command",
                )
            )
        }
        forbidden.errorCode shouldBeEqualTo SchedulingPolicyErrorCode.POLICY_ACTOR_FORBIDDEN
    }

    @Test
    fun `scheduled worker reconstructs command from durable evidence without request tenant context`() {
        val setupService = service()
        val admin = actor("admin", ActorType.ADMIN, ActorRole.ADMIN)
        val draft = setupService.createDraft(createDraft(admin))
        val definitionId = draft.definition.id.shouldNotBeNull()
        setupService.approve(ApproveSchedulingPolicyCommand(scope, definitionId, 1L, admin))
        val scheduled = setupService.schedule(
            ScheduleSchedulingPolicyCommand(
                scope = scope,
                definitionId = definitionId,
                expectedDraftRevision = 1L,
                expectedActiveRevision = 1L,
                preview = preview(definitionId, tenantGeneration = 0L),
                actor = admin,
            )
        )
        val runnerService = SchedulingPolicyCommandService(
            policyRepository,
            jobRepository,
            PolicyTenantBoundaryVerifier { _, _ -> false },
            previewVerifier,
            PolicyActivationPublisher(eventRepository::insertPolicyActivated),
            Clock.fixed(now, ZoneOffset.UTC),
        )

        val commandId = scheduled.id.shouldNotBeNull()
        transaction {
            jobRepository.claimDueActivation(
                commandId,
                "worker-1",
                now,
                now.plusSeconds(30),
            )
        }
        val result = runnerService.executeClaimedScheduled(
            commandId = commandId,
            owner = "worker-1",
            actor = actor(
                "policy-runner",
                ActorType.SYSTEM,
                ActorRole.SYSTEM,
                AuthenticationAssurance.SERVICE,
            ).copy(
                allowedTenantCodes = emptySet(),
                allowedClinicIds = emptySet(),
            ),
            databaseNow = now,
        )

        result.definition.lifecycle shouldBeEqualTo PolicyLifecycle.ACTIVE
        result.generation.tenantGeneration shouldBeEqualTo 1L
    }

    @Test
    fun `same scoped idempotency key with different intent is rejected`() {
        val service = service()
        val admin = actor("admin", ActorType.ADMIN, ActorRole.ADMIN)
        val draft = service.createDraft(createDraft(admin))
        val definitionId = draft.definition.id.shouldNotBeNull()
        service.approve(ApproveSchedulingPolicyCommand(scope, definitionId, 1L, admin))
        service.activate(
            activationCommand(definitionId, admin, 1L, "same-key")
        )

        val conflict = assertFailsWith<SchedulingPolicyApiException> {
            service.activate(
                activationCommand(definitionId, admin, 2L, "same-key")
            )
        }
        conflict.errorCode shouldBeEqualTo SchedulingPolicyErrorCode.POLICY_IDEMPOTENCY_CONFLICT
    }

    @Test
    fun `draft revision invalidates prior approval and preview evidence`() {
        val service = service()
        val admin = actor("admin", ActorType.ADMIN, ActorRole.ADMIN)
        val draft = service.createDraft(createDraft(admin))
        val definitionId = draft.definition.id.shouldNotBeNull()
        service.approve(ApproveSchedulingPolicyCommand(scope, definitionId, 1L, admin))
        val revised = service.reviseDraft(
            ReviseSchedulingPolicyDraftCommand(
                scope = scope,
                definitionId = definitionId,
                expectedDraftRevision = 1L,
                expectedScopeRevision = 1L,
                schemaVersion = 1,
                effectiveFrom = now.plusSeconds(60),
                effectiveUntil = null,
                payloadHash = "c".repeat(64),
                payloadJson = "{\"revision\":2}",
                changeReason = "Revise activation boundary",
                actor = admin,
            )
        )
        revised.definition.revision shouldBeEqualTo 2L
        revised.head.revision shouldBeEqualTo 2L

        val stale = assertFailsWith<SchedulingPolicyApiException> {
            service.activate(
                activationCommand(
                    definitionId = definitionId,
                    actor = admin,
                    expectedHeadRevision = 2L,
                    idempotencyKey = "stale-after-revision",
                )
            )
        }
        stale.errorCode shouldBeEqualTo SchedulingPolicyErrorCode.POLICY_DRAFT_STALE
    }

    @Test
    fun `manual replay creates a new command and never rewrites missed evidence`() {
        val service = service()
        val admin = actor("admin", ActorType.ADMIN, ActorRole.ADMIN)
        val draft = service.createDraft(createDraft(admin))
        val definitionId = draft.definition.id.shouldNotBeNull()
        service.approve(ApproveSchedulingPolicyCommand(scope, definitionId, 1L, admin))
        val scheduled = service.schedule(
            ScheduleSchedulingPolicyCommand(
                scope = scope,
                definitionId = definitionId,
                expectedDraftRevision = 1L,
                expectedActiveRevision = 1L,
                preview = preview(definitionId, 0L),
                actor = admin,
            )
        )
        transaction {
            jobRepository.claimDueActivation(
                    scheduled.id.shouldNotBeNull(),
                    "missed-worker",
                    now,
                    now.plusSeconds(30),
                ).shouldBeTrue()
            jobRepository.markActivationMissed(
                    scheduled.id.shouldNotBeNull(),
                    "missed-worker",
                    "POLICY_ACTIVATION_MISSED",
                    now.plusSeconds(10),
                ).shouldBeTrue()
        }

        val replay = service.activate(
            activationCommand(
                definitionId = definitionId,
                actor = admin,
                expectedHeadRevision = 2L,
                idempotencyKey = "manual-replay",
            ).copy(replayOfCommandId = scheduled.id)
        )

        replay.command.replayOfCommandId shouldBeEqualTo scheduled.id
        replay.command.status shouldBeEqualTo PolicyActivationCommandStatus.COMPLETED
        transaction {
            requireNotNull(jobRepository.findActivation(scheduled.id.shouldNotBeNull())).status shouldBeEqualTo
                PolicyActivationCommandStatus.MISSED
        }
    }

    @Test
    fun `publisher failure rolls back lifecycle generation command and outbox`() {
        val admin = actor("admin", ActorType.ADMIN, ActorRole.ADMIN)
        val setupService = service()
        val draft = setupService.createDraft(createDraft(admin))
        val definitionId = draft.definition.id.shouldNotBeNull()
        setupService.approve(ApproveSchedulingPolicyCommand(scope, definitionId, 1L, admin))
        val failing = service(PolicyActivationPublisher { _, _, _, _ -> error("injected publisher failure") })

        assertFailsWith<IllegalStateException> {
            failing.activate(
                activationCommand(definitionId, admin, 1L, "activate-rollback")
            )
        }

        transaction {
            requireNotNull(policyRepository.findDefinition(definitionId)).lifecycle shouldBeEqualTo PolicyLifecycle.DRAFT
            policyRepository.lockScopeHead(scope).generation shouldBeEqualTo 0L
            SchedulingPolicyActivationCommands.selectAll().count() shouldBeEqualTo 0L
            SchedulingOutboxEvents.selectAll().count() shouldBeEqualTo 0L
        }
    }

    @Test
    fun `tenant capability service scope and preview checks fail closed`() {
        val admin = actor("admin", ActorType.ADMIN, ActorRole.ADMIN)
        val crossTenant = createDraft(admin).copy(
            scope = PolicyScopeRef(tenantGroupId = 2L, scope = PolicyScope.TENANT_DEFAULT)
        )
        val tenantDenied = assertFailsWith<SchedulingPolicyApiException> {
            service().createDraft(crossTenant)
        }
        tenantDenied.errorCode shouldBeEqualTo SchedulingPolicyErrorCode.POLICY_ACTOR_FORBIDDEN

        val clinicDenied = assertFailsWith<SchedulingPolicyApiException> {
            service().createDraft(
                createDraft(admin).copy(
                    scope = PolicyScopeRef(
                        tenantGroupId = 1L,
                        scope = PolicyScope.CLINIC_OVERRIDE,
                        clinicId = 41L,
                    )
                )
            )
        }
        clinicDenied.errorCode shouldBeEqualTo SchedulingPolicyErrorCode.POLICY_ACTOR_FORBIDDEN

        val missingCapability = assertFailsWith<SchedulingPolicyApiException> {
            service().createDraft(createDraft(admin.copy(scopes = emptySet())))
        }
        missingCapability.errorCode shouldBeEqualTo SchedulingPolicyErrorCode.POLICY_ACTOR_FORBIDDEN

        val setupService = service()
        val draft = setupService.createDraft(createDraft(admin))
        val definitionId = draft.definition.id.shouldNotBeNull()
        setupService.approve(ApproveSchedulingPolicyCommand(scope, definitionId, 1L, admin))
        val previewDeniedService = SchedulingPolicyCommandService(
            policyRepository,
            jobRepository,
            PolicyTenantBoundaryVerifier { requestedScope, actor ->
                requestedScope.tenantGroupId == 1L && "tenant-one" in actor.allowedTenantCodes
            },
            PolicyPreviewEvidenceVerifier { _, _, _ -> false },
            PolicyActivationPublisher(eventRepository::insertPolicyActivated),
            Clock.fixed(now, ZoneOffset.UTC),
        )
        val stalePreview = assertFailsWith<SchedulingPolicyApiException> {
            previewDeniedService.activate(
                activationCommand(definitionId, admin, 1L, "preview-denied")
            )
        }
        stalePreview.errorCode shouldBeEqualTo SchedulingPolicyErrorCode.POLICY_PREVIEW_STALE
    }

    private fun service(
        publisher: PolicyActivationPublisher =
            PolicyActivationPublisher(eventRepository::insertPolicyActivated),
    ): SchedulingPolicyCommandService =
        SchedulingPolicyCommandService(
            policyRepository = policyRepository,
            jobRepository = jobRepository,
            tenantBoundaryVerifier = PolicyTenantBoundaryVerifier { requestedScope, actor ->
                requestedScope.tenantGroupId == 1L && "tenant-one" in actor.allowedTenantCodes
            },
            previewVerifier = previewVerifier,
            publisher = publisher,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

    private fun createDraft(
        actor: ActorContext,
        kind: SchedulingPolicyKind = SchedulingPolicyKind.BOOKING_COMMITMENT,
    ) = CreateSchedulingPolicyDraftCommand(
        scope = scope,
        kind = kind,
        schemaVersion = 1,
        effectiveFrom = now,
        effectiveUntil = null,
        payloadHash = "a".repeat(64),
        payloadJson = "{}",
        changeReason = "Initial policy",
        expectedScopeRevision = 0L,
        actor = actor,
    )

    private fun activationCommand(
        definitionId: Long,
        actor: ActorContext,
        expectedHeadRevision: Long,
        idempotencyKey: String,
    ) = ActivateSchedulingPolicyCommand(
        scope = scope,
        definitionId = definitionId,
        expectedDraftRevision = 1L,
        expectedActiveRevision = expectedHeadRevision,
        idempotencyKey = idempotencyKey,
        preview = preview(definitionId, tenantGeneration = 0L),
        actor = actor,
    )

    private fun preview(
        definitionId: Long,
        tenantGeneration: Long,
    ) = PolicyPreviewEvidence(
        definitionId = definitionId,
        draftRevision = 1L,
        tenantGeneration = tenantGeneration,
        clinicGeneration = 0L,
        evidenceId = "preview-$definitionId-$tenantGeneration",
    )

    private fun actor(
        id: String,
        type: ActorType,
        role: ActorRole,
        assurance: AuthenticationAssurance = AuthenticationAssurance.PASSWORD,
    ) = ActorContext(
        actorId = id,
        actorType = type,
        roles = setOf(role),
        scopes =
            if (type == ActorType.SYSTEM) {
                setOf("policy:scheduled-activation")
            } else {
                setOf("policy:write")
            },
        allowedTenantCodes = setOf("tenant-one"),
        allowedClinicIds = emptySet(),
        patientSubjectId = null,
        assurance = assurance,
        issuer = "test-gateway",
        tokenId = "token-$id",
        authenticatedAt = now.minusSeconds(60),
        correlationId = "correlation-$id",
    )

    private companion object {
        val ALL_TABLES = arrayOf(
            TenantGroups,
            Clinics,
            ProductCatalogProjections,
            AppointmentPlans,
            SchedulingPolicyDefinitions,
            SchedulingPolicyApprovals,
            SchedulingPolicyScopeHeads,
            SchedulingPolicyActivationCommands,
            SchedulingOutboxEvents,
        )
    }
}
