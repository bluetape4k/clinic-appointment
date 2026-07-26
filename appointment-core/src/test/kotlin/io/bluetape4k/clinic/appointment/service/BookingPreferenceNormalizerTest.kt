package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.model.plan.BookingPreferenceSnapshot
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class BookingPreferenceNormalizerTest {

    @Test
    fun `normalizes an exact local time while preserving original values`() {
        val local = LocalDateTime.of(2026, 7, 27, 10, 30)
        val zone = ZoneId.of("Asia/Seoul")

        val result = BookingPreferenceNormalizer.exactDateTime(local, zone)

        result shouldBeEqualTo BookingPreferenceSnapshot.ExactDateTime(
            originalLocalDateTime = local,
            originalOffset = ZoneOffset.ofHours(9),
            zoneId = zone,
            normalizedInstant = Instant.parse("2026-07-27T01:30:00Z"),
        )
    }

    @Test
    fun `rejects a DST gap`() {
        assertFailsWith<IllegalArgumentException> {
            BookingPreferenceNormalizer.exactDateTime(
                LocalDateTime.of(2026, 3, 8, 2, 30),
                ZoneId.of("America/New_York"),
            )
        }
    }

    @Test
    fun `requires an explicit valid offset during a DST overlap`() {
        val local = LocalDateTime.of(2026, 11, 1, 1, 30)
        val zone = ZoneId.of("America/New_York")

        assertFailsWith<IllegalArgumentException> {
            BookingPreferenceNormalizer.exactDateTime(local, zone)
        }
        assertFailsWith<IllegalArgumentException> {
            BookingPreferenceNormalizer.exactDateTime(local, zone, ZoneOffset.UTC)
        }

        BookingPreferenceNormalizer.exactDateTime(local, zone, ZoneOffset.ofHours(-4))
            .normalizedInstant shouldBeEqualTo Instant.parse("2026-11-01T05:30:00Z")
        BookingPreferenceNormalizer.exactDateTime(local, zone, ZoneOffset.ofHours(-5))
            .normalizedInstant shouldBeEqualTo Instant.parse("2026-11-01T06:30:00Z")
    }

    @Test
    fun `rejects an inverted preferred date range`() {
        assertFailsWith<IllegalArgumentException> {
            BookingPreferenceNormalizer.dateRange(
                startDate = LocalDate.of(2026, 8, 2),
                endDate = LocalDate.of(2026, 8, 1),
                zoneId = ZoneId.of("Asia/Seoul"),
            )
        }
    }
}
