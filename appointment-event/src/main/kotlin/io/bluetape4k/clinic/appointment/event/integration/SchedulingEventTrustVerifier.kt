package io.bluetape4k.clinic.appointment.event.integration

import io.bluetape4k.clinic.appointment.model.plan.BookingPreferenceSnapshot
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration

fun interface SchedulingEventSignatureVerifier {
    fun verify(envelope: UntrustedSchedulingEventEnvelope<*>): Boolean
}

class SchedulingTrustException(
    val reasonCode: String,
) : RuntimeException(reasonCode)

class SchedulingEventTrustVerifier(
    private val signatureVerifier: SchedulingEventSignatureVerifier,
    private val allowedProducers: Set<String>,
    private val allowedKeyIds: Set<String>,
    private val allowedAlgorithms: Set<String>,
    private val expectedIssuer: String,
    private val expectedAudience: String,
    private val replayWindow: Duration,
    private val clock: Clock,
) {
    init {
        require(allowedProducers.isNotEmpty()) { "allowedProducers must not be empty" }
        require(allowedKeyIds.isNotEmpty()) { "allowedKeyIds must not be empty" }
        require(allowedAlgorithms.isNotEmpty()) { "allowedAlgorithms must not be empty" }
        require(!replayWindow.isZero && !replayWindow.isNegative) { "replayWindow must be positive" }
    }

    fun verify(
        envelope: UntrustedSchedulingEventEnvelope<PurchaseCompletedEvent>,
    ): TrustedSchedulingEventEnvelope<PurchaseCompletedEvent> {
        PurchaseEventBounds.validateEnvelopeMetadata(envelope)
        trust(envelope.eventType == "PurchaseCompleted", "EVENT_TYPE_NOT_ALLOWED")
        trust(envelope.producer in allowedProducers, "PRODUCER_NOT_ALLOWED")
        trust(envelope.keyId in allowedKeyIds, "KEY_NOT_ALLOWED")
        trust(envelope.algorithm in allowedAlgorithms, "ALGORITHM_NOT_ALLOWED")
        trust(envelope.issuer == expectedIssuer, "ISSUER_NOT_ALLOWED")
        trust(envelope.audience == expectedAudience, "AUDIENCE_NOT_ALLOWED")
        val now = clock.instant()
        trust(!envelope.occurredAt.isBefore(now.minus(replayWindow)), "REPLAY_WINDOW_EXCEEDED")
        trust(!envelope.occurredAt.isAfter(now.plusSeconds(30)), "EVENT_FROM_FUTURE")
        trust(envelope.payloadHash == PurchaseCompletedPayloadHasher.hash(envelope.payload), "PAYLOAD_HASH_MISMATCH")
        trust(signatureVerifier.verify(envelope), "SIGNATURE_INVALID")
        return envelope.trusted()
    }

    /**
     * 고정 allowlist의 실행 BOM event만 trusted envelope로 승격합니다.
     *
     * @param envelope raw payload에서 strict decoding한 schema version 1 envelope입니다.
     * @return metadata, replay window, canonical hash, 서명을 모두 통과한 envelope입니다.
     * @throws SchedulingTrustException 허용된 신뢰 계약 중 하나라도 실패하면 발생합니다.
     */
    fun verifyPackageExecution(
        envelope: UntrustedSchedulingEventEnvelope<PackageExecutionEvent>,
    ): TrustedSchedulingEventEnvelope<PackageExecutionEvent> {
        try {
            PackageExecutionEventBounds.validate(envelope)
        } catch (_: IllegalArgumentException) {
            throw SchedulingTrustException("PAYLOAD_CONTRACT_INVALID")
        }
        trust(envelope.eventType == "PackageExecutionPlanned", "EVENT_TYPE_NOT_ALLOWED")
        trust(envelope.schemaVersion == 1, "SCHEMA_VERSION_NOT_ALLOWED")
        trust(envelope.producer in allowedProducers, "PRODUCER_NOT_ALLOWED")
        trust(envelope.keyId in allowedKeyIds, "KEY_NOT_ALLOWED")
        trust(envelope.algorithm in allowedAlgorithms, "ALGORITHM_NOT_ALLOWED")
        trust(envelope.issuer == expectedIssuer, "ISSUER_NOT_ALLOWED")
        trust(envelope.audience == expectedAudience, "AUDIENCE_NOT_ALLOWED")
        val now = clock.instant()
        trust(!envelope.occurredAt.isBefore(now.minus(replayWindow)), "REPLAY_WINDOW_EXCEEDED")
        trust(!envelope.occurredAt.isAfter(now.plusSeconds(30)), "EVENT_FROM_FUTURE")
        trust(envelope.payloadHash == PackageExecutionPayloadHasher.hash(envelope.payload), "PAYLOAD_HASH_MISMATCH")
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
        return CanonicalFrameWriter().apply {
            string("sourceAggregateId", event.sourceAggregateId)
            long("sourceAggregateVersion", event.sourceAggregateVersion)
            long("tenantGroupId", event.tenantGroupId)
            long("clinicId", event.clinicId)
            string("sourcePurchaseAuthority", event.sourcePurchaseAuthority)
            string("sourcePurchaseId", event.sourcePurchaseId)
            string("patientReferenceToken", event.patientReferenceToken)
            string("catalogSourceAuthority", event.catalogSourceAuthority)
            string("productId", event.productId)
            long("catalogVersion", event.catalogVersion)
            bookingPreference("bookingPreference", event.bookingPreference)
        }.toByteArray()
    }
}

internal class CanonicalFrameWriter {
    private val out = ByteArrayOutputStream()

    fun string(name: String, value: String?) {
        frame(name, "string", value?.toByteArray(StandardCharsets.UTF_8))
    }

    fun int(name: String, value: Int) {
        frame(name, "int", value.toString().toByteArray(StandardCharsets.UTF_8))
    }

    fun long(name: String, value: Long) {
        frame(name, "long", value.toString().toByteArray(StandardCharsets.UTF_8))
    }

    fun instant(name: String, value: java.time.Instant) {
        frame(name, "instant", value.toString().toByteArray(StandardCharsets.UTF_8))
    }

    fun bookingPreference(name: String, value: BookingPreferenceSnapshot) {
        when (value) {
            is BookingPreferenceSnapshot.ExactDateTime -> {
                string("$name.type", "EXACT_DATE_TIME")
                string("$name.originalLocalDateTime", value.originalLocalDateTime.toString())
                string("$name.originalOffset", value.originalOffset.toString())
                string("$name.zoneId", value.zoneId.id)
                instant("$name.normalizedInstant", value.normalizedInstant)
            }
            is BookingPreferenceSnapshot.DateRange -> {
                string("$name.type", "DATE_RANGE")
                string("$name.startDate", value.startDate.toString())
                string("$name.endDate", value.endDate.toString())
                string("$name.zoneId", value.zoneId.id)
            }
            is BookingPreferenceSnapshot.PreferredWeekdaysAndWindows -> {
                string("$name.type", "PREFERRED_WEEKDAYS_AND_WINDOWS")
                int("$name.weekdays.size", value.weekdays.size)
                value.weekdays.forEachIndexed { index, weekday ->
                    string("$name.weekdays[$index]", weekday.name)
                }
                int("$name.localTimeWindows.size", value.localTimeWindows.size)
                value.localTimeWindows.forEachIndexed { index, window ->
                    string("$name.localTimeWindows[$index].start", window.start.toString())
                    string("$name.localTimeWindows[$index].end", window.end.toString())
                }
                string("$name.zoneId", value.zoneId.id)
            }
            BookingPreferenceSnapshot.NotProvided -> string("$name.type", "NOT_PROVIDED")
        }
    }

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun frame(
        name: String,
        type: String,
        value: ByteArray?,
    ) {
        writeAscii(name)
        out.write(0)
        writeAscii(type)
        out.write(0)
        if (value == null) {
            writeAscii("-1")
        } else {
            writeAscii(value.size.toString())
            out.write(0)
            out.write(value)
        }
        out.write(0)
    }

    private fun writeAscii(value: String) {
        out.write(value.toByteArray(StandardCharsets.US_ASCII))
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
            envelope.algorithm,
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
            envelope.algorithm,
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
        algorithm: String,
        correlationId: String,
        payloadHash: String,
        schemaVersion: Int,
    ) {
        listOf(eventId, eventType, producer, issuer, audience, keyId, algorithm, correlationId)
            .forEach(::boundedIdentifier)
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
