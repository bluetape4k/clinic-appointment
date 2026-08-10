package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Duration

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

    @Test
    fun `never-resuming outbox poll은 설정된 deadline에서 취소되어 다음 tick을 막지 않는다`() {
        val dispatcher = mockk<NotificationOutboxDispatcher>()
        coEvery { dispatcher.dispatchOnce() } coAnswers {
            awaitCancellation()
            emptyList()
        }

        NotificationOutboxSchedulingRunner(dispatcher, Duration.ofMillis(100)).poll()

        coVerify(exactly = 1) { dispatcher.dispatchOnce() }
    }

    @Test
    fun `reminder poll은 보정 결과를 낮은 cardinality metric으로 기록한다`() {
        val scheduler = mockk<AppointmentReminderScheduler>()
        val metrics = mockk<NotificationOutboxMetrics>(relaxed = true)
        val result = ReminderRecoveryScanResult(notYetDue = 1, enqueued = 2, suppressed = 3, alreadyExists = 4)
        coEvery { scheduler.triggerOnce() } returns result

        NotificationReminderSchedulingRunner(scheduler, metrics).poll()

        coVerify(exactly = 1) { scheduler.triggerOnce() }
        verify(exactly = 1) { metrics.recordReminderRecovery(result) }
    }

    @Test
    fun `reminder 보정 실패는 다음 scheduled tick을 위해 호출 경계에서 흡수한다`() {
        val scheduler = mockk<AppointmentReminderScheduler>()
        coEvery { scheduler.triggerOnce() } throws IllegalStateException("temporary database error")

        NotificationReminderSchedulingRunner(scheduler).poll()

        coVerify(exactly = 1) { scheduler.triggerOnce() }
    }
}
