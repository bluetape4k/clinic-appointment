package io.bluetape4k.clinic.appointment.api.service

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** appointment ID를 외부에 노출하지 않는 환자 취소 이력 reference codec입니다. */
class PatientHistoryReferenceCodec(
    keys: List<PatientHistoryReferenceKey>,
) {
    private val keyRing = keys.associate { key ->
        require(KEY_ID.matches(key.id)) { "reference key id is invalid" }
        require(key.secret.size >= 16) { "reference key must contain at least 128 bits" }
        key.id to key.secret.copyOf()
    }
    private val activeKey = keys.firstOrNull() ?: error("at least one reference key is required")

    init {
        require(keyRing.size == keys.size) { "reference key ids must be unique" }
    }

    /** canonical tenant/patient/appointment/detail tuple을 domain-separated HMAC으로 감쌉니다. */
    fun encode(
        tenantGroupId: Long,
        patientScopeFingerprint: String,
        appointmentId: Long,
        detailId: Long,
    ): String {
        require(tenantGroupId > 0 && appointmentId > 0 && detailId > 0)
        require(FINGERPRINT.matches(patientScopeFingerprint))
        val digest = hmac(activeKey.secret, canonicalBytes(tenantGroupId, patientScopeFingerprint, appointmentId, detailId))
        return "v1.${activeKey.id}.${Base64.getUrlEncoder().withoutPadding().encodeToString(digest)}"
    }

    /** reference가 현재 actor scope와 detail identity에 결속됐는지 constant-time으로 확인합니다. */
    fun matches(
        reference: String,
        tenantGroupId: Long,
        patientScopeFingerprint: String,
        appointmentId: Long,
        detailId: Long,
    ): Boolean {
        val parts = reference.split('.')
        if (parts.size != 3 || parts[0] != "v1" || !KEY_ID.matches(parts[1])) return false
        val key = keyRing[parts[1]] ?: return false
        val supplied = try { Base64.getUrlDecoder().decode(parts[2]) } catch (_: IllegalArgumentException) { return false }
        if (supplied.size != 32) return false
        if (Base64.getUrlEncoder().withoutPadding().encodeToString(supplied) != parts[2]) return false
        val expected = hmac(key, canonicalBytes(tenantGroupId, patientScopeFingerprint, appointmentId, detailId))
        return MessageDigest.isEqual(expected, supplied)
    }

    private fun canonicalBytes(
        tenantGroupId: Long,
        patientScopeFingerprint: String,
        appointmentId: Long,
        detailId: Long,
    ): ByteArray {
        val fingerprint = patientScopeFingerprint.toByteArray(StandardCharsets.UTF_8)
        val purpose = PURPOSE.toByteArray(StandardCharsets.UTF_8)
        return ByteBuffer.allocate(4 + purpose.size + 8 + 4 + fingerprint.size + 8 + 8).apply {
            putInt(purpose.size)
            put(purpose)
            putLong(tenantGroupId)
            putInt(fingerprint.size)
            put(fingerprint)
            putLong(appointmentId)
            putLong(detailId)
        }.array()
    }

    private fun hmac(secret: ByteArray, bytes: ByteArray): ByteArray =
        Mac.getInstance(HMAC_SHA256).run {
            init(SecretKeySpec(secret, HMAC_SHA256))
            doFinal(bytes)
        }

    companion object {
        private const val HMAC_SHA256 = "HmacSHA256"
        private const val PURPOSE = "patient-history-appointment-ref-v1"
        private val KEY_ID = Regex("[A-Za-z0-9_-]{1,32}")
        private val FINGERPRINT = Regex("[0-9a-f]{64}")
    }
}

data class PatientHistoryReferenceKey(
    val id: String,
    val secret: ByteArray,
)
