package io.bluetape4k.clinic.appointment.event.notification

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import org.junit.jupiter.api.Test
import java.io.Serializable

class NotificationOutboxHasherTest {

    @Test
    fun `idempotency candidates include active and previous keys without duplicate key id`() {
        val hasher: NotificationOutboxHasher = DefaultNotificationOutboxHasher(
            keyRing = StaticNotificationOutboxKeyRing(
                active = NotificationHmacKey("active-key", keyBytes(1)),
                previous = NotificationHmacKey("previous-key", keyBytes(2)),
            ),
        )

        val candidates = hasher.idempotencyCandidates(idempotencyInput())

        candidates.map { it.keyId } shouldBeEqualTo listOf("active-key", "previous-key")
        candidates.map { it.version }.toSet() shouldBeEqualTo setOf(1)
        candidates.map { it.value }.toSet().size shouldBeEqualTo 2
    }

    @Test
    fun `idempotency candidates skip previous key with duplicate key id`() {
        val hasher: NotificationOutboxHasher = DefaultNotificationOutboxHasher(
            keyRing = StaticNotificationOutboxKeyRing(
                active = NotificationHmacKey("active-key", keyBytes(1)),
                previous = NotificationHmacKey("active-key", keyBytes(3)),
            ),
        )

        val failure = assertFailsWith<NotificationContractException> {
            hasher.idempotencyCandidates(idempotencyInput())
        }

        failure.failureCode shouldBeEqualTo NotificationFailureCode.HMAC_KEY_UNAVAILABLE
    }

    @Test
    fun `idempotency and audit fingerprints use separated HMAC domains`() {
        val hasher: NotificationOutboxHasher = DefaultNotificationOutboxHasher(
            keyRing = StaticNotificationOutboxKeyRing(
                active = NotificationHmacKey("active-key", keyBytes(1)),
                previous = null,
            ),
        )

        val idempotency = hasher.idempotencyCandidates(idempotencyInput()).single()
        val audit = hasher.auditFingerprint(auditInput(stableSubject = "appointment-30"))

        idempotency.keyId shouldBeEqualTo "active-key"
        audit.keyId shouldBeEqualTo "active-key"
        idempotency.version shouldBeEqualTo audit.version
        idempotency.value.equals(audit.value).shouldBeFalse()
    }

    @Test
    fun `idempotency fields affect digest with fixed deterministic normalization`() {
        val hasher: NotificationOutboxHasher = DefaultNotificationOutboxHasher(
            keyRing = StaticNotificationOutboxKeyRing(
                active = NotificationHmacKey("active-key", keyBytes(1)),
                previous = null,
            ),
        )

        val base = hasher.idempotencyCandidates(idempotencyInput()).single().value
        val changedRevision = hasher.idempotencyCandidates(
            idempotencyInput(appointmentVersionOrRevision = 124L),
        ).single().value
        val changedSlot = hasher.idempotencyCandidates(
            idempotencyInput(notificationSlot = NotificationSlot.REMINDER_24H),
        ).single().value

        base.equals(changedRevision).shouldBeFalse()
        base.equals(changedSlot).shouldBeFalse()
    }

    @Test
    fun `unavailable active key fails with hmac key unavailable`() {
        val hasher: NotificationOutboxHasher = DefaultNotificationOutboxHasher(
            keyRing = ThrowingNotificationOutboxKeyRing,
        )

        val failure = assertFailsWith<NotificationContractException> {
            hasher.idempotencyCandidates(idempotencyInput())
        }

        failure.failureCode shouldBeEqualTo NotificationFailureCode.HMAC_KEY_UNAVAILABLE
    }

    @Test
    fun `hmac key defensively copies secret bytes`() {
        val secret = keyBytes(1)
        val key = NotificationHmacKey("active-key", secret)
        val before = key.sign("clinic-notification:idempotency:v1", "payload")

        secret.fill(99)

        key.sign("clinic-notification:idempotency:v1", "payload") shouldBeEqualTo before
    }

    @Test
    fun `hmac key is not serializable`() {
        val key = NotificationHmacKey("active-key", keyBytes(1))
        val candidate: Any = key

        (candidate is Serializable).shouldBeFalse()
    }

    @Test
    fun `hmac key rejects short secrets and unsafe key ids`() {
        NotificationHmacKey("A", keyBytes(1)).keyId shouldBeEqualTo "A"
        NotificationHmacKey("key_01JZ:prod-1.ok", keyBytes(2)).keyId shouldBeEqualTo "key_01JZ:prod-1.ok"

        assertFailsWith<IllegalArgumentException> { NotificationHmacKey("active-key", ByteArray(31)) }
        assertFailsWith<IllegalArgumentException> { NotificationHmacKey("_bad", keyBytes(1)) }
        assertFailsWith<IllegalArgumentException> { NotificationHmacKey("bad key", keyBytes(1)) }
        assertFailsWith<IllegalArgumentException> { NotificationHmacKey("a".repeat(129), keyBytes(1)) }
        assertFailsWith<IllegalArgumentException> { NotificationHmacKey("bad\u0001key", keyBytes(1)) }
    }

    @Test
    fun `audit input enforces bounded non profile opaque strings`() {
        NotificationAuditInput(
            tenantGroupId = TenantGroupId(10L),
            stableSubject = "s".repeat(255),
            purpose = "p".repeat(64),
        ).purpose shouldBeEqualTo "p".repeat(64)

        assertFailsWith<IllegalArgumentException> {
            NotificationAuditInput(TenantGroupId(10L), stableSubject = "s".repeat(256), purpose = "audit")
        }
        assertFailsWith<IllegalArgumentException> {
            NotificationAuditInput(TenantGroupId(10L), stableSubject = "bad\u0001subject", purpose = "audit")
        }
        assertFailsWith<IllegalArgumentException> {
            NotificationAuditInput(TenantGroupId(10L), stableSubject = "subject", purpose = "p".repeat(65))
        }
        assertFailsWith<IllegalArgumentException> {
            NotificationAuditInput(TenantGroupId(10L), stableSubject = "subject", purpose = "bad\u0001purpose")
        }
    }

    private fun idempotencyInput(
        appointmentVersionOrRevision: Long = 123L,
        notificationSlot: NotificationSlot = NotificationSlot.CONFIRMED,
    ): NotificationIdempotencyInput =
        NotificationIdempotencyInput(
            tenantGroupId = TenantGroupId(10L),
            clinicId = ClinicId(20L),
            appointmentId = AppointmentId(30L),
            appointmentVersionOrRevision = appointmentVersionOrRevision,
            eventType = NotificationEventType.CONFIRMED,
            channel = NotificationChannelType.SMS,
            notificationSlot = notificationSlot,
        )

    private fun auditInput(stableSubject: String = "appointment-30"): NotificationAuditInput =
        NotificationAuditInput(
            tenantGroupId = TenantGroupId(10L),
            stableSubject = stableSubject,
            purpose = "delivery-audit",
        )

    private object ThrowingNotificationOutboxKeyRing : NotificationOutboxKeyRing {
        override fun active(): NotificationHmacKey =
            throw NotificationHmacKeyUnavailableException("active key is unavailable")

        override fun previous(): NotificationHmacKey? =
            NotificationHmacKey("previous-key", ByteArray(32) { index -> (2 + index).toByte() })
    }

    private fun keyBytes(seed: Int): ByteArray =
        ByteArray(32) { index -> (seed + index).toByte() }
}
