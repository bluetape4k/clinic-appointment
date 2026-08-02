package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.clinic.appointment.api.config.SchedulingPolicyProperties
import io.bluetape4k.clinic.appointment.api.dto.ActivateSchedulingPolicyRequest
import io.bluetape4k.clinic.appointment.api.dto.ApproveSchedulingPolicyRequest
import io.bluetape4k.clinic.appointment.api.dto.CreateSchedulingPolicyDraftRequest
import io.bluetape4k.clinic.appointment.api.dto.PolicyGenerationRequest
import io.bluetape4k.clinic.appointment.api.dto.PolicyGenerationResponse
import io.bluetape4k.clinic.appointment.api.dto.PreviewSchedulingPolicyRequest
import io.bluetape4k.clinic.appointment.api.dto.ReplaySchedulingPolicyRequest
import io.bluetape4k.clinic.appointment.api.dto.RetireSchedulingPolicyRequest
import io.bluetape4k.clinic.appointment.api.dto.ScheduleSchedulingPolicyRequest
import io.bluetape4k.clinic.appointment.api.dto.SchedulingPolicyActivationResponse
import io.bluetape4k.clinic.appointment.api.dto.SchedulingPolicyApprovalResponse
import io.bluetape4k.clinic.appointment.api.dto.SchedulingPolicyMutationResponse
import io.bluetape4k.clinic.appointment.api.dto.SchedulingPolicyPreviewProgressResponse
import io.bluetape4k.clinic.appointment.api.dto.SchedulingPolicyPreviewResponse
import io.bluetape4k.clinic.appointment.api.dto.ValidateSchedulingPolicyRequest
import io.bluetape4k.clinic.appointment.api.policy.SchedulingPolicyAdministrationService
import io.bluetape4k.clinic.appointment.api.policy.SchedulingPolicyPreviewSubmission
import io.bluetape4k.clinic.appointment.api.security.ActorContext
import io.bluetape4k.clinic.appointment.api.security.ActorContextResolver
import io.bluetape4k.clinic.appointment.api.security.ActorType
import io.bluetape4k.clinic.appointment.api.security.AuthenticationAssurance
import io.bluetape4k.clinic.appointment.api.security.CorrelationIdFilter
import io.bluetape4k.clinic.appointment.api.tenant.TenantClinicAccessChecker
import io.bluetape4k.clinic.appointment.api.tenant.TenantInfo
import io.bluetape4k.clinic.appointment.model.dto.PolicyPreviewJobStatus
import io.bluetape4k.clinic.appointment.model.dto.PolicyScopeRef
import io.bluetape4k.clinic.appointment.model.policy.ActorRole
import io.bluetape4k.clinic.appointment.model.policy.PolicyLifecycle
import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import io.bluetape4k.clinic.appointment.model.policy.SchedulingPolicyKind
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant

/**
 * tenant controller가 HTTP 계약만 소유하고 권위 scope/actor를 application service에
 * 전달하는지 검증한다. Exposed transaction이나 lifecycle 판단은 mock 경계를 넘지 않는다.
 */
class TenantSchedulingPolicyControllerTest {

    private val administrationService = mockk<SchedulingPolicyAdministrationService>()
    private val accessChecker = mockk<TenantClinicAccessChecker>()
    private val actorResolver = mockk<ActorContextResolver>()
    private val actor = actor()
    private val controller = TenantSchedulingPolicyController(
        administrationService,
        accessChecker,
        actorResolver,
        SchedulingPolicyProperties(previewPollInterval = Duration.ofMillis(1_500)),
    )

    @Test
    fun `tenant lifecycle routes preserve trusted scope actor CAS evidence and idempotency headers`() {
        val scope = PolicyScopeRef(11L, PolicyScope.TENANT_DEFAULT)
        every { accessChecker.requireTenant("clinic-a") } returns TenantInfo(11L, "clinic-a", "Clinic A")
        every { actorResolver.resolve(null, "clinic-a", null, "corr-tenant") } returns actor
        val generation = PolicyGenerationRequest(9L, 0L)
        val draft = CreateSchedulingPolicyDraftRequest(
            kind = SchedulingPolicyKind.BOOKING_COMMITMENT,
            schemaVersion = 1,
            effectiveFrom = Instant.parse("2026-08-01T00:00:00Z"),
            effectiveUntil = null,
            payload = JsonMapper.builder().build().createObjectNode(),
            expectedScopeRevision = 2L,
            changeReason = "Introduce tenant booking rules",
        )
        val validate = ValidateSchedulingPolicyRequest(expectedDraftRevision = 3L)
        val approve = ApproveSchedulingPolicyRequest(3L, "evidence-token", "Reviewed impact")
        val schedule = ScheduleSchedulingPolicyRequest(
            3L,
            2L,
            generation,
            "evidence-token",
            draft.effectiveFrom,
            "Schedule approved policy",
        )
        val activate = ActivateSchedulingPolicyRequest(
            3L,
            2L,
            generation,
            "evidence-token",
            "Activate approved policy",
        )
        val retire = RetireSchedulingPolicyRequest(3L, generation, "Retire superseded policy")
        val replay = ReplaySchedulingPolicyRequest(generation, "Replay missed activation")
        val mutationResponse = mutationResponse()
        val approvalResponse = SchedulingPolicyApprovalResponse(
            71L,
            3L,
            Instant.parse("2026-07-28T00:10:00Z"),
            "corr-tenant",
        )
        val activationResponse = activationResponse()
        every { administrationService.createDraft(scope, actor, draft) } returns mutationResponse
        every { administrationService.validate(scope, actor, 71L, validate) } returns mutationResponse
        every { administrationService.approve(scope, actor, 71L, approve) } returns approvalResponse
        every { administrationService.schedule(scope, actor, 71L, schedule) } returns activationResponse
        every {
            administrationService.activate(scope, actor, 71L, "activate-key", activate)
        } returns activationResponse
        every { administrationService.retire(scope, actor, 71L, retire) } returns mutationResponse
        every {
            administrationService.replay(scope, actor, 91L, "replay-key", replay)
        } returns activationResponse

        controller.createDraft("clinic-a", draft, null, request("corr-tenant")).statusCode shouldBeEqualTo
            HttpStatus.CREATED
        controller.validate("clinic-a", 71L, validate, null, request("corr-tenant"))
        controller.approve("clinic-a", 71L, approve, null, request("corr-tenant"))
        controller.schedule("clinic-a", 71L, schedule, null, request("corr-tenant")).statusCode shouldBeEqualTo
            HttpStatus.ACCEPTED
        controller.activate("clinic-a", 71L, "activate-key", activate, null, request("corr-tenant"))
        controller.retire("clinic-a", 71L, retire, null, request("corr-tenant"))
        controller.replay("clinic-a", 91L, "replay-key", replay, null, request("corr-tenant"))

        verify(exactly = 1) { administrationService.createDraft(scope, actor, draft) }
        verify(exactly = 1) { administrationService.validate(scope, actor, 71L, validate) }
        verify(exactly = 1) { administrationService.approve(scope, actor, 71L, approve) }
        verify(exactly = 1) { administrationService.schedule(scope, actor, 71L, schedule) }
        verify(exactly = 1) {
            administrationService.activate(scope, actor, 71L, "activate-key", activate)
        }
        verify(exactly = 1) { administrationService.retire(scope, actor, 71L, retire) }
        verify(exactly = 1) {
            administrationService.replay(scope, actor, 91L, "replay-key", replay)
        }
    }

    @Test
    fun `async preview returns exact tenant polling location and configured retry after`() {
        every { accessChecker.requireTenant("clinic-a") } returns TenantInfo(11L, "clinic-a", "Clinic A")
        every { actorResolver.resolve(null, "clinic-a", null, "corr-tenant") } returns actor
        val response = previewResponse()
        every {
            administrationService.preview(
                PolicyScopeRef(11L, PolicyScope.TENANT_DEFAULT),
                actor,
                71L,
                any(),
            )
        } returns SchedulingPolicyPreviewSubmission(response, asynchronous = true)

        val http = controller.preview(
            tenantCode = "clinic-a",
            id = 71L,
            request = PreviewSchedulingPolicyRequest(
                expectedDraftRevision = 3L,
                expectedGeneration = PolicyGenerationRequest(9L, 0L),
            ),
            authentication = null,
            servletRequest = request("corr-tenant"),
        )

        http.statusCode shouldBeEqualTo HttpStatus.ACCEPTED
        http.headers.location.toString() shouldBeEqualTo
            "/api/clinic-a/admin/scheduling-policies/preview-jobs/301"
        http.headers.getFirst(HttpHeaders.RETRY_AFTER) shouldBeEqualTo "2"
        http.body?.data?.status shouldBeEqualTo PolicyPreviewJobStatus.PENDING
        verify(exactly = 1) {
            administrationService.preview(
                PolicyScopeRef(11L, PolicyScope.TENANT_DEFAULT),
                actor,
                71L,
                match {
                    it.expectedDraftRevision == 3L &&
                        it.expectedGeneration.tenantGeneration == 9L &&
                        it.expectedGeneration.clinicGeneration == 0L
                },
            )
        }
    }

    @Test
    fun `effective read rejects local date time before compiling policy`() {
        every { accessChecker.requireTenant("clinic-a") } returns TenantInfo(11L, "clinic-a", "Clinic A")
        every { actorResolver.resolve(null, "clinic-a", null, "corr-tenant") } returns actor

        assertFailsWith<IllegalArgumentException> {
            controller.effective(
                tenantCode = "clinic-a",
                decisionAt = "2026-07-28T09:00:00",
                serviceAt = "2026-07-28T10:00:00+09:00",
                authentication = null,
                servletRequest = request("corr-tenant"),
            )
        }
        verify(exactly = 0) { administrationService.tenantEffective(any(), any(), any(), any()) }
    }

    private fun previewResponse() = SchedulingPolicyPreviewResponse(
        jobId = 301L,
        definitionId = 71L,
        status = PolicyPreviewJobStatus.PENDING,
        pinnedRevision = 3L,
        pinnedGeneration = PolicyGenerationResponse(9L, 0L),
        progress = SchedulingPolicyPreviewProgressResponse(0L, 0L, null, null, null, null),
        resultHash = null,
        activationEvidenceToken = null,
        errorCode = null,
        correlationId = "corr-tenant",
    )

    private fun mutationResponse() = SchedulingPolicyMutationResponse(
        definitionId = 71L,
        draftRevision = 3L,
        lifecycle = PolicyLifecycle.DRAFT,
        generation = PolicyGenerationResponse(9L, 0L),
        scopeRevision = 2L,
        correlationId = "corr-tenant",
    )

    private fun activationResponse() = SchedulingPolicyActivationResponse(
        commandId = 91L,
        definitionId = 71L,
        draftRevision = 3L,
        lifecycle = PolicyLifecycle.ACTIVE,
        generation = PolicyGenerationResponse(9L, 0L),
        status = "COMPLETED",
        effectiveFrom = Instant.parse("2026-08-01T00:00:00Z"),
        idempotentReplay = false,
        correlationId = "corr-tenant",
    )

    private fun request(correlationId: String) = MockHttpServletRequest().apply {
        setAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE, correlationId)
    }

    private fun actor() = ActorContext(
        actorId = "tenant-admin",
        actorType = ActorType.ADMIN,
        roles = setOf(ActorRole.ADMIN),
        scopes = setOf("policy:write"),
        allowedTenantCodes = setOf("clinic-a"),
        allowedClinicIds = emptySet(),
        patientSubjectId = null,
        assurance = AuthenticationAssurance.MFA,
        issuer = "gateway",
        tokenId = "tenant-token",
        authenticatedAt = Instant.parse("2026-07-28T00:00:00Z"),
        correlationId = "corr-tenant",
    )
}
