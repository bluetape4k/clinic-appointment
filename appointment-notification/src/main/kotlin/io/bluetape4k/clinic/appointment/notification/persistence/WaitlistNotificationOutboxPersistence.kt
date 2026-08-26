package io.bluetape4k.clinic.appointment.notification.persistence

import io.bluetape4k.clinic.appointment.event.waitlist.WaitlistNotificationOutboxCodec
import io.bluetape4k.clinic.appointment.event.waitlist.WaitlistNotificationOutboxEnvelope
import io.bluetape4k.clinic.appointment.event.waitlist.WaitlistNotificationOutboxContractException
import io.bluetape4k.clinic.appointment.service.waitlist.WaitlistOfferNotificationDraft
import io.bluetape4k.clinic.appointment.service.waitlist.WaitlistOfferNotificationPort
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.upsert
import java.io.Serializable
import java.time.Instant

/** waitlist notification outbox row의 제한된 lifecycle입니다. */
enum class WaitlistNotificationOutboxStatus {
    PENDING,
    PROCESSING,
    RETRY_WAIT,
    SENT,
    SUPPRESSED,
    EXHAUSTED,
}

/** outbox row를 caller transaction에 전달하는 adapter contract입니다. */
fun interface WaitlistNotificationOutboxSink {
    fun enqueue(row: WaitlistNotificationOutboxRow): WaitlistNotificationOutboxRecord
}

data class WaitlistNotificationOutboxRow(
    val id: Long? = null,
    val status: WaitlistNotificationOutboxStatus = WaitlistNotificationOutboxStatus.PENDING,
    val idempotencyKey: String,
    val eventId: String,
    val tenantGroupId: Long,
    val clinicId: Long,
    val offerId: Long,
    val holdId: Long,
    val waitlistEntryId: Long,
    val reasonCode: String,
    val correlationId: String,
    val payloadJson: String,
    val occurredAt: Instant,
    val availableAt: Instant,
) : Serializable {
    init {
        require(idempotencyKey.isNotBlank()) { "idempotencyKey must not be blank" }
        require(eventId.isNotBlank()) { "eventId must not be blank" }
        require(tenantGroupId > 0L) { "tenantGroupId must be positive" }
        require(clinicId > 0L) { "clinicId must be positive" }
        require(offerId > 0L) { "offerId must be positive" }
        require(holdId > 0L) { "holdId must be positive" }
        require(waitlistEntryId > 0L) { "waitlistEntryId must be positive" }
        require(payloadJson.isNotBlank()) { "payloadJson must not be blank" }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

data class WaitlistNotificationOutboxRecord(
    val id: Long,
    val status: WaitlistNotificationOutboxStatus,
    val idempotencyKey: String,
    val eventId: String,
    val tenantGroupId: Long,
    val clinicId: Long,
    val offerId: Long,
    val holdId: Long,
    val waitlistEntryId: Long,
    val reasonCode: String,
    val correlationId: String,
    val payloadJson: String,
    val occurredAt: Instant,
    val availableAt: Instant,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L

        fun from(row: WaitlistNotificationOutboxRow, id: Long): WaitlistNotificationOutboxRecord =
            WaitlistNotificationOutboxRecord(
                id = id,
                status = row.status,
                idempotencyKey = row.idempotencyKey,
                eventId = row.eventId,
                tenantGroupId = row.tenantGroupId,
                clinicId = row.clinicId,
                offerId = row.offerId,
                holdId = row.holdId,
                waitlistEntryId = row.waitlistEntryId,
                reasonCode = row.reasonCode,
                correlationId = row.correlationId,
                payloadJson = row.payloadJson,
                occurredAt = row.occurredAt,
                availableAt = row.availableAt,
            )
    }
}

/** waitlist notification payload와 row를 caller transaction 안에서 기록하는 adapter입니다. */
class WaitlistNotificationOutboxAdapter(
    private val sink: (WaitlistNotificationOutboxRow) -> WaitlistNotificationOutboxRecord,
    private val codec: WaitlistNotificationOutboxCodec = WaitlistNotificationOutboxCodec(),
) : WaitlistOfferNotificationPort {

    constructor(
        sink: WaitlistNotificationOutboxSink,
        codec: WaitlistNotificationOutboxCodec = WaitlistNotificationOutboxCodec(),
    ) : this(sink::enqueue, codec)

    override fun enqueue(draft: WaitlistOfferNotificationDraft) {
        persist(draft)
    }

    /** 테스트와 audit adapter가 저장된 row를 확인할 수 있는 명시적 결과 API입니다. */
    fun persist(draft: WaitlistOfferNotificationDraft): WaitlistNotificationOutboxRecord {
        val envelope = WaitlistNotificationOutboxEnvelope.from(draft)
        val row = WaitlistNotificationOutboxRow(
            status = WaitlistNotificationOutboxStatus.PENDING,
            idempotencyKey = envelope.idempotencyKey,
            eventId = envelope.eventId,
            tenantGroupId = envelope.tenantGroupId,
            clinicId = envelope.clinicId,
            offerId = envelope.offerId,
            holdId = envelope.holdId,
            waitlistEntryId = envelope.waitlistEntryId,
            reasonCode = envelope.reasonCode,
            correlationId = envelope.correlationId,
            payloadJson = codec.encode(envelope),
            occurredAt = envelope.occurredAt,
            availableAt = envelope.availableAt,
        )
        return sink(row)
    }
}

/** caller-owned transaction 전용 waitlist notification outbox table입니다. */
object WaitlistNotificationOutboxEvents : LongIdTable("clinic_waitlist_notification_outbox") {
    val status = enumerationByName<WaitlistNotificationOutboxStatus>("status", 32)
    val idempotencyKey = varchar("idempotency_key", 128)
    val eventId = varchar("event_id", 160)
    val tenantGroupId = long("tenant_group_id")
    val clinicId = long("clinic_id")
    val offerId = long("offer_id")
    val holdId = long("hold_id")
    val waitlistEntryId = long("waitlist_entry_id")
    val reasonCode = varchar("reason_code", 96)
    val correlationId = varchar("correlation_id", 128)
    val payloadJson = text("payload_json")
    val occurredAt = timestamp("occurred_at")
    val availableAt = timestamp("available_at")
    val leaseOwner = varchar("lease_owner", 128).nullable()
    val leaseToken = varchar("lease_token", 128).nullable()
    val leaseUntil = timestamp("lease_until").nullable()
    val attemptNumber = integer("attempt_number").default(0)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    val terminalAt = timestamp("terminal_at").nullable()

    init {
        uniqueIndex(
            "uk_waitlist_notification_outbox_idempotency",
            tenantGroupId,
            clinicId,
            idempotencyKey,
        )
        index(
            "idx_waitlist_notification_outbox_ready",
            false,
            tenantGroupId,
            clinicId,
            status,
            availableAt,
            id,
        )
        index(
            "idx_waitlist_notification_outbox_lease",
            false,
            status,
            leaseUntil,
            id,
        )
    }
}

/** Exposed repository는 자체 transaction을 열지 않습니다. */
class WaitlistNotificationOutboxRepository : WaitlistNotificationOutboxSink {

    override fun enqueue(row: WaitlistNotificationOutboxRow): WaitlistNotificationOutboxRecord {
        // TransactionManager.current()가 caller transaction 부재를 즉시 실패시킨다.
        TransactionManager.current()
        val now = Instant.now()
        WaitlistNotificationOutboxEvents.upsert(
            WaitlistNotificationOutboxEvents.tenantGroupId,
            WaitlistNotificationOutboxEvents.clinicId,
            WaitlistNotificationOutboxEvents.idempotencyKey,
            onUpdate = {
                it[WaitlistNotificationOutboxEvents.idempotencyKey] = row.idempotencyKey
                it[WaitlistNotificationOutboxEvents.updatedAt] = now
            },
        ) {
            it[status] = row.status
            it[idempotencyKey] = row.idempotencyKey
            it[eventId] = row.eventId
            it[tenantGroupId] = row.tenantGroupId
            it[clinicId] = row.clinicId
            it[offerId] = row.offerId
            it[holdId] = row.holdId
            it[waitlistEntryId] = row.waitlistEntryId
            it[reasonCode] = row.reasonCode
            it[correlationId] = row.correlationId
            it[payloadJson] = row.payloadJson
            it[occurredAt] = row.occurredAt
            it[availableAt] = row.availableAt
            it[leaseOwner] = null
            it[leaseToken] = null
            it[leaseUntil] = null
            it[attemptNumber] = 0
            it[updatedAt] = now
            it[terminalAt] = null
        }
        return WaitlistNotificationOutboxEvents
            .selectAll()
            .where {
                (WaitlistNotificationOutboxEvents.tenantGroupId eq row.tenantGroupId) and
                    (WaitlistNotificationOutboxEvents.clinicId eq row.clinicId) and
                    (WaitlistNotificationOutboxEvents.idempotencyKey eq row.idempotencyKey)
            }
            .single()
            .toRecord()
    }

    private fun ResultRow.toRecord() = WaitlistNotificationOutboxRecord(
        id = this[WaitlistNotificationOutboxEvents.id].value,
        status = this[WaitlistNotificationOutboxEvents.status],
        idempotencyKey = this[WaitlistNotificationOutboxEvents.idempotencyKey],
        eventId = this[WaitlistNotificationOutboxEvents.eventId],
        tenantGroupId = this[WaitlistNotificationOutboxEvents.tenantGroupId],
        clinicId = this[WaitlistNotificationOutboxEvents.clinicId],
        offerId = this[WaitlistNotificationOutboxEvents.offerId],
        holdId = this[WaitlistNotificationOutboxEvents.holdId],
        waitlistEntryId = this[WaitlistNotificationOutboxEvents.waitlistEntryId],
        reasonCode = this[WaitlistNotificationOutboxEvents.reasonCode],
        correlationId = this[WaitlistNotificationOutboxEvents.correlationId],
        payloadJson = this[WaitlistNotificationOutboxEvents.payloadJson],
        occurredAt = this[WaitlistNotificationOutboxEvents.occurredAt],
        availableAt = this[WaitlistNotificationOutboxEvents.availableAt],
    )
}
