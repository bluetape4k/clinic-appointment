package io.bluetape4k.clinic.appointment.api.controller

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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant

/**
 * clinic controller가 DB ownership과 principal membership을 먼저 확인하고 exact clinic
 * scope만 application service에 전달하는지 검증한다.
 */
class ClinicSchedulingPolicyControllerTest {

    private val administrationService = mockk<SchedulingPolicyAdministrationService>()
    private val accessChecker = mockk<TenantClinicAccessChecker>()
    private val actorResolver = mockk<ActorContextResolver>()
    private val actor = actor()
    private val controller = ClinicSchedulingPolicyController(
        administrationService,
        accessChecker,
        actorResolver,
        SchedulingPolicyProperties(previewPollInterval = Duration.ofMillis(1_500)),
    )

    @Test
    fun `clinic lifecycle routes preserve owned scope actor CAS evidence and idempotency headers`() {
        bindContext()
        val scope = PolicyScopeRef(11L, PolicyScope.CLINIC_OVERRIDE, 41L)
        val generation = PolicyGenerationRequest(9L, 2L)
        val draft = CreateSchedulingPolicyDraftRequest(
            kind = SchedulingPolicyKind.BOOKING_COMMITMENT,
            schemaVersion = 1,
            effectiveFrom = Instant.parse("2026-08-01T00:00:00Z"),
            effectiveUntil = null,
            payload = JsonMapper.builder().build().createObjectNode(),
            expectedScopeRevision = 2L,
            changeReason = "Introduce clinic override",
        )
        val validate = ValidateSchedulingPolicyRequest(expectedDraftRevision = 3L)
        val approve = ApproveSchedulingPolicyRequest(3L, "evidence-token", "Reviewed clinic impact")
        val schedule = ScheduleSchedulingPolicyRequest(
            3L,
            2L,
            generation,
            "evidence-token",
            draft.effectiveFrom,
            "Schedule clinic override",
        )
        val activate = ActivateSchedulingPolicyRequest(
            3L,
            2L,
            generation,
            "evidence-token",
            "Activate clinic override",
        )
        val retire = RetireSchedulingPolicyRequest(3L, generation, "Retire clinic override")
        val replay = ReplaySchedulingPolicyRequest(generation, "Replay clinic activation")
        val mutationResponse = mutationResponse()
        val approvalResponse = SchedulingPolicyApprovalResponse(
            71L,
            3L,
            Instant.parse("2026-07-28T00:10:00Z"),
            "corr-clinic",
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

        assertEquals(
            HttpStatus.CREATED,
            controller.createDraft("clinic-a", 41L, draft, null, request()).statusCode,
        )
        controller.validate("clinic-a", 41L, 71L, validate, null, request())
        controller.approve("clinic-a", 41L, 71L, approve, null, request())
        assertEquals(
            HttpStatus.ACCEPTED,
            controller.schedule("clinic-a", 41L, 71L, schedule, null, request()).statusCode,
        )
        controller.activate("clinic-a", 41L, 71L, "activate-key", activate, null, request())
        controller.retire("clinic-a", 41L, 71L, retire, null, request())
        controller.replay("clinic-a", 41L, 91L, "replay-key", replay, null, request())

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
    fun `async preview returns exact clinic polling location and scoped command`() {
        bindContext()
        every {
            administrationService.preview(
                PolicyScopeRef(11L, PolicyScope.CLINIC_OVERRIDE, 41L),
                actor,
                71L,
                any(),
            )
        } returns SchedulingPolicyPreviewSubmission(previewResponse(), asynchronous = true)

        val http = controller.preview(
            tenantCode = "clinic-a",
            clinicId = 41L,
            id = 71L,
            request = PreviewSchedulingPolicyRequest(3L, PolicyGenerationRequest(9L, 2L)),
            authentication = null,
            servletRequest = request(),
        )

        assertEquals(HttpStatus.ACCEPTED, http.statusCode)
        assertEquals(
            "/api/clinic-a/admin/clinics/41/scheduling-policies/preview-jobs/301",
            http.headers.location.toString(),
        )
        assertEquals("2", http.headers.getFirst(HttpHeaders.RETRY_AFTER))
        verify(exactly = 1) { accessChecker.verifyClinic("clinic-a", 41L) }
        verify(exactly = 1) {
            administrationService.preview(
                PolicyScopeRef(11L, PolicyScope.CLINIC_OVERRIDE, 41L),
                actor,
                71L,
                match { it.expectedGeneration.clinicGeneration == 2L },
            )
        }
    }

    @Test
    fun `effective read normalizes explicit offsets before clinic compilation`() {
        bindContext()
        val expected = mockk<EffectiveSchedulingPolicyResponse>()
        every {
            administrationService.clinicEffective(
                PolicyScopeRef(11L, PolicyScope.CLINIC_OVERRIDE, 41L),
                actor,
                Instant.parse("2026-07-28T00:00:00Z"),
                Instant.parse("2026-07-28T01:00:00Z"),
            )
        } returns expected

        val response = controller.effective(
            tenantCode = "clinic-a",
            clinicId = 41L,
            decisionAt = "2026-07-28T09:00:00+09:00",
            serviceAt = "2026-07-28T10:00:00+09:00",
            authentication = null,
            servletRequest = request(),
        )

        assertEquals(expected, response.data)
        verify(exactly = 1) {
            administrationService.clinicEffective(
                PolicyScopeRef(11L, PolicyScope.CLINIC_OVERRIDE, 41L),
                actor,
                Instant.parse("2026-07-28T00:00:00Z"),
                Instant.parse("2026-07-28T01:00:00Z"),
            )
        }
    }

    private fun bindContext() {
        every { accessChecker.verifyClinic("clinic-a", 41L) } returns
            TenantInfo(11L, "clinic-a", "Clinic A")
        every { actorResolver.resolve(null, "clinic-a", 41L, "corr-clinic") } returns actor
    }

    private fun previewResponse() = SchedulingPolicyPreviewResponse(
        jobId = 301L,
        definitionId = 71L,
        status = PolicyPreviewJobStatus.PENDING,
        pinnedRevision = 3L,
        pinnedGeneration = PolicyGenerationResponse(9L, 2L),
        progress = SchedulingPolicyPreviewProgressResponse(0L, 0L, null, null, null, null),
        resultHash = null,
        activationEvidenceToken = null,
        errorCode = null,
        correlationId = "corr-clinic",
    )

    private fun mutationResponse() = SchedulingPolicyMutationResponse(
        definitionId = 71L,
        draftRevision = 3L,
        lifecycle = PolicyLifecycle.DRAFT,
        generation = PolicyGenerationResponse(9L, 2L),
        scopeRevision = 2L,
        correlationId = "corr-clinic",
    )

    private fun activationResponse() = SchedulingPolicyActivationResponse(
        commandId = 91L,
        definitionId = 71L,
        draftRevision = 3L,
        lifecycle = PolicyLifecycle.ACTIVE,
        generation = PolicyGenerationResponse(9L, 2L),
        status = "COMPLETED",
        effectiveFrom = Instant.parse("2026-08-01T00:00:00Z"),
        idempotentReplay = false,
        correlationId = "corr-clinic",
    )

    private fun request() = MockHttpServletRequest().apply {
        setAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE, "corr-clinic")
    }

    private fun actor() = ActorContext(
        actorId = "clinic-staff",
        actorType = ActorType.STAFF,
        roles = setOf(ActorRole.STAFF),
        scopes = setOf("policy:write"),
        allowedTenantCodes = setOf("clinic-a"),
        allowedClinicIds = setOf(41L),
        patientSubjectId = null,
        assurance = AuthenticationAssurance.MFA,
        issuer = "gateway",
        tokenId = "clinic-token",
        authenticatedAt = Instant.parse("2026-07-28T00:00:00Z"),
        correlationId = "corr-clinic",
    )
}
