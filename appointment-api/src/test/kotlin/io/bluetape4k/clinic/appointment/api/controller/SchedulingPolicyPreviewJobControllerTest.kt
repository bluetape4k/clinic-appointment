package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.clinic.appointment.api.config.SchedulingPolicyApiException
import io.bluetape4k.clinic.appointment.api.config.SchedulingPolicyErrorCode
import io.bluetape4k.clinic.appointment.api.config.SchedulingPolicyProperties
import io.bluetape4k.clinic.appointment.api.dto.PolicyGenerationResponse
import io.bluetape4k.clinic.appointment.api.dto.SchedulingPolicyPreviewProgressResponse
import io.bluetape4k.clinic.appointment.api.dto.SchedulingPolicyPreviewResponse
import io.bluetape4k.clinic.appointment.api.policy.SchedulingPolicyAdministrationService
import io.bluetape4k.clinic.appointment.api.policy.SchedulingPolicyPollingLimiter
import io.bluetape4k.clinic.appointment.api.security.ActorContext
import io.bluetape4k.clinic.appointment.api.security.ActorContextResolver
import io.bluetape4k.clinic.appointment.api.security.ActorType
import io.bluetape4k.clinic.appointment.api.security.AuthenticationAssurance
import io.bluetape4k.clinic.appointment.api.security.CorrelationIdFilter
import io.bluetape4k.clinic.appointment.api.tenant.TenantClinicAccessChecker
import io.bluetape4k.clinic.appointment.api.tenant.TenantInfo
import io.bluetape4k.clinic.appointment.model.dto.PolicyPreviewJobStatus
import io.bluetape4k.clinic.appointment.model.dto.PolicyScopeRef
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyPreviewJobRecord
import io.bluetape4k.clinic.appointment.model.policy.ActorRole
import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletRequest
import java.time.Duration
import java.time.Instant

/**
 * preview GET이 저장된 job projection만 scope-aware로 읽고 비종결 상태에 polling
 * backoff를 제공하는지 검증한다.
 */
class SchedulingPolicyPreviewJobControllerTest {

    private val administrationService = mockk<SchedulingPolicyAdministrationService>()
    private val accessChecker = mockk<TenantClinicAccessChecker>()
    private val actorResolver = mockk<ActorContextResolver>()
    private val actor = actor()
    private val properties = SchedulingPolicyProperties(previewPollInterval = Duration.ofMillis(1_500))
    private val controller = SchedulingPolicyPreviewJobController(
        administrationService,
        accessChecker,
        actorResolver,
        properties,
    )

    @Test
    fun `tenant pending job returns configured retry after with exact scope`() {
        every { accessChecker.requireTenant("clinic-a") } returns TenantInfo(11L, "clinic-a", "Clinic A")
        every { actorResolver.resolve(null, "clinic-a", null, "corr-job") } returns actor
        every {
            administrationService.previewJob(
                PolicyScopeRef(11L, PolicyScope.TENANT_DEFAULT),
                actor,
                301L,
            )
        } returns response(PolicyPreviewJobStatus.PENDING)

        val http = controller.tenantJob("clinic-a", 301L, null, request())

        http.headers.getFirst(HttpHeaders.RETRY_AFTER) shouldBeEqualTo "2"
        http.body?.data?.status shouldBeEqualTo PolicyPreviewJobStatus.PENDING
        verify(exactly = 1) {
            administrationService.previewJob(
                PolicyScopeRef(11L, PolicyScope.TENANT_DEFAULT),
                actor,
                301L,
            )
        }
    }

    @Test
    fun `clinic completed job omits retry after and keeps terminal evidence`() {
        every { accessChecker.verifyClinic("clinic-a", 41L) } returns TenantInfo(11L, "clinic-a", "Clinic A")
        every { actorResolver.resolve(null, "clinic-a", 41L, "corr-job") } returns actor
        every {
            administrationService.previewJob(
                PolicyScopeRef(11L, PolicyScope.CLINIC_OVERRIDE, 41L),
                actor,
                301L,
            )
        } returns response(PolicyPreviewJobStatus.COMPLETED)

        val http = controller.clinicJob("clinic-a", 41L, 301L, null, request())

        http.headers.getFirst(HttpHeaders.RETRY_AFTER).shouldBeNull()
        http.body?.data?.activationEvidenceToken shouldBeEqualTo "evidence-token"
    }

    @Test
    fun `polling limiter isolates keys and rejects only an early repeat`() {
        var now = 1_000_000_000L
        val limiter = SchedulingPolicyPollingLimiter(
            SchedulingPolicyProperties(previewPollInterval = Duration.ofSeconds(1)),
            monotonicNanos = { now },
        )
        val tenantScope = PolicyScopeRef(11L, PolicyScope.TENANT_DEFAULT)
        val clinicScope = PolicyScopeRef(11L, PolicyScope.CLINIC_OVERRIDE, 41L)

        limiter.requireAllowed(tenantScope, record(PolicyPreviewJobStatus.RUNNING, 301L, tenantScope))
        val repeated = assertFailsWith<SchedulingPolicyApiException> {
            limiter.requireAllowed(tenantScope, record(PolicyPreviewJobStatus.RUNNING, 301L, tenantScope))
        }
        repeated.errorCode shouldBeEqualTo SchedulingPolicyErrorCode.POLICY_PREVIEW_LIMITED

        limiter.requireAllowed(clinicScope, record(PolicyPreviewJobStatus.RUNNING, 301L, clinicScope))
        now += Duration.ofSeconds(1).toNanos()
        limiter.requireAllowed(tenantScope, record(PolicyPreviewJobStatus.RUNNING, 301L, tenantScope))
    }

    @Test
    fun `polling limiter enforces a hard tracked job cap and admits new work after expiry`() {
        var now = 1_000_000_000L
        val limiter = SchedulingPolicyPollingLimiter(
            SchedulingPolicyProperties(previewPollInterval = Duration.ofSeconds(1)),
            monotonicNanos = { now },
        )
        val tenantScope = PolicyScopeRef(11L, PolicyScope.TENANT_DEFAULT)

        repeat(10_000) { index ->
            limiter.requireAllowed(
                tenantScope,
                record(PolicyPreviewJobStatus.RUNNING, index.toLong() + 1L, tenantScope),
            )
        }
        val saturated = assertFailsWith<SchedulingPolicyApiException> {
            limiter.requireAllowed(
                tenantScope,
                record(PolicyPreviewJobStatus.RUNNING, 10_001L, tenantScope),
            )
        }
        saturated.errorCode shouldBeEqualTo SchedulingPolicyErrorCode.POLICY_PREVIEW_LIMITED

        now += Duration.ofSeconds(1).toNanos()
        limiter.requireAllowed(
            tenantScope,
            record(PolicyPreviewJobStatus.RUNNING, 10_001L, tenantScope),
        )
    }

    private fun response(status: PolicyPreviewJobStatus) = SchedulingPolicyPreviewResponse(
        jobId = 301L,
        definitionId = 71L,
        status = status,
        pinnedRevision = 3L,
        pinnedGeneration = PolicyGenerationResponse(9L, 0L),
        progress = SchedulingPolicyPreviewProgressResponse(20L, 4L, null, null, null, null),
        resultHash = "result-hash".takeIf { status == PolicyPreviewJobStatus.COMPLETED },
        activationEvidenceToken = "evidence-token".takeIf { status == PolicyPreviewJobStatus.COMPLETED },
        errorCode = null,
        correlationId = "corr-job",
    )

    private fun record(
        status: PolicyPreviewJobStatus,
        id: Long,
        scope: PolicyScopeRef,
    ) = SchedulingPolicyPreviewJobRecord(
        id = id,
        tenantGroupId = scope.tenantGroupId,
        scope = scope.scope,
        clinicId = scope.clinicId,
        definitionId = 71L,
        draftRevision = 3L,
        tenantGeneration = 9L,
        clinicGeneration = if (scope.scope == PolicyScope.CLINIC_OVERRIDE) 2L else 0L,
        partitionCount = 1,
        status = status,
        deadlineAt = Instant.parse("2026-07-28T01:00:00Z"),
        nextAttemptAt = Instant.parse("2026-07-28T00:00:00Z"),
    )

    private fun request() = MockHttpServletRequest().apply {
        setAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE, "corr-job")
    }

    private fun actor() = ActorContext(
        actorId = "policy-admin",
        actorType = ActorType.ADMIN,
        roles = setOf(ActorRole.ADMIN),
        scopes = setOf("policy:write"),
        allowedTenantCodes = setOf("clinic-a"),
        allowedClinicIds = setOf(41L),
        patientSubjectId = null,
        assurance = AuthenticationAssurance.MFA,
        issuer = "gateway",
        tokenId = "job-token",
        authenticatedAt = Instant.parse("2026-07-28T00:00:00Z"),
        correlationId = "corr-job",
    )
}
