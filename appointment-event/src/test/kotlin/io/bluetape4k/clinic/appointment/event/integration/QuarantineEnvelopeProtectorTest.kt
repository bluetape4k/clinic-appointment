package io.bluetape4k.clinic.appointment.event.integration

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.model.plan.BookingPreferenceSnapshot
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Base64

class QuarantineEnvelopeProtectorTest {

    @Test
    fun `AES GCM protection keeps a stable evidence hash and randomized ciphertext`() {
        val protector = AesGcmQuarantineEnvelopeProtector(
            encryptionKey = ByteArray(32) { index -> index.toByte() },
            keyId = "quarantine-key-1",
        )
        val envelope = envelope()

        val first = protector.protect(envelope)
        val second = protector.protect(envelope)

        first.keyId shouldBeEqualTo "quarantine-key-1"
        first.envelopeHash shouldBeEqualTo second.envelopeHash
        first.envelopeHash.matches(Regex("[0-9a-f]{64}")).shouldBeTrue()
        first.ciphertext.equals(second.ciphertext).shouldBeFalse()
        first.ciphertext.contains("patient-token").shouldBeFalse()
        Base64.getDecoder().decode(first.ciphertext).size.let { it >= 29 }.shouldBeTrue()
    }

    @Test
    fun `invalid AES key size is rejected at construction`() {
        assertFailsWith<IllegalArgumentException> {
            AesGcmQuarantineEnvelopeProtector(ByteArray(15), "quarantine-key-1")
        }
    }

    private fun envelope(): UntrustedSchedulingEventEnvelope<PurchaseCompletedEvent> {
        val payload = PurchaseCompletedEvent(
            sourceAggregateId = "purchase-aggregate",
            sourceAggregateVersion = 1L,
            tenantGroupId = 1L,
            clinicId = 2L,
            sourcePurchaseAuthority = "commerce",
            sourcePurchaseId = "purchase-1",
            patientReferenceToken = "patient-token",
            catalogSourceAuthority = "product-catalog",
            productId = "laser-care",
            catalogVersion = 7L,
            bookingPreference = BookingPreferenceSnapshot.NotProvided,
        )
        return UntrustedSchedulingEventEnvelope(
            eventId = "event-1",
            eventType = "PurchaseCompleted",
            occurredAt = Instant.parse("2026-07-26T05:09:50Z"),
            receivedAt = Instant.parse("2026-07-26T05:10:00Z"),
            producer = "commerce-service",
            issuer = "commerce-issuer",
            audience = "appointment-service",
            keyId = "commerce-key",
            schemaVersion = 2,
            correlationId = "correlation-1",
            payloadHash = PurchaseCompletedPayloadHasher.hash(payload),
            signature = "valid-signature",
            payload = payload,
        )
    }
}
