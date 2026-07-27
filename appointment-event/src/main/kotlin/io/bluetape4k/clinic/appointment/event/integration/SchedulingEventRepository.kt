package io.bluetape4k.clinic.appointment.event.integration

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

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
        reasonCode: String = "SOURCE_VERSION_GAP",
    ) {
        SchedulingInboxEvents.update({ SchedulingInboxEvents.id eq inboxId }) {
            it[status] = SchedulingInboxStatus.WAITING_GAP
            it[SchedulingInboxEvents.attemptCount] = attemptCount
            it[SchedulingInboxEvents.replayAfter] = replayAfter
            it[failureCode] = reasonCode
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
            it[schemaVersion] = 1
            it[payloadJson] =
                """{"eventId":"$outboxEventId","causationEventId":"${envelope.eventId}","correlationId":"${envelope.correlationId}","planId":$planId,"tenantGroupId":${payload.tenantGroupId},"clinicId":${payload.clinicId},"sourcePurchaseAuthority":"${payload.sourcePurchaseAuthority}","sourcePurchaseId":"${payload.sourcePurchaseId}","sourceAggregateVersion":${payload.sourceAggregateVersion}}"""
            it[status] = SchedulingOutboxStatus.PENDING
            it[attemptCount] = 0
        }
    }
}
