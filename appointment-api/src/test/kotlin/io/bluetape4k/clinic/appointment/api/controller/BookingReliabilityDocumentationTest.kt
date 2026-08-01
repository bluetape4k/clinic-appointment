package io.bluetape4k.clinic.appointment.api.controller

import org.junit.jupiter.api.Assertions.assertTrue
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
        ).forEach { required -> assertTrue(content.contains(required), required) }
        assertTrue(!content.contains("phoneNumber"))
        assertTrue(!content.contains("emailAddress"))
    }
}
