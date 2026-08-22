package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.metrics.SkipReason
import io.mockk.verify
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.time.Duration

internal class NotificationLeaderAopMetricsRecorderTest {

    @Test
    fun `reminder lock 획득은 health monitor에 기록한다`() {
        val monitor = mockk<NotificationLeaderHealthMonitor>(relaxed = true)
        val recorder = NotificationLeaderAopMetricsRecorder(monitor)

        recorder.onLockAcquired(
            name = REMINDER_RECOVERY_LOCK_NAME,
            options = LeaderElectionOptions(),
            acquireElapsed = Duration.ZERO,
        )

        verify(exactly = 1) { monitor.recordAcquired() }
    }

    @Test
    fun `reminder lock backend failure만 health acquisition failure로 기록한다`() {
        val monitor = mockk<NotificationLeaderHealthMonitor>(relaxed = true)
        val recorder = NotificationLeaderAopMetricsRecorder(monitor)

        recorder.onLockNotAcquired(
            name = REMINDER_RECOVERY_LOCK_NAME,
            options = LeaderElectionOptions(),
            reason = SkipReason.BACKEND_ERROR,
        )

        verify(exactly = 1) { monitor.recordAcquisitionFailure() }
    }

    @Test
    fun `contention과 다른 lock callback은 reminder health를 변경하지 않는다`() {
        val monitor = mockk<NotificationLeaderHealthMonitor>(relaxed = true)
        val recorder = NotificationLeaderAopMetricsRecorder(monitor)
        val options = LeaderElectionOptions()

        recorder.onLockNotAcquired(
            name = REMINDER_RECOVERY_LOCK_NAME,
            options = options,
            reason = SkipReason.CONTENTION,
        )
        recorder.onLockAcquired(
            name = "other-lock",
            options = options,
            acquireElapsed = Duration.ZERO,
        )
        recorder.onLockNotAcquired(
            name = "other-lock",
            options = options,
            reason = SkipReason.BACKEND_ERROR,
        )

        verify(exactly = 0) { monitor.recordAcquired() }
        verify(exactly = 0) { monitor.recordAcquisitionFailure() }
    }
}
