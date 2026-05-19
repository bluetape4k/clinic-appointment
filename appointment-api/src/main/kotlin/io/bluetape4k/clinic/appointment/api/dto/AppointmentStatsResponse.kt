package io.bluetape4k.clinic.appointment.api.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDate

/**
 * Response for GET /api/{tenantCode}/admin/stats/appointments.
 *
 * ## Behavior / Contract
 * - [daily] is ordered by date ascending.
 * - [totals] keys are [AppointmentState][io.bluetape4k.clinic.appointment.statemachine.AppointmentState] names.
 * - When no appointments exist for the period, [daily] is empty and all [totals] values are 0.
 */
@Schema(description = "Daily appointment counts grouped by status for the given clinic and date range")
data class AppointmentStatsResponse(
    @Schema(description = "Target clinic ID")
    val clinicId: Long,
    @Schema(description = "Inclusive start date of the query range")
    val from: LocalDate,
    @Schema(description = "Inclusive end date of the query range")
    val to: LocalDate,
    @Schema(description = "Aggregated totals per status across the entire period; keys are AppointmentState names")
    val totals: Map<String, Long>,
    @Schema(description = "Day-by-day breakdown; one entry per date that has at least one appointment")
    val daily: List<DailyAppointmentBucket>,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * Per-day appointment counts broken down by status.
 *
 * ## Behavior / Contract
 * - [countsByStatus] contains only statuses that have count > 0 for this date.
 * - [total] equals the sum of all values in [countsByStatus].
 */
@Schema(description = "Appointment counts for a single day, broken down by status")
data class DailyAppointmentBucket(
    @Schema(description = "The date this bucket represents")
    val date: LocalDate,
    @Schema(description = "Count per status; keys are AppointmentState names, values are counts (> 0 only)")
    val countsByStatus: Map<String, Long>,
    @Schema(description = "Sum of all status counts for this day")
    val total: Long,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
