package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.clinic.appointment.event.notification.NotificationFailureCode
import java.io.Serializable
import java.time.Duration
import java.time.Instant

/**
 * 알림 durable retry의 단일 판단 지점입니다.
 *
 * provider retry와 durable retry가 곱으로 불어나지 않도록 outbox attempt 번호를 기준으로
 * exponential backoff와 재현 가능한 jitter를 계산합니다.
 */
class NotificationRetryPolicy(
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val maxElapsed: Duration = DEFAULT_MAX_ELAPSED,
    private val baseDelay: Duration = DEFAULT_BASE_DELAY,
    private val jitterPercent: Int = DEFAULT_JITTER_PERCENT,
) {

    init {
        require(maxAttempts in 1..10) { "maxAttempts must be between 1 and 10" }
        require(maxElapsed in Duration.ofMinutes(15)..Duration.ofHours(72)) {
            "maxElapsed must be between 15 minutes and 72 hours"
        }
        require(!baseDelay.isNegative && !baseDelay.isZero) { "baseDelay must be positive" }
        require(jitterPercent in 0..50) { "jitterPercent must be between 0 and 50" }
    }

    fun decide(
        attemptNumber: Int,
        firstAttemptAt: Instant,
        now: Instant,
        failureCode: NotificationFailureCode,
        jitterSeed: Long,
    ): NotificationRetryDecision {
        require(attemptNumber > 0) { "attemptNumber must be positive" }
        val exhausted = attemptNumber >= maxAttempts ||
            !Duration.between(firstAttemptAt, now).minus(maxElapsed).isNegative
        if (exhausted) {
            return NotificationRetryDecision.exhausted(failureCode)
        }
        return NotificationRetryDecision.retryWait(
            failureCode = failureCode,
            retryDelay = backoffFor(attemptNumber, jitterSeed),
        )
    }

    private fun backoffFor(
        attemptNumber: Int,
        jitterSeed: Long,
    ): Duration {
        val exponential = baseDelay.multipliedBy(1L shl (attemptNumber - 1).coerceAtMost(MAX_SHIFT))
        val jitterBoundMillis = exponential.toMillis() * jitterPercent / 100
        if (jitterBoundMillis == 0L) return exponential
        val mixed = java.lang.Long.rotateLeft(
            jitterSeed xor (attemptNumber.toLong() * GOLDEN_RATIO_MIX),
            17,
        )
        val jitterMillis = Math.floorMod(mixed, jitterBoundMillis + 1L)
        return exponential.plusMillis(jitterMillis)
    }

    private companion object {
        const val DEFAULT_MAX_ATTEMPTS = 6
        const val MAX_SHIFT = 20
        const val DEFAULT_JITTER_PERCENT = 20
        const val GOLDEN_RATIO_MIX = -7046029254386353131L
        val DEFAULT_MAX_ELAPSED: Duration = Duration.ofHours(24)
        val DEFAULT_BASE_DELAY: Duration = Duration.ofMinutes(1)
    }
}

enum class NotificationRetryDecisionKind {
    RETRY_WAIT,
    EXHAUSTED,
}

data class NotificationRetryDecision(
    val kind: NotificationRetryDecisionKind,
    val failureCode: NotificationFailureCode,
    val retryDelay: Duration?,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L

        fun retryWait(
            failureCode: NotificationFailureCode,
            retryDelay: Duration,
        ): NotificationRetryDecision =
            NotificationRetryDecision(NotificationRetryDecisionKind.RETRY_WAIT, failureCode, retryDelay)

        fun exhausted(failureCode: NotificationFailureCode): NotificationRetryDecision =
            NotificationRetryDecision(NotificationRetryDecisionKind.EXHAUSTED, failureCode, null)
    }
}
