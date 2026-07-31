package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxStatus
import kotlinx.coroutines.delay
import java.io.Serializable
import java.time.Duration

/**
 * 종료된 notification outbox row를 제한된 page 단위로 삭제합니다.
 *
 * 삭제 기준 시각은 repository가 DB 현재 시각으로 계산한다. runner는 상태별 retention,
 * page 크기, page 사이 backpressure만 책임진다.
 */
class NotificationRetentionRunner(
    private val workStore: NotificationOutboxWorkStore,
    private val sentRetention: Duration = Duration.ofDays(7),
    private val suppressedRetention: Duration = Duration.ofDays(7),
    private val exhaustedRetention: Duration = Duration.ofDays(30),
    private val pageSize: Int = 100,
    private val maxPagesPerStatus: Int = 10,
    private val backpressure: Duration = Duration.ofMillis(100),
    private val readiness: NotificationSchemaReadiness? = null,
) {

    init {
        listOf(sentRetention, suppressedRetention, exhaustedRetention).forEach {
            require(!it.isNegative && !it.isZero) { "retention must be positive" }
        }
        require(pageSize > 0) { "pageSize must be positive" }
        require(maxPagesPerStatus > 0) { "maxPagesPerStatus must be positive" }
        require(!backpressure.isNegative) { "backpressure must be non-negative" }
    }

    suspend fun runOnce(): NotificationRetentionResult {
        if (readiness?.check()?.available == false) {
            return NotificationRetentionResult(emptyMap())
        }
        val deleted = linkedMapOf<NotificationOutboxStatus, Int>()
        retentionByStatus().forEach { (status, retention) ->
            deleted[status] = deleteStatus(status, retention)
        }
        return NotificationRetentionResult(deleted)
    }

    private suspend fun deleteStatus(
        status: NotificationOutboxStatus,
        retention: Duration,
    ): Int {
        var total = 0
        repeat(maxPagesPerStatus) { page ->
            val deleted = workStore.deleteTerminalBatch(status, retention, pageSize)
            total += deleted
            if (deleted < pageSize) return total
            if (!backpressure.isZero && page < maxPagesPerStatus - 1) {
                delay(backpressure.toMillis())
            }
        }
        return total
    }

    private fun retentionByStatus(): Map<NotificationOutboxStatus, Duration> =
        linkedMapOf(
            NotificationOutboxStatus.SENT to sentRetention,
            NotificationOutboxStatus.SUPPRESSED to suppressedRetention,
            NotificationOutboxStatus.EXHAUSTED to exhaustedRetention,
        )
}

data class NotificationRetentionResult(
    val deletedByStatus: Map<NotificationOutboxStatus, Int>,
) : Serializable {
    val deletedTotal: Int = deletedByStatus.values.sum()

    companion object {
        private const val serialVersionUID = 1L
    }
}
