package io.bluetape4k.clinic.appointment.api.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDate

/**
 * Response for GET /api/{tenantCode}/admin/stats/doctors.
 *
 * ## Behavior / Contract
 * - [doctors] is sorted by [DoctorBucket.totalAppointments] descending (service layer responsibility).
 * - The list is limited to the top N doctors as specified by the `limit` query parameter.
 * - Doctor names are not included; callers resolve ID→name via DoctorService.
 */
@Schema(description = "Per-doctor appointment counts and completion rates for the given clinic and date range")
data class DoctorStatsResponse(
    @Schema(description = "Target clinic ID")
    val clinicId: Long,
    @Schema(description = "Inclusive start date of the query range")
    val from: LocalDate,
    @Schema(description = "Inclusive end date of the query range")
    val to: LocalDate,
    @Schema(description = "Top-N doctors sorted by totalAppointments descending")
    val doctors: List<DoctorBucket>,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * Appointment metrics for a single doctor over the query period.
 *
 * ## Behavior / Contract
 * - [completionRate] = completed / (completed + cancelled + noShow).
 * - When the denominator is 0 (no terminal appointments), [completionRate] is 0.0.
 * - [totalAppointments] includes all statuses, not only terminal ones.
 */
@Schema(description = "Appointment metrics for a single doctor")
data class DoctorBucket(
    @Schema(description = "Doctor ID")
    val doctorId: Long,
    @Schema(description = "Total appointments across all statuses in the period")
    val totalAppointments: Long,
    @Schema(description = "Count of COMPLETED appointments")
    val completed: Long,
    @Schema(description = "Count of CANCELLED appointments")
    val cancelled: Long,
    @Schema(description = "Count of NO_SHOW appointments")
    val noShow: Long,
    @Schema(description = "completed / (completed + cancelled + noShow); 0.0 when denominator is 0")
    val completionRate: Double,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
