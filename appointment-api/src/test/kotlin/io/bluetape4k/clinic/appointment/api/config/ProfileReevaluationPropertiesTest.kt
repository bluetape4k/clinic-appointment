package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationMutationMode
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitmentStatus
import io.bluetape4k.clinic.appointment.model.profile.ProfileReevaluationTargets
import io.bluetape4k.clinic.appointment.service.ProfileReevaluationTargetResolver
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Duration

class ProfileReevaluationPropertiesTest {

    @Test
    fun `기본값은 재평가를 비활성화하고 dry run 단계로 고정한다`() {
        val properties = ProfileReevaluationProperties()

        properties.enabled.shouldBeFalse()
        properties.mutationMode shouldBeEqualTo ProfileReevaluationMutationMode.DRY_RUN
        properties.clinicAllowlist.isEmpty().shouldBeTrue()
        properties.runtimeAccess().allows(scope(1L)).shouldBeFalse()
        properties.targetFor(AppointmentCommitmentStatus.HELD) shouldBeEqualTo Duration.ofMinutes(5)
        properties.targetFor(AppointmentCommitmentStatus.PROPOSED) shouldBeEqualTo Duration.ofMinutes(30)
    }

    @Test
    fun `활성화에는 https 고정 host와 명시적 병원 allowlist가 필요하다`() {
        assertFailsWith<IllegalArgumentException> {
            ProfileReevaluationProperties(enabled = true)
        }
        assertFailsWith<IllegalArgumentException> {
            ProfileReevaluationProperties(
                enabled = true,
                assessment = ProfileAssessmentProperties(
                    baseUrl = URI("http://crm.example.test"),
                    allowedHosts = setOf("crm.example.test"),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProfileReevaluationProperties(
                enabled = true,
                assessment = ProfileAssessmentProperties(
                    baseUrl = URI("https://crm.example.test"),
                    allowedHosts = setOf("other.example.test"),
                ),
            )
        }

        val enabled = enabledProperties()
        enabled.runtimeAccess().allows(scope(11L)).shouldBeTrue()
        enabled.runtimeAccess().allows(scope(12L)).shouldBeFalse()
    }

    @Test
    fun `시간 동시성 페이지 재시도 상호 제약을 벗어난 설정은 거부한다`() {
        listOf<() -> Unit>(
            { ProfileReevaluationProperties(heldTarget = Duration.ofSeconds(59)) },
            { ProfileReevaluationProperties(proposedTarget = Duration.ofMinutes(121)) },
            {
                ProfileReevaluationProperties(
                    leaseDuration = Duration.ofSeconds(10),
                    leaseRenewInterval = Duration.ofSeconds(10),
                )
            },
            { ProfileReevaluationProperties(globalConcurrency = 0) },
            { ProfileReevaluationProperties(globalConcurrency = 2, perClinicConcurrency = 3) },
            { ProfileReevaluationProperties(pageSize = 0) },
            { ProfileReevaluationProperties(retryMaxAttempts = 0) },
            { ProfileReevaluationProperties(retryJitter = 1.01) },
        ).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { invalid() }
        }
    }

    @Test
    fun `disabled mode는 enabled 설정보다 우선하고 빈 allowlist는 아무 병원도 허용하지 않는다`() {
        enabledProperties(mutationMode = ProfileReevaluationMutationMode.DISABLED)
            .runtimeAccess()
            .allows(scope(11L))
            .shouldBeFalse()

        enabledProperties(clinicAllowlist = emptySet())
            .runtimeAccess()
            .allows(scope(11L))
            .shouldBeFalse()
    }

    @Test
    fun `처리 목표는 병원 테넌트 환경설정 순서로 해석한다`() {
        val platform = ProfileReevaluationProperties(
            heldTarget = Duration.ofMinutes(5),
            proposedTarget = Duration.ofMinutes(30),
        ).platformTargets()
        val tenant = ProfileReevaluationTargets(
            heldTarget = Duration.ofMinutes(4),
            proposedTarget = Duration.ofMinutes(20),
        )
        val clinic = ProfileReevaluationTargets(heldTarget = Duration.ofMinutes(2))

        ProfileReevaluationTargetResolver.resolve(
            AppointmentCommitmentStatus.HELD,
            platform,
            tenant,
            clinic,
        ) shouldBeEqualTo Duration.ofMinutes(2)
        ProfileReevaluationTargetResolver.resolve(
            AppointmentCommitmentStatus.PROPOSED,
            platform,
            tenant,
            clinic,
        ) shouldBeEqualTo Duration.ofMinutes(20)
        ProfileReevaluationTargetResolver.resolve(
            AppointmentCommitmentStatus.PROPOSED,
            platform,
        ) shouldBeEqualTo Duration.ofMinutes(30)
    }

    private fun enabledProperties(
        mutationMode: ProfileReevaluationMutationMode =
            ProfileReevaluationMutationMode.APPLY_PROPOSED_AND_HELD,
        clinicAllowlist: Set<Long> = setOf(11L),
    ): ProfileReevaluationProperties =
        ProfileReevaluationProperties(
            enabled = true,
            mutationMode = mutationMode,
            clinicAllowlist = clinicAllowlist,
            assessment = ProfileAssessmentProperties(
                baseUrl = URI("https://crm.example.test"),
                allowedHosts = setOf("crm.example.test"),
            ),
        )

    private fun scope(clinicId: Long) =
        io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationScope(
            tenantGroupId = 1L,
            clinicId = clinicId,
            patientReferenceFingerprint = "a".repeat(64),
        )
}
