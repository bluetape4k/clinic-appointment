package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.clinic.appointment.api.dto.ApiResponse
import io.bluetape4k.clinic.appointment.api.dto.AppointmentStatsResponse
import io.bluetape4k.clinic.appointment.api.dto.CancellationStatsResponse
import io.bluetape4k.clinic.appointment.api.dto.DoctorStatsResponse
import io.bluetape4k.clinic.appointment.api.service.DashboardStatsService
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse as OApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/**
 * Admin dashboard statistics REST controller.
 *
 * ## Behavior / Contract
 * - All endpoints require `clinicId > 0`; invalid values return HTTP 400.
 * - `from` / `to` default to [today-29, today] (30 days) when omitted.
 * - Period exceeding 366 days returns HTTP 400.
 * - Unknown `clinicId` returns HTTP 200 with empty data.
 */
@Tag(name = "Admin - Dashboard Stats", description = "Appointment statistics for admin dashboard")
@RestController
@RequestMapping("/api/admin/stats")
class DashboardStatsController(
    private val dashboardStatsService: DashboardStatsService,
) {
    companion object : KLogging()

    /**
     * Returns daily appointment counts grouped by status.
     *
     * @param clinicId Target clinic ID (must be > 0)
     * @param from Inclusive start date; defaults to today-29 when null
     * @param to Inclusive end date; defaults to today when null
     * @param statuses Optional status name filter; null means all statuses
     */
    @Operation(summary = "Daily appointment counts grouped by status")
    @ApiResponses(
        OApiResponse(responseCode = "200", description = "Success"),
        OApiResponse(responseCode = "400", description = "Invalid parameters"),
    )
    @GetMapping("/appointments")
    fun getAppointmentStats(
        @Parameter(description = "Clinic ID") @RequestParam clinicId: Long,
        @Parameter(description = "Start date (ISO)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @Parameter(description = "End date (ISO)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @Parameter(description = "Status name filter list") @RequestParam(required = false) statuses: List<String>?,
    ): ResponseEntity<ApiResponse<AppointmentStatsResponse>> {
        log.debug { "GET /api/admin/stats/appointments clinicId=$clinicId from=$from to=$to statuses=$statuses" }
        val result = dashboardStatsService.getAppointmentStats(clinicId, from, to, statuses)
        return ResponseEntity.ok(ApiResponse.ok(result))
    }

    /**
     * Returns per-doctor appointment metrics sorted by total appointments descending.
     *
     * @param clinicId Target clinic ID (must be > 0)
     * @param from Inclusive start date; defaults to today-29 when null
     * @param to Inclusive end date; defaults to today when null
     * @param limit Max number of doctors to return (1..100, default 20)
     */
    @Operation(summary = "Per-doctor appointment metrics")
    @ApiResponses(
        OApiResponse(responseCode = "200", description = "Success"),
        OApiResponse(responseCode = "400", description = "Invalid parameters"),
    )
    @GetMapping("/doctors")
    fun getDoctorStats(
        @Parameter(description = "Clinic ID") @RequestParam clinicId: Long,
        @Parameter(description = "Start date (ISO)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @Parameter(description = "End date (ISO)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @Parameter(description = "Max doctors to return (1..100)") @RequestParam(defaultValue = "20") limit: Int,
    ): ResponseEntity<ApiResponse<DoctorStatsResponse>> {
        log.debug { "GET /api/admin/stats/doctors clinicId=$clinicId from=$from to=$to limit=$limit" }
        val result = dashboardStatsService.getDoctorStats(clinicId, from, to, limit)
        return ResponseEntity.ok(ApiResponse.ok(result))
    }

    /**
     * Returns cancellation and no-show trends.
     *
     * @param clinicId Target clinic ID (must be > 0)
     * @param from Inclusive start date; defaults to today-29 when null
     * @param to Inclusive end date; defaults to today when null
     */
    @Operation(summary = "Cancellation and no-show trends")
    @ApiResponses(
        OApiResponse(responseCode = "200", description = "Success"),
        OApiResponse(responseCode = "400", description = "Invalid parameters"),
    )
    @GetMapping("/cancellations")
    fun getCancellationStats(
        @Parameter(description = "Clinic ID") @RequestParam clinicId: Long,
        @Parameter(description = "Start date (ISO)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @Parameter(description = "End date (ISO)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): ResponseEntity<ApiResponse<CancellationStatsResponse>> {
        log.debug { "GET /api/admin/stats/cancellations clinicId=$clinicId from=$from to=$to" }
        val result = dashboardStatsService.getCancellationStats(clinicId, from, to)
        return ResponseEntity.ok(ApiResponse.ok(result))
    }
}
