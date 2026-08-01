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
    private val maxCandidatesPerRun: Int = batchSize,
) {

    init {
        require(batchSize > 0) { "batchSize must be positive" }
        require(maxCandidatesPerRun >= batchSize) { "maxCandidatesPerRun must be at least batchSize" }
    }

    suspend fun triggerOnce(): ReminderRecoveryScanResult? {
        if (!triggerGuard.shouldTrigger()) return null
        var aggregate = ReminderRecoveryScanResult(0, 0, 0)
        while (aggregate.scanned < maxCandidatesPerRun) {
            val pageLimit = minOf(batchSize, maxCandidatesPerRun - aggregate.scanned)
            val page = scanner.scanOnce(pageLimit)
            aggregate = aggregate + page
            if (page.scanned < pageLimit) break
        }
        return aggregate
    }

    private operator fun ReminderRecoveryScanResult.plus(other: ReminderRecoveryScanResult): ReminderRecoveryScanResult =
        ReminderRecoveryScanResult(
            notYetDue = notYetDue + other.notYetDue,
            enqueued = enqueued + other.enqueued,
            suppressed = suppressed + other.suppressed,
            alreadyExists = alreadyExists + other.alreadyExists,
            scanned = scanned + other.scanned,
        )
}

fun interface ReminderRecoveryTriggerGuard {
    fun shouldTrigger(): Boolean
}
