package io.bluetape4k.clinic.appointment.notification

/**
 * reminder recovery scanner를 주기적으로 깨우는 얇은 trigger입니다.
 *
 * scheduled runner가 leader action으로 전체 scan을 감싼다. 발송·suppression
 * 정확성은 outbox unique key, repository CAS, worker lease/fencing이 보장한다.
 * deprecated trigger guard는 기존 direct caller 호환용으로만 남아 있다.
 */
class AppointmentReminderScheduler(
    private val scanner: NotificationReminderRecoveryScanner,
    @Suppress("DEPRECATION")
    @Deprecated("scheduled path는 leaderElector action 경계를 사용합니다")
    private val triggerGuard: ReminderRecoveryTriggerGuard? = null,
    private val batchSize: Int = 100,
    private val maxCandidatesPerRun: Int = batchSize,
) {

    init {
        require(batchSize > 0) { "batchSize must be positive" }
        require(maxCandidatesPerRun >= batchSize) { "maxCandidatesPerRun must be at least batchSize" }
    }

    suspend fun triggerOnce(): ReminderRecoveryScanResult? {
        // 기존 direct caller의 호환성만 보존하며, scheduled path는 runner의 leader action이 경계를 소유합니다.
        if (triggerGuard?.shouldTrigger() == false) return null
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

@Deprecated("scheduled path는 ReminderRecoveryTriggerGuard 대신 LeaderGroupElector action을 사용합니다")
fun interface ReminderRecoveryTriggerGuard {
    fun shouldTrigger(): Boolean
}
