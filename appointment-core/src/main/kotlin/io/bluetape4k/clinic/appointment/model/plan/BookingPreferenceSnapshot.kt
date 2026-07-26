package io.bluetape4k.clinic.appointment.model.plan

import java.io.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Immutable customer scheduling preference captured by the purchase service.
 */
sealed interface BookingPreferenceSnapshot : Serializable {

    /**
     * An exact local date-time and its unambiguous normalized instant.
     */
    data class ExactDateTime(
        val originalLocalDateTime: LocalDateTime,
        val originalOffset: ZoneOffset,
        val zoneId: ZoneId,
        val normalizedInstant: Instant,
    ) : BookingPreferenceSnapshot {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    /**
     * An inclusive preferred local-date range.
     */
    data class DateRange(
        val startDate: LocalDate,
        val endDate: LocalDate,
        val zoneId: ZoneId,
    ) : BookingPreferenceSnapshot {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    /**
     * Preferred weekdays and local time windows.
     */
    data class PreferredWeekdaysAndWindows(
        val weekdays: List<DayOfWeek>,
        val localTimeWindows: List<LocalTimeWindow>,
        val zoneId: ZoneId,
    ) : BookingPreferenceSnapshot {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    /**
     * Marker used when the purchase contains no customer preference.
     */
    data object NotProvided : BookingPreferenceSnapshot
}

/**
 * A local wall-clock window with an exclusive end.
 */
data class LocalTimeWindow(
    val start: LocalTime,
    val end: LocalTime,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    init {
        require(start < end) { "start($start) must be before end($end)" }
    }
}
