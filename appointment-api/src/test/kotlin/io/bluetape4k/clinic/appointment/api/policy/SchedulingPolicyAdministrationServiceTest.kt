package io.bluetape4k.clinic.appointment.api.policy

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBe
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.clinic.appointment.api.config.SchedulingPolicyApiException
import io.bluetape4k.clinic.appointment.api.config.SchedulingPolicyErrorCode
import io.bluetape4k.clinic.appointment.api.config.SchedulingPolicyProperties
import io.bluetape4k.clinic.appointment.api.dto.CreateSchedulingPolicyDraftRequest
import io.bluetape4k.clinic.appointment.api.dto.PolicyGenerationRequest
import io.bluetape4k.clinic.appointment.api.dto.PreviewSchedulingPolicyRequest
import io.bluetape4k.clinic.appointment.api.security.ActorContext
import io.bluetape4k.clinic.appointment.api.security.ActorType
import io.bluetape4k.clinic.appointment.api.security.AuthenticationAssurance
import io.bluetape4k.clinic.appointment.model.dto.PolicyScopeRef
import io.bluetape4k.clinic.appointment.model.policy.ActorRole
import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import io.bluetape4k.clinic.appointment.model.policy.SchedulingPolicyKind
import io.bluetape4k.clinic.appointment.repository.SchedulingPolicyJobRepository
import io.bluetape4k.clinic.appointment.repository.SchedulingPolicyRepository
import io.mockk.mockk
import io.mockk.every
import io.mockk.verify
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Instant
import tools.jackson.databind.json.JsonMapper

/**
 * 정책 HTTP facade가 단계적 rollout 설정에서도 처리 불가능한 durable 작업을 만들지
 * 않는지 검증한다.
 *
 * `adminWriteEnabled=true`, `previewWorkerEnabled=false`는 draft 관리 단계의 합법적인
 * 구성이다. 이때 preview가 동기 한도를 넘으면 worker가 회수하지 못하는 `PENDING` 행을
 * 만들 수 있으므로, facade는 preview service 호출 전에 route를 fail-closed 해야 한다.
 */
class SchedulingPolicyAdministrationServiceTest {

    private val previewService = mockk<SchedulingPolicyPreviewService>(relaxed = true)
    private val commandService = mockk<SchedulingPolicyCommandService>()
    private val registry = SimpleMeterRegistry()
    private val service = SchedulingPolicyAdministrationService(
        commandService = commandService,
        previewService = previewService,
        previewStore = mockk(),
        previewVerifier = mockk(),
        policyRepository = mockk<SchedulingPolicyRepository>(),
        jobRepository = mockk<SchedulingPolicyJobRepository>(),
        tenantEffectiveService = mockk(),
        clinicEffectiveService = mockk(),
        metrics = SchedulingPolicyMetrics(registry),
        properties = SchedulingPolicyProperties(
            shadowCompileEnabled = true,
            effectiveReadEnabled = true,
            adminWriteEnabled = true,
            previewWorkerEnabled = false,
        ),
    )

    @Test
    fun `preview is hidden before worker rollout so no orphan async job can be created`() {
        val failure = assertFailsWith<SchedulingPolicyApiException> {
            service.preview(
                scope = PolicyScopeRef(11L, PolicyScope.TENANT_DEFAULT),
                actor = actor(),
                definitionId = 71L,
                request = PreviewSchedulingPolicyRequest(
                    expectedDraftRevision = 3L,
                    expectedGeneration = PolicyGenerationRequest(9L, 0L),
                ),
            )
        }

        failure.errorCode shouldBeEqualTo SchedulingPolicyErrorCode.POLICY_RESOURCE_NOT_FOUND
        verify(exactly = 0) { previewService.submit(any()) }
        registry
            .get("clinic.scheduling.policy.administration")
            .tags(
                "result", "rejected",
                "operation", "preview",
                "scope_type", "tenant_default",
            )
            .counter()
            .count() shouldBeEqualTo 1.0
    }

    @Test
    fun `metric failure never changes successful result or original application exception`() {
        val brokenMetrics = mockk<SchedulingPolicyMetrics>()
        every {
            brokenMetrics.recordAdministration(any(), any(), any())
        } throws IllegalStateException("meter registry unavailable")
        val resilientService = serviceWith(brokenMetrics)
        val scope = PolicyScopeRef(11L, PolicyScope.TENANT_DEFAULT)

        resilientService.observe(
            PolicyAdministrationMetricOperation.VALIDATE,
            scope,
        ) {
            "committed"
        } shouldBeEqualTo "committed"

        val original = IOException("repository adapter failed")
        val propagated = assertFailsWith<IOException> {
            resilientService.observe(
                PolicyAdministrationMetricOperation.VALIDATE,
                scope,
            ) {
                throw original
            }
        }
        propagated shouldBe original
        verify(exactly = 1) {
            brokenMetrics.recordAdministration(
                PolicyAdministrationMetricResult.SUCCEEDED,
                PolicyAdministrationMetricOperation.VALIDATE,
                PolicyScope.TENANT_DEFAULT,
            )
        }
        verify(exactly = 1) {
            brokenMetrics.recordAdministration(
                PolicyAdministrationMetricResult.REJECTED,
                PolicyAdministrationMetricOperation.VALIDATE,
                PolicyScope.TENANT_DEFAULT,
            )
        }
    }

    @Test
    fun `new reliability drafts require the current schema`() {
        assertFailsWith<IllegalArgumentException> {
            service.createDraft(
                scope = PolicyScopeRef(11L, PolicyScope.TENANT_DEFAULT),
                actor = actor(),
                request = CreateSchedulingPolicyDraftRequest(
                    kind = SchedulingPolicyKind.PRIORITY_AND_RELIABILITY,
                    schemaVersion = 1,
                    effectiveFrom = Instant.parse("2026-08-01T00:00:00Z"),
                    effectiveUntil = null,
                    payload = JsonMapper.builder().build().createObjectNode(),
                    expectedScopeRevision = 0L,
                    changeReason = "Introduce reliability thresholds",
                ),
            )
        }
        verify(exactly = 0) { commandService.createDraft(any()) }
    }

    private fun serviceWith(metrics: SchedulingPolicyMetrics) =
        SchedulingPolicyAdministrationService(
            commandService = mockk(),
            previewService = previewService,
            previewStore = mockk(),
            previewVerifier = mockk(),
            policyRepository = mockk<SchedulingPolicyRepository>(),
            jobRepository = mockk<SchedulingPolicyJobRepository>(),
            tenantEffectiveService = mockk(),
            clinicEffectiveService = mockk(),
            metrics = metrics,
            properties = SchedulingPolicyProperties(
                shadowCompileEnabled = true,
                effectiveReadEnabled = true,
                adminWriteEnabled = true,
                previewWorkerEnabled = false,
            ),
        )

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
