package io.bluetape4k.clinic.appointment.api.profile

import kotlinx.coroutines.runBlocking
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 식별자를 노출하지 않고 재평가 backlog와 외부 의존성 상태를 요약합니다.
 */
class ProfileReevaluationHealthIndicator(
    private val source: ProfileReevaluationHealthSource,
    private val backlogWarningAge: Duration = Duration.ofMinutes(30),
    private val assessmentFailureThreshold: Int = 5,
) : HealthIndicator {
    init {
        require(backlogWarningAge.isPositive) { "backlogWarningAge must be positive" }
        require(assessmentFailureThreshold > 0) { "assessmentFailureThreshold must be positive" }
    }

    override fun health(): Health {
        val snapshot = runBlocking { source.snapshot() }
        val unhealthy =
            snapshot.consecutiveAssessmentFailures >= assessmentFailureThreshold ||
                snapshot.oldestBacklogAge >= backlogWarningAge ||
                snapshot.leaseRenewalFailures > 0
        val builder = if (unhealthy) Health.status("DEGRADED") else Health.up()
        return builder
            .withDetail("oldestBacklogAgeSeconds", snapshot.oldestBacklogAge.seconds)
            .withDetail("leaseRenewalFailures", snapshot.leaseRenewalFailures)
            .withDetail("consecutiveAssessmentFailures", snapshot.consecutiveAssessmentFailures)
            .withDetail("failedJobs", snapshot.failedJobs)
            .withDetail("drainState", snapshot.drainState.name)
            .build()
    }
}

fun interface ProfileReevaluationHealthSource {
    suspend fun snapshot(): ProfileReevaluationOperationalSnapshot
}

data class ProfileReevaluationOperationalSnapshot(
    val pendingJobs: Long = 0,
    val runningJobs: Long = 0,
    val retryWaitJobs: Long = 0,
    val failedJobs: Long = 0,
    val activeLeases: Long = 0,
    val oldestBacklogAge: Duration = Duration.ZERO,
    val leaseRenewalFailures: Long = 0,
    val consecutiveAssessmentFailures: Int = 0,
    val drainState: ProfileReevaluationDrainState = ProfileReevaluationDrainState.DRAINED,
) {
    init {
        require(
            listOf(
                pendingJobs,
                runningJobs,
                retryWaitJobs,
                failedJobs,
                activeLeases,
                leaseRenewalFailures,
            ).all { it >= 0 },
        ) { "operational counts must be non-negative" }
        require(!oldestBacklogAge.isNegative) { "oldestBacklogAge must be non-negative" }
        require(consecutiveAssessmentFailures >= 0) {
            "consecutiveAssessmentFailures must be non-negative"
        }
    }
}

enum class ProfileReevaluationDrainState {
    ACTIVE,
    DRAINING,
    DRAINED,
}

/**
 * 최근 lease 연장 실패와 CRM 연속 실패를 식별자 없이 집계합니다.
 */
class ProfileReevaluationOperationalMonitor {
    private val leaseRenewalFailures = AtomicLong()
    private val consecutiveAssessmentFailures = AtomicInteger()

    fun leaseRenewalSucceeded() {
        leaseRenewalFailures.set(0)
    }

    fun leaseRenewalFailed() {
        leaseRenewalFailures.incrementAndGet()
    }

    fun assessmentSucceeded() {
        consecutiveAssessmentFailures.set(0)
    }

    fun assessmentFailed() {
        consecutiveAssessmentFailures.incrementAndGet()
    }

    fun enrich(snapshot: ProfileReevaluationOperationalSnapshot): ProfileReevaluationOperationalSnapshot =
        snapshot.copy(
            leaseRenewalFailures = leaseRenewalFailures.get(),
            consecutiveAssessmentFailures = consecutiveAssessmentFailures.get(),
        )
}
