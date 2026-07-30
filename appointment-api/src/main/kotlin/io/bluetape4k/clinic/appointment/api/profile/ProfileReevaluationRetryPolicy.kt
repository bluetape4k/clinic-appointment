package io.bluetape4k.clinic.appointment.api.profile

import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationJobRecord
import io.bluetape4k.clinic.appointment.model.dto.RedriveProfileReevaluationJob
import io.bluetape4k.clinic.appointment.model.profile.ProfileReevaluationJobStatus
import java.time.Duration
import java.time.Instant
import kotlin.math.pow
import kotlin.random.Random

/**
 * 프로필 재평가의 자동 재시도 지연과 종료 시점을 결정합니다.
 *
 * attempt 횟수와 최초 시도 후 경과 시간 중 먼저 한도에 도달한 조건을 적용합니다.
 */
class ProfileReevaluationRetryPolicy(
    private val maxAttempts: Int = 5,
    private val maxElapsedTime: Duration = Duration.ofMinutes(15),
    private val initialBackoff: Duration = Duration.ofSeconds(2),
    private val maxBackoff: Duration = Duration.ofMinutes(1),
    private val jitterRatio: Double = 0.2,
    private val randomFraction: () -> Double = Random.Default::nextDouble,
) {
    init {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        require(!maxElapsedTime.isNegative && !maxElapsedTime.isZero) {
            "maxElapsedTime must be positive"
        }
        require(!initialBackoff.isNegative && !initialBackoff.isZero) {
            "initialBackoff must be positive"
        }
        require(maxBackoff >= initialBackoff) { "maxBackoff must be at least initialBackoff" }
        require(jitterRatio in 0.0..1.0) { "jitterRatio must be between 0.0 and 1.0" }
    }

    fun decide(
        attemptCount: Int,
        firstAttemptAt: Instant,
        now: Instant,
    ): ProfileReevaluationRetryDecision {
        require(attemptCount > 0) { "attemptCount must be positive" }
        require(!firstAttemptAt.isAfter(now)) { "firstAttemptAt must not be after now" }
        if (attemptCount >= maxAttempts || Duration.between(firstAttemptAt, now) >= maxElapsedTime) {
            return ProfileReevaluationRetryDecision.Failed
        }

        val exponent = (attemptCount - 1).coerceAtMost(MAX_BACKOFF_EXPONENT)
        val baseMillis =
            (initialBackoff.toMillis() * 2.0.pow(exponent))
                .coerceAtMost(maxBackoff.toMillis().toDouble())
        val fraction = randomFraction().coerceIn(0.0, 1.0)
        val multiplier = (1.0 - jitterRatio) + fraction * jitterRatio * 2.0
        val delayMillis = (baseMillis * multiplier).toLong().coerceAtLeast(1L)
        return ProfileReevaluationRetryDecision.Retry(Duration.ofMillis(delayMillis))
    }

    private companion object {
        const val MAX_BACKOFF_EXPONENT = 30
    }
}

sealed interface ProfileReevaluationRetryDecision {
    data class Retry(val delay: Duration) : ProfileReevaluationRetryDecision

    data object Failed : ProfileReevaluationRetryDecision
}

/**
 * 최종 실패 작업의 자동 redrive를 최대 두 번, 30분 간격으로 제한합니다.
 */
class ProfileReevaluationRedrivePolicy(
    private val maxRedrives: Int = 2,
    private val cooldown: Duration = Duration.ofMinutes(30),
) {
    init {
        require(maxRedrives >= 0) { "maxRedrives must be non-negative" }
        require(!cooldown.isNegative) { "cooldown must be non-negative" }
    }

    fun commandFor(
        failedJob: ProfileReevaluationJobRecord,
        now: Instant,
    ): RedriveProfileReevaluationJob? {
        if (failedJob.status != ProfileReevaluationJobStatus.FAILED) return null
        if (failedJob.redriveCount > 0) return null
        if (failedJob.redriveGeneration >= maxRedrives) return null
        if (failedJob.updatedAt.plus(cooldown).isAfter(now)) return null
        return RedriveProfileReevaluationJob(
            jobId = failedJob.id,
            cooldown = cooldown,
            expectedRedriveCount = failedJob.redriveCount,
        )
    }
}
