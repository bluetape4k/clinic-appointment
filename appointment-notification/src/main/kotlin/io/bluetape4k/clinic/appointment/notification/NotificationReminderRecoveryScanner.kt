package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.clinic.appointment.event.notification.AppointmentId
import io.bluetape4k.clinic.appointment.event.notification.ClinicId
import io.bluetape4k.clinic.appointment.event.notification.NotificationIdempotencyKey
import io.bluetape4k.clinic.appointment.event.notification.NotificationSlot
import io.bluetape4k.clinic.appointment.event.notification.NotificationSuppressionReasonCode
import io.bluetape4k.clinic.appointment.event.notification.TenantGroupId
import java.io.Serializable
import java.time.Duration
import java.time.Instant

/**
 * 중단 시간 동안 누락될 수 있는 reminder materialization을 outbox 기준으로 보정합니다.
 *
 * 정확성은 materializer가 사용하는 outbox unique key와 repository CAS가 담당한다.
 * 이 scanner는 due 경계 판단과 처리량 제한만 책임지며 provider를 호출하지 않는다.
 */
class NotificationReminderRecoveryScanner(
    private val source: ReminderRecoverySource,
    private val materializer: ReminderRecoveryMaterializer,
    private val catchUpWindow: Duration,
    private val clock: ReminderRecoveryClock = ReminderRecoveryClock { Instant.now() },
) {

    init {
        require(!catchUpWindow.isNegative && !catchUpWindow.isZero) { "catchUpWindow must be positive" }
    }

    suspend fun scanOnce(limit: Int): ReminderRecoveryScanResult {
        require(limit > 0) { "limit must be positive" }
        val now = clock.now()
        var notYetDue = 0
        var enqueued = 0
        var suppressed = 0
        source.findCandidates(now, limit).forEach { candidate ->
            when {
                candidate.dueAt.isAfter(now) -> notYetDue++
                candidate.dueAt.isBefore(now.minus(catchUpWindow)) -> {
                    if (materializer.suppressMissed(candidate) == ReminderRecoveryMaterializationResult.SUPPRESSED) {
                        suppressed++
                    }
                }
                else -> {
                    if (materializer.enqueue(candidate) == ReminderRecoveryMaterializationResult.ENQUEUED) {
                        enqueued++
                    }
                }
            }
        }
        return ReminderRecoveryScanResult(notYetDue = notYetDue, enqueued = enqueued, suppressed = suppressed)
    }
}

/** reminder due와 catch-up 경계를 판정할 현재 시각을 제공하는 port입니다. */
fun interface ReminderRecoveryClock {
    suspend fun now(): Instant
}

fun interface ReminderRecoverySource {
    suspend fun findCandidates(now: Instant, limit: Int): List<ReminderRecoveryCandidate>
}

interface ReminderRecoveryMaterializer {
    /**
     * [candidate]의 기존 [ReminderRecoveryCandidate.idempotencyKey]를 그대로 사용해
     * outbox unique key 기반 upsert를 수행해야 합니다.
     */
    suspend fun enqueue(candidate: ReminderRecoveryCandidate): ReminderRecoveryMaterializationResult

    /**
     * [candidate]와 같은 멱등성 key로 window-missed suppression을 기록해야 합니다.
     */
    suspend fun suppressMissed(candidate: ReminderRecoveryCandidate): ReminderRecoveryMaterializationResult
}

data class ReminderRecoveryCandidate(
    val tenantGroupId: TenantGroupId,
    val clinicId: ClinicId,
    val appointmentId: AppointmentId,
    val slot: NotificationSlot,
    val idempotencyKey: NotificationIdempotencyKey,
    val dueAt: Instant,
) : Serializable {
    init {
        require(slot == NotificationSlot.REMINDER_24H || slot == NotificationSlot.REMINDER_SAME_DAY) {
            "slot must be a reminder slot"
        }
    }

    override fun toString(): String =
        "ReminderRecoveryCandidate(scope=<redacted>, appointmentId=<redacted>, slot=$slot, idempotencyKey=<redacted>, dueAt=$dueAt)"

    companion object {
        private const val serialVersionUID = 1L
    }
}

data class ReminderRecoverySuppression(
    val candidate: ReminderRecoveryCandidate,
    val reason: NotificationSuppressionReasonCode,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

enum class ReminderRecoveryMaterializationResult {
    ENQUEUED,
    SUPPRESSED,
    ALREADY_EXISTS,
}

data class ReminderRecoveryScanResult(
    val notYetDue: Int,
    val enqueued: Int,
    val suppressed: Int,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
