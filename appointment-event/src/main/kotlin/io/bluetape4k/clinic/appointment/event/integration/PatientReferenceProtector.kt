package io.bluetape4k.clinic.appointment.event.integration

import java.io.Serializable
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class ProtectedPatientReference(
    val ciphertext: String,
    val keyId: String,
    val fingerprint: String,
) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

fun interface PatientReferenceProtector {
    fun protect(tenantGroupId: Long, patientReferenceToken: String): ProtectedPatientReference
}

class AesGcmPatientReferenceProtector(
    encryptionKey: ByteArray,
    fingerprintKey: ByteArray,
    private val keyId: String,
) : PatientReferenceProtector {
    init {
        require(encryptionKey.size in setOf(16, 24, 32)) {
            "encryptionKey must be a 128, 192, or 256 bit AES key"
        }
        require(fingerprintKey.size >= 32) {
            "fingerprintKey must contain at least 32 bytes"
        }
        require(SAFE_KEY_ID.matches(keyId)) {
            "keyId must be a safe 1-128 character identifier"
        }
    }

    private val encryptionKey: SecretKey = SecretKeySpec(encryptionKey.copyOf(), "AES")
    private val fingerprintKey: SecretKey = SecretKeySpec(fingerprintKey.copyOf(), "HmacSHA256")

    override fun protect(
        tenantGroupId: Long,
        patientReferenceToken: String,
    ): ProtectedPatientReference {
        require(tenantGroupId > 0) { "tenantGroupId must be positive" }
        require(patientReferenceToken.isNotBlank()) { "patientReferenceToken must not be blank" }
        val tenantDomain = "appointment-patient-reference\u0000$tenantGroupId"
            .toByteArray(StandardCharsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey)
        cipher.updateAAD(tenantDomain)
        val ciphertext = cipher.doFinal(patientReferenceToken.toByteArray(StandardCharsets.UTF_8))
        val packed = cipher.iv + ciphertext
        val mac = Mac.getInstance("HmacSHA256").apply { init(fingerprintKey) }
        mac.update(tenantDomain)
        mac.update(0)
        val fingerprint = mac.doFinal(patientReferenceToken.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return ProtectedPatientReference(
            ciphertext = Base64.getEncoder().encodeToString(packed),
            keyId = keyId,
            fingerprint = fingerprint,
        )
    }

    private companion object {
        val SAFE_KEY_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    }
}
