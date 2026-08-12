package io.bluetape4k.clinic.appointment.model.identity

import java.text.Normalizer
import java.util.Locale

/** 환자 인증에 사용할 수 있는 login identifier 종류입니다. */
enum class PatientLoginIdentifierKey {
    PHONE,
    EMAIL,
    LOGIN_ID,
}

/**
 * 정규화가 끝난 환자 login identifier입니다.
 *
 * raw 입력은 [of]에서만 받고, 저장소와 application layer에는 canonical value만 전달합니다.
 */
data class PatientLoginIdentifier(
    val key: PatientLoginIdentifierKey,
    val value: String,
) {
    init {
        require(value == normalize(key, value)) { "identifier value must be canonical" }
    }

    companion object {
        private const val MAX_EMAIL_LENGTH = 254
        private const val MAX_LOGIN_ID_LENGTH = 64
        private val EMAIL_PATTERN = Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
        private val LOGIN_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{2,63}")
        private val KOREAN_PHONE_PATTERN = Regex("(?:010\\d{8}|01[16789]\\d{7,8})")
        private val PHONE_SEPARATOR = Regex("[\\s()\\-]")

        /** raw HTTP 입력을 key별 canonical value로 변환합니다. */
        fun of(key: PatientLoginIdentifierKey, rawValue: String): PatientLoginIdentifier {
            val normalized = normalize(key, rawValue)
            return PatientLoginIdentifier(key, normalized)
        }

        /** 등록은 최소 한 개, 최대 세 개의 서로 다른 key를 허용합니다. */
        fun validateForRegistration(identifiers: Collection<PatientLoginIdentifier>) {
            require(identifiers.size in 1..PatientLoginIdentifierKey.entries.size) {
                "one to three identifiers are required"
            }
            require(identifiers.map(PatientLoginIdentifier::key).toSet().size == identifiers.size) {
                "identifier keys must be unique"
            }
        }

        /** key에 맞는 canonical value를 반환합니다. */
        fun normalize(key: PatientLoginIdentifierKey, rawValue: String): String {
            require(rawValue.none(Char::isISOControl)) {
                "identifier value contains control characters"
            }
            val value = Normalizer.normalize(rawValue.trim(), Normalizer.Form.NFC)
            require(value.isNotEmpty()) {
                "identifier value is blank"
            }
            return when (key) {
                PatientLoginIdentifierKey.PHONE -> normalizePhone(value)
                PatientLoginIdentifierKey.EMAIL -> normalizeEmail(value)
                PatientLoginIdentifierKey.LOGIN_ID -> normalizeLoginId(value)
            }
        }

        private fun normalizePhone(value: String): String {
            val compact = value.replace(PHONE_SEPARATOR, "")
            val national = when {
                compact.startsWith("+82") -> "0" + compact.removePrefix("+82")
                else -> compact
            }
            require(KOREAN_PHONE_PATTERN.matches(national)) { "invalid Korean phone number" }
            return "+82" + national.removePrefix("0")
        }

        private fun normalizeEmail(value: String): String {
            val normalized = value.lowercase(Locale.ROOT)
            require(normalized.length <= MAX_EMAIL_LENGTH && EMAIL_PATTERN.matches(normalized)) {
                "invalid email address"
            }
            return normalized
        }

        private fun normalizeLoginId(value: String): String {
            val normalized = value.lowercase(Locale.ROOT)
            require(normalized.length <= MAX_LOGIN_ID_LENGTH && LOGIN_ID_PATTERN.matches(normalized)) {
                "invalid login id"
            }
            return normalized
        }
    }
}
