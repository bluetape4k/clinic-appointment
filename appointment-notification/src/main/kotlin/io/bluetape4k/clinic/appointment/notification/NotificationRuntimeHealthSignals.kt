package io.bluetape4k.clinic.appointment.notification

import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 기본 Actuator liveness에 연결하는 개인정보 없는 runtime 저하 신호입니다. */
class NotificationRuntimeHealthSignals {
    private val providerCircuitOpen = AtomicBoolean()
    private val memberCircuitOpen = AtomicBoolean()
    private val retentionFailures = AtomicInteger()

    fun setProviderCircuitOpen(open: Boolean) {
        providerCircuitOpen.set(open)
    }

    fun setMemberCircuitOpen(open: Boolean) {
        memberCircuitOpen.set(open)
    }

    fun recordRetentionSuccess() {
        retentionFailures.set(0)
    }

    fun recordRetentionFailure() {
        retentionFailures.incrementAndGet()
    }

    fun snapshot(
        oldestReadyAge: Duration?,
        backlogCapped: Boolean = false,
    ): NotificationOutboxLivenessSnapshot =
        NotificationOutboxLivenessSnapshot(
            providerCircuitOpen = if (providerCircuitOpen.get()) 1 else 0,
            memberCircuitOpen = if (memberCircuitOpen.get()) 1 else 0,
            oldestActiveAge = oldestReadyAge,
            retentionFailures = retentionFailures.get(),
            backlogCapped = backlogCapped,
        )
}
