package io.bluetape4k.clinic.appointment.notification

/**
 * reminder recovery scanner를 주기적으로 깨우는 얇은 trigger입니다.
 *
 * leader guard는 중복 trigger를 줄이는 최적화일 뿐이다. 발송·suppression 정확성은
 * outbox unique key, repository CAS, worker lease/fencing이 보장한다.
 */
class AppointmentReminderScheduler(
    private val scanner: NotificationReminderRecoveryScanner,
    private val triggerGuard: ReminderRecoveryTriggerGuard = ReminderRecoveryTriggerGuard { true },
    private val batchSize: Int = 100,
) {

    init {
        require(batchSize > 0) { "batchSize must be positive" }
    }

    suspend fun triggerOnce(): ReminderRecoveryScanResult? {
        if (!triggerGuard.shouldTrigger()) return null
        return scanner.scanOnce(batchSize)
    }
}

fun interface ReminderRecoveryTriggerGuard {
    fun shouldTrigger(): Boolean
}
