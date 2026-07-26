package io.bluetape4k.clinic.appointment.event.integration

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
)

fun interface PatientReferenceProtector {
    fun protect(tenantGroupId: Long, patientReferenceToken: String): ProtectedPatientReference
}

class AesGcmPatientReferenceProtector(
    encryptionKey: ByteArray,
    fingerprintKey: ByteArray,
    private val keyId: String,
) : PatientReferenceProtector {
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
}
