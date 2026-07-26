package io.bluetape4k.clinic.appointment.event.integration

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

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
 * Caller-transaction repository for redacted inbox/outbox convergence state.
 */
class SchedulingEventRepository {

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

    fun latestProcessedSourceVersion(
        producer: String,
        sourceAggregateId: String,
    ): Long? =
        SchedulingInboxEvents
            .selectAll()
            .where {
                (SchedulingInboxEvents.producer eq producer) and
                    (SchedulingInboxEvents.sourceAggregateId eq sourceAggregateId) and
                    (SchedulingInboxEvents.status eq SchedulingInboxStatus.PROCESSED)
            }
            .orderBy(SchedulingInboxEvents.sourceAggregateVersion, SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.get(SchedulingInboxEvents.sourceAggregateVersion)

    fun insertReceived(
        envelope: TrustedSchedulingEventEnvelope<PurchaseCompletedEvent>,
    ): Long =
        SchedulingInboxEvents.insertAndGetId {
            it[eventId] = envelope.eventId
            it[eventType] = envelope.eventType
            it[producer] = envelope.producer
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

    fun markWaitingGap(
        inboxId: Long,
        attemptCount: Int,
        replayAfter: Instant,
    ) {
        SchedulingInboxEvents.update({ SchedulingInboxEvents.id eq inboxId }) {
            it[status] = SchedulingInboxStatus.WAITING_GAP
            it[SchedulingInboxEvents.attemptCount] = attemptCount
            it[SchedulingInboxEvents.replayAfter] = replayAfter
            it[failureCode] = "SOURCE_VERSION_GAP"
        }
    }

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

    fun insertPlanCreatedOutbox(
        envelope: TrustedSchedulingEventEnvelope<PurchaseCompletedEvent>,
        planId: Long,
    ) {
        SchedulingOutboxEvents.insertAndGetId {
            it[eventId] = envelope.eventId
            it[eventType] = "AppointmentPlanCreated"
            it[tenantGroupId] = envelope.payload.tenantGroupId
            it[clinicId] = envelope.payload.clinicId
            it[SchedulingOutboxEvents.planId] = planId
            it[schemaVersion] = 1
            it[payloadJson] =
                """{"eventId":"${envelope.eventId}","planId":$planId,"tenantGroupId":${envelope.payload.tenantGroupId},"clinicId":${envelope.payload.clinicId}}"""
            it[status] = SchedulingOutboxStatus.PENDING
            it[attemptCount] = 0
        }
    }
}
