package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.jupiter.api.Test

internal class NotificationSchedulingRunnersTest {

    @Test
    fun `outbox poll은 dispatcher를 한 번 실행한다`() {
        val dispatcher = mockk<NotificationOutboxDispatcher>()
        coEvery { dispatcher.dispatchOnce() } returns emptyList()

        NotificationOutboxSchedulingRunner(dispatcher).poll()

        coVerify(exactly = 1) { dispatcher.dispatchOnce() }
    }

    @Test
    fun `retention poll은 bounded 정리 작업을 한 번 실행한다`() {
        val retention = mockk<NotificationRetentionRunner>()
        val signals = NotificationRuntimeHealthSignals()
        coEvery { retention.runOnce() } returns NotificationRetentionResult(emptyMap())

        signals.recordRetentionFailure()
        NotificationRetentionSchedulingRunner(retention, signals).poll()

        coVerify(exactly = 1) { retention.runOnce() }
        signals.snapshot(null).retentionFailures shouldBeEqualTo 0
    }

    @Test
    fun `retention 실패는 runtime health signal에 누적한다`() {
        val retention = mockk<NotificationRetentionRunner>()
        val signals = NotificationRuntimeHealthSignals()
        coEvery { retention.runOnce() } throws IllegalStateException("temporary database error")

        NotificationRetentionSchedulingRunner(retention, signals).poll()

        signals.snapshot(null).retentionFailures shouldBeEqualTo 1
    }

    @Test
    fun `worker route가 없는 SHADOW에서도 관측 snapshot은 갱신한다`() {
        val metrics = mockk<NotificationOutboxMetrics>()
        coEvery { metrics.refreshSnapshot() } returns NotificationOutboxObservationSnapshot(0, null)

        NotificationObservationSchedulingRunner(metrics).poll()

        coVerify(exactly = 1) { metrics.refreshSnapshot() }
    }

    @Test
    fun `관측 실패는 scheduler를 중단시키지 않고 호출 경계에서 흡수한다`() {
        val metrics = mockk<NotificationOutboxMetrics>()
        coEvery { metrics.refreshSnapshot() } throws IllegalStateException("temporary database error")

        NotificationObservationSchedulingRunner(metrics).poll()

        coVerify(exactly = 1) { metrics.refreshSnapshot() }
    }

    @Test
    fun `poll 실패는 scheduler를 중단시키지 않고 호출 경계에서 흡수한다`() {
        val dispatcher = mockk<NotificationOutboxDispatcher>()
        coEvery { dispatcher.dispatchOnce() } throws IllegalStateException("temporary database error")

        NotificationOutboxSchedulingRunner(dispatcher).poll()

        coVerify(exactly = 1) { dispatcher.dispatchOnce() }
    }
}
