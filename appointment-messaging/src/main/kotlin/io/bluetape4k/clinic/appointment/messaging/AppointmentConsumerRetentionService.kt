package io.bluetape4k.clinic.appointment.messaging

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.SortOrder
import java.time.Clock
import java.time.Instant

data class AppointmentConsumerRetentionResult(
    val inbox: Int,
    val rejected: Int,
    val quarantine: Int,
    val replayAudit: Int,
) {
    val total: Int
        get() = inbox + rejected + quarantine + replayAudit
}

/** terminal metadata만 bounded batch로 제거하는 운영 adapter입니다. */
class AppointmentConsumerRetentionService(
    private val database: Database,
    private val inboxStore: AppointmentConsumerInboxStore,
    private val properties: AppointmentConsumerRetentionProperties,
    private val metrics: AppointmentConsumerMetrics = NoopAppointmentConsumerMetrics,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun cleanup(now: Instant = clock.instant()): AppointmentConsumerRetentionResult {
        if (!properties.enabled) return AppointmentConsumerRetentionResult(0, 0, 0, 0)

        val inboxCutoff = now.minus(properties.processedAge)
        val rejectedCutoff = now.minus(properties.rejectedAge)
        val quarantineCutoff = now.minus(properties.quarantineAge)
        val replayAuditCutoff = now.minus(properties.replayAuditAge)
        inboxStore.oldestProcessingAge(now)?.let(metrics::recordOldestAge)

        val inbox = inboxStore.cleanupTerminal(inboxCutoff, properties.batchSize)
        metrics.retentionDeleted(AppointmentRetentionTable.INBOX, inbox)
        val (rejected, quarantine, replayAudit) = transaction(database) {
            Triple(
                deleteRejected(rejectedCutoff),
                deleteQuarantine(quarantineCutoff),
                deleteReplayAudit(replayAuditCutoff),
            )
        }
        metrics.retentionDeleted(AppointmentRetentionTable.REJECTED, rejected)
        metrics.retentionDeleted(AppointmentRetentionTable.QUARANTINE, quarantine)
        metrics.retentionDeleted(AppointmentRetentionTable.REPLAY_AUDIT, replayAudit)
        return AppointmentConsumerRetentionResult(inbox, rejected, quarantine, replayAudit)
    }

    private fun deleteRejected(cutoff: Instant): Int {
        val ids = AppointmentConsumerRejectedRecordTable
            .selectAll()
            .where { AppointmentConsumerRejectedRecordTable.createdAt lessEq cutoff }
            .orderBy(AppointmentConsumerRejectedRecordTable.id to SortOrder.ASC)
            .limit(properties.batchSize)
            .map { it[AppointmentConsumerRejectedRecordTable.id] }
        if (ids.isEmpty()) return 0
        val predicate = ids.map { AppointmentConsumerRejectedRecordTable.id eq it }
            .reduce { left, right -> left or right }
        return AppointmentConsumerRejectedRecordTable.deleteWhere { predicate }
    }

    private fun deleteQuarantine(cutoff: Instant): Int {
        val ids = AppointmentConsumerQuarantineTable
            .selectAll()
            .where { AppointmentConsumerQuarantineTable.createdAt lessEq cutoff }
            .orderBy(AppointmentConsumerQuarantineTable.id to SortOrder.ASC)
            .limit(properties.batchSize)
            .map { it[AppointmentConsumerQuarantineTable.id] }
        if (ids.isEmpty()) return 0
        val predicate = ids.map { AppointmentConsumerQuarantineTable.id eq it }
            .reduce { left, right -> left or right }
        return AppointmentConsumerQuarantineTable.deleteWhere { predicate }
    }

    private fun deleteReplayAudit(cutoff: Instant): Int {
        val ids = AppointmentConsumerReplayAuditTable
            .selectAll()
            .where {
                (AppointmentConsumerReplayAuditTable.createdAt lessEq cutoff) and
                    (AppointmentConsumerReplayAuditTable.status inList listOf(
                        AppointmentReplayAuditStatus.DRY_RUN,
                        AppointmentReplayAuditStatus.EXECUTED,
                        AppointmentReplayAuditStatus.REJECTED,
                    ))
            }
            .orderBy(AppointmentConsumerReplayAuditTable.id to SortOrder.ASC)
            .limit(properties.batchSize)
            .map { it[AppointmentConsumerReplayAuditTable.id] }
        if (ids.isEmpty()) return 0
        val predicate = ids.map { AppointmentConsumerReplayAuditTable.id eq it }
            .reduce { left, right -> left or right }
        return AppointmentConsumerReplayAuditTable.deleteWhere { predicate }
    }
}
