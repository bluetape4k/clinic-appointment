package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.clinic.appointment.api.service.PatientHistoryCursorKey
import io.bluetape4k.clinic.appointment.api.service.PatientHistoryReferenceKey
import org.springframework.boot.context.properties.ConfigurationProperties
import java.util.Base64

/**
 * 환자 취소 이력 API의 secret-backed key ring 설정입니다.
 *
 * API는 기본적으로 비활성화되어 있으며, 활성화할 때는 운영 secret manager가 주입한
 * Base64 key ring과 외부 [io.bluetape4k.clinic.appointment.api.service.PatientHistoryTokenRegistry]
 * bean이 모두 있어야 합니다. 코드에 내장된 기본 secret이나 process-local registry는
 * production wiring으로 허용하지 않습니다.
 */
@ConfigurationProperties(prefix = "appointment.patient-history")
data class PatientHistoryProperties(
    val apiEnabled: Boolean = false,
    val backfillEnabled: Boolean = false,
    val backfillBatchSize: Int = 500,
    val activeKeyId: String = "",
    val cursorKeySecrets: Map<String, String> = emptyMap(),
    val referenceKeySecrets: Map<String, String> = emptyMap(),
) {
    init {
        require(backfillBatchSize in 1..500) { "patient history backfillBatchSize must be 1..500" }
        if (apiEnabled) {
            require(activeKeyId.matches(KEY_ID)) { "patient history activeKeyId is invalid" }
            require(cursorKeySecrets.isNotEmpty()) { "patient history cursor key ring is required" }
            require(referenceKeySecrets.isNotEmpty()) { "patient history reference key ring is required" }
            require(cursorKeySecrets.containsKey(activeKeyId)) {
                "patient history activeKeyId must exist in cursorKeySecrets"
            }
            require(referenceKeySecrets.containsKey(activeKeyId)) {
                "patient history activeKeyId must exist in referenceKeySecrets"
            }
        }
        cursorKeySecrets.keys.forEach { require(KEY_ID.matches(it)) { "patient history cursor key id is invalid" } }
        referenceKeySecrets.keys.forEach { require(KEY_ID.matches(it)) { "patient history reference key id is invalid" } }
    }

    /** active key를 먼저 둔 deterministic cursor key ring을 생성합니다. */
    fun cursorKeys(): List<PatientHistoryCursorKey> =
        orderedSecrets(cursorKeySecrets, activeKeyId).map { (id, encoded) ->
            PatientHistoryCursorKey(id, decodeSecret("cursorKeySecrets[$id]", encoded))
        }

    /** active key를 먼저 둔 deterministic appointment reference key ring을 생성합니다. */
    fun referenceKeys(): List<PatientHistoryReferenceKey> =
        orderedSecrets(referenceKeySecrets, activeKeyId).map { (id, encoded) ->
            PatientHistoryReferenceKey(id, decodeSecret("referenceKeySecrets[$id]", encoded))
        }

    private fun orderedSecrets(secrets: Map<String, String>, activeId: String): List<Pair<String, String>> {
        if (secrets.isEmpty()) return emptyList()
        val active = secrets[activeId]
            ?: throw IllegalStateException("patient history active key is missing")
        return listOf(activeId to active) + secrets
            .filterKeys { it != activeId }
            .toSortedMap()
            .toList()
    }

    private fun decodeSecret(name: String, encoded: String): ByteArray {
        require(encoded.isNotBlank()) { "$name must not be blank" }
        val decoded = try {
            Base64.getDecoder().decode(encoded)
        } catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException("$name must be valid Base64", failure)
        }
        require(decoded.size >= MINIMUM_SECRET_BYTES) { "$name must contain at least 128 bits" }
        return decoded
    }

    companion object {
        private const val MINIMUM_SECRET_BYTES = 16
        private val KEY_ID = Regex("[A-Za-z0-9_-]{1,32}")
    }
}
