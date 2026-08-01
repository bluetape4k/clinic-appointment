package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.event.notification.AppointmentId
import io.bluetape4k.clinic.appointment.event.notification.ClinicId
import io.bluetape4k.clinic.appointment.event.notification.NotificationIdempotencyKey
import io.bluetape4k.clinic.appointment.event.notification.NotificationSlot
import io.bluetape4k.clinic.appointment.event.notification.NotificationSuppressionReasonCode
import io.bluetape4k.clinic.appointment.event.notification.TenantGroupId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

internal class NotificationReminderRecoveryScannerTest {

    private val now = Instant.parse("2026-07-31T00:00:00Z")

    @Test
    fun `due 전은 유지하고 catch-up window 안은 enqueue window 뒤는 missed suppression 한다`() {
        runBlocking {
            val source = FakeReminderSource(
                listOf(
                    reminder(1L, now.plusSeconds(60)),
                    reminder(2L, now.minus(Duration.ofMinutes(10))),
                    reminder(3L, now.minus(Duration.ofMinutes(31))),
                )
            )
            val materializer = FakeReminderMaterializer()
            val scanner = NotificationReminderRecoveryScanner(
                source = source,
                materializer = materializer,
                catchUpWindow = Duration.ofMinutes(30),
                clock = { now },
            )

            val result = scanner.scanOnce(limit = 10)

            result.notYetDue shouldBeEqualTo 1
            result.enqueued shouldBeEqualTo 1
            result.suppressed shouldBeEqualTo 1
            materializer.enqueued.single().appointmentId shouldBeEqualTo AppointmentId(2L)
            materializer.enqueued.single().idempotencyKey shouldBeEqualTo reminderKey(2L)
            materializer.suppressed.single().reason shouldBeEqualTo NotificationSuppressionReasonCode.REMINDER_WINDOW_MISSED
        }
    }

    @Test
    fun `catch-up replay는 같은 idempotency key를 사용하고 unique conflict를 성공으로 세지 않는다`() {
        runBlocking {
            val candidate = reminder(20L, now.minus(Duration.ofMinutes(5)))
            val materializer = FakeReminderMaterializer()
            val scanner = NotificationReminderRecoveryScanner(
                source = FakeReminderSource(listOf(candidate)),
                materializer = materializer,
                catchUpWindow = Duration.ofMinutes(30),
                clock = { now },
            )

            scanner.scanOnce(limit = 10).enqueued shouldBeEqualTo 1
            val replay = scanner.scanOnce(limit = 10)
            replay.enqueued shouldBeEqualTo 0
            replay.alreadyExists shouldBeEqualTo 1

            materializer.enqueueAttempts shouldBeEqualTo listOf(candidate.idempotencyKey, candidate.idempotencyKey)
            materializer.enqueued shouldBeEqualTo listOf(candidate)
        }
    }

    @Test
    fun `LeaderGroupElector는 scanner trigger 최적화일 뿐 skip되어도 정확성 계약을 대신하지 않는다`() {
        runBlocking {
            val materializer = FakeReminderMaterializer()
            val scheduler = AppointmentReminderScheduler(
                scanner = NotificationReminderRecoveryScanner(
                    source = FakeReminderSource(listOf(reminder(10L, now.minusSeconds(1)))),
                    materializer = materializer,
                    catchUpWindow = Duration.ofMinutes(30),
                    clock = { now },
                ),
                triggerGuard = ReminderRecoveryTriggerGuard { false },
            )

            scheduler.triggerOnce()

            materializer.enqueued.size shouldBeEqualTo 0
        }
    }

    @Test
    fun `한 실행은 작은 DB page를 반복하되 설정된 전체 후보 상한에서 멈춘다`() {
        runBlocking {
            val source = PagedReminderSource((1L..6L).map { reminder(it, now.minusSeconds(1)) })
            val scheduler = AppointmentReminderScheduler(
                scanner = NotificationReminderRecoveryScanner(
                    source = source,
                    materializer = FakeReminderMaterializer(),
                    catchUpWindow = Duration.ofMinutes(30),
                    clock = { now },
                ),
                batchSize = 2,
                maxCandidatesPerRun = 5,
            )

            val result = scheduler.triggerOnce()!!

            result.scanned shouldBeEqualTo 5
            result.enqueued shouldBeEqualTo 5
            source.calls shouldBeEqualTo 3
        }
    }

    private fun reminder(id: Long, dueAt: Instant): ReminderRecoveryCandidate =
        ReminderRecoveryCandidate(
            tenantGroupId = TenantGroupId(1L),
            clinicId = ClinicId(1L),
            appointmentId = AppointmentId(id),
            slot = NotificationSlot.REMINDER_24H,
            idempotencyKey = reminderKey(id),
            dueAt = dueAt,
        )

    private fun reminderKey(id: Long): NotificationIdempotencyKey =
        NotificationIdempotencyKey(id.toString(16).padStart(64, '0'))

    private class FakeReminderSource(
        private val candidates: List<ReminderRecoveryCandidate>,
    ) : ReminderRecoverySource {
        override suspend fun findCandidates(now: Instant, limit: Int): List<ReminderRecoveryCandidate> =
            candidates.take(limit)
    }

    private class PagedReminderSource(candidates: List<ReminderRecoveryCandidate>) : ReminderRecoverySource {
        private val remaining = candidates.toMutableList()
        var calls: Int = 0
            private set

        override suspend fun findCandidates(now: Instant, limit: Int): List<ReminderRecoveryCandidate> {
            calls++
            return remaining.take(limit).also { remaining.subList(0, it.size).clear() }
        }
    }

    private class FakeReminderMaterializer : ReminderRecoveryMaterializer {
        val enqueued = mutableListOf<ReminderRecoveryCandidate>()
        val suppressed = mutableListOf<ReminderRecoverySuppression>()
        val enqueueAttempts = mutableListOf<NotificationIdempotencyKey>()
        private val materializedKeys = mutableSetOf<NotificationIdempotencyKey>()

        override suspend fun enqueue(candidate: ReminderRecoveryCandidate): ReminderRecoveryMaterializationResult {
            enqueueAttempts += candidate.idempotencyKey
            if (!materializedKeys.add(candidate.idempotencyKey)) {
                return ReminderRecoveryMaterializationResult.ALREADY_EXISTS
            }
            enqueued += candidate
            return ReminderRecoveryMaterializationResult.ENQUEUED
        }

        override suspend fun suppressMissed(candidate: ReminderRecoveryCandidate): ReminderRecoveryMaterializationResult {
            if (!materializedKeys.add(candidate.idempotencyKey)) {
                return ReminderRecoveryMaterializationResult.ALREADY_EXISTS
            }
            suppressed += ReminderRecoverySuppression(candidate, NotificationSuppressionReasonCode.REMINDER_WINDOW_MISSED)
            return ReminderRecoveryMaterializationResult.SUPPRESSED
        }
    }
}
