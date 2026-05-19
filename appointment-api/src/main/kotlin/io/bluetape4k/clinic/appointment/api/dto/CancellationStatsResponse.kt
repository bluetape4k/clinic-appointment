package io.bluetape4k.clinic.appointment.api.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDate

/**
 * Response for GET /api/admin/stats/cancellations.
 *
 * ## Behavior / Contract
 * - Denominator for rate calculations: CANCELLED + COMPLETED + NO_SHOW + RESCHEDULED (terminal statuses only).
 *   In-progress statuses (REQUESTED, CONFIRMED, etc.) are excluded to avoid noise.
 * - [cancellationRate] = CANCELLED / denominator; 0.0 when denominator is 0.
 * - [noShowRate] = NO_SHOW / denominator; 0.0 when denominator is 0.
 * - [daily] contains one entry per date that has at least one tracked appointment.
 */
@Schema(description = "Cancellation and no-show trends for the given clinic and date range")
data class CancellationStatsResponse(
    @Schema(description = "Target clinic ID")
    val clinicId: Long,
    @Schema(description = "Inclusive start date of the query range")
    val from: LocalDate,
    @Schema(description = "Inclusive end date of the query range")
    val to: LocalDate,
    @Schema(description = "Total CANCELLED appointments in the period")
    val totalCancelled: Long,
    @Schema(description = "Total NO_SHOW appointments in the period")
    val totalNoShow: Long,
    @Schema(description = "Total RESCHEDULED appointments in the period")
    val totalRescheduled: Long,
    @Schema(description = "Total COMPLETED appointments in the period")
    val totalCompleted: Long,
    @Schema(description = "CANCELLED / (CANCELLED + COMPLETED + NO_SHOW + RESCHEDULED); 0.0 when denominator is 0")
    val cancellationRate: Double,
    @Schema(description = "NO_SHOW / (CANCELLED + COMPLETED + NO_SHOW + RESCHEDULED); 0.0 when denominator is 0")
    val noShowRate: Double,
    @Schema(description = "Day-by-day breakdown of terminal-status counts")
    val daily: List<DailyCancellationBucket>,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * Per-day counts of terminal-status appointments.
 *
 * ## Behavior / Contract
 * - COMPLETED is not tracked here; this bucket focuses on negative outcomes.
 * - A date appears only if at least one of [cancelled], [noShow], or [rescheduled] is > 0.
 */
@Schema(description = "Daily cancellation, no-show, and rescheduled counts")
data class DailyCancellationBucket(
    @Schema(description = "The date this bucket represents")
    val date: LocalDate,
    @Schema(description = "CANCELLED count for this day")
    val cancelled: Long,
    @Schema(description = "NO_SHOW count for this day")
    val noShow: Long,
    @Schema(description = "RESCHEDULED count for this day")
    val rescheduled: Long,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
