package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.metrics.LeaderAopMetricsRecorder
import io.bluetape4k.leader.metrics.SkipReason
import kotlin.time.Duration

/** reminder lock의 upstream AOP callback을 bounded notification health 상태로 연결합니다. */
class NotificationLeaderAopMetricsRecorder(
    private val monitor: NotificationLeaderHealthMonitor,
    private val lockName: String = REMINDER_RECOVERY_LOCK_NAME,
) : LeaderAopMetricsRecorder {

    override fun onLockAcquired(
        name: String,
        options: LeaderElectionOptions,
        acquireElapsed: Duration,
    ) {
        if (name == lockName) {
            monitor.recordAcquired()
        }
    }

    override fun onLockNotAcquired(
        name: String,
        options: LeaderElectionOptions,
        reason: SkipReason,
    ) {
        if (name == lockName && reason == SkipReason.BACKEND_ERROR) {
            monitor.recordAcquisitionFailure()
        }
    }
}
