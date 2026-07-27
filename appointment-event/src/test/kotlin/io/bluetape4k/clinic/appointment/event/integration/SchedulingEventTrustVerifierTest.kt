package io.bluetape4k.clinic.appointment.event.integration

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.clinic.appointment.model.plan.BookingPreferenceSnapshot
import io.bluetape4k.clinic.appointment.model.plan.LocalTimeWindow
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class SchedulingEventTrustVerifierTest {

    @Test
    fun `payload hash uses typed frames instead of booking preference toString`() {
        val preference = BookingPreferenceSnapshot.PreferredWeekdaysAndWindows(
            weekdays = listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
            localTimeWindows = listOf(
                LocalTimeWindow(LocalTime.of(9, 0), LocalTime.of(11, 0)),
                LocalTimeWindow(LocalTime.of(14, 0), LocalTime.of(16, 0)),
            ),
            zoneId = ZoneId.of("Asia/Seoul"),
        )

        val canonical = PurchaseCompletedPayloadHasher.canonicalBytes(payload(preference = preference))
            .toString(StandardCharsets.UTF_8)

        canonical.contains("bookingPreference.type").shouldBeTrue()
        canonical.contains("PREFERRED_WEEKDAYS_AND_WINDOWS").shouldBeTrue()
        canonical.contains("PreferredWeekdaysAndWindows(").shouldBeFalse()
        canonical.contains("LocalTimeWindow(").shouldBeFalse()
    }

    @Test
    fun `booking preference subtypes participate in the payload hash independently`() {
        val preferences = listOf(
            BookingPreferenceSnapshot.NotProvided,
            BookingPreferenceSnapshot.DateRange(
                startDate = LocalDate.parse("2026-08-01"),
                endDate = LocalDate.parse("2026-08-05"),
                zoneId = ZoneId.of("Asia/Seoul"),
            ),
            BookingPreferenceSnapshot.ExactDateTime(
                originalLocalDateTime = LocalDateTime.parse("2026-08-01T10:30:00"),
                originalOffset = ZoneOffset.of("+09:00"),
                zoneId = ZoneId.of("Asia/Seoul"),
                normalizedInstant = Instant.parse("2026-08-01T01:30:00Z"),
            ),
            BookingPreferenceSnapshot.PreferredWeekdaysAndWindows(
                weekdays = listOf(DayOfWeek.MONDAY),
                localTimeWindows = listOf(LocalTimeWindow(LocalTime.of(10, 0), LocalTime.of(12, 0))),
                zoneId = ZoneId.of("Asia/Seoul"),
            ),
        )

        val hashes = preferences.map { preference ->
            PurchaseCompletedPayloadHasher.hash(
                payload(
                    patientReferenceToken = "patient-token\u0000with\ncontrol",
                    preference = preference,
                )
            )
        }

        hashes.distinct().size shouldBeEqualTo preferences.size
    }

    @Test
    fun `trust verification accepts a length-framed payload containing control characters`() {
        val payload = payload(
            patientReferenceToken = "patient-token\u0000with\ncontrol",
            preference = BookingPreferenceSnapshot.DateRange(
                startDate = LocalDate.parse("2026-08-01"),
                endDate = LocalDate.parse("2026-08-03"),
                zoneId = ZoneId.of("Asia/Seoul"),
            ),
        )
        val envelope = envelope(payload)
        val verifier = SchedulingEventTrustVerifier(
            signatureVerifier = SchedulingEventSignatureVerifier { true },
            allowedProducers = setOf("commerce-service"),
            allowedKeyIds = setOf("commerce-key"),
            allowedAlgorithms = setOf("EdDSA"),
            expectedIssuer = "commerce-issuer",
            expectedAudience = "appointment-service",
            replayWindow = java.time.Duration.ofMinutes(5),
            clock = Clock.fixed(Instant.parse("2026-07-26T05:10:00Z"), ZoneId.of("UTC")),
        )

        val trusted = verifier.verify(envelope)

        trusted.payload.patientReferenceToken shouldBeEqualTo payload.patientReferenceToken
    }

    @Test
    fun `trust verification rejects a disallowed signature algorithm before signature verification`() {
        var signatureVerificationCalls = 0
        val verifier = SchedulingEventTrustVerifier(
            signatureVerifier = SchedulingEventSignatureVerifier {
                signatureVerificationCalls += 1
                true
            },
            allowedProducers = setOf("commerce-service"),
            allowedKeyIds = setOf("commerce-key"),
            allowedAlgorithms = setOf("EdDSA"),
            expectedIssuer = "commerce-issuer",
            expectedAudience = "appointment-service",
            replayWindow = java.time.Duration.ofMinutes(5),
            clock = Clock.fixed(Instant.parse("2026-07-26T05:10:00Z"), ZoneId.of("UTC")),
        )

        val failure = assertFailsWith<SchedulingTrustException> {
            verifier.verify(envelope(payload()).copy(algorithm = "none"))
        }

        failure.reasonCode shouldBeEqualTo "ALGORITHM_NOT_ALLOWED"
        signatureVerificationCalls shouldBeEqualTo 0
    }

    @Test
    fun `trust verification requires explicit nonempty trust allowlists`() {
        fun verifier(
            producers: Set<String> = setOf("commerce-service"),
            keyIds: Set<String> = setOf("commerce-key"),
            algorithms: Set<String> = setOf("EdDSA"),
        ) = SchedulingEventTrustVerifier(
            signatureVerifier = SchedulingEventSignatureVerifier { true },
            allowedProducers = producers,
            allowedKeyIds = keyIds,
            allowedAlgorithms = algorithms,
            expectedIssuer = "commerce-issuer",
            expectedAudience = "appointment-service",
            replayWindow = java.time.Duration.ofMinutes(5),
            clock = Clock.fixed(Instant.parse("2026-07-26T05:10:00Z"), ZoneId.of("UTC")),
        )

        listOf(
            { verifier(producers = emptySet()) },
            { verifier(keyIds = emptySet()) },
            { verifier(algorithms = emptySet()) },
        ).forEach { construction ->
            assertFailsWith<IllegalArgumentException> { construction() }
        }
    }

    private fun envelope(
        payload: PurchaseCompletedEvent,
    ) = UntrustedSchedulingEventEnvelope(
        eventId = "event-1",
        eventType = "PurchaseCompleted",
        occurredAt = Instant.parse("2026-07-26T05:09:50Z"),
        receivedAt = Instant.parse("2026-07-26T05:10:00Z"),
        producer = "commerce-service",
        issuer = "commerce-issuer",
        audience = "appointment-service",
        keyId = "commerce-key",
        algorithm = "EdDSA",
        schemaVersion = 2,
        correlationId = "correlation-1",
        payloadHash = PurchaseCompletedPayloadHasher.hash(payload),
        signature = "valid-signature",
        payload = payload,
    )

    private fun payload(
        patientReferenceToken: String = "patient-token",
        preference: BookingPreferenceSnapshot = BookingPreferenceSnapshot.NotProvided,
    ) = PurchaseCompletedEvent(
        sourceAggregateId = "purchase-aggregate",
        sourceAggregateVersion = 1L,
        tenantGroupId = 1L,
        clinicId = 2L,
        sourcePurchaseAuthority = "commerce",
        sourcePurchaseId = "purchase-1",
        patientReferenceToken = patientReferenceToken,
        catalogSourceAuthority = "product-catalog",
        productId = "laser-care",
        catalogVersion = 7L,
        bookingPreference = preference,
    )
}
