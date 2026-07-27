package io.bluetape4k.clinic.appointment.api.policy

import com.fasterxml.jackson.databind.ObjectMapper
import io.bluetape4k.clinic.appointment.api.config.SchedulingPolicyApiException
import io.bluetape4k.clinic.appointment.api.config.SchedulingPolicyErrorCode
import io.bluetape4k.clinic.appointment.api.config.GlobalExceptionHandler
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
        )
        val retryableErrors = setOf(
            SchedulingPolicyErrorCode.POLICY_PREVIEW_LIMITED,
            SchedulingPolicyErrorCode.POLICY_EFFECTIVE_READ_CONFLICT,
            SchedulingPolicyErrorCode.POLICY_EFFECTIVE_READ_UNAVAILABLE,
        )

        assertEquals(expectedStatus.keys, SchedulingPolicyErrorCode.entries.toSet())
        expectedStatus.forEach { (error, status) ->
            assertEquals(status, error.httpStatus.value())
            assertEquals(error in retryableErrors, error.retryable)
            assertTrue(error.safeMessage.isNotBlank())
            assertTrue(error.action.isNotBlank())
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
        assertTrue(nonRetryableJson.contains("\"retryable\":false"))
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

        assertEquals(429, response.statusCode.value())
        assertEquals("POLICY_PREVIEW_LIMITED", response.body!!.errorCode)
        assertEquals("correlation-policy-error", response.body!!.correlationId)
        assertEquals(true, response.body!!.retryable)
        assertEquals(SchedulingPolicyErrorCode.POLICY_PREVIEW_LIMITED.action, response.body!!.action)
        assertEquals(SchedulingPolicyErrorCode.POLICY_PREVIEW_LIMITED.safeMessage, response.body!!.error)
        assertFalse(response.body!!.error.contains("internal SQL detail"))
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

        assertEquals(409, conflict.statusCode.value())
        assertEquals("POLICY_EFFECTIVE_READ_CONFLICT", conflict.body!!.errorCode)
        assertEquals(true, conflict.body!!.retryable)
        assertEquals("correlation-effective-read", conflict.body!!.correlationId)
        assertEquals(503, unavailable.statusCode.value())
        assertEquals("POLICY_EFFECTIVE_READ_UNAVAILABLE", unavailable.body!!.errorCode)
        assertEquals(true, unavailable.body!!.retryable)
        assertFalse(unavailable.body!!.error.contains("secret database detail"))
    }

    @Test
    fun `draft approval activation idempotency and retirement preserve lifecycle history`() {
        val service = service()
        val admin = actor("admin-a", ActorType.ADMIN, ActorRole.ADMIN)
        val draft = service.createDraft(createDraft(admin))
        assertEquals(PolicyLifecycle.DRAFT, draft.definition.lifecycle)
        assertEquals(1L, draft.definition.revision)
        assertEquals(1L, draft.head.revision)
        assertEquals(0L, draft.head.generation)

        service.approve(
            ApproveSchedulingPolicyCommand(scope, draft.definition.id!!, 1L, admin)
        )
        val activation = activationCommand(
            definitionId = draft.definition.id!!,
            actor = admin,
            expectedHeadRevision = 1L,
            idempotencyKey = "activate-basic",
        )
        val first = service.activate(activation)
        assertEquals(PolicyLifecycle.ACTIVE, first.definition.lifecycle)
        assertEquals(PolicyActivationCommandStatus.COMPLETED, first.command.status)
        assertEquals(1L, first.generation.tenantGeneration)
        assertFalse(first.idempotentReplay)

        val replay = service.activate(activation)
        assertTrue(replay.idempotentReplay)
        assertEquals(first.command.id, replay.command.id)
        transaction {
            assertEquals(1L, SchedulingOutboxEvents.selectAll().count())
        }

        val retired = service.retire(
            RetireSchedulingPolicyCommand(
                scope = scope,
                definitionId = draft.definition.id!!,
                expectedDraftRevision = 1L,
                expectedScopeRevision = 2L,
                actor = admin,
            )
        )
        assertEquals(PolicyLifecycle.RETIRED, retired.definition.lifecycle)
        assertEquals(2L, retired.head.generation)
        transaction {
            assertNotNull(policyRepository.findDefinition(draft.definition.id!!))
            assertEquals(1L, SchedulingOutboxEvents.selectAll().count())
        }
    }

    @Test
    fun `sensitive policy requires two MFA approvers and separate activator`() {
        val service = service()
        val creator = actor("creator", ActorType.ADMIN, ActorRole.ADMIN, AuthenticationAssurance.MFA)
        val draft = service.createDraft(
            createDraft(creator, SchedulingPolicyKind.CAPACITY_AND_OVERBOOKING)
        )

        val creatorApproval = assertThrows(SchedulingPolicyApiException::class.java) {
            service.approve(
                ApproveSchedulingPolicyCommand(scope, draft.definition.id!!, 1L, creator)
            )
        }
        assertEquals(
            SchedulingPolicyErrorCode.POLICY_APPROVAL_INSUFFICIENT,
            creatorApproval.errorCode,
        )

        val approverOne = actor("approver-1", ActorType.ADMIN, ActorRole.ADMIN, AuthenticationAssurance.MFA)
        val approverTwo = actor("approver-2", ActorType.STAFF, ActorRole.STAFF, AuthenticationAssurance.MFA)
        service.approve(ApproveSchedulingPolicyCommand(scope, draft.definition.id!!, 1L, approverOne))
        service.approve(ApproveSchedulingPolicyCommand(scope, draft.definition.id!!, 1L, approverTwo))

        val separated = actor("activator", ActorType.ADMIN, ActorRole.ADMIN, AuthenticationAssurance.MFA)
        val result = service.activate(
            activationCommand(
                draft.definition.id!!,
                separated,
                expectedHeadRevision = 1L,
                idempotencyKey = "activate-sensitive",
            )
        )
        assertEquals(PolicyLifecycle.ACTIVE, result.definition.lifecycle)
    }

    @Test
    fun `scheduled activation requires durable command evidence for service actor`() {
        val service = service()
        val admin = actor("admin", ActorType.ADMIN, ActorRole.ADMIN)
        val draft = service.createDraft(createDraft(admin))
        service.approve(ApproveSchedulingPolicyCommand(scope, draft.definition.id!!, 1L, admin))

        val scheduled = service.schedule(
            ScheduleSchedulingPolicyCommand(
                scope = scope,
                definitionId = draft.definition.id!!,
                expectedDraftRevision = 1L,
                expectedActiveRevision = 1L,
                preview = preview(draft.definition.id!!, tenantGeneration = 0L),
                actor = admin,
            )
        )
        assertEquals(PolicyActivationCommandStatus.PENDING, scheduled.status)
        assertEquals(2L, scheduled.expectedActiveRevision)
        assertEquals(
            jobRepository.hashIdempotencyKey(
                "scheduled:${draft.definition.id}:${draft.definition.version}:${draft.definition.effectiveFrom.toEpochMilli()}"
            ),
            scheduled.idempotencyKeyHash,
        )

        val system = actor(
            "policy-runner",
            ActorType.SYSTEM,
            ActorRole.SYSTEM,
            AuthenticationAssurance.SERVICE,
        )
        val missingServiceScope = assertThrows(SchedulingPolicyApiException::class.java) {
            service.activate(
                activationCommand(
                    definitionId = draft.definition.id!!,
                    actor = system.copy(scopes = setOf("policy:write")),
                    expectedHeadRevision = 2L,
                    idempotencyKey = "unused-for-scheduled",
                ).copy(idempotencyKey = null, scheduledCommandId = scheduled.id)
            )
        }
        assertEquals(SchedulingPolicyErrorCode.POLICY_ACTOR_FORBIDDEN, missingServiceScope.errorCode)

        val result = service.activate(
            activationCommand(
                definitionId = draft.definition.id!!,
                actor = system,
                expectedHeadRevision = 2L,
                idempotencyKey = "unused-for-scheduled",
            ).copy(idempotencyKey = null, scheduledCommandId = scheduled.id)
        )
        assertEquals(PolicyLifecycle.ACTIVE, result.definition.lifecycle)

        val forbidden = assertThrows(SchedulingPolicyApiException::class.java) {
            service.activate(
                activationCommand(
                    definitionId = draft.definition.id!!,
                    actor = system,
                    expectedHeadRevision = 3L,
                    idempotencyKey = "system-without-command",
                )
            )
        }
        assertEquals(SchedulingPolicyErrorCode.POLICY_ACTOR_FORBIDDEN, forbidden.errorCode)
    }

    @Test
    fun `same scoped idempotency key with different intent is rejected`() {
        val service = service()
        val admin = actor("admin", ActorType.ADMIN, ActorRole.ADMIN)
        val draft = service.createDraft(createDraft(admin))
        service.approve(ApproveSchedulingPolicyCommand(scope, draft.definition.id!!, 1L, admin))
        service.activate(
            activationCommand(draft.definition.id!!, admin, 1L, "same-key")
        )

        val conflict = assertThrows(SchedulingPolicyApiException::class.java) {
            service.activate(
                activationCommand(draft.definition.id!!, admin, 2L, "same-key")
            )
        }
        assertEquals(SchedulingPolicyErrorCode.POLICY_IDEMPOTENCY_CONFLICT, conflict.errorCode)
    }

    @Test
    fun `draft revision invalidates prior approval and preview evidence`() {
        val service = service()
        val admin = actor("admin", ActorType.ADMIN, ActorRole.ADMIN)
        val draft = service.createDraft(createDraft(admin))
        service.approve(ApproveSchedulingPolicyCommand(scope, draft.definition.id!!, 1L, admin))
        val revised = service.reviseDraft(
            ReviseSchedulingPolicyDraftCommand(
                scope = scope,
                definitionId = draft.definition.id!!,
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
        assertEquals(2L, revised.definition.revision)
        assertEquals(2L, revised.head.revision)

        val stale = assertThrows(SchedulingPolicyApiException::class.java) {
            service.activate(
                activationCommand(
                    definitionId = draft.definition.id!!,
                    actor = admin,
                    expectedHeadRevision = 2L,
                    idempotencyKey = "stale-after-revision",
                )
            )
        }
        assertEquals(SchedulingPolicyErrorCode.POLICY_DRAFT_STALE, stale.errorCode)
    }

    @Test
    fun `manual replay creates a new command and never rewrites missed evidence`() {
        val service = service()
        val admin = actor("admin", ActorType.ADMIN, ActorRole.ADMIN)
        val draft = service.createDraft(createDraft(admin))
        service.approve(ApproveSchedulingPolicyCommand(scope, draft.definition.id!!, 1L, admin))
        val scheduled = service.schedule(
            ScheduleSchedulingPolicyCommand(
                scope = scope,
                definitionId = draft.definition.id!!,
                expectedDraftRevision = 1L,
                expectedActiveRevision = 1L,
                preview = preview(draft.definition.id!!, 0L),
                actor = admin,
            )
        )
        transaction {
            assertTrue(
                jobRepository.claimDueActivation(
                    scheduled.id!!,
                    "missed-worker",
                    now,
                    now.plusSeconds(30),
                )
            )
            assertTrue(
                jobRepository.markActivationMissed(
                    scheduled.id!!,
                    "missed-worker",
                    "POLICY_ACTIVATION_MISSED",
                    now.plusSeconds(10),
                )
            )
        }

        val replay = service.activate(
            activationCommand(
                definitionId = draft.definition.id!!,
                actor = admin,
                expectedHeadRevision = 2L,
                idempotencyKey = "manual-replay",
            ).copy(replayOfCommandId = scheduled.id)
        )

        assertEquals(scheduled.id, replay.command.replayOfCommandId)
        assertEquals(PolicyActivationCommandStatus.COMPLETED, replay.command.status)
        transaction {
            assertEquals(
                PolicyActivationCommandStatus.MISSED,
                jobRepository.findActivation(scheduled.id!!)!!.status,
            )
        }
    }

    @Test
    fun `publisher failure rolls back lifecycle generation command and outbox`() {
        val admin = actor("admin", ActorType.ADMIN, ActorRole.ADMIN)
        val setupService = service()
        val draft = setupService.createDraft(createDraft(admin))
        setupService.approve(ApproveSchedulingPolicyCommand(scope, draft.definition.id!!, 1L, admin))
        val failing = service(PolicyActivationPublisher { _, _, _, _ -> error("injected publisher failure") })

        assertThrows(IllegalStateException::class.java) {
            failing.activate(
                activationCommand(draft.definition.id!!, admin, 1L, "activate-rollback")
            )
        }

        transaction {
            assertEquals(PolicyLifecycle.DRAFT, policyRepository.findDefinition(draft.definition.id!!)!!.lifecycle)
            assertEquals(0L, policyRepository.lockScopeHead(scope).generation)
            assertEquals(0L, SchedulingPolicyActivationCommands.selectAll().count())
            assertEquals(0L, SchedulingOutboxEvents.selectAll().count())
        }
    }

    @Test
    fun `tenant capability service scope and preview checks fail closed`() {
        val admin = actor("admin", ActorType.ADMIN, ActorRole.ADMIN)
        val crossTenant = createDraft(admin).copy(
            scope = PolicyScopeRef(tenantGroupId = 2L, scope = PolicyScope.TENANT_DEFAULT)
        )
        val tenantDenied = assertThrows(SchedulingPolicyApiException::class.java) {
            service().createDraft(crossTenant)
        }
        assertEquals(SchedulingPolicyErrorCode.POLICY_ACTOR_FORBIDDEN, tenantDenied.errorCode)

        val clinicDenied = assertThrows(SchedulingPolicyApiException::class.java) {
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
        assertEquals(SchedulingPolicyErrorCode.POLICY_ACTOR_FORBIDDEN, clinicDenied.errorCode)

        val missingCapability = assertThrows(SchedulingPolicyApiException::class.java) {
            service().createDraft(createDraft(admin.copy(scopes = emptySet())))
        }
        assertEquals(SchedulingPolicyErrorCode.POLICY_ACTOR_FORBIDDEN, missingCapability.errorCode)

        val setupService = service()
        val draft = setupService.createDraft(createDraft(admin))
        setupService.approve(ApproveSchedulingPolicyCommand(scope, draft.definition.id!!, 1L, admin))
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
        val stalePreview = assertThrows(SchedulingPolicyApiException::class.java) {
            previewDeniedService.activate(
                activationCommand(draft.definition.id!!, admin, 1L, "preview-denied")
            )
        }
        assertEquals(SchedulingPolicyErrorCode.POLICY_PREVIEW_STALE, stalePreview.errorCode)
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
