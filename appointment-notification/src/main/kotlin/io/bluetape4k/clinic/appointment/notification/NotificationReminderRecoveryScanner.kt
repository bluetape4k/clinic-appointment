package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.clinic.appointment.event.notification.AppointmentId
import io.bluetape4k.clinic.appointment.event.notification.ClinicId
import io.bluetape4k.clinic.appointment.event.notification.NotificationIdempotencyKey
import io.bluetape4k.clinic.appointment.event.notification.NotificationSlot
import io.bluetape4k.clinic.appointment.event.notification.NotificationSuppressionReasonCode
import io.bluetape4k.clinic.appointment.event.notification.LegacySuppressionDraft
import io.bluetape4k.clinic.appointment.event.notification.SendableNotificationDraft
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
        var alreadyExists = 0
        val candidates = source.findCandidates(now, limit)
        candidates.forEach { candidate ->
            when {
                candidate.dueAt.isAfter(now) -> {
                    when (materializer.scheduleFuture(candidate)) {
                        null -> notYetDue++
                        ReminderRecoveryMaterializationResult.ENQUEUED -> enqueued++
                        ReminderRecoveryMaterializationResult.SUPPRESSED -> suppressed++
                        ReminderRecoveryMaterializationResult.ALREADY_EXISTS -> alreadyExists++
                    }
                }
                candidate.dueAt.isBefore(now.minus(catchUpWindow)) -> {
                    when (materializer.suppressMissed(candidate)) {
                        ReminderRecoveryMaterializationResult.SUPPRESSED -> suppressed++
                        ReminderRecoveryMaterializationResult.ALREADY_EXISTS -> alreadyExists++
                        ReminderRecoveryMaterializationResult.ENQUEUED -> Unit
                    }
                }
                else -> {
                    when (materializer.enqueue(candidate)) {
                        ReminderRecoveryMaterializationResult.ENQUEUED -> enqueued++
                        ReminderRecoveryMaterializationResult.SUPPRESSED -> suppressed++
                        ReminderRecoveryMaterializationResult.ALREADY_EXISTS -> alreadyExists++
                    }
                }
            }
        }
        return ReminderRecoveryScanResult(
            notYetDue = notYetDue,
            enqueued = enqueued,
            suppressed = suppressed,
            alreadyExists = alreadyExists,
            scanned = candidates.size,
        )
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

    /**
     * 아직 발송 시각이 아닌 후보를 미래 `availableAt` outbox로 미리 기록합니다.
     * 이를 지원하지 않는 adapter는 `null`을 반환해 기존 not-yet-due 동작을 유지합니다.
     */
    suspend fun scheduleFuture(candidate: ReminderRecoveryCandidate): ReminderRecoveryMaterializationResult? = null
}

data class ReminderRecoveryCandidate(
    val tenantGroupId: TenantGroupId,
    val clinicId: ClinicId,
    val appointmentId: AppointmentId,
    val slot: NotificationSlot,
    val idempotencyKey: NotificationIdempotencyKey,
    val dueAt: Instant,
    val payload: ReminderRecoveryPayload? = null,
    val progress: ReminderRecoveryProgress? = null,
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

/** 한 예약의 모든 reminder slot 처리가 끝난 뒤 전진할 durable 순회 위치입니다. */
data class ReminderRecoveryProgress(
    val runId: String,
    val appointmentId: Long,
    val completesRun: Boolean = false,
    val advancesCursor: Boolean = true,
) : Serializable {
    init {
        require(runId.isNotBlank()) { "runId must not be blank" }
        require(appointmentId > 0) { "appointmentId must be positive" }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** 예약 조회 adapter가 materializer에 전달하는 개인정보 비포함 outbox draft입니다. */
data class ReminderRecoveryPayload(
    val sendableDraft: SendableNotificationDraft?,
    val suppressionDraft: LegacySuppressionDraft,
) : Serializable {
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
    val alreadyExists: Int = 0,
    val scanned: Int = notYetDue + enqueued + suppressed + alreadyExists,
) : Serializable {
    init {
        require(listOf(notYetDue, enqueued, suppressed, alreadyExists, scanned).all { it >= 0 }) {
            "reminder recovery counts must be non-negative"
        }
        require(scanned == notYetDue + enqueued + suppressed + alreadyExists) {
            "scanned must equal the sum of reminder recovery outcomes"
        }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}
