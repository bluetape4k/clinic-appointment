package io.bluetape4k.clinic.appointment.event.integration

import io.bluetape4k.clinic.appointment.service.StrictJsonPayloadDecoder
import java.time.Clock
import java.time.Duration

fun interface BookingReliabilitySignalEventDecoder {
    fun decode(rawPayload: ByteArray): BookingReliabilitySignalEvent
}

class StrictBookingReliabilitySignalEventDecoder(
    private val decoder: StrictJsonPayloadDecoder = StrictJsonPayloadDecoder(),
) : BookingReliabilitySignalEventDecoder {
    override fun decode(rawPayload: ByteArray): BookingReliabilitySignalEvent =
        decoder.decode(rawPayload, BookingReliabilitySignalEvent::class.java)
}

sealed interface BookingReliabilityIngressResult {
    data class Accepted(val eventRecordId: Long) : BookingReliabilityIngressResult
    data class Quarantined(val quarantineId: Long, val reasonCode: String) : BookingReliabilityIngressResult
}

/**
 * Booking reliability signal ingress.
 *
 * Raw JSON is strict-decoded before hash/signature validation, then accepted facts
 * are stored without PII. Trust failures and source-version conflicts are recorded
 * as both terminal rejections and quarantine rows in the caller-owned transaction.
 */
class BookingReliabilityEventIngress(
    private val trustVerifier: SchedulingEventTrustVerifier,
    private val payloadDecoder: BookingReliabilitySignalEventDecoder,
    private val eventRepository: BookingReliabilityEventRepository,
    private val quarantineEnvelopeProtector: QuarantineEnvelopeProtector,
    private val quarantineRepository: SchedulingQuarantineRepository,
    private val rejectionRepository: UntrustedSchedulingEventRejectionRepository,
    private val clock: Clock,
    private val quarantineRetention: Duration = Duration.ofDays(30),
    private val quarantineRetentionClass: QuarantineRetentionClass = QuarantineRetentionClass.STANDARD,
) {
    init {
        require(!quarantineRetention.isNegative) { "quarantineRetention must be non-negative" }
    }

    fun accept(
        rawEnvelope: UntrustedSchedulingEventEnvelope<BookingReliabilitySignalEvent>,
        rawPayload: ByteArray,
    ): BookingReliabilityIngressResult {
        val trusted = try {
            verify(rawEnvelope, rawPayload)
        } catch (failure: SchedulingTrustException) {
            return quarantine(
                rawEnvelope,
                quarantineEnvelopeProtector.protectUntrusted(rawEnvelope),
                failure.reasonCode,
            )
        }

        val verifiedEnvelope = rawEnvelope.copy(payload = trusted.payload)
        val eventRecordId = try {
            eventRepository.recordAccepted(trusted)
        } catch (failure: SchedulingTrustException) {
            return quarantine(
                verifiedEnvelope,
                quarantineEnvelopeProtector.protect(verifiedEnvelope),
                failure.reasonCode,
            )
        }
        return BookingReliabilityIngressResult.Accepted(eventRecordId)
    }

    fun verify(
        rawEnvelope: UntrustedSchedulingEventEnvelope<BookingReliabilitySignalEvent>,
        rawPayload: ByteArray,
    ): TrustedSchedulingEventEnvelope<BookingReliabilitySignalEvent> {
        trust(rawPayload.size <= MAX_PAYLOAD_BYTES, "PAYLOAD_TOO_LARGE")
        trust(maxJsonDepth(rawPayload) <= MAX_JSON_DEPTH, "PAYLOAD_DEPTH_EXCEEDED")
        trust(rawEnvelope.eventType == EVENT_TYPE, "EVENT_TYPE_NOT_ALLOWED")
        trust(rawEnvelope.schemaVersion == SCHEMA_VERSION, "SCHEMA_VERSION_NOT_ALLOWED")
        validateRawMetadata(rawEnvelope)
        val payload = try {
            payloadDecoder.decode(rawPayload)
        } catch (_: Exception) {
            throw SchedulingTrustException("BOOKING_RELIABILITY_MAPPING_FAILED")
        }
        return trustVerifier.verifyBookingReliability(rawEnvelope.withPayload(payload))
    }

    private fun quarantine(
        envelope: UntrustedSchedulingEventEnvelope<BookingReliabilitySignalEvent>,
        protectedEnvelope: ProtectedQuarantineEnvelope,
        reasonCode: String,
    ): BookingReliabilityIngressResult.Quarantined {
        if (!rejectionRepository.exists(envelope.eventId)) {
            rejectionRepository.record(
                UntrustedEventRejection(
                    eventId = envelope.eventId,
                    eventType = envelope.eventType,
                    producer = envelope.producer,
                    sourceAuthority = envelope.payload.sourceAuthority,
                    sourceAggregateId = envelope.payload.sourceAggregateId,
                    sourceAggregateVersion = envelope.payload.sourceVersion,
                    claimedTenantGroupId = envelope.payload.tenantGroupId,
                    claimedClinicId = envelope.payload.clinicId,
                    schemaVersion = envelope.schemaVersion,
                    correlationId = envelope.correlationId,
                    reasonCode = reasonCode,
                    envelopeHash = protectedEnvelope.envelopeHash,
                    detectedAt = clock.instant(),
                )
            )
        }
        val existing = quarantineRepository.findByEventId(envelope.eventId)
        val quarantine = existing ?: quarantineRepository.recordDetected(
            QuarantineDetection(
                eventId = envelope.eventId,
                eventType = envelope.eventType,
                protectedEnvelope = protectedEnvelope,
                producer = envelope.producer,
                sourceAuthority = envelope.payload.sourceAuthority,
                schemaVersion = envelope.schemaVersion,
                sourceAggregateId = envelope.payload.sourceAggregateId,
                sourceAggregateVersion = envelope.payload.sourceVersion,
                tenantGroupId = envelope.payload.tenantGroupId,
                clinicId = envelope.payload.clinicId,
                reasonCode = reasonCode,
                detectedAt = clock.instant(),
                correlationId = envelope.correlationId,
                retentionClass = quarantineRetentionClass,
                payloadExpiresAt = clock.instant().plus(quarantineRetention),
            )
        )
        return BookingReliabilityIngressResult.Quarantined(quarantine.id, reasonCode)
    }

    private fun validateRawMetadata(envelope: UntrustedSchedulingEventEnvelope<*>) {
        val identifiers = listOf(
            envelope.eventId,
            envelope.eventType,
            envelope.producer,
            envelope.issuer,
            envelope.audience,
            envelope.keyId,
            envelope.algorithm,
            envelope.correlationId,
        )
        trust(
            identifiers.all { it.length in 1..MAX_IDENTIFIER_LENGTH && IDENTIFIER.matches(it) },
            "ENVELOPE_METADATA_INVALID",
        )
        trust(SHA256.matches(envelope.payloadHash), "ENVELOPE_METADATA_INVALID")
        trust(envelope.signature.length in 1..MAX_SIGNATURE_LENGTH, "ENVELOPE_METADATA_INVALID")
    }

    private fun maxJsonDepth(payload: ByteArray): Int {
        val openings = ArrayDeque<Char>()
        var maximum = 0
        var insideString = false
        var escaped = false
        payload.forEach { byte ->
            val character = byte.toInt().toChar()
            if (insideString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> insideString = false
                }
            } else {
                when (character) {
                    '"' -> insideString = true
                    '{', '[' -> {
                        openings.addLast(character)
                        maximum = maxOf(maximum, openings.size)
                    }
                    '}' -> trust(openings.removeLastOrNull() == '{', "PAYLOAD_STRUCTURE_INVALID")
                    ']' -> trust(openings.removeLastOrNull() == '[', "PAYLOAD_STRUCTURE_INVALID")
                }
            }
        }
        trust(!insideString && openings.isEmpty(), "PAYLOAD_STRUCTURE_INVALID")
        return maximum
    }

    private fun UntrustedSchedulingEventEnvelope<BookingReliabilitySignalEvent>.withPayload(
        payload: BookingReliabilitySignalEvent,
    ): UntrustedSchedulingEventEnvelope<BookingReliabilitySignalEvent> =
        copy(payload = payload)

    private fun trust(condition: Boolean, reasonCode: String) {
        if (!condition) throw SchedulingTrustException(reasonCode)
    }

    private companion object {
        const val EVENT_TYPE = "BookingReliabilitySignalRecorded"
        const val SCHEMA_VERSION = 1
        const val MAX_PAYLOAD_BYTES = 1_048_576
        const val MAX_JSON_DEPTH = 32
        const val MAX_IDENTIFIER_LENGTH = 128
        const val MAX_SIGNATURE_LENGTH = 1_024
        val IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9._:-]*")
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
