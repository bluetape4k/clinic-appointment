package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.event.notification.NotificationFailureCode
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

internal class NotificationRetryPolicyTest {

    private val now = Instant.parse("2026-07-31T00:00:00Z")
    private val policy = NotificationRetryPolicy()

    @Test
    fun `attempt 1부터 5까지 deterministic exponential backoff로 retry wait를 반환한다`() {
        val firstAttemptAt = now.minus(Duration.ofMinutes(10))

        val delays = (1..5).map { attempt ->
            policy.decide(
                attemptNumber = attempt,
                firstAttemptAt = firstAttemptAt,
                now = now,
                failureCode = NotificationFailureCode.PROVIDER_UNAVAILABLE,
                jitterSeed = 42L,
            ).retryDelay
        }
        val repeated = (1..5).map { attempt ->
            policy.decide(
                attemptNumber = attempt,
                firstAttemptAt = firstAttemptAt,
                now = now,
                failureCode = NotificationFailureCode.PROVIDER_UNAVAILABLE,
                jitterSeed = 42L,
            ).retryDelay
        }

        repeated shouldBeEqualTo delays
        delays.zip(listOf(60L, 120L, 240L, 480L, 960L)).forEach { (actual, baseSeconds) ->
            val delay = requireNotNull(actual)
            (delay >= Duration.ofSeconds(baseSeconds)).shouldBeTrue()
            (delay <= Duration.ofMillis(baseSeconds * 1_200L)).shouldBeTrue()
        }
    }

    @Test
    fun `attempt 6은 DELIVERY_RESULT_UNKNOWN도 exhausted로 종결한다`() {
        val decision = policy.decide(
            attemptNumber = 6,
            firstAttemptAt = now.minus(Duration.ofMinutes(10)),
            now = now,
            failureCode = NotificationFailureCode.DELIVERY_RESULT_UNKNOWN,
            jitterSeed = 42L,
        )

        decision.kind shouldBeEqualTo NotificationRetryDecisionKind.EXHAUSTED
        decision.failureCode shouldBeEqualTo NotificationFailureCode.DELIVERY_RESULT_UNKNOWN
    }

    @Test
    fun `첫 시도 후 24시간이 지나면 다음 attempt를 만들지 않고 exhausted로 종결한다`() {
        val decision = policy.decide(
            attemptNumber = 2,
            firstAttemptAt = now.minus(Duration.ofHours(24)).minusSeconds(1),
            now = now,
            failureCode = NotificationFailureCode.PROVIDER_RATE_LIMITED,
            jitterSeed = 42L,
        )

        decision.kind shouldBeEqualTo NotificationRetryDecisionKind.EXHAUSTED
    }
}
