package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.notification.persistence.ClaimedNotification
import io.bluetape4k.clinic.appointment.notification.persistence.CompleteNotificationCommand
import io.bluetape4k.clinic.appointment.notification.persistence.NotificationCandidate
import io.bluetape4k.clinic.appointment.notification.persistence.NotificationFairCursor
import io.bluetape4k.clinic.appointment.notification.persistence.NotificationOutboxStatus
import io.bluetape4k.clinic.appointment.notification.persistence.RetryNotificationCommand
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Duration

internal class NotificationRetentionRunnerTest {

    @Test
    fun `기본 retention은 SENT와 SUPPRESSED 7일 EXHAUSTED 30일을 bounded page로 삭제한다`() {
        runBlocking {
            val store = RetentionFakeWorkStore(
                responses = mutableMapOf(
                    NotificationOutboxStatus.SENT to mutableListOf(100, 20, 0),
                    NotificationOutboxStatus.SUPPRESSED to mutableListOf(3, 0),
                    NotificationOutboxStatus.EXHAUSTED to mutableListOf(100, 100, 1),
                ),
            )
            val runner = NotificationRetentionRunner(
                workStore = store,
                pageSize = 100,
                backpressure = Duration.ZERO,
            )

            val result = runner.runOnce()

            result.deletedByStatus[NotificationOutboxStatus.SENT] shouldBeEqualTo 120
            result.deletedByStatus[NotificationOutboxStatus.SUPPRESSED] shouldBeEqualTo 3
            result.deletedByStatus[NotificationOutboxStatus.EXHAUSTED] shouldBeEqualTo 201
            store.calls shouldBeEqualTo listOf(
                RetentionCall(NotificationOutboxStatus.SENT, Duration.ofDays(7), 100),
                RetentionCall(NotificationOutboxStatus.SENT, Duration.ofDays(7), 100),
                RetentionCall(NotificationOutboxStatus.SUPPRESSED, Duration.ofDays(7), 100),
                RetentionCall(NotificationOutboxStatus.EXHAUSTED, Duration.ofDays(30), 100),
                RetentionCall(NotificationOutboxStatus.EXHAUSTED, Duration.ofDays(30), 100),
                RetentionCall(NotificationOutboxStatus.EXHAUSTED, Duration.ofDays(30), 100),
            )
        }
    }

    private data class RetentionCall(
        val status: NotificationOutboxStatus,
        val retention: Duration,
        val limit: Int,
    )

    private class RetentionFakeWorkStore(
        private val responses: MutableMap<NotificationOutboxStatus, MutableList<Int>>,
    ) : NotificationOutboxWorkStore {
        val calls = mutableListOf<RetentionCall>()

        override suspend fun findFairCandidates(limit: Int, cursor: NotificationFairCursor?): NotificationCandidatePage =
            NotificationCandidatePage(emptyList(), null)

        override suspend fun claim(id: Long, owner: String): ClaimedNotification? = null

        override suspend fun recoverExpired(limit: Int, owner: String): List<ClaimedNotification> = emptyList()

        override suspend fun complete(command: CompleteNotificationCommand): Boolean = true

        override suspend fun retry(command: RetryNotificationCommand): Boolean = true

        override suspend fun currentDatabaseTime(): java.time.Instant = java.time.Instant.parse("2026-07-31T00:00:00Z")

        override suspend fun deleteTerminalBatch(
            status: NotificationOutboxStatus,
            retention: Duration,
            limit: Int,
        ): Int {
            calls += RetentionCall(status, retention, limit)
            return responses.getValue(status).removeFirst()
        }
    }
}
