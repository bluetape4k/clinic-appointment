package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.clinic.appointment.model.plan.BookingPreferenceSnapshot
import io.bluetape4k.clinic.appointment.model.plan.LocalTimeWindow
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Normalizes customer-entered local scheduling preferences without choosing an appointment date.
 */
object BookingPreferenceNormalizer {

    /**
     * Normalizes an exact local date-time using the supplied clinic zone rules.
     */
    fun exactDateTime(
        localDateTime: LocalDateTime,
        zoneId: ZoneId,
        offset: ZoneOffset? = null,
    ): BookingPreferenceSnapshot.ExactDateTime {
        val validOffsets = zoneId.rules.getValidOffsets(localDateTime)
        require(validOffsets.isNotEmpty()) {
            "localDateTime($localDateTime) is in a daylight-saving gap for zone($zoneId)"
        }
        require(validOffsets.size == 1 || offset != null) {
            "offset is required for an overlapping localDateTime($localDateTime) in zone($zoneId)"
        }

        val effectiveOffset = offset ?: validOffsets.single()
        require(effectiveOffset in validOffsets) {
            "offset($effectiveOffset) is not valid for localDateTime($localDateTime) in zone($zoneId)"
        }

        return BookingPreferenceSnapshot.ExactDateTime(
            originalLocalDateTime = localDateTime,
            originalOffset = effectiveOffset,
            zoneId = zoneId,
            normalizedInstant = localDateTime.toInstant(effectiveOffset),
        )
    }

    /**
     * Validates and preserves an inclusive date-range preference.
     */
    fun dateRange(
        startDate: LocalDate,
        endDate: LocalDate,
        zoneId: ZoneId,
    ): BookingPreferenceSnapshot.DateRange {
        require(startDate <= endDate) { "startDate($startDate) must not be after endDate($endDate)" }
        return BookingPreferenceSnapshot.DateRange(startDate, endDate, zoneId)
    }

    /**
     * Validates and preserves weekday and local-window preferences.
     */
    fun preferredWeekdaysAndWindows(
        weekdays: List<DayOfWeek>,
        localTimeWindows: List<LocalTimeWindow>,
        zoneId: ZoneId,
    ): BookingPreferenceSnapshot.PreferredWeekdaysAndWindows {
        require(weekdays.isNotEmpty()) { "weekdays must not be empty" }
        require(localTimeWindows.isNotEmpty()) { "localTimeWindows must not be empty" }
        require(weekdays.distinct().size == weekdays.size) { "weekdays must not contain duplicates" }
        require(localTimeWindows.distinct().size == localTimeWindows.size) {
            "localTimeWindows must not contain duplicates"
        }
        return BookingPreferenceSnapshot.PreferredWeekdaysAndWindows(
            weekdays = weekdays.toList(),
            localTimeWindows = localTimeWindows.toList(),
            zoneId = zoneId,
        )
    }
}
