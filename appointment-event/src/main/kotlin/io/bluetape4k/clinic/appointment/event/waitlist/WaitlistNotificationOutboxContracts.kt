package io.bluetape4k.clinic.appointment.event.waitlist

import io.bluetape4k.clinic.appointment.event.AppointmentEventJson
import io.bluetape4k.clinic.appointment.service.waitlist.WaitlistOfferNotificationDraft
import tools.jackson.module.kotlin.readValue
import java.io.Serializable
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

/**
 * waitlist offer 알림의 canonical durable payload다.
 *
 * offer/hold/entry 식별자만 보관하며 member, appointment, 연락처, rendered message는
 * 저장하지 않는다. notification worker는 entry ID로 최신 member profile을 조회한다.
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
        require(eventId.length <= 160) { "eventId must not exceed 160 characters" }
        require(idempotencyKey.isNotBlank()) { "idempotencyKey must not be blank" }
        require(idempotencyKey.length <= 128) { "idempotencyKey must not exceed 128 characters" }
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

/** waitlist notification payload를 위한 strict canonical codec. */
class WaitlistNotificationOutboxCodec {
    private val mapper = AppointmentEventJson.mapper

    fun encode(envelope: WaitlistNotificationOutboxEnvelope): String =
        AppointmentEventJson.writeCanonical(envelope.toJson())

    fun decode(json: String): WaitlistNotificationOutboxEnvelope =
        try {
            AppointmentEventJson.requireDocumentSize(json)
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

/** waitlist notification event 계약 위반을 표현하는 예외다. */
class WaitlistNotificationOutboxContractException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
