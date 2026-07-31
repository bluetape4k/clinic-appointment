package io.bluetape4k.clinic.appointment.event.notification

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import org.junit.jupiter.api.Test

class NotificationOutboxHasherTest {

    @Test
    fun `idempotency candidates include active and previous keys without duplicate key id`() {
        val hasher: NotificationOutboxHasher = DefaultNotificationOutboxHasher(
            keyRing = StaticNotificationOutboxKeyRing(
                active = NotificationHmacKey("active-key", byteArrayOf(1, 2, 3, 4)),
                previous = NotificationHmacKey("previous-key", byteArrayOf(5, 6, 7, 8)),
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
                active = NotificationHmacKey("active-key", byteArrayOf(1, 2, 3, 4)),
                previous = NotificationHmacKey("active-key", byteArrayOf(9, 10, 11, 12)),
            ),
        )

        hasher.idempotencyCandidates(idempotencyInput()).map { it.keyId } shouldBeEqualTo listOf("active-key")
    }

    @Test
    fun `idempotency and audit fingerprints use separated HMAC domains`() {
        val hasher: NotificationOutboxHasher = DefaultNotificationOutboxHasher(
            keyRing = StaticNotificationOutboxKeyRing(
                active = NotificationHmacKey("active-key", byteArrayOf(1, 2, 3, 4)),
                previous = null,
            ),
        )

        val idempotency = hasher.idempotencyCandidates(idempotencyInput()).single()
        val audit = hasher.auditFingerprint(auditInput(stableSubject = idempotencyInput().normalize()))

        idempotency.keyId shouldBeEqualTo "active-key"
        audit.keyId shouldBeEqualTo "active-key"
        idempotency.version shouldBeEqualTo audit.version
        idempotency.value.equals(audit.value).shouldBeFalse()
    }

    @Test
    fun `idempotency fields affect digest with fixed deterministic normalization`() {
        val hasher: NotificationOutboxHasher = DefaultNotificationOutboxHasher(
            keyRing = StaticNotificationOutboxKeyRing(
                active = NotificationHmacKey("active-key", byteArrayOf(1, 2, 3, 4)),
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
        val secret = byteArrayOf(1, 2, 3, 4)
        val key = NotificationHmacKey("active-key", secret)
        val before = key.sign("clinic-notification:idempotency:v1", "payload")

        secret.fill(99)
        val exposed = key.secretBytes()
        exposed.fill(88)

        key.sign("clinic-notification:idempotency:v1", "payload") shouldBeEqualTo before
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
            throw IllegalStateException("active key is unavailable")

        override fun previous(): NotificationHmacKey? =
            NotificationHmacKey("previous-key", byteArrayOf(5, 6, 7, 8))
    }
}
