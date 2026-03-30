package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.clinic.appointment.model.dto.EquipmentUnavailabilityExceptionRecord
import io.bluetape4k.clinic.appointment.model.dto.EquipmentUnavailabilityRecord
import io.bluetape4k.clinic.appointment.model.dto.UnavailablePeriod
import io.bluetape4k.clinic.appointment.model.tables.ExceptionType
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

object UnavailabilityExpander {

    fun expand(
        rule: EquipmentUnavailabilityRecord,
        exceptions: List<EquipmentUnavailabilityExceptionRecord>,
        range: ClosedRange<LocalDate>,
    ): List<UnavailablePeriod> {
        val skipDates = exceptions
            .filter { it.exceptionType == ExceptionType.SKIP }
            .map { it.originalDate }
            .toSet()

        val rescheduleMap = exceptions
            .filter { it.exceptionType == ExceptionType.RESCHEDULE }
            .associateBy { it.originalDate }

        return if (!rule.isRecurring) {
            expandOneTime(rule, range, skipDates, rescheduleMap)
        } else {
            expandRecurring(rule, range, skipDates, rescheduleMap)
        }
    }

    private fun expandOneTime(
        rule: EquipmentUnavailabilityRecord,
        range: ClosedRange<LocalDate>,
        skipDates: Set<LocalDate>,
        rescheduleMap: Map<LocalDate, EquipmentUnavailabilityExceptionRecord>,
    ): List<UnavailablePeriod> {
        val date = rule.unavailableDate ?: return emptyList()
        if (date !in range || date in skipDates) return emptyList()

        val rescheduled = rescheduleMap[date]
        return listOf(
            UnavailablePeriod(
                equipmentId = rule.equipmentId,
                date        = rescheduled?.rescheduledDate ?: date,
                startTime   = rescheduled?.rescheduledStartTime ?: rule.startTime,
                endTime     = rescheduled?.rescheduledEndTime ?: rule.endTime,
            )
        )
    }

    private fun expandRecurring(
        rule: EquipmentUnavailabilityRecord,
        range: ClosedRange<LocalDate>,
        skipDates: Set<LocalDate>,
        rescheduleMap: Map<LocalDate, EquipmentUnavailabilityExceptionRecord>,
    ): List<UnavailablePeriod> {
        val dow = rule.recurringDayOfWeek ?: return emptyList()
        val effectiveStart = maxOf(rule.effectiveFrom, range.start)
        val effectiveEnd   = rule.effectiveUntil?.let { minOf(it, range.endInclusive) } ?: range.endInclusive

        val result = mutableListOf<UnavailablePeriod>()
        var current = effectiveStart.with(TemporalAdjusters.nextOrSame(dow))

        while (!current.isAfter(effectiveEnd)) {
            if (current !in skipDates) {
                val rescheduled = rescheduleMap[current]
                result.add(
                    UnavailablePeriod(
                        equipmentId = rule.equipmentId,
                        date        = rescheduled?.rescheduledDate ?: current,
                        startTime   = rescheduled?.rescheduledStartTime ?: rule.startTime,
                        endTime     = rescheduled?.rescheduledEndTime ?: rule.endTime,
                    )
                )
            }
            current = current.plusWeeks(1)
        }
        return result
    }
}
