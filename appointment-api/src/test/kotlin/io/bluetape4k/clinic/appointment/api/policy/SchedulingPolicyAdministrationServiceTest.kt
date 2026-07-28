package io.bluetape4k.clinic.appointment.api.policy

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.api.config.SchedulingPolicyApiException
import io.bluetape4k.clinic.appointment.api.config.SchedulingPolicyErrorCode
import io.bluetape4k.clinic.appointment.api.config.SchedulingPolicyProperties
import io.bluetape4k.clinic.appointment.api.dto.PolicyGenerationRequest
import io.bluetape4k.clinic.appointment.api.dto.PreviewSchedulingPolicyRequest
import io.bluetape4k.clinic.appointment.api.security.ActorContext
import io.bluetape4k.clinic.appointment.api.security.ActorType
import io.bluetape4k.clinic.appointment.api.security.AuthenticationAssurance
import io.bluetape4k.clinic.appointment.model.dto.PolicyScopeRef
import io.bluetape4k.clinic.appointment.model.policy.ActorRole
import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import io.bluetape4k.clinic.appointment.repository.SchedulingPolicyJobRepository
import io.bluetape4k.clinic.appointment.repository.SchedulingPolicyRepository
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

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
    private val service = SchedulingPolicyAdministrationService(
        commandService = mockk(),
        previewService = previewService,
        previewStore = mockk(),
        previewVerifier = mockk(),
        policyRepository = mockk<SchedulingPolicyRepository>(),
        jobRepository = mockk<SchedulingPolicyJobRepository>(),
        tenantEffectiveService = mockk(),
        clinicEffectiveService = mockk(),
        properties = SchedulingPolicyProperties(
            shadowCompileEnabled = true,
            effectiveReadEnabled = true,
            adminWriteEnabled = true,
            previewWorkerEnabled = false,
        ),
    )

    @Test
    fun `preview is hidden before worker rollout so no orphan async job can be created`() {
        val failure = assertThrows<SchedulingPolicyApiException> {
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
