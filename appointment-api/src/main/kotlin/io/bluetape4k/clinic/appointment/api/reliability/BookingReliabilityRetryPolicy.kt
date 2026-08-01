package io.bluetape4k.clinic.appointment.api.reliability

import java.time.Duration

/** 재평가 worker의 bounded exponential backoff 계산기입니다. */
class BookingReliabilityRetryPolicy(
    private val baseDelay: Duration = Duration.ofSeconds(5),
    private val maximumDelay: Duration = Duration.ofMinutes(10),
    private val maximumAttempts: Int = 5,
) {
    init {
        require(!baseDelay.isNegative && !baseDelay.isZero) { "baseDelay must be positive" }
        require(!maximumDelay.isNegative && !maximumDelay.isZero) { "maximumDelay must be positive" }
        require(maximumDelay >= baseDelay) { "maximumDelay must not be shorter than baseDelay" }
        require(maximumAttempts > 0) { "maximumAttempts must be positive" }
    }

    fun shouldRetry(attempt: Int, cancelled: Boolean = false): Boolean =
        !cancelled && attempt in 0 until maximumAttempts

    fun delayFor(attempt: Int): Duration {
        require(attempt >= 0) { "attempt must be non-negative" }
        val multiplier = 1L shl attempt.coerceAtMost(30)
        val millis = baseDelay.toMillis().coerceAtMost(Long.MAX_VALUE / multiplier) * multiplier
        return Duration.ofMillis(millis.coerceAtMost(maximumDelay.toMillis()))
    }
}
