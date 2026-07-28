package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * scheduling-policy 기능 플래그와 worker 안전 상한의 fail-closed 계약을 검증한다.
 *
 * SaaS 병원마다 정책을 다르게 운영하더라도 새 배포가 자동으로 정책 읽기나 비동기 작업을
 * 시작하면 안 된다. 또한 후행 기능은 선행 기능이 활성화된 경우에만 켤 수 있어야 하며,
 * 페이지·queue·lease·tick 상한은 설정 오류 때문에 무제한 값으로 확장될 수 없어야 한다.
 */
class SchedulingPolicyPropertiesTest {

    @Test
    fun `all policy features are disabled and worker limits are bounded by default`() {
        val properties = SchedulingPolicyProperties()

        properties.shadowCompileEnabled.shouldBeFalse()
        properties.effectiveReadEnabled.shouldBeFalse()
        properties.adminWriteEnabled.shouldBeFalse()
        properties.previewWorkerEnabled.shouldBeFalse()
        properties.scheduledActivationEnabled.shouldBeFalse()

        properties.previewPageSize shouldBeEqualTo 5_000
        properties.previewSyncRowLimit shouldBeEqualTo 10_000
        properties.previewSyncDeadline shouldBeEqualTo Duration.ofSeconds(2)
        properties.previewQueueCapacity shouldBeEqualTo 100
        properties.previewTenantConcurrency shouldBeEqualTo 2
        properties.maxPreviewJobsPerTick shouldBeEqualTo 10
        properties.maxActivationClaimsPerTick shouldBeEqualTo 25
        properties.workerPollInterval shouldBeEqualTo Duration.ofSeconds(1)
    }

    @Test
    fun `feature flags reject every dependency order inversion`() {
        assertFailsWith<IllegalArgumentException> {
            SchedulingPolicyProperties(effectiveReadEnabled = true)
        }
        assertFailsWith<IllegalArgumentException> {
            SchedulingPolicyProperties(
                shadowCompileEnabled = true,
                adminWriteEnabled = true,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SchedulingPolicyProperties(
                shadowCompileEnabled = true,
                effectiveReadEnabled = true,
                previewWorkerEnabled = true,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SchedulingPolicyProperties(
                shadowCompileEnabled = true,
                effectiveReadEnabled = true,
                adminWriteEnabled = true,
                scheduledActivationEnabled = true,
            )
        }
    }

    @Test
    fun `feature flags accept only the documented progressive rollout chain`() {
        val rolloutStates = listOf(
            SchedulingPolicyProperties(shadowCompileEnabled = true),
            SchedulingPolicyProperties(
                shadowCompileEnabled = true,
                effectiveReadEnabled = true,
            ),
            SchedulingPolicyProperties(
                shadowCompileEnabled = true,
                effectiveReadEnabled = true,
                adminWriteEnabled = true,
            ),
            SchedulingPolicyProperties(
                shadowCompileEnabled = true,
                effectiveReadEnabled = true,
                adminWriteEnabled = true,
                previewWorkerEnabled = true,
            ),
            SchedulingPolicyProperties(
                shadowCompileEnabled = true,
                effectiveReadEnabled = true,
                adminWriteEnabled = true,
                previewWorkerEnabled = true,
                scheduledActivationEnabled = true,
            ),
        )

        rolloutStates.map { properties ->
            listOf(
                properties.shadowCompileEnabled,
                properties.effectiveReadEnabled,
                properties.adminWriteEnabled,
                properties.previewWorkerEnabled,
                properties.scheduledActivationEnabled,
            )
        } shouldBeEqualTo listOf(
            listOf(true, false, false, false, false),
            listOf(true, true, false, false, false),
            listOf(true, true, true, false, false),
            listOf(true, true, true, true, false),
            listOf(true, true, true, true, true),
        )
    }

    @Test
    fun `worker resource and time limits reject unbounded or internally inconsistent values`() {
        assertFailsWith<IllegalArgumentException> {
            SchedulingPolicyProperties(previewPageSize = 5_001)
        }
        assertFailsWith<IllegalArgumentException> {
            SchedulingPolicyProperties(
                previewPageSize = 5_000,
                previewSyncRowLimit = 4_999,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SchedulingPolicyProperties(previewQueueCapacity = 101)
        }
        assertFailsWith<IllegalArgumentException> {
            SchedulingPolicyProperties(previewTenantConcurrency = 3)
        }
        assertFailsWith<IllegalArgumentException> {
            SchedulingPolicyProperties(previewSyncDeadline = Duration.ZERO)
        }
        assertFailsWith<IllegalArgumentException> {
            SchedulingPolicyProperties(workerPollInterval = Duration.ofMinutes(1).plusMillis(1))
        }
        assertFailsWith<IllegalArgumentException> {
            SchedulingPolicyProperties(
                activationLatenessWarning = Duration.ofMinutes(6),
                activationMissedAfter = Duration.ofMinutes(5),
            )
        }
    }
}
