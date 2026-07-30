package io.bluetape4k.clinic.appointment.api.profile

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitmentStatus
import io.bluetape4k.clinic.appointment.event.profile.ProfileReevaluationEventObservationResult
import io.bluetape4k.clinic.appointment.model.profile.ProfileReevaluationJobStatus
import io.bluetape4k.clinic.appointment.model.profile.ProfileReevaluationOutcomeType
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import java.time.Duration

class ProfileReevaluationMetricsTest {

    @Test
    fun `metric tag는 닫힌 결과 값만 사용하고 업무 식별자를 노출하지 않는다`() {
        val registry = SimpleMeterRegistry()
        val metrics = ProfileReevaluationMetrics(registry)

        metrics.recordEvent(ProfileReevaluationEventMetricResult.ACCEPTED)
        metrics.recordJob(ProfileReevaluationJobStatus.RUNNING)
        metrics.recordOutcome(
            AppointmentCommitmentStatus.HELD,
            ProfileReevaluationOutcomeType.HOLD_KEPT,
            Duration.ofMillis(20),
        )
        metrics.recordAssessment(ProfileAssessmentMetricResult.SUCCESS, Duration.ofMillis(10))
        metrics.recordOperational(ProfileReevaluationOperationalMetric.LEASE_LOST)
        metrics.recordDryRunParity(ProfileReevaluationParityMetric.MATCH)

        val forbidden = setOf("tenant", "clinic", "patient", "appointment", "event", "job_id")
        registry.meters
            .flatMap { it.id.tags }
            .any { it.key in forbidden }
            .shouldBeFalse()
    }

    @Test
    fun `운영상 지연은 기술 실패 재시도와 다른 metric으로 기록한다`() {
        val registry = SimpleMeterRegistry()
        val metrics = ProfileReevaluationMetrics(registry)

        metrics.recordOperational(ProfileReevaluationOperationalMetric.DEFER)
        metrics.recordOperational(ProfileReevaluationOperationalMetric.RETRY)

        registry.get(ProfileReevaluationMetrics.OPERATIONAL)
            .tag("result", "defer")
            .counter()
            .count() shouldBeEqualTo 1.0
        registry.get(ProfileReevaluationMetrics.OPERATIONAL)
            .tag("result", "retry")
            .counter()
            .count() shouldBeEqualTo 1.0
    }

    @Test
    fun `프로필 이벤트 관측 adapter는 quarantine을 rejected result로 기록한다`() {
        val registry = SimpleMeterRegistry()
        val metrics = ProfileReevaluationMetrics(registry)
        val observer = ProfileReevaluationMetricsEventObserver(metrics)

        observer.record(ProfileReevaluationEventObservationResult.REJECTED)

        registry.get(ProfileReevaluationMetrics.EVENTS)
            .tag("result", "rejected")
            .counter()
            .count() shouldBeEqualTo 1.0
        registry.get(ProfileReevaluationMetrics.EVENTS)
            .counter()
            .id.tags.map { it.key } shouldBeEqualTo listOf("result")
    }

    @Test
    fun `health는 집계값만 제공하고 식별자 detail을 만들지 않는다`() {
        val indicator = ProfileReevaluationHealthIndicator(
            source = ProfileReevaluationHealthSource {
                ProfileReevaluationOperationalSnapshot(
                    pendingJobs = 2,
                    failedJobs = 1,
                    oldestBacklogAge = Duration.ofMinutes(31),
                    drainState = ProfileReevaluationDrainState.DRAINING,
                )
            },
        )

        val health = indicator.health()

        health.status.code shouldBeEqualTo "DEGRADED"
        health.details.keys.intersect(
            setOf("tenantId", "clinicId", "patientId", "appointmentId", "eventId", "jobId"),
        ).isEmpty() shouldBeEqualTo true
    }

    @Test
    fun `정상 lease 연장이 확인되면 이전 연장 실패 상태를 해소한다`() {
        val monitor = ProfileReevaluationOperationalMonitor()

        monitor.leaseRenewalFailed()
        monitor.leaseRenewalSucceeded()

        monitor.enrich(ProfileReevaluationOperationalSnapshot())
            .leaseRenewalFailures shouldBeEqualTo 0L
    }
}
