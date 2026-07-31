package io.bluetape4k.clinic.appointment.event.notification

import java.io.Serializable
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 알림 outbox HMAC key 조회 계약이다.
 *
 * `active()`는 새 fingerprint 생성에 반드시 필요하다. `previous()`는 key rotation 이후
 * 기존 idempotency key 조회 후보를 만들 때만 사용한다.
 */
interface NotificationOutboxKeyRing {
    fun active(): NotificationHmacKey?

    fun previous(): List<NotificationHmacKey>
}

/**
 * 테스트와 고정 설정에서 사용하는 in-memory key ring이다.
 */
class StaticNotificationOutboxKeyRing(
    private val active: NotificationHmacKey?,
    previous: List<NotificationHmacKey>,
) : NotificationOutboxKeyRing {

    private val previous = previous.toList()

    override fun active(): NotificationHmacKey? = active

    override fun previous(): List<NotificationHmacKey> = previous
}

/**
 * 알림 outbox HMAC secret이다.
 *
 * secret byte 배열은 생성자와 accessor 양쪽에서 방어적으로 복사한다.
 */
class NotificationHmacKey(
    val keyId: String,
    secretBytes: ByteArray,
) : Serializable {

    private val secretBytes = secretBytes.copyOf()

    init {
        require(keyId.isNotBlank()) { "keyId must not be blank" }
        require(secretBytes.isNotEmpty()) { "secretBytes must not be empty" }
    }

    fun secretBytes(): ByteArray = secretBytes.copyOf()

    fun sign(domainPrefix: String, value: String): String {
        require(domainPrefix.isNotBlank()) { "domainPrefix must not be blank" }
        require(value.isNotBlank()) { "value must not be blank" }

        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(secretBytes, HMAC_SHA256))
        return mac.doFinal("$domainPrefix:$value".toByteArray(Charsets.UTF_8)).toHex()
    }

    companion object {
        private const val serialVersionUID = 1L
        private const val HMAC_SHA256 = "HmacSHA256"
    }
}

/**
 * HMAC fingerprint와 해당 key id를 함께 보관하는 조회 후보다.
 */
data class NotificationOutboxFingerprint(
    val keyId: String,
    val fingerprint: String,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 알림 outbox idempotency와 audit fingerprint를 생성한다.
 */
class NotificationOutboxHasher(
    private val keyRing: NotificationOutboxKeyRing,
) {

    fun fingerprint(value: String): NotificationOutboxFingerprint =
        activeKey().fingerprint(IDEMPOTENCY_DOMAIN_PREFIX, value)

    fun auditFingerprint(value: String): NotificationOutboxFingerprint =
        activeKey().fingerprint(AUDIT_DOMAIN_PREFIX, value)

    fun candidates(value: String): List<NotificationOutboxFingerprint> {
        val keys = buildList {
            add(activeKey())
            addAll(keyRing.previous())
        }

        return keys
            .distinctBy { it.keyId }
            .map { it.fingerprint(IDEMPOTENCY_DOMAIN_PREFIX, value) }
    }

    private fun activeKey(): NotificationHmacKey =
        keyRing.active()
            ?: throw NotificationContractException(
                failureCode = NotificationFailureCode.HMAC_KEY_UNAVAILABLE,
                message = "Active notification HMAC key is unavailable",
            )

    private fun NotificationHmacKey.fingerprint(
        domainPrefix: String,
        value: String,
    ): NotificationOutboxFingerprint =
        NotificationOutboxFingerprint(
            keyId = keyId,
            fingerprint = sign(domainPrefix, value),
        )

    companion object {
        const val IDEMPOTENCY_DOMAIN_PREFIX = "clinic-notification:idempotency:v1"
        const val AUDIT_DOMAIN_PREFIX = "clinic-notification:audit:v1"
    }
}

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte) }
