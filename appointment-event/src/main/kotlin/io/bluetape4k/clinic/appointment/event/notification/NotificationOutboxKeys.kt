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
    fun active(): NotificationHmacKey

    fun previous(): NotificationHmacKey?
}

/**
 * 알림 outbox HMAC digest 생성 port다.
 */
interface NotificationOutboxHasher {
    fun idempotencyCandidates(input: NotificationIdempotencyInput): List<NotificationIdempotencyDigest>

    fun auditFingerprint(input: NotificationAuditInput): NotificationAuditFingerprint
}

/**
 * 테스트와 고정 설정에서 사용하는 in-memory key ring이다.
 */
class StaticNotificationOutboxKeyRing(
    private val active: NotificationHmacKey,
    private val previous: NotificationHmacKey?,
) : NotificationOutboxKeyRing {

    override fun active(): NotificationHmacKey = active

    override fun previous(): NotificationHmacKey? = previous
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

    fun sign(domainPrefix: String, normalizedInput: String): String {
        require(domainPrefix.isNotBlank()) { "domainPrefix must not be blank" }
        require(normalizedInput.isNotBlank()) { "normalizedInput must not be blank" }

        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(secretBytes, HMAC_SHA256))
        return mac.doFinal(lengthPrefixed(listOf(domainPrefix, normalizedInput)).toByteArray(Charsets.UTF_8)).toHex()
    }

    companion object {
        private const val serialVersionUID = 1L
        private const val HMAC_SHA256 = "HmacSHA256"
    }
}

/**
 * idempotency digest를 계산할 deterministic 입력이다.
 *
 * field 순서는 tenant group, clinic, appointment, revision, event type, channel, slot로
 * 고정한다. member profile이나 수신자 원문 데이터는 포함하지 않는다.
 */
data class NotificationIdempotencyInput(
    val tenantGroupId: TenantGroupId,
    val clinicId: ClinicId,
    val appointmentId: AppointmentId,
    val appointmentVersionOrRevision: Long,
    val eventType: NotificationEventType,
    val channel: NotificationChannelType,
    val notificationSlot: NotificationSlot,
) : Serializable {
    init {
        require(appointmentVersionOrRevision >= 0) { "appointmentVersionOrRevision must not be negative" }
    }

    fun normalize(): String =
        lengthPrefixed(
            listOf(
                tenantGroupId.value.toString(),
                clinicId.value.toString(),
                appointmentId.value.toString(),
                appointmentVersionOrRevision.toString(),
                eventType.name,
                channel.name,
                notificationSlot.name,
            ),
        )

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * audit fingerprint를 계산할 deterministic 입력이다.
 *
 * `stableSubject`와 `purpose`는 운영 감사 대상을 표현하는 opaque 값이며 회원 이름,
 * 전화번호, 메시지 본문 같은 profile 원문을 저장하지 않는다.
 */
data class NotificationAuditInput(
    val tenantGroupId: TenantGroupId,
    val stableSubject: String,
    val purpose: String,
) : Serializable {
    init {
        require(stableSubject.isNotBlank()) { "stableSubject must not be blank" }
        require(purpose.isNotBlank()) { "purpose must not be blank" }
    }

    fun normalize(): String =
        lengthPrefixed(
            listOf(
                tenantGroupId.value.toString(),
                stableSubject,
                purpose,
            ),
        )

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * idempotency HMAC digest와 key version을 함께 보관하는 조회 후보다.
 */
data class NotificationIdempotencyDigest(
    val keyId: String,
    val version: Int,
    val value: String,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * audit HMAC fingerprint와 key version이다.
 */
data class NotificationAuditFingerprint(
    val keyId: String,
    val version: Int,
    val value: String,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 알림 outbox idempotency와 audit fingerprint를 생성하는 기본 구현체다.
 */
class DefaultNotificationOutboxHasher(
    private val keyRing: NotificationOutboxKeyRing,
) : NotificationOutboxHasher {

    override fun idempotencyCandidates(input: NotificationIdempotencyInput): List<NotificationIdempotencyDigest> {
        val active = activeKey()
        val previous = previousKey()
        val keys = if (previous == null || previous.keyId == active.keyId) {
            listOf(active)
        } else {
            listOf(active, previous)
        }

        return keys.map { key ->
            NotificationIdempotencyDigest(
                keyId = key.keyId,
                version = DIGEST_VERSION,
                value = key.sign(IDEMPOTENCY_DOMAIN_PREFIX, input.normalize()),
            )
        }
    }

    override fun auditFingerprint(input: NotificationAuditInput): NotificationAuditFingerprint {
        val key = activeKey()
        return NotificationAuditFingerprint(
            keyId = key.keyId,
            version = DIGEST_VERSION,
            value = key.sign(AUDIT_DOMAIN_PREFIX, input.normalize()),
        )
    }

    private fun activeKey(): NotificationHmacKey =
        try {
            keyRing.active()
        } catch (e: RuntimeException) {
            throw NotificationContractException(
                failureCode = NotificationFailureCode.HMAC_KEY_UNAVAILABLE,
                message = "Active notification HMAC key is unavailable",
                cause = e,
            )
        }

    private fun previousKey(): NotificationHmacKey? =
        try {
            keyRing.previous()
        } catch (e: RuntimeException) {
            throw NotificationContractException(
                failureCode = NotificationFailureCode.HMAC_KEY_UNAVAILABLE,
                message = "Previous notification HMAC key is unavailable",
                cause = e,
            )
        }

    companion object {
        const val IDEMPOTENCY_DOMAIN_PREFIX = "clinic-notification:idempotency:v1"
        const val AUDIT_DOMAIN_PREFIX = "clinic-notification:audit:v1"
        const val DIGEST_VERSION = 1
    }
}

private fun lengthPrefixed(values: List<String>): String =
    values.joinToString(separator = "\u0000") { value -> "${value.length}\u0000$value" }

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte) }
