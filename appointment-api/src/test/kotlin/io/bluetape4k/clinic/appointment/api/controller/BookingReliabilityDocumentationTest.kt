package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class BookingReliabilityDocumentationTest {

    @Test
    fun `API documentation stays aligned with the privacy and rollout contract`() {
        val path = sequenceOf(
            Path.of("docs/api/booking-reliability.md"),
            Path.of("../docs/api/booking-reliability.md"),
        ).first { Files.exists(it) }
        val content = Files.readString(path)
        listOf(
            "/api/{tenantCode}/clinics/{clinicId}/members/{memberId}/booking-reliability",
            "`/decision`",
            "`/override`",
            "`/clear`",
            "`/audit`",
            "Idempotency-Key",
            "BOOKING_REVIEW_REQUIRED",
            "BOOKING_DECISION_UNAVAILABLE",
            "opaque",
            "OFF",
            "SHADOW",
            "ENFORCE",
        ).forEach { required -> content.contains(required).shouldBeTrue() }
        content.contains("phoneNumber").shouldBeFalse()
        content.contains("emailAddress").shouldBeFalse()
    }
}
