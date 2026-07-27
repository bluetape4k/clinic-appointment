package io.bluetape4k.clinic.appointment.event.integration

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

/**
 * Read model for one durable inbound scheduling event.
 *
 * @property id Database identity.
 * @property eventId Stable producer event identity used for deduplication.
 * @property sourceAggregateVersion Positive producer aggregate version.
 * @property status Current convergence lifecycle.
 * @property attemptCount Number of bounded gap/replay attempts.
 * @property failureCode Sanitized stable reason code, or `null` when none.
 * @property replayAfter Earliest UTC replay instant, or `null` when not waiting.
 */
data class SchedulingInboxRecord(
    val id: Long,
    val eventId: String,
    val sourceAggregateVersion: Long,
    val status: SchedulingInboxStatus,
    val attemptCount: Int,
    val failureCode: String?,
    val replayAfter: Instant?,
)

/**
 * Database-computed V9 outbox dual-write convergence evidence.
 *
 * @property aggregateIdentityMissingCount Rows missing `aggregate_type` or
 * `aggregate_id`. Policy publication and V10 cutover require zero.
 * @property legacyPlanRowCount Rows that still carry the legacy plan foreign
 * key and therefore must also carry the equivalent generic plan identity.
 * @property legacyPlanMismatchCount Legacy plan rows whose generic type is not
 * `APPOINTMENT_PLAN` or whose generic ID differs from the decimal plan ID.
 * @property dualWriteParityGauge Ratio in `0.0..1.0` of legacy plan rows with
 * matching generic identity. An empty legacy set reports `1.0`.
 */
data class OutboxDualWriteConvergence(
    val aggregateIdentityMissingCount: Long,
    val legacyPlanRowCount: Long,
    val legacyPlanMismatchCount: Long,
) {
    /** True only when every current row satisfies the V9 writer contract. */
    val converged: Boolean
        get() = aggregateIdentityMissingCount == 0L && legacyPlanMismatchCount == 0L

    /** Legacy plan identity parity exposed as an operator gauge in `0.0..1.0`. */
    val dualWriteParityGauge: Double
        get() = if (legacyPlanRowCount == 0L) {
            1.0
        } else {
            (legacyPlanRowCount - legacyPlanMismatchCount).toDouble() / legacyPlanRowCount
        }
}

/**
 * Caller-transaction repository for redacted inbox/outbox convergence state.
 *
 * Every method must run inside a caller-owned Exposed `transaction {}` so inbox
 * state, appointment-plan creation, and outbox publication evidence can commit
 * or roll back atomically.
 */
class SchedulingEventRepository {

    /**
     * Returns one inbox record visible in the current transaction.
     *
     * @param eventId Stable bounded producer event ID.
     * @return The record, or `null` when no row is visible.
     */
    fun findInbox(eventId: String): SchedulingInboxRecord? =
        SchedulingInboxEvents
            .selectAll()
            .where { SchedulingInboxEvents.eventId eq eventId }
            .singleOrNull()
            ?.let { row ->
                SchedulingInboxRecord(
                    id = row[SchedulingInboxEvents.id].value,
                    eventId = row[SchedulingInboxEvents.eventId],
                    sourceAggregateVersion = row[SchedulingInboxEvents.sourceAggregateVersion],
                    status = row[SchedulingInboxEvents.status],
                    attemptCount = row[SchedulingInboxEvents.attemptCount],
                    failureCode = row[SchedulingInboxEvents.failureCode],
                    replayAfter = row[SchedulingInboxEvents.replayAfter],
                )
            }

    /**
     * Returns the latest processed version for one exact producer aggregate.
     *
     * Tenant, clinic, producer, authority, and source ID form the isolation
     * boundary; a version from another boundary must never suppress this event.
     *
     * @return Highest processed positive version, or `null` before convergence.
     */
    fun latestProcessedSourceVersion(
        tenantGroupId: Long,
        clinicId: Long,
        producer: String,
        sourceAuthority: String,
        sourceAggregateId: String,
    ): Long? =
        SchedulingInboxEvents
            .selectAll()
            .where {
                (SchedulingInboxEvents.tenantGroupId eq tenantGroupId) and
                    (SchedulingInboxEvents.clinicId eq clinicId) and
                    (SchedulingInboxEvents.producer eq producer) and
                    (SchedulingInboxEvents.sourceAuthority eq sourceAuthority) and
                    (SchedulingInboxEvents.sourceAggregateId eq sourceAggregateId) and
                    (SchedulingInboxEvents.status eq SchedulingInboxStatus.PROCESSED)
            }
            .orderBy(SchedulingInboxEvents.sourceAggregateVersion, SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.get(SchedulingInboxEvents.sourceAggregateVersion)

    /**
     * Inserts a redacted `RECEIVED` inbox row from a trusted envelope.
     *
     * Only verified metadata and the payload hash are persisted. Patient
     * reference tokens and signatures have no inbox columns.
     *
     * @return Generated database identity.
     */
    fun insertReceived(
        envelope: TrustedSchedulingEventEnvelope<PurchaseCompletedEvent>,
    ): Long =
        SchedulingInboxEvents.insertAndGetId {
            it[eventId] = envelope.eventId
            it[eventType] = envelope.eventType
            it[producer] = envelope.producer
            it[sourceAuthority] = envelope.payload.sourcePurchaseAuthority
            it[sourceAggregateId] = envelope.payload.sourceAggregateId
            it[sourceAggregateVersion] = envelope.payload.sourceAggregateVersion
            it[tenantGroupId] = envelope.payload.tenantGroupId
            it[clinicId] = envelope.payload.clinicId
            it[payloadHash] = envelope.payloadHash
            it[status] = SchedulingInboxStatus.RECEIVED
            it[attemptCount] = 0
            it[occurredAt] = envelope.occurredAt
            it[receivedAt] = envelope.receivedAt
        }.value

    /**
     * Marks one inbox row processed at the supplied UTC instant.
     *
     * [reasonCode] is an optional sanitized convergence code, never raw
     * exception text or payload data.
     */
    fun markProcessed(
        inboxId: Long,
        processedAt: Instant,
        reasonCode: String? = null,
    ) {
        SchedulingInboxEvents.update({ SchedulingInboxEvents.id eq inboxId }) {
            it[status] = SchedulingInboxStatus.PROCESSED
            it[failureCode] = reasonCode
            it[SchedulingInboxEvents.processedAt] = processedAt
            it[replayAfter] = null
        }
    }

    /**
     * Records a bounded source-version gap retry.
     *
     * [attemptCount] is the total attempt number and [replayAfter] is the
     * earliest UTC retry instant determined by the domain backoff contract.
     */
    fun markWaitingGap(
        inboxId: Long,
        attemptCount: Int,
        replayAfter: Instant,
        reasonCode: String = "SOURCE_VERSION_GAP",
    ) {
        SchedulingInboxEvents.update({ SchedulingInboxEvents.id eq inboxId }) {
            it[status] = SchedulingInboxStatus.WAITING_GAP
            it[SchedulingInboxEvents.attemptCount] = attemptCount
            it[SchedulingInboxEvents.replayAfter] = replayAfter
            it[failureCode] = reasonCode
        }
    }

    /**
     * Terminally quarantines one trusted inbox row with a stable reason code.
     *
     * [processedAt] is the UTC terminal instant. [attemptCount] is replaced
     * only when the caller supplies a final bounded retry count.
     */
    fun markQuarantined(
        inboxId: Long,
        reasonCode: String,
        processedAt: Instant,
        attemptCount: Int? = null,
    ) {
        SchedulingInboxEvents.update({ SchedulingInboxEvents.id eq inboxId }) {
            it[status] = SchedulingInboxStatus.QUARANTINED
            it[failureCode] = reasonCode
            it[SchedulingInboxEvents.processedAt] = processedAt
            it[replayAfter] = null
            attemptCount?.let { count -> it[SchedulingInboxEvents.attemptCount] = count }
        }
    }

    /**
     * Appends one redacted `AppointmentPlanCreated` event.
     *
     * The deterministic event ID binds the trusted inbound event to the newly
     * created positive [planId]. The row dual-writes the legacy plan foreign key
     * and generic `APPOINTMENT_PLAN` aggregate identity until every consumer has
     * migrated. It preserves the real inbound event as causation and never
     * copies patient references, signatures, treatment details, or credentials.
     *
     * This method does not open or commit a transaction. The caller must invoke
     * it in the same Exposed transaction that persists the plan and marks the
     * inbox event processed, so all three effects roll back together.
     *
     * @param envelope Authenticated, integrity-checked purchase event envelope.
     * @param planId Positive database identity of the plan created in the same
     * transaction.
     */
    fun insertPlanCreatedOutbox(
        envelope: TrustedSchedulingEventEnvelope<PurchaseCompletedEvent>,
        planId: Long,
    ) {
        require(planId > 0) { "planId must be positive" }
        val outboxEventId = UUID.nameUUIDFromBytes(
            "AppointmentPlanCreated:${envelope.eventId}:$planId".toByteArray(StandardCharsets.UTF_8)
        ).toString()
        val payload = envelope.payload
        SchedulingOutboxEvents.insertAndGetId {
            it[eventId] = outboxEventId
            it[causationEventId] = envelope.eventId
            it[correlationId] = envelope.correlationId
            it[eventType] = "AppointmentPlanCreated"
            it[tenantGroupId] = payload.tenantGroupId
            it[clinicId] = payload.clinicId
            it[SchedulingOutboxEvents.planId] = planId
            it[aggregateType] = APPOINTMENT_PLAN_AGGREGATE_TYPE
            it[aggregateId] = planId.toString()
            it[schemaVersion] = 1
            it[payloadJson] = planCreatedPayloadJson(outboxEventId, envelope, planId)
            it[status] = SchedulingOutboxStatus.PENDING
            it[attemptCount] = 0
        }
    }

    /**
     * Produces stable plan-event JSON from an explicit privacy-safe allow-list.
     *
     * Every string is JSON escaped, including metadata from an already trusted
     * envelope. This prevents malformed payloads or field injection without
     * broadening the contract to patient references or treatment details.
     */
    private fun planCreatedPayloadJson(
        outboxEventId: String,
        envelope: TrustedSchedulingEventEnvelope<PurchaseCompletedEvent>,
        planId: Long,
    ): String {
        val payload = envelope.payload
        return buildString {
            append('{')
            append("\"eventId\":").appendJsonString(outboxEventId)
            append(",\"causationEventId\":").appendJsonString(envelope.eventId)
            append(",\"correlationId\":").appendJsonString(envelope.correlationId)
            append(",\"planId\":").append(planId)
            append(",\"tenantGroupId\":").append(payload.tenantGroupId)
            append(",\"clinicId\":").append(payload.clinicId)
            append(",\"sourcePurchaseAuthority\":").appendJsonString(payload.sourcePurchaseAuthority)
            append(",\"sourcePurchaseId\":").appendJsonString(payload.sourcePurchaseId)
            append(",\"sourceAggregateVersion\":").append(payload.sourceAggregateVersion)
            append('}')
        }
    }

    /** Appends one JSON string with control characters and metacharacters escaped. */
    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    /**
     * Reads database-computed evidence that all V9 outbox writers dual-write.
     *
     * The aggregation executes in SQL and returns one bounded row regardless of
     * outbox size. Operators keep policy publication disabled when either
     * missing or mismatch count is non-zero. This method is observational and
     * does not repair legacy rows.
     */
    fun readOutboxDualWriteConvergence(): OutboxDualWriteConvergence {
        val dialect = TransactionManager.current().db.dialect.name
        val planIdAsText = if (dialect.contains("mysql", ignoreCase = true)) {
            "CAST(plan_id AS CHAR)"
        } else {
            "CAST(plan_id AS VARCHAR)"
        }
        var result: OutboxDualWriteConvergence? = null
        TransactionManager.current().exec(
            """
            SELECT
                SUM(CASE
                    WHEN aggregate_type IS NULL OR aggregate_id IS NULL THEN 1
                    ELSE 0
                END) AS aggregate_identity_missing_count,
                SUM(CASE WHEN plan_id IS NOT NULL THEN 1 ELSE 0 END) AS legacy_plan_row_count,
                SUM(CASE
                    WHEN plan_id IS NOT NULL AND (
                        aggregate_type IS NULL
                        OR aggregate_id IS NULL
                        OR aggregate_type <> '$APPOINTMENT_PLAN_AGGREGATE_TYPE'
                        OR aggregate_id <> $planIdAsText
                    ) THEN 1
                    ELSE 0
                END) AS legacy_plan_mismatch_count
            FROM ${SchedulingOutboxEvents.tableName}
            """.trimIndent(),
        ) { rows ->
            check(rows.next()) { "Outbox convergence query returned no aggregate row" }
            result = OutboxDualWriteConvergence(
                aggregateIdentityMissingCount = rows.getLong("aggregate_identity_missing_count"),
                legacyPlanRowCount = rows.getLong("legacy_plan_row_count"),
                legacyPlanMismatchCount = rows.getLong("legacy_plan_mismatch_count"),
            )
        }
        return checkNotNull(result) { "Outbox convergence query produced no result" }
    }

    private companion object {
        const val APPOINTMENT_PLAN_AGGREGATE_TYPE = "APPOINTMENT_PLAN"
    }
}
