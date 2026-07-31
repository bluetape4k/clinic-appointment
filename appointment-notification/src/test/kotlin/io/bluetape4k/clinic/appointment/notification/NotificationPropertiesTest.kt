package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.event.notification.NotificationChannelType
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
        worker.pollInterval shouldBeEqualTo Duration.ofSeconds(1)
        worker.validate()
    }

    @Test
    fun `provider timeout은 채널별 소문자 key를 우선하고 없으면 전역값을 사용한다`() {
        val worker = NotificationProperties.WorkerProperties(
            providerTimeout = Duration.ofSeconds(30),
            channels = mapOf(
                "sms" to NotificationProperties.ChannelWorkerProperties(
                    providerTimeout = Duration.ofSeconds(5),
                ),
            ),
        )

        worker.providerTimeoutFor(NotificationChannelType.SMS) shouldBeEqualTo Duration.ofSeconds(5)
        worker.providerTimeoutFor(NotificationChannelType.EMAIL) shouldBeEqualTo Duration.ofSeconds(30)
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
            NotificationProperties.WorkerProperties(pollInterval = Duration.ZERO).validate()
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
    fun `retention 설정은 주기 보존 기간 page와 backpressure를 함께 검증한다`() {
        val retention = NotificationProperties().retention.validate()

        retention.pollInterval shouldBeEqualTo Duration.ofHours(1)
        retention.sent shouldBeEqualTo Duration.ofDays(7)
        retention.suppressed shouldBeEqualTo Duration.ofDays(7)
        retention.exhausted shouldBeEqualTo Duration.ofDays(30)

        assertFailsWith<IllegalStateException> {
            retention.copy(pollInterval = Duration.ZERO).validate()
        }
        assertFailsWith<IllegalStateException> {
            retention.copy(pageSize = 0).validate()
        }
        assertFailsWith<IllegalStateException> {
            retention.copy(backpressure = Duration.ofMillis(-1)).validate()
        }
    }

    @Test
    fun `관측 설정은 worker보다 낮은 빈도와 경고 임계값 식별 상한을 사용한다`() {
        val observation = NotificationProperties().observation.validate()

        observation.pollInterval shouldBeEqualTo Duration.ofSeconds(10)
        observation.limit shouldBeEqualTo 10_001

        assertFailsWith<IllegalStateException> {
            observation.copy(pollInterval = Duration.ZERO).validate()
        }
        assertFailsWith<IllegalStateException> {
            observation.copy(limit = 0).validate()
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
