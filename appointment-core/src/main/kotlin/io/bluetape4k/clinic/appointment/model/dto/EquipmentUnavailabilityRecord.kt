package io.bluetape4k.clinic.appointment.model.dto

import io.bluetape4k.clinic.appointment.model.tables.ExceptionType
import java.io.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

data class EquipmentUnavailabilityRecord(
    val id: Long,
    val equipmentId: Long,
    val clinicId: Long,
    val unavailableDate: LocalDate?,
    val isRecurring: Boolean,
    val recurringDayOfWeek: DayOfWeek?,
    val effectiveFrom: LocalDate,
    val effectiveUntil: LocalDate?,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val reason: String?,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

data class EquipmentUnavailabilityExceptionRecord(
    val id: Long,
    val unavailabilityId: Long,
    val originalDate: LocalDate,
    val exceptionType: ExceptionType,
    val rescheduledDate: LocalDate?,
    val rescheduledStartTime: LocalTime?,
    val rescheduledEndTime: LocalTime?,
    val reason: String?,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
