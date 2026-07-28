package io.bluetape4k.clinic.appointment.api.policy

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import io.bluetape4k.clinic.appointment.model.policy.SchedulingPolicyKind
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * 정책 관측 지표가 SaaS tenant 수나 환자·예약 수에 따라 label cardinality를 늘리지 않는지
 * 검증한다.
 *
 * 테스트는 모든 meter tag key가 `result`, `kind`, `scope_type` allowlist 안에 있는지 직접
 * 확인한다. tenant ID, clinic ID, actor ID, token, correlation ID, payload, 예외 메시지는
 * 어떤 metric API에도 인자로 전달하지 않는다.
 */
class SchedulingPolicyMetricsTest {

    @Test
    fun `all policy meters use only approved low cardinality tags`() {
        val registry = SimpleMeterRegistry()
        val metrics = SchedulingPolicyMetrics(registry)

        metrics.recordActivation(
            result = PolicyActivationMetricResult.COMPLETED,
            kind = SchedulingPolicyKind.BOOKING_COMMITMENT,
            scope = PolicyScope.CLINIC_OVERRIDE,
        )
        metrics.recordActivationLateness(
            lateness = Duration.ofSeconds(75),
            kind = SchedulingPolicyKind.BOOKING_COMMITMENT,
            scope = PolicyScope.CLINIC_OVERRIDE,
        )
        metrics.recordPreview(
            result = PolicyPreviewMetricResult.ACCEPTED_ASYNC,
            kind = SchedulingPolicyKind.BOOKING_COMMITMENT,
            scope = PolicyScope.CLINIC_OVERRIDE,
        )
        metrics.recordCompile(
            PolicyCompileMetricResult.COLD,
            SchedulingPolicyKind.BOOKING_COMMITMENT,
            PolicyScope.TENANT_DEFAULT,
        )
        metrics.recordCache(
            PolicyCacheMetricResult.STALE_REJECTION,
            SchedulingPolicyKind.BOOKING_COMMITMENT,
            PolicyScope.TENANT_DEFAULT,
        )
        metrics.recordEffectiveRead(PolicyEffectiveReadMetricResult.UNAVAILABLE, PolicyScope.TENANT_DEFAULT)
        metrics.recordOutbox(PolicyOutboxMetricResult.PENDING)
        metrics.recordAggregateNull(PolicyAggregateMetricKind.APPOINTMENT)
        metrics.recordDualWriteParity(PolicyDualWriteMetricResult.MATCHED)
        metrics.recordAdministration(
            result = PolicyAdministrationMetricResult.REJECTED,
            operation = PolicyAdministrationMetricOperation.PREVIEW,
            scope = PolicyScope.TENANT_DEFAULT,
        )

        registry.meters.isNotEmpty().shouldBeTrue()
        registry.meters
            .flatMap { it.id.tags }
            .map { it.key }
            .all(APPROVED_TAG_KEYS::contains)
            .shouldBeTrue()
        registry
            .get("clinic.scheduling.policy.activation")
            .tag("result", "completed")
            .counter()
            .count() shouldBeEqualTo 1.0
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
    fun `registry failure is isolated from every worker control flow metric`() {
        val registry = SimpleMeterRegistry()
        registry.config().onMeterAdded {
            throw IllegalStateException("simulated registry failure")
        }
        val metrics = SchedulingPolicyMetrics(registry)

        metrics.recordActivationLateness(
            Duration.ofMinutes(6),
            SchedulingPolicyKind.BOOKING_COMMITMENT,
            PolicyScope.TENANT_DEFAULT,
        )
        metrics.recordActivation(
            PolicyActivationMetricResult.COMPLETED,
            SchedulingPolicyKind.BOOKING_COMMITMENT,
            PolicyScope.TENANT_DEFAULT,
        )
        metrics.recordPreview(
            PolicyPreviewMetricResult.COMPLETED_ASYNC,
            SchedulingPolicyKind.BOOKING_COMMITMENT,
            PolicyScope.CLINIC_OVERRIDE,
        )
    }

    private companion object {
        val APPROVED_TAG_KEYS = setOf("result", "kind", "operation", "scope_type")
    }
}
