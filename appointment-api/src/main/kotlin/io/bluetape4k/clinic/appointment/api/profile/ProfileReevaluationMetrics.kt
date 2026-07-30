package io.bluetape4k.clinic.appointment.api.profile

import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitmentStatus
import io.bluetape4k.clinic.appointment.model.profile.ProfileReevaluationJobStatus
import io.bluetape4k.clinic.appointment.model.profile.ProfileReevaluationOutcomeType
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Duration

/**
 * 프로필 재평가의 저카디널리티 운영 지표를 기록합니다.
 *
 * 모든 tag 값은 닫힌 enum에서만 가져옵니다. tenant, clinic, patient, appointment,
 * event 식별자는 metric 이름과 tag에 포함하지 않습니다.
 */
class ProfileReevaluationMetrics(
    private val registry: MeterRegistry,
    private val monitor: ProfileReevaluationOperationalMonitor =
        ProfileReevaluationOperationalMonitor(),
) {
    fun recordEvent(result: ProfileReevaluationEventMetricResult) =
        counter(EVENTS, "result", result.metricValue).increment()

    fun recordJob(status: ProfileReevaluationJobStatus) =
        counter(JOBS, "status", status.name.lowercase()).increment()

    fun recordOutcome(
        status: AppointmentCommitmentStatus,
        outcome: ProfileReevaluationOutcomeType,
        duration: Duration,
    ) {
        Counter.builder(OUTCOMES)
            .tags(
                "commitment_status", status.name.lowercase(),
                "outcome", outcome.name.lowercase(),
            )
            .register(registry)
            .increment()
        Timer.builder(PROCESSING_DURATION)
            .publishPercentileHistogram()
            .tags(
                "commitment_status", status.name.lowercase(),
                "outcome", outcome.name.lowercase(),
            )
            .register(registry)
            .record(duration)
    }

    fun recordFairWait(duration: Duration) =
        Timer.builder(FAIR_WAIT)
            .publishPercentileHistogram()
            .register(registry)
            .record(duration)

    fun recordAssessment(
        result: ProfileAssessmentMetricResult,
        duration: Duration,
    ) {
        if (result == ProfileAssessmentMetricResult.SUCCESS) {
            monitor.assessmentSucceeded()
        } else {
            monitor.assessmentFailed()
        }
        Timer.builder(ASSESSMENT_LATENCY)
            .publishPercentileHistogram()
            .tag("result", result.value)
            .register(registry)
            .record(duration)
    }

    fun recordOperational(result: ProfileReevaluationOperationalMetric) {
        if (result == ProfileReevaluationOperationalMetric.LEASE_LOST) {
            monitor.leaseRenewalFailed()
        }
        counter(OPERATIONAL, "result", result.metricValue).increment()
    }

    fun recordLeaseRenewalSucceeded() {
        monitor.leaseRenewalSucceeded()
    }

    fun recordDryRunParity(result: ProfileReevaluationParityMetric) =
        counter(DRY_RUN_PARITY, "result", result.metricValue).increment()

    private fun counter(
        name: String,
        key: String,
        value: String,
    ): Counter = Counter.builder(name).tag(key, value).register(registry)

    companion object {
        const val EVENTS = "clinic.profile.reevaluation.events"
        const val JOBS = "clinic.profile.reevaluation.jobs"
        const val OUTCOMES = "clinic.profile.reevaluation.outcomes"
        const val FAIR_WAIT = "clinic.profile.reevaluation.fair.wait"
        const val PROCESSING_DURATION = "clinic.profile.reevaluation.processing.duration"
        const val ASSESSMENT_LATENCY = "clinic.profile.reevaluation.assessment.latency"
        const val OPERATIONAL = "clinic.profile.reevaluation.operational"
        const val DRY_RUN_PARITY = "clinic.profile.reevaluation.dryrun.parity"
    }
}

enum class ProfileReevaluationEventMetricResult(val metricValue: String) {
    ACCEPTED("accepted"),
    REJECTED("rejected"),
    STALE("stale"),
}

enum class ProfileReevaluationOperationalMetric(val metricValue: String) {
    LEASE_LOST("lease_lost"),
    RETRY("retry"),
    FAILED("failed"),
    REDRIVE("redrive"),
}

enum class ProfileReevaluationParityMetric(val metricValue: String) {
    MATCH("match"),
    DIFFERENT("different"),
}
