package io.bluetape4k.clinic.appointment.notification

import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.time.Duration
import java.time.Instant

/**
 * 알림 outbox HMAC key-ring 설정입니다.
 *
 * key material은 설정 값에 직접 넣지 않고 외부 secret reference만 전달합니다.
 */
@ConfigurationProperties(prefix = "clinic.notification.crypto")
data class NotificationCryptoProperties(
    val active: KeyReference? = null,
    val previous: KeyReference? = null,
    val maximumPreviousOverlap: Duration = Duration.ofDays(35),
) : Serializable {

    fun validate(now: Instant = Instant.now()): NotificationCryptoProperties {
        val activeKey = active ?: fail("active key reference is required")
        activeKey.validate("active")
        check(!activeKey.activatedAt.isAfter(now)) { "active key reference is not active yet" }
        check(activeKey.expiresAt.isAfter(now)) { "active key reference is expired" }
        previous?.let { previousKey ->
            previousKey.validate("previous")
            check(previousKey.keyId != activeKey.keyId) { "previous keyId must differ from active keyId" }
            check(!maximumPreviousOverlap.isNegative && !maximumPreviousOverlap.isZero) {
                "maximumPreviousOverlap must be positive"
            }
            check(previousKey.activatedAt.isBefore(activeKey.activatedAt)) {
                "previous key must be activated before active key"
            }
            check(previousKey.expiresAt.isAfter(now)) { "previous key reference is expired" }
            val overlap = Duration.between(activeKey.activatedAt, previousKey.expiresAt)
            check(!overlap.isNegative && overlap <= maximumPreviousOverlap) {
                "previous key overlap must not exceed $maximumPreviousOverlap"
            }
        }
        return this
    }

    private fun KeyReference.validate(label: String) {
        check(keyId.matches(SAFE_KEY_ID)) { "$label keyId must be a safe identifier" }
        check(EXTERNAL_SECRET_REFERENCE.matches(secretReference)) {
            "$label secret reference must use an external secret provider"
        }
        check(activatedAt.isBefore(expiresAt)) { "$label key activation must be before expiry" }
    }

    private fun fail(message: String): Nothing = throw IllegalStateException(message)

    data class KeyReference(
        val keyId: String,
        val secretReference: String,
        val activatedAt: Instant,
        val expiresAt: Instant,
    ) : Serializable {
        override fun toString(): String =
            "KeyReference(keyId=$keyId, secretReference=<redacted>, activatedAt=$activatedAt, expiresAt=$expiresAt)"

        companion object {
            private const val serialVersionUID = 1L
        }
    }

    companion object {
        private const val serialVersionUID = 1L
        private val SAFE_KEY_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        private val EXTERNAL_SECRET_REFERENCE =
            Regex("(?:vault|aws-secretsmanager|gcp-secretmanager|azure-keyvault|env|file):\\S+", RegexOption.IGNORE_CASE)
    }
}
