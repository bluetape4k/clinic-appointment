package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.clinic.appointment.api.dto.ApiResponse
import io.bluetape4k.clinic.appointment.api.dto.AppointmentResponse
import io.bluetape4k.clinic.appointment.api.dto.CreateAppointmentRequest
import io.bluetape4k.clinic.appointment.api.dto.StateHistoryResponse
import io.bluetape4k.clinic.appointment.api.dto.UpdateStatusRequest
import io.bluetape4k.clinic.appointment.api.dto.toResponse
import io.bluetape4k.clinic.appointment.api.service.AppointmentService
import io.bluetape4k.clinic.appointment.api.tenant.TenantClinicAccessChecker
import io.bluetape4k.clinic.appointment.timezone.ClinicTimezoneService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse as OApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/**
 * 예약 REST 컨트롤러.
 *
 * @param appointmentService 예약 유스케이스 서비스
 * @param timezoneService 병원 타임존 조회 서비스
 */
@Tag(name = "Appointments", description = "Appointment scheduling and management")
@RestController
@RequestMapping("/api/{tenantCode}/appointments")
class AppointmentController(
    private val appointmentService: AppointmentService,
    private val timezoneService: ClinicTimezoneService,
    private val tenantClinicAccessChecker: TenantClinicAccessChecker,
) {
    companion object : KLogging()

    @Operation(summary = "Get appointments by date range")
    @ApiResponses(
        OApiResponse(responseCode = "200", description = "Success"),
        OApiResponse(responseCode = "400", description = "Invalid parameters"),
    )
    @GetMapping
    fun getByDateRange(
        @PathVariable tenantCode: String,
        @RequestParam clinicId: Long,
        @Parameter(description = "Start date (ISO format)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) startDate: LocalDate,
        @Parameter(description = "End date (ISO format)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) endDate: LocalDate,
    ): ResponseEntity<ApiResponse<List<AppointmentResponse>>> {
        tenantClinicAccessChecker.verifyClinic(tenantCode, clinicId)
        log.debug { "GET appointments tenantCode=$tenantCode, clinicId=$clinicId, startDate=$startDate, endDate=$endDate" }
        val records = appointmentService.getByDateRange(clinicId, startDate, endDate)
        val (timezone, locale) = timezoneService.getTimezoneAndLocale(clinicId)
        return ResponseEntity.ok(ApiResponse.ok(records.map { it.toResponse(timezone, locale) }))
    }

    @Operation(summary = "Get appointment by ID")
    @ApiResponses(
        OApiResponse(responseCode = "200", description = "Success"),
        OApiResponse(responseCode = "404", description = "Appointment not found"),
    )
    @GetMapping("/{id}")
    fun getById(
        @PathVariable tenantCode: String,
        @PathVariable id: Long,
    ): ResponseEntity<ApiResponse<AppointmentResponse>> {
        val tenant = tenantClinicAccessChecker.requireTenant(tenantCode)
        log.debug { "GET appointment tenantCode=$tenantCode, id=$id" }
        val record = appointmentService.getById(id, tenant.id)
        val (timezone, locale) = timezoneService.getTimezoneAndLocale(record.clinicId)
        return ResponseEntity.ok(ApiResponse.ok(record.toResponse(timezone, locale)))
    }

    @Operation(summary = "Create a new appointment")
    @ApiResponses(
        OApiResponse(responseCode = "201", description = "Appointment created"),
        OApiResponse(responseCode = "400", description = "Invalid parameters"),
        OApiResponse(responseCode = "409", description = "Scheduling conflict"),
    )
    @PostMapping
    fun create(
        @PathVariable tenantCode: String,
        @Valid @RequestBody request: CreateAppointmentRequest,
    ): ResponseEntity<ApiResponse<AppointmentResponse>> {
        tenantClinicAccessChecker.verifySchedulingResources(
            tenantCode = tenantCode,
            clinicId = request.clinicId,
            doctorId = request.doctorId,
            treatmentTypeId = request.treatmentTypeId,
            equipmentId = request.equipmentId,
        )
        log.debug { "POST appointment tenantCode=$tenantCode, patient=${request.patientName}" }
        val saved = appointmentService.create(request)
        val (timezone, locale) = timezoneService.getTimezoneAndLocale(saved.clinicId)
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(saved.toResponse(timezone, locale)))
    }

    @Operation(summary = "Get state change history for an appointment")
    @ApiResponses(
        OApiResponse(responseCode = "200", description = "Success"),
        OApiResponse(responseCode = "404", description = "Appointment not found"),
    )
    @GetMapping("/{id}/history")
    fun getHistory(
        @PathVariable tenantCode: String,
        @PathVariable id: Long,
    ): ResponseEntity<ApiResponse<List<StateHistoryResponse>>> {
        val tenant = tenantClinicAccessChecker.requireTenant(tenantCode)
        log.debug { "GET appointment history tenantCode=$tenantCode, id=$id" }
        val history = appointmentService.getStateHistory(id, tenant.id)
        return ResponseEntity.ok(ApiResponse.ok(history.map { it.toResponse() }))
    }

    @Operation(summary = "Update appointment status")
    @ApiResponses(
        OApiResponse(responseCode = "200", description = "Success"),
        OApiResponse(responseCode = "400", description = "Invalid parameters"),
        OApiResponse(responseCode = "404", description = "Appointment not found"),
        OApiResponse(responseCode = "409", description = "Invalid state transition"),
    )
    @PatchMapping("/{id}/status")
    suspend fun updateStatus(
        @PathVariable tenantCode: String,
        @PathVariable id: Long,
        @Valid @RequestBody request: UpdateStatusRequest,
    ): ResponseEntity<ApiResponse<AppointmentResponse>> {
        val tenant = tenantClinicAccessChecker.requireTenant(tenantCode)
        log.debug { "PATCH appointment status tenantCode=$tenantCode, id=$id, target=${request.status}" }
        val updated = appointmentService.updateStatus(id, tenant.id, request.status, request.reason)
        val (timezone, locale) = timezoneService.getTimezoneAndLocale(updated.clinicId)
        return ResponseEntity.ok(ApiResponse.ok(updated.toResponse(timezone, locale)))
    }

    @Operation(summary = "Cancel an appointment")
    @ApiResponses(
        OApiResponse(responseCode = "200", description = "Success"),
        OApiResponse(responseCode = "404", description = "Appointment not found"),
        OApiResponse(responseCode = "409", description = "Invalid state transition"),
    )
    @DeleteMapping("/{id}")
    suspend fun cancel(
        @PathVariable tenantCode: String,
        @PathVariable id: Long,
        @Parameter(description = "Cancellation reason", required = false) @RequestParam(required = false) reason: String?,
    ): ResponseEntity<ApiResponse<AppointmentResponse>> {
        val tenant = tenantClinicAccessChecker.requireTenant(tenantCode)
        log.debug { "DELETE appointment tenantCode=$tenantCode, id=$id, reason=$reason" }
        val cancelled = appointmentService.cancel(id, tenant.id, reason)
        val (timezone, locale) = timezoneService.getTimezoneAndLocale(cancelled.clinicId)
        return ResponseEntity.ok(ApiResponse.ok(cancelled.toResponse(timezone, locale)))
    }
}
