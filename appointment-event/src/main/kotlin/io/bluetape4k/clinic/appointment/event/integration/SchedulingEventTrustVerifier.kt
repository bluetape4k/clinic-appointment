package io.bluetape4k.clinic.appointment.event.integration

import io.bluetape4k.clinic.appointment.model.plan.BookingPreferenceSnapshot
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration

fun interface SchedulingEventSignatureVerifier {
    fun verify(envelope: UntrustedSchedulingEventEnvelope<PurchaseCompletedEvent>): Boolean
}

class SchedulingTrustException(
    val reasonCode: String,
) : RuntimeException(reasonCode)

class SchedulingEventTrustVerifier(
    private val signatureVerifier: SchedulingEventSignatureVerifier,
    private val allowedProducers: Set<String>,
    private val allowedKeyIds: Set<String>,
    private val expectedIssuer: String,
    private val expectedAudience: String,
    private val replayWindow: Duration,
    private val clock: Clock,
) {
    fun verify(
        envelope: UntrustedSchedulingEventEnvelope<PurchaseCompletedEvent>,
    ): TrustedSchedulingEventEnvelope<PurchaseCompletedEvent> {
        PurchaseEventBounds.validateEnvelopeMetadata(envelope)
        trust(envelope.eventType == "PurchaseCompleted", "EVENT_TYPE_NOT_ALLOWED")
        trust(envelope.producer in allowedProducers, "PRODUCER_NOT_ALLOWED")
        trust(envelope.keyId in allowedKeyIds, "KEY_NOT_ALLOWED")
        trust(envelope.issuer == expectedIssuer, "ISSUER_NOT_ALLOWED")
        trust(envelope.audience == expectedAudience, "AUDIENCE_NOT_ALLOWED")
        val now = clock.instant()
        trust(!envelope.occurredAt.isBefore(now.minus(replayWindow)), "REPLAY_WINDOW_EXCEEDED")
        trust(!envelope.occurredAt.isAfter(now.plusSeconds(30)), "EVENT_FROM_FUTURE")
        trust(envelope.payloadHash == PurchaseCompletedPayloadHasher.hash(envelope.payload), "PAYLOAD_HASH_MISMATCH")
        trust(signatureVerifier.verify(envelope), "SIGNATURE_INVALID")
        return envelope.trusted()
    }

    private fun trust(condition: Boolean, reasonCode: String) {
        if (!condition) throw SchedulingTrustException(reasonCode)
    }
}

object PurchaseCompletedPayloadHasher {
    fun hash(event: PurchaseCompletedEvent): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(canonicalBytes(event))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    internal fun canonicalBytes(event: PurchaseCompletedEvent): ByteArray {
        val canonical = listOf(
            event.sourceAggregateId,
            event.sourceAggregateVersion.toString(),
            event.tenantGroupId.toString(),
            event.clinicId.toString(),
            event.sourcePurchaseAuthority,
            event.sourcePurchaseId,
            event.patientReferenceToken,
            event.catalogSourceAuthority,
            event.productId,
            event.catalogVersion.toString(),
            event.bookingPreference.toString(),
        ).joinToString("\u0000")
        return canonical.toByteArray(StandardCharsets.UTF_8)
    }
}

internal object PurchaseEventBounds {
    private const val MAX_IDENTIFIER_LENGTH = 128
    private const val MAX_TOKEN_LENGTH = 2_048
    private const val MAX_ZONE_ID_LENGTH = 128
    private const val MAX_TIME_WINDOWS = 32
    private const val MAX_CANONICAL_PAYLOAD_BYTES = 32 * 1_024
    private val identifier = Regex("[A-Za-z0-9][A-Za-z0-9._:-]*")

    fun validate(envelope: TrustedSchedulingEventEnvelope<PurchaseCompletedEvent>) {
        validateMetadata(
            envelope.eventId,
            envelope.eventType,
            envelope.producer,
            envelope.issuer,
            envelope.audience,
            envelope.keyId,
            envelope.correlationId,
            envelope.payloadHash,
            envelope.schemaVersion,
        )
        validatePayload(envelope.payload)
    }

    fun validateEnvelopeMetadata(envelope: UntrustedSchedulingEventEnvelope<PurchaseCompletedEvent>) {
        validateMetadata(
            envelope.eventId,
            envelope.eventType,
            envelope.producer,
            envelope.issuer,
            envelope.audience,
            envelope.keyId,
            envelope.correlationId,
            envelope.payloadHash,
            envelope.schemaVersion,
        )
        require(envelope.signature.length <= 1_024) { "signature is too long" }
        validatePayload(envelope.payload)
    }

    private fun validateMetadata(
        eventId: String,
        eventType: String,
        producer: String,
        issuer: String,
        audience: String,
        keyId: String,
        correlationId: String,
        payloadHash: String,
        schemaVersion: Int,
    ) {
        listOf(eventId, eventType, producer, issuer, audience, keyId, correlationId).forEach(::boundedIdentifier)
        require(payloadHash.matches(Regex("[0-9a-f]{64}"))) { "payloadHash must be lowercase SHA-256" }
        require(schemaVersion > 0) { "schemaVersion must be positive" }
    }

    private fun validatePayload(payload: PurchaseCompletedEvent) {
        boundedIdentifier(payload.sourceAggregateId)
        boundedIdentifier(payload.sourcePurchaseAuthority)
        boundedIdentifier(payload.sourcePurchaseId)
        boundedIdentifier(payload.catalogSourceAuthority)
        boundedIdentifier(payload.productId)
        require(payload.sourceAggregateVersion > 0) { "sourceAggregateVersion must be positive" }
        require(payload.tenantGroupId > 0) { "tenantGroupId must be positive" }
        require(payload.clinicId > 0) { "clinicId must be positive" }
        require(payload.catalogVersion > 0) { "catalogVersion must be positive" }
        require(payload.patientReferenceToken.isNotBlank()) { "patientReferenceToken must not be blank" }
        require(payload.patientReferenceToken.length <= MAX_TOKEN_LENGTH) { "patientReferenceToken is too long" }
        validateBookingPreference(payload.bookingPreference)
        require(PurchaseCompletedPayloadHasher.canonicalBytes(payload).size <= MAX_CANONICAL_PAYLOAD_BYTES) {
            "purchase payload is too large"
        }
    }

    private fun validateBookingPreference(preference: BookingPreferenceSnapshot) {
        when (preference) {
            is BookingPreferenceSnapshot.ExactDateTime -> {
                require(preference.zoneId.id.length <= MAX_ZONE_ID_LENGTH) { "zoneId is too long" }
                val validOffsets = preference.zoneId.rules.getValidOffsets(preference.originalLocalDateTime)
                require(preference.originalOffset in validOffsets) {
                    "originalOffset is invalid for originalLocalDateTime and zoneId"
                }
                require(
                    preference.normalizedInstant ==
                        preference.originalLocalDateTime.toInstant(preference.originalOffset),
                ) { "normalizedInstant is inconsistent with the original local date-time" }
            }

            is BookingPreferenceSnapshot.DateRange -> {
                require(preference.zoneId.id.length <= MAX_ZONE_ID_LENGTH) { "zoneId is too long" }
                require(preference.startDate <= preference.endDate) { "date range is reversed" }
            }

            is BookingPreferenceSnapshot.PreferredWeekdaysAndWindows -> {
                require(preference.zoneId.id.length <= MAX_ZONE_ID_LENGTH) { "zoneId is too long" }
                require(preference.weekdays.isNotEmpty()) { "weekdays must not be empty" }
                require(preference.weekdays.size <= 7) { "too many weekdays" }
                require(preference.weekdays.distinct().size == preference.weekdays.size) {
                    "weekdays must not contain duplicates"
                }
                require(preference.localTimeWindows.isNotEmpty()) { "localTimeWindows must not be empty" }
                require(preference.localTimeWindows.size <= MAX_TIME_WINDOWS) { "too many localTimeWindows" }
                require(preference.localTimeWindows.distinct().size == preference.localTimeWindows.size) {
                    "localTimeWindows must not contain duplicates"
                }
            }

            BookingPreferenceSnapshot.NotProvided -> Unit
        }
    }

    private fun boundedIdentifier(value: String) {
        require(value.length in 1..MAX_IDENTIFIER_LENGTH) { "identifier length is invalid" }
        require(identifier.matches(value)) { "identifier contains unsafe characters" }
    }
}
