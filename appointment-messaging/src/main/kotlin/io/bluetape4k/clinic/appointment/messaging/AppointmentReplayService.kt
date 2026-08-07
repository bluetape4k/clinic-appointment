package io.bluetape4k.clinic.appointment.messaging

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

data class AppointmentReplayExecution(
    val groupId: String,
    val identity: AppointmentConsumerIdentity,
)

fun interface AppointmentReplaySource {
    /** source 구현은 이 replay request 전용 group으로만 읽고 operations group offset을 변경하지 않아야 합니다. */
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
    private val authorizer: AppointmentReplayAuthorizer = TenantScopedAppointmentReplayAuthorizer(),
    private val metrics: AppointmentConsumerMetrics = NoopAppointmentConsumerMetrics,
) {
    constructor(database: Database, source: AppointmentReplaySource) : this(
        database = database,
        source = source,
        authorizer = TenantScopedAppointmentReplayAuthorizer(),
        metrics = NoopAppointmentConsumerMetrics,
    )

    fun replay(
        requestId: String,
        request: AppointmentReplayRequest,
        actor: AppointmentReplayActor,
    ): AppointmentReplayResult {
        require(requestId.matches(REQUEST_ID_PATTERN)) { "replay requestId is not canonical" }
        try {
            authorizer.authorize(actor, request)
        } catch (failure: IllegalArgumentException) {
            metrics.replay(AppointmentReplayAuditStatus.REJECTED)
            throw AppointmentReplayAuthorizationException("appointment replay is not authorized")
        }
        val requestHash = requestHash(request)
        val initialStatus = if (request.dryRun) AppointmentReplayAuditStatus.DRY_RUN
        else AppointmentReplayAuditStatus.REQUESTED
        val claimAt = Instant.now()

        val shouldExecute = transaction(database) {
            val existing = AppointmentConsumerReplayAuditTable
                .selectAll()
                .where { AppointmentConsumerReplayAuditTable.requestId eq requestId }
                .singleOrNull()
            if (existing == null) {
                val inserted = AppointmentConsumerReplayAuditTable.insertIgnore {
                    it[AppointmentConsumerReplayAuditTable.requestId] = requestId
                    it[logicalConsumerId] = request.identity.consumerId.value
                    it[logicalStreamId] = request.identity.streamId.value
                    it[tenantGroupId] = request.tenantGroupId
                    it[clinicId] = request.clinicId
                    it[fromOffset] = request.fromOffset
                    it[toOffset] = request.toOffset
                    it[AppointmentConsumerReplayAuditTable.requestHash] = requestHash
                    it[dryRun] = request.dryRun
                    it[approvedBy] = request.approver
                    it[status] = initialStatus
                    // REQUESTED + completedAt는 실행 claim timestamp로 사용합니다.
                    it[completedAt] = claimAt.takeUnless { request.dryRun }
                }.insertedCount == 1
                inserted && !request.dryRun
            } else {
                require(existing[AppointmentConsumerReplayAuditTable.requestHash] == requestHash) {
                    "replay requestId is already bound to a different scope or range"
                }
                val status = existing[AppointmentConsumerReplayAuditTable.status]
                when {
                    status == AppointmentReplayAuditStatus.DRY_RUN && !request.dryRun -> {
                        AppointmentConsumerReplayAuditTable.update({
                            (AppointmentConsumerReplayAuditTable.requestId eq requestId) and
                                (AppointmentConsumerReplayAuditTable.status eq AppointmentReplayAuditStatus.DRY_RUN) and
                                AppointmentConsumerReplayAuditTable.completedAt.isNull()
                        }) {
                            it[AppointmentConsumerReplayAuditTable.dryRun] = false
                            it[AppointmentConsumerReplayAuditTable.status] = AppointmentReplayAuditStatus.REQUESTED
                            it[AppointmentConsumerReplayAuditTable.completedAt] = claimAt
                        } == 1
                    }

                    status == AppointmentReplayAuditStatus.REQUESTED && !request.dryRun &&
                        existing[AppointmentConsumerReplayAuditTable.completedAt] == null ->
                        AppointmentConsumerReplayAuditTable.update({
                            (AppointmentConsumerReplayAuditTable.requestId eq requestId) and
                                (AppointmentConsumerReplayAuditTable.status eq AppointmentReplayAuditStatus.REQUESTED) and
                                AppointmentConsumerReplayAuditTable.completedAt.isNull()
                        }) {
                            it[AppointmentConsumerReplayAuditTable.completedAt] = claimAt
                        } == 1
                    else -> false
                }
            }
        }

        if (!shouldExecute) {
            return auditResult(requestId).also { metrics.replay(it.status) }
        }

        val execution = AppointmentReplayExecution(
            groupId = replayGroupId(request.identity.consumerId.value, requestId),
            // 원래 logical inbox identity를 유지해 replay가 consumer dedup 경계를 우회하지 않게 합니다.
            identity = request.identity,
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
                .also { metrics.replay(it.status) }
        } catch (failure: Exception) {
            transaction(database) {
                AppointmentConsumerReplayAuditTable.update({ AppointmentConsumerReplayAuditTable.requestId eq requestId }) {
                    it[status] = AppointmentReplayAuditStatus.REJECTED
                    it[completedAt] = Instant.now()
                }
            }
            metrics.replay(AppointmentReplayAuditStatus.REJECTED)
            throw AppointmentReplayException("approved appointment replay failed", failure)
        }
    }

    private fun auditResult(requestId: String): AppointmentReplayResult = transaction(database) {
        val existing = AppointmentConsumerReplayAuditTable
            .selectAll()
            .where { AppointmentConsumerReplayAuditTable.requestId eq requestId }
            .single()
        val status = existing[AppointmentConsumerReplayAuditTable.status]
        AppointmentReplayResult(
            requestId = requestId,
            status = status,
            replayGroupId = status.takeUnless { it == AppointmentReplayAuditStatus.DRY_RUN }
                ?.let { replayGroupId(existing[AppointmentConsumerReplayAuditTable.logicalConsumerId], requestId) },
            replayedRecords = 0,
        )
    }

    private fun replayGroupId(consumerId: String, requestId: String): String =
        "appointment-$consumerId-replay-$requestId-v1"

    private fun requestHash(request: AppointmentReplayRequest): String =
        MessageDigest.getInstance("SHA-256")
            .digest(
                listOf(
                    request.identity.consumerId.value,
                    request.identity.streamId.value,
                    request.tenantGroupId,
                    request.clinicId,
                    request.approver,
                    request.fromOffset,
                    request.toOffset,
                ).joinToString("|").toByteArray(StandardCharsets.UTF_8),
            )
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    companion object {
        private val REQUEST_ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    }
}

class AppointmentReplayException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
