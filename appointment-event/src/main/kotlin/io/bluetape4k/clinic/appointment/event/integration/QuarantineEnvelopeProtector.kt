package io.bluetape4k.clinic.appointment.event.integration

import java.nio.charset.StandardCharsets
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
