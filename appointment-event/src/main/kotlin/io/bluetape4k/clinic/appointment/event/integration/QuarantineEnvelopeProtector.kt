package io.bluetape4k.clinic.appointment.event.integration

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

data class ProtectedQuarantineEnvelope(
    val ciphertext: String,
    val keyId: String,
    val envelopeHash: String,
)

fun interface QuarantineEnvelopeProtector {
    /**
     * Encrypts the bounded original envelope before any persistence transaction.
     */
    fun protect(
        envelope: UntrustedSchedulingEventEnvelope<PurchaseCompletedEvent>,
    ): ProtectedQuarantineEnvelope
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
        envelope: UntrustedSchedulingEventEnvelope<PurchaseCompletedEvent>,
    ): ProtectedQuarantineEnvelope {
        PurchaseEventBounds.validateEnvelopeMetadata(envelope)
        val plaintext = canonicalBytes(envelope)
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(plaintext)
            .joinToString("") { byte -> "%02x".format(byte) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey)
        cipher.updateAAD(
            "appointment-quarantine\u0000${envelope.payload.tenantGroupId}\u0000${envelope.payload.clinicId}\u0000${envelope.eventId}"
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
        envelope: UntrustedSchedulingEventEnvelope<PurchaseCompletedEvent>,
    ): ByteArray {
        val metadata = CanonicalFrameWriter().apply {
            string("eventId", envelope.eventId)
            string("eventType", envelope.eventType)
            instant("occurredAt", envelope.occurredAt)
            instant("receivedAt", envelope.receivedAt)
            string("producer", envelope.producer)
            string("issuer", envelope.issuer)
            string("audience", envelope.audience)
            string("keyId", envelope.keyId)
            string("algorithm", envelope.algorithm)
            int("schemaVersion", envelope.schemaVersion)
            string("correlationId", envelope.correlationId)
            string("payloadHash", envelope.payloadHash)
            string("signature", envelope.signature)
        }.toByteArray()
        return metadata + PurchaseCompletedPayloadHasher.canonicalBytes(envelope.payload)
    }
}
