package io.bluetape4k.clinic.appointment.event.integration

import io.bluetape4k.clinic.appointment.event.profile.PatientSchedulingAssessmentChanged
import io.bluetape4k.clinic.appointment.event.profile.PatientSchedulingAssessmentChangedHasher
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * 격리 저장소에 보관할 암호화 envelope와 무결성 증거입니다.
 *
 * @property ciphertext bounded 원문의 AES-GCM 암호문입니다. transport 상한을 초과해
 * 재처리할 수 없는 원문은 저장 비용 증폭을 막기 위해 `null`입니다.
 * @property keyId 복호화 key rotation에 사용하는 불투명 key 식별자입니다. key 원문이나
 * secret을 포함하면 안 됩니다.
 * @property envelopeHash canonical metadata와 원문 전체를 결합한 소문자 SHA-256입니다.
 * 운영자가 승인한 정확한 envelope와 redrive 입력을 결합하는 데 사용합니다.
 */
data class ProtectedQuarantineEnvelope(
    val ciphertext: String?,
    val keyId: String,
    val envelopeHash: String,
)

fun interface QuarantineEnvelopeProtector {
    /**
     * Encrypts the bounded original envelope before any persistence transaction.
     */
    fun protect(
        envelope: UntrustedSchedulingEventEnvelope<*>,
    ): ProtectedQuarantineEnvelope

    /** trust 검증 전에 관측된 envelope를 bounded evidence로 보호합니다. */
    fun protectUntrusted(
        envelope: UntrustedSchedulingEventEnvelope<*>,
    ): ProtectedQuarantineEnvelope = protect(envelope)
}

class AesGcmQuarantineEnvelopeProtector(
    encryptionKey: ByteArray,
    private val keyId: String,
) : QuarantineEnvelopeProtector {
    private val encryptionKey: SecretKey = SecretKeySpec(encryptionKey.copyOf(), "AES")

    init {
        require(encryptionKey.size in setOf(16, 24, 32)) {
            "AES encryption key must be 16, 24, or 32 bytes"
        }
        require(keyId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) {
            "keyId contains unsafe characters"
        }
    }

    override fun protect(
        envelope: UntrustedSchedulingEventEnvelope<*>,
    ): ProtectedQuarantineEnvelope {
        if (envelope.payload is PurchaseCompletedEvent) {
            @Suppress("UNCHECKED_CAST")
            PurchaseEventBounds.validateEnvelopeMetadata(
                envelope as UntrustedSchedulingEventEnvelope<PurchaseCompletedEvent>,
            )
        } else if (envelope.payload is PatientSchedulingAssessmentChanged) {
            @Suppress("UNCHECKED_CAST")
            ProfileAssessmentEventBounds.validateEnvelopeMetadata(
                envelope as UntrustedSchedulingEventEnvelope<PatientSchedulingAssessmentChanged>,
            )
        } else if (envelope.payload is BookingReliabilitySignalEvent) {
            @Suppress("UNCHECKED_CAST")
            BookingReliabilityEventBounds.validate(
                envelope as UntrustedSchedulingEventEnvelope<BookingReliabilitySignalEvent>,
            )
        }
        return encrypt(envelope, tolerant = false)
    }

    override fun protectUntrusted(
        envelope: UntrustedSchedulingEventEnvelope<*>,
    ): ProtectedQuarantineEnvelope = encrypt(envelope, tolerant = true)

    private fun encrypt(
        envelope: UntrustedSchedulingEventEnvelope<*>,
        tolerant: Boolean,
    ): ProtectedQuarantineEnvelope {
        val plaintext = canonicalBytes(envelope, tolerant)
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(plaintext)
            .joinToString("") { byte -> "%02x".format(byte) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey)
        val scope = scope(envelope.payload)
        cipher.updateAAD(
            "appointment-quarantine\u0000${scope.first}\u0000${scope.second}\u0000${aadComponent(envelope.eventId, tolerant)}"
                .toByteArray(StandardCharsets.UTF_8)
        )
        val encrypted = cipher.doFinal(plaintext)
        return ProtectedQuarantineEnvelope(
            ciphertext = Base64.getEncoder().encodeToString(cipher.iv + encrypted),
            keyId = keyId,
            envelopeHash = hash,
        )
    }

    private fun canonicalBytes(
        envelope: UntrustedSchedulingEventEnvelope<*>,
        tolerant: Boolean,
    ): ByteArray {
        val metadata = CanonicalFrameWriter().apply {
            metadataString("eventId", envelope.eventId, MAX_IDENTIFIER_LENGTH, tolerant)
            metadataString("eventType", envelope.eventType, MAX_IDENTIFIER_LENGTH, tolerant)
            instant("occurredAt", envelope.occurredAt)
            instant("receivedAt", envelope.receivedAt)
            metadataString("producer", envelope.producer, MAX_IDENTIFIER_LENGTH, tolerant)
            metadataString("issuer", envelope.issuer, MAX_IDENTIFIER_LENGTH, tolerant)
            metadataString("audience", envelope.audience, MAX_IDENTIFIER_LENGTH, tolerant)
            metadataString("keyId", envelope.keyId, MAX_IDENTIFIER_LENGTH, tolerant)
            metadataString("algorithm", envelope.algorithm, MAX_IDENTIFIER_LENGTH, tolerant)
            int("schemaVersion", envelope.schemaVersion)
            metadataString("correlationId", envelope.correlationId, MAX_IDENTIFIER_LENGTH, tolerant)
            metadataString("payloadHash", envelope.payloadHash, MAX_SHA256_LENGTH, tolerant)
            metadataString("signature", envelope.signature, MAX_SIGNATURE_LENGTH, tolerant)
        }.toByteArray()
        val payload = when (val event = envelope.payload) {
            is PurchaseCompletedEvent -> PurchaseCompletedPayloadHasher.canonicalBytes(event)
            is PatientSchedulingAssessmentChanged ->
                PatientSchedulingAssessmentChangedHasher.canonicalBytes(event)
            is BookingReliabilitySignalEvent -> BookingReliabilitySignalPayloadHasher.canonicalBytes(event)
            else -> throw IllegalArgumentException("unsupported quarantine envelope payload")
        }
        return metadata + payload
    }

    private fun CanonicalFrameWriter.metadataString(
        name: String,
        value: String,
        maximumLength: Int,
        tolerant: Boolean,
    ) {
        if (tolerant) {
            boundedString(name, value, maximumLength)
        } else {
            string(name, value)
        }
    }

    /** 초과 metadata는 전체 길이와 앞 256자의 SHA-256만 frame에 남깁니다. */
    private fun CanonicalFrameWriter.boundedString(
        name: String,
        value: String,
        maximumLength: Int,
    ) {
        if (value.length <= maximumLength) {
            string(name, value)
        } else {
            int("$name.length", value.length)
            string("$name.sampleHash", boundedSampleHash(value))
        }
    }

    private fun aadComponent(value: String, tolerant: Boolean): String =
        if (!tolerant || (value.length <= MAX_IDENTIFIER_LENGTH && SAFE_IDENTIFIER.matches(value))) {
            value
        } else {
            "invalid:${value.length}:${boundedSampleHash(value)}"
        }

    /** 입력 전체를 복제하지 않고 고정 길이 표본만 hash합니다. */
    private fun boundedSampleHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestOutputStream(OutputStream.nullOutputStream(), digest).use { digestStream ->
            OutputStreamWriter(digestStream, StandardCharsets.UTF_8).use { writer ->
                writer.write(value, 0, minOf(value.length, MAX_METADATA_SAMPLE_LENGTH))
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun scope(payload: Any?): Pair<Long, Long> =
        when (payload) {
            is PurchaseCompletedEvent -> payload.tenantGroupId to payload.clinicId
            is PatientSchedulingAssessmentChanged -> payload.tenantGroupId to payload.clinicId
            is BookingReliabilitySignalEvent -> payload.tenantGroupId to payload.clinicId
            else -> throw IllegalArgumentException("unsupported quarantine envelope payload")
        }

    private companion object {
        const val MAX_IDENTIFIER_LENGTH = 128
        const val MAX_SHA256_LENGTH = 64
        const val MAX_SIGNATURE_LENGTH = 1_024
        const val MAX_METADATA_SAMPLE_LENGTH = 256
        val SAFE_IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    }
}
