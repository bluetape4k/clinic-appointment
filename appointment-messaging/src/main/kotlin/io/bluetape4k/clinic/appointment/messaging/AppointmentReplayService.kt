package io.bluetape4k.clinic.appointment.messaging

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.Database
import java.time.Instant

data class AppointmentReplayExecution(
    val groupId: String,
    val identity: AppointmentConsumerIdentity,
)

fun interface AppointmentReplaySource {
    /** source 구현은 이 replay group으로만 읽고 operations group offset을 변경하지 않아야 합니다. */
    fun replay(request: AppointmentReplayRequest, execution: AppointmentReplayExecution): Int
}

data class AppointmentReplayResult(
    val requestId: String,
    val status: AppointmentReplayAuditStatus,
    val replayGroupId: String?,
    val replayedRecords: Int,
)

/** 승인된 replay의 dry-run/audit/separate-group 경계를 담당합니다. */
class AppointmentReplayService(
    private val database: Database,
    private val source: AppointmentReplaySource,
) {
    fun replay(requestId: String, request: AppointmentReplayRequest): AppointmentReplayResult {
        require(requestId.matches(REQUEST_ID_PATTERN)) { "replay requestId is not canonical" }
        val initialStatus = if (request.dryRun) AppointmentReplayAuditStatus.DRY_RUN
        else AppointmentReplayAuditStatus.REQUESTED

        val inserted = transaction(database) {
            AppointmentConsumerReplayAuditTable.insertIgnore {
                it[AppointmentConsumerReplayAuditTable.requestId] = requestId
                it[logicalConsumerId] = request.identity.consumerId.value
                it[logicalStreamId] = request.identity.streamId.value
                it[tenantGroupId] = request.tenantGroupId
                it[clinicId] = request.clinicId
                it[fromOffset] = request.fromOffset
                it[toOffset] = request.toOffset
                it[dryRun] = request.dryRun
                it[approvedBy] = request.approver
                it[status] = initialStatus
            }.insertedCount == 1
        }
        if (!inserted) {
            return transaction(database) {
                val existing = AppointmentConsumerReplayAuditTable
                    .selectAll()
                    .where { AppointmentConsumerReplayAuditTable.requestId eq requestId }
                    .single()
                AppointmentReplayResult(
                    requestId = requestId,
                    status = existing[AppointmentConsumerReplayAuditTable.status],
                    replayGroupId = existing[AppointmentConsumerReplayAuditTable.logicalConsumerId]
                        .takeUnless { existing[AppointmentConsumerReplayAuditTable.dryRun] }
                        ?.let { replayGroupId(it) },
                    replayedRecords = 0,
                )
            }
        }

        if (request.dryRun) {
            transaction(database) {
                AppointmentConsumerReplayAuditTable.update({ AppointmentConsumerReplayAuditTable.requestId eq requestId }) {
                    it[completedAt] = Instant.now()
                }
            }
            return AppointmentReplayResult(requestId, AppointmentReplayAuditStatus.DRY_RUN, null, 0)
        }

        val execution = AppointmentReplayExecution(
            groupId = replayGroupId(request.identity.consumerId.value),
            identity = AppointmentConsumerIdentity(
                consumerId = AppointmentLogicalConsumerId("${request.identity.consumerId.value}-replay"),
                streamId = request.identity.streamId,
            ),
        )
        return try {
            val replayed = source.replay(request, execution)
            require(replayed >= 0) { "replay source returned a negative count" }
            transaction(database) {
                AppointmentConsumerReplayAuditTable.update({ AppointmentConsumerReplayAuditTable.requestId eq requestId }) {
                    it[status] = AppointmentReplayAuditStatus.EXECUTED
                    it[completedAt] = Instant.now()
                }
            }
            AppointmentReplayResult(requestId, AppointmentReplayAuditStatus.EXECUTED, execution.groupId, replayed)
        } catch (failure: Exception) {
            transaction(database) {
                AppointmentConsumerReplayAuditTable.update({ AppointmentConsumerReplayAuditTable.requestId eq requestId }) {
                    it[status] = AppointmentReplayAuditStatus.REJECTED
                    it[completedAt] = Instant.now()
                }
            }
            throw AppointmentReplayException("approved appointment replay failed", failure)
        }
    }

    private fun replayGroupId(consumerId: String): String = "appointment-$consumerId-replay-v1"

    companion object {
        private val REQUEST_ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    }
}

class AppointmentReplayException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
