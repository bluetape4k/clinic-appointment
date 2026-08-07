package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.clinic.appointment.api.dto.ApiResponse
import io.bluetape4k.clinic.appointment.api.dto.AppointmentStatsResponse
import io.bluetape4k.clinic.appointment.api.dto.CancellationStatsResponse
import io.bluetape4k.clinic.appointment.api.dto.DoctorStatsResponse
import io.bluetape4k.clinic.appointment.api.service.DashboardStatsService
import io.bluetape4k.clinic.appointment.api.tenant.TenantClinicAccessChecker
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
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/**
 * 관리자 대시보드 통계 REST 컨트롤러입니다.
 *
 * ## 동작 / 계약
 * - 모든 endpoint는 `clinicId > 0`을 요구하며, 잘못된 값은 HTTP 400을 반환합니다.
 * - `from` / `to`를 생략하면 [today-29, today] (30일)를 기본값으로 사용합니다.
 * - 기간이 366일을 초과하면 HTTP 400을 반환합니다.
 * - 알 수 없는 `clinicId`에는 데이터가 빈 HTTP 200 응답을 반환합니다.
 */
@Tag(name = "Admin - Dashboard Stats", description = "Appointment statistics for admin dashboard")
@RestController
@RequestMapping("/api/{tenantCode}/admin/stats")
class DashboardStatsController(
    private val dashboardStatsService: DashboardStatsService,
    private val tenantClinicAccessChecker: TenantClinicAccessChecker,
) {
    companion object : KLogging()

    /**
     * 상태별 일일 예약 건수를 반환합니다.
     *
     * @param clinicId 대상 clinic ID입니다(0보다 커야 합니다).
     * @param from 포함할 시작 날짜이며, null이면 today-29를 사용합니다.
     * @param to 포함할 종료 날짜이며, null이면 today를 사용합니다.
     * @param statuses 선택적 상태 이름 필터이며, null이면 모든 상태를 사용합니다.
     */
    @Operation(summary = "Daily appointment counts grouped by status")
    @ApiResponses(
        OApiResponse(responseCode = "200", description = "Success"),
        OApiResponse(responseCode = "400", description = "Invalid parameters"),
    )
    @GetMapping("/appointments")
    fun getAppointmentStats(
        @PathVariable tenantCode: String,
        @Parameter(description = "Clinic ID") @RequestParam clinicId: Long,
        @Parameter(description = "Start date (ISO)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @Parameter(description = "End date (ISO)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @Parameter(description = "Status name filter list") @RequestParam(required = false) statuses: List<String>?,
    ): ResponseEntity<ApiResponse<AppointmentStatsResponse>> {
        tenantClinicAccessChecker.verifyClinic(tenantCode, clinicId)
        log.debug { "GET admin appointment stats tenantCode=$tenantCode, clinicId=$clinicId from=$from to=$to statuses=$statuses" }
        val result = dashboardStatsService.getAppointmentStats(clinicId, from, to, statuses)
        return ResponseEntity.ok(ApiResponse.ok(result))
    }

    /**
     * 의사별 예약 지표를 전체 예약 건수 내림차순으로 정렬해 반환합니다.
     *
     * @param clinicId 대상 clinic ID입니다(0보다 커야 합니다).
     * @param from 포함할 시작 날짜이며, null이면 today-29를 사용합니다.
     * @param to 포함할 종료 날짜이며, null이면 today를 사용합니다.
     * @param limit 반환할 최대 의사 수이며, 범위는 1..100이고 기본값은 20입니다.
     */
    @Operation(summary = "Per-doctor appointment metrics")
    @ApiResponses(
        OApiResponse(responseCode = "200", description = "Success"),
        OApiResponse(responseCode = "400", description = "Invalid parameters"),
    )
    @GetMapping("/doctors")
    fun getDoctorStats(
        @PathVariable tenantCode: String,
        @Parameter(description = "Clinic ID") @RequestParam clinicId: Long,
        @Parameter(description = "Start date (ISO)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @Parameter(description = "End date (ISO)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @Parameter(description = "Max doctors to return (1..100)") @RequestParam(defaultValue = "20") limit: Int,
    ): ResponseEntity<ApiResponse<DoctorStatsResponse>> {
        tenantClinicAccessChecker.verifyClinic(tenantCode, clinicId)
        log.debug { "GET admin doctor stats tenantCode=$tenantCode, clinicId=$clinicId from=$from to=$to limit=$limit" }
        val result = dashboardStatsService.getDoctorStats(clinicId, from, to, limit)
        return ResponseEntity.ok(ApiResponse.ok(result))
    }

    /**
     * 취소 및 no-show 추이를 반환합니다.
     *
     * @param clinicId 대상 clinic ID입니다(0보다 커야 합니다).
     * @param from 포함할 시작 날짜이며, null이면 today-29를 사용합니다.
     * @param to 포함할 종료 날짜이며, null이면 today를 사용합니다.
     */
    @Operation(summary = "Cancellation and no-show trends")
    @ApiResponses(
        OApiResponse(responseCode = "200", description = "Success"),
        OApiResponse(responseCode = "400", description = "Invalid parameters"),
    )
    @GetMapping("/cancellations")
    fun getCancellationStats(
        @PathVariable tenantCode: String,
        @Parameter(description = "Clinic ID") @RequestParam clinicId: Long,
        @Parameter(description = "Start date (ISO)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @Parameter(description = "End date (ISO)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): ResponseEntity<ApiResponse<CancellationStatsResponse>> {
        tenantClinicAccessChecker.verifyClinic(tenantCode, clinicId)
        log.debug { "GET admin cancellation stats tenantCode=$tenantCode, clinicId=$clinicId from=$from to=$to" }
        val result = dashboardStatsService.getCancellationStats(clinicId, from, to)
        return ResponseEntity.ok(ApiResponse.ok(result))
    }
}
