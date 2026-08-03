package io.bluetape4k.clinic.appointment.event.waitlist

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
import tools.jackson.module.kotlin.readValue
import java.io.Serializable
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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

/**
 * waitlist offer 알림의 canonical durable payload입니다.
 *
 * offer/hold/entry 식별자만 보관하며 member, appointment, 연락처, rendered message는
 * 저장하지 않습니다. notification worker는 entry ID로 최신 member profile을 조회합니다.
 */
data class WaitlistNotificationOutboxEnvelope(
    val schemaVersion: Int,
    val eventId: String,
    val idempotencyKey: String,
    val tenantGroupId: Long,
    val clinicId: Long,
    val offerId: Long,
    val holdId: Long,
    val waitlistEntryId: Long,
    val reasonCode: String,
    val correlationId: String,
    val occurredAt: Instant,
    val availableAt: Instant,
) : Serializable {

    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "schemaVersion must be $CURRENT_SCHEMA_VERSION" }
        require(eventId.isNotBlank()) { "eventId must not be blank" }
        require(idempotencyKey.isNotBlank()) { "idempotencyKey must not be blank" }
        require(tenantGroupId > 0L) { "tenantGroupId must be positive" }
        require(clinicId > 0L) { "clinicId must be positive" }
        require(offerId > 0L) { "offerId must be positive" }
        require(holdId > 0L) { "holdId must be positive" }
        require(waitlistEntryId > 0L) { "waitlistEntryId must be positive" }
        require(reasonCode.matches(REASON_CODE_PATTERN)) { "reasonCode must be an uppercase bounded code" }
        require(correlationId.isNotBlank()) { "correlationId must not be blank" }
        require(correlationId.length <= 128) { "correlationId must not exceed 128 characters" }
        require(correlationId.none(Char::isISOControl)) { "correlationId must not contain control characters" }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        private val REASON_CODE_PATTERN = Regex("[A-Z][A-Z0-9_]{0,95}")
        private const val serialVersionUID = 1L

        fun from(
            draft: WaitlistOfferNotificationDraft,
            idempotencyKey: String = WaitlistNotificationOutboxKeys.idempotencyKey(draft),
            availableAt: Instant = draft.occurredAt,
        ): WaitlistNotificationOutboxEnvelope =
            WaitlistNotificationOutboxEnvelope(
                schemaVersion = CURRENT_SCHEMA_VERSION,
                eventId = "waitlist-offer-v1:${draft.offerId}",
                idempotencyKey = idempotencyKey,
                tenantGroupId = draft.tenantGroupId,
                clinicId = draft.clinicId,
                offerId = draft.offerId,
                holdId = draft.holdId,
                waitlistEntryId = draft.waitlistEntryId,
                reasonCode = draft.reasonCode.code,
                correlationId = draft.correlationId.value,
                occurredAt = draft.occurredAt,
                availableAt = availableAt,
            )
    }
}

/** strict canonical codec for waitlist notification payloads. */
class WaitlistNotificationOutboxCodec {
    private val mapper = tools.jackson.module.kotlin.jsonMapper {
        addModule(
            tools.jackson.module.kotlin.kotlinModule {
                enable(tools.jackson.module.kotlin.KotlinFeature.StrictNullChecks)
            },
        )
        enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
        enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
    }

    fun encode(envelope: WaitlistNotificationOutboxEnvelope): String =
        mapper.writeValueAsString(envelope.toJson())

    fun decode(json: String): WaitlistNotificationOutboxEnvelope =
        try {
            mapper.readValue<WaitlistNotificationOutboxEnvelopeJson>(json).toEnvelope()
        } catch (failure: WaitlistNotificationOutboxContractException) {
            throw failure
        } catch (failure: Exception) {
            throw WaitlistNotificationOutboxContractException("Invalid waitlist notification outbox payload", failure)
        }

    private fun WaitlistNotificationOutboxEnvelope.toJson() = WaitlistNotificationOutboxEnvelopeJson(
        schemaVersion = schemaVersion,
        eventId = eventId,
        idempotencyKey = idempotencyKey,
        tenantGroupId = tenantGroupId,
        clinicId = clinicId,
        offerId = offerId,
        holdId = holdId,
        waitlistEntryId = waitlistEntryId,
        reasonCode = reasonCode,
        correlationId = correlationId,
        occurredAt = occurredAt.toString(),
        availableAt = availableAt.toString(),
    )

    private fun WaitlistNotificationOutboxEnvelopeJson.toEnvelope() =
        WaitlistNotificationOutboxEnvelope(
            schemaVersion = schemaVersion,
            eventId = eventId,
            idempotencyKey = idempotencyKey,
            tenantGroupId = tenantGroupId,
            clinicId = clinicId,
            offerId = offerId,
            holdId = holdId,
            waitlistEntryId = waitlistEntryId,
            reasonCode = reasonCode,
            correlationId = correlationId,
            occurredAt = Instant.parse(occurredAt),
            availableAt = Instant.parse(availableAt),
        )
}

private data class WaitlistNotificationOutboxEnvelopeJson(
    val schemaVersion: Int,
    val eventId: String,
    val idempotencyKey: String,
    val tenantGroupId: Long,
    val clinicId: Long,
    val offerId: Long,
    val holdId: Long,
    val waitlistEntryId: Long,
    val reasonCode: String,
    val correlationId: String,
    val occurredAt: String,
    val availableAt: String,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
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

    constructor(
        repository: WaitlistNotificationOutboxRepository,
        codec: WaitlistNotificationOutboxCodec = WaitlistNotificationOutboxCodec(),
    ) : this(repository::enqueue, codec)

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

private object WaitlistNotificationOutboxKeys {
    fun idempotencyKey(draft: WaitlistOfferNotificationDraft): String {
        val normalized = listOf(
            draft.tenantGroupId,
            draft.clinicId,
            draft.offerId,
            draft.holdId,
            draft.waitlistEntryId,
            draft.reasonCode.code,
        ).joinToString(separator = "\u0000")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        return "wl-notification-v1:$digest"
    }
}

class WaitlistNotificationOutboxContractException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
