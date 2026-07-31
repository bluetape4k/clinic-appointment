package io.bluetape4k.clinic.appointment.event.notification

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import org.junit.jupiter.api.Test

class NotificationOutboxHasherTest {

    @Test
    fun `idempotency candidates include active and previous keys without duplicate key id`() {
        val hasher = NotificationOutboxHasher(
            keyRing = StaticNotificationOutboxKeyRing(
                active = NotificationHmacKey("active-key", byteArrayOf(1, 2, 3, 4)),
                previous = listOf(
                    NotificationHmacKey("previous-key", byteArrayOf(5, 6, 7, 8)),
                    NotificationHmacKey("active-key", byteArrayOf(9, 10, 11, 12)),
                ),
            ),
        )

        val candidates = hasher.candidates("notification-business-key")

        candidates.map { it.keyId } shouldBeEqualTo listOf("active-key", "previous-key")
        candidates.map { it.fingerprint }.toSet().size shouldBeEqualTo 2
    }

    @Test
    fun `idempotency and audit fingerprints use separated HMAC domains`() {
        val hasher = NotificationOutboxHasher(
            keyRing = StaticNotificationOutboxKeyRing(
                active = NotificationHmacKey("active-key", byteArrayOf(1, 2, 3, 4)),
                previous = emptyList(),
            ),
        )

        val idempotency = hasher.fingerprint("notification-business-key")
        val audit = hasher.auditFingerprint("notification-business-key")

        idempotency.keyId shouldBeEqualTo "active-key"
        audit.keyId shouldBeEqualTo "active-key"
        idempotency.fingerprint.equals(audit.fingerprint).shouldBeFalse()
    }

    @Test
    fun `unavailable active key fails with hmac key unavailable`() {
        val hasher = NotificationOutboxHasher(
            keyRing = StaticNotificationOutboxKeyRing(
                active = null,
                previous = listOf(NotificationHmacKey("previous-key", byteArrayOf(5, 6, 7, 8))),
            ),
        )

        val failure = assertFailsWith<NotificationContractException> {
            hasher.fingerprint("notification-business-key")
        }

        failure.failureCode shouldBeEqualTo NotificationFailureCode.HMAC_KEY_UNAVAILABLE
    }

    @Test
    fun `hmac key defensively copies secret bytes`() {
        val secret = byteArrayOf(1, 2, 3, 4)
        val key = NotificationHmacKey("active-key", secret)
        val before = key.sign("clinic-notification:idempotency:v1", "payload")

        secret.fill(99)
        val exposed = key.secretBytes()
        exposed.fill(88)

        key.sign("clinic-notification:idempotency:v1", "payload") shouldBeEqualTo before
    }
}
