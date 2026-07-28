package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.api.policy.ExposedSchedulingPolicyPreviewStore
import io.bluetape4k.clinic.appointment.api.policy.ExposedSchedulingPolicyWorkerStore
import io.bluetape4k.clinic.appointment.api.policy.PolicyPreviewEvidenceVerifier
import io.bluetape4k.clinic.appointment.api.policy.ScheduledPolicyActivationExecutor
import io.bluetape4k.clinic.appointment.api.policy.SchedulingPolicyAdministrationService
import io.bluetape4k.clinic.appointment.api.policy.SchedulingPolicyCommandService
import io.bluetape4k.clinic.appointment.api.policy.SchedulingPolicyPreviewService
import io.bluetape4k.clinic.appointment.api.policy.SchedulingPolicyWorker
import io.bluetape4k.clinic.appointment.api.policy.TenantEffectiveSchedulingPolicyService
import io.bluetape4k.clinic.appointment.repository.SchedulingPolicyJobRepository
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.util.Base64

/**
 * 예약 정책의 비밀값 기반 fail-closed Spring 조립 경계를 검증한다.
 *
 * 기본 SaaS 배포는 정책 write·preview·worker 빈을 만들지 않는다. 운영자가 JWT와 분리된
 * idempotency HMAC 비밀값을 명시했을 때만 동일한 durable repository를 공유하는 command,
 * preview, evidence verifier, worker graph가 전부 생성되어야 한다. 일부만 생성되는 구성은
 * activation은 받지만 preview 증거를 검증하지 못하거나, durable 작업은 쌓지만 회수하지
 * 못하는 운영 장애가 되므로 이 테스트에서 허용하지 않는다.
 */
class SchedulingPolicyBeanWiringTest {

    private val contextRunner =
        ApplicationContextRunner()
            .withUserConfiguration(ServiceConfig::class.java)
            .withBean("meterRegistry", MeterRegistry::class.java, { SimpleMeterRegistry() })

    @Test
    fun `policy write preview and worker graph remains absent without dedicated secret`() {
        contextRunner.run { context ->
            assertBeanCount(context, TenantEffectiveSchedulingPolicyService::class.java, 1)
            assertBeanCount(context, SchedulingPolicyJobRepository::class.java, 0)
            assertBeanCount(context, SchedulingPolicyCommandService::class.java, 0)
            assertBeanCount(context, ExposedSchedulingPolicyPreviewStore::class.java, 0)
            assertBeanCount(context, SchedulingPolicyPreviewService::class.java, 0)
            assertBeanCount(context, PolicyPreviewEvidenceVerifier::class.java, 0)
            assertBeanCount(context, ExposedSchedulingPolicyWorkerStore::class.java, 0)
            assertBeanCount(context, ScheduledPolicyActivationExecutor::class.java, 0)
            assertBeanCount(context, SchedulingPolicyWorker::class.java, 0)
            assertBeanCount(context, SchedulingPolicyAdministrationService::class.java, 0)
        }
    }

    @Test
    fun `dedicated secret creates the complete durable command preview and worker graph`() {
        val encodedSecret = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })

        contextRunner
            .withPropertyValues("scheduling.policy.idempotency-hash-secret=$encodedSecret")
            .run { context ->
                assertBeanCount(context, SchedulingPolicyJobRepository::class.java, 1)
                assertBeanCount(context, SchedulingPolicyCommandService::class.java, 1)
                assertBeanCount(context, ExposedSchedulingPolicyPreviewStore::class.java, 1)
                assertBeanCount(context, SchedulingPolicyPreviewService::class.java, 1)
                assertBeanCount(context, PolicyPreviewEvidenceVerifier::class.java, 1)
                assertBeanCount(context, ExposedSchedulingPolicyWorkerStore::class.java, 1)
                assertBeanCount(context, ScheduledPolicyActivationExecutor::class.java, 1)
                assertBeanCount(context, SchedulingPolicyWorker::class.java, 1)
                assertBeanCount(context, SchedulingPolicyAdministrationService::class.java, 1)
            }
    }

    private fun <T : Any> assertBeanCount(
        context: org.springframework.context.ApplicationContext,
        type: Class<T>,
        expected: Int,
    ) {
        context.getBeansOfType(type).size shouldBeEqualTo expected
    }
}
