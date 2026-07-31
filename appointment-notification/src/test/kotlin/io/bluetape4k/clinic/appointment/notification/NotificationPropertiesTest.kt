package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import java.time.Duration
import org.junit.jupiter.api.Test

internal class NotificationPropertiesTest {

    @Test
    fun `worker 기본값은 durable retry와 catch-up 경계를 보수적으로 제한한다`() {
        val worker = NotificationProperties().worker

        worker.maxAttempts shouldBeEqualTo 6
        worker.maxElapsed shouldBeEqualTo Duration.ofHours(24)
        worker.providerAttemptsPerLease shouldBeEqualTo 1
        worker.catchUpWindow shouldBeEqualTo Duration.ofMinutes(30)
        worker.validate()
    }

    @Test
    fun `worker 설정은 attempt elapsed provider 곱과 lease timeout 경계를 fail-closed로 거절한다`() {
        assertFailsWith<IllegalStateException> {
            NotificationProperties.WorkerProperties(maxAttempts = 11).validate()
        }
        assertFailsWith<IllegalStateException> {
            NotificationProperties.WorkerProperties(maxElapsed = Duration.ofMinutes(14)).validate()
        }
        assertFailsWith<IllegalStateException> {
            NotificationProperties.WorkerProperties(maxAttempts = 7, providerAttemptsPerLease = 2).validate()
        }
        assertFailsWith<IllegalStateException> {
            NotificationProperties.WorkerProperties(
                leaseDuration = Duration.ofSeconds(60),
                providerTimeout = Duration.ofSeconds(30),
                providerAttemptsPerLease = 2,
            ).validate()
        }
        assertFailsWith<IllegalStateException> {
            NotificationProperties.WorkerProperties(dbClaimMaxConcurrency = 0).validate()
        }
        assertFailsWith<IllegalStateException> {
            NotificationProperties.WorkerProperties(memberResolverRateLimitPerSecond = 0).validate()
        }
        assertFailsWith<IllegalStateException> {
            NotificationProperties.WorkerProperties(
                channels = mapOf(
                    "sms" to NotificationProperties.ChannelWorkerProperties(bulkheadMaxConcurrentCalls = 0),
                ),
            ).validate()
        }
    }

    @Test
    fun `worker 전체 동시성은 member resolver와 channel provider 상한의 최솟값을 넘지 않는다`() {
        val failure = assertFailsWith<IllegalStateException> {
            NotificationProperties.WorkerProperties(
                globalConcurrency = 5,
                dbClaimMaxConcurrency = 4,
                memberResolverMaxConcurrency = 4,
                channels = mapOf(
                    "sms" to NotificationProperties.ChannelWorkerProperties(providerMaxConcurrency = 3),
                ),
            ).validate()
        }

        failure.message shouldBeEqualTo
            "globalConcurrency must not exceed DB claim, member resolver, and provider capacities"
    }
}
