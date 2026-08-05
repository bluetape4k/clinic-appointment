package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.clinic.appointment.api.dto.ApiResponse
import io.bluetape4k.clinic.appointment.api.dto.AppointmentResponse
import io.bluetape4k.clinic.appointment.api.dto.CreateAppointmentRequest
import io.bluetape4k.clinic.appointment.api.dto.SchedulingApiErrorResponse
import io.bluetape4k.clinic.appointment.api.dto.StateHistoryResponse
import io.bluetape4k.clinic.appointment.api.dto.UpdateStatusRequest
import io.bluetape4k.clinic.appointment.api.dto.toResponse
import io.bluetape4k.clinic.appointment.api.notification.LegacyAppointmentMemberResolver
import io.bluetape4k.clinic.appointment.api.notification.MemberResolution
import io.bluetape4k.clinic.appointment.api.security.CorrelationIdFilter
import io.bluetape4k.clinic.appointment.api.service.AppointmentService
import io.bluetape4k.clinic.appointment.api.tenant.TenantClinicAccessChecker
import io.bluetape4k.clinic.appointment.timezone.ClinicTimezoneService
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import io.bluetape4k.clinic.appointment.service.AppointmentCommandContext
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.responses.ApiResponse as OApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import jakarta.validation.Valid
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
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
    private val appointmentMemberResolver: LegacyAppointmentMemberResolver,
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
        val tenant = tenantClinicAccessChecker.requireTenant(tenantCode)
        tenantClinicAccessChecker.verifyClinic(tenantCode, clinicId)
        val scope = TenantClinicScope(tenant.id, clinicId)
        log.debug { "GET appointments scope=<redacted>, startDate=$startDate, endDate=$endDate" }
        val records = appointmentService.getByDateRange(scope, startDate, endDate)
        val (timezone, locale) = timezoneService.getTimezoneAndLocale(scope)
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
        log.debug { "GET appointment scope=<redacted>" }
        val record = appointmentService.getById(id, tenant.id)
        val (timezone, locale) = timezoneService.getTimezoneAndLocale(TenantClinicScope(tenant.id, record.clinicId))
        return ResponseEntity.ok(ApiResponse.ok(record.toResponse(timezone, locale)))
    }

    @Operation(
        summary = "Create a new appointment",
        description = "Legacy creation requires a verified memberId by default. A missing memberId is accepted only for an expiring clinic-scoped OBSERVE transition exception; patient name and phone never replace it. A successful 2xx response means the appointment mutation and its durable outbox intent committed; Kafka delivery is asynchronous.",
    )
    @ApiResponses(
        OApiResponse(responseCode = "200", description = "Existing appointment replayed; the durable outbox intent already committed"),
        OApiResponse(responseCode = "201", description = "Appointment and durable outbox intent committed; Kafka delivery is asynchronous"),
        OApiResponse(responseCode = "400", description = "Invalid parameters"),
        OApiResponse(responseCode = "403", description = "Member or clinic scope rejected", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class), examples = [ExampleObject(name = "memberScopeMismatch", value = NotificationOpenApiExamples.MEMBER_SCOPE_MISMATCH)])]),
        OApiResponse(responseCode = "404", description = "Member not found", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class), examples = [ExampleObject(name = "memberNotFound", value = NotificationOpenApiExamples.MEMBER_NOT_FOUND)])]),
        OApiResponse(responseCode = "409", description = "Scheduling, idempotency, or ambiguous member reference conflict", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class), examples = [ExampleObject(name = "memberReferenceAmbiguous", value = NotificationOpenApiExamples.MEMBER_REFERENCE_AMBIGUOUS)])]),
        OApiResponse(responseCode = "422", description = "Verified member identifier required", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class), examples = [ExampleObject(name = "verifiedMemberRequired", value = NotificationOpenApiExamples.MEMBER_ID_REQUIRED)])]),
        OApiResponse(responseCode = "503", description = "Member directory or notification enqueue unavailable; retry the same idempotency key after the indicated delay", headers = [Header(name = "Retry-After", description = "Seconds before retrying with the same idempotency key", schema = Schema(type = "integer", example = "5"))], content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class), examples = [ExampleObject(name = "memberDirectoryUnavailable", value = NotificationOpenApiExamples.MEMBER_DIRECTORY_UNAVAILABLE)])]),
    )
    @PostMapping
    fun create(
        @PathVariable tenantCode: String,
        @Parameter(description = "Optional key that safely replays the same appointment creation request")
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        @Valid @RequestBody request: CreateAppointmentRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<ApiResponse<AppointmentResponse>> {
        val tenant = tenantClinicAccessChecker.verifySchedulingResources(
            tenantCode = tenantCode,
            clinicId = request.clinicId,
            doctorId = request.doctorId,
            treatmentTypeId = request.treatmentTypeId,
            equipmentId = request.equipmentId,
        )
        val resolution = appointmentMemberResolver.resolveLegacy(
            tenantGroupId = tenant.id,
            clinicId = request.clinicId,
            requested = request.memberId?.let(::MemberId),
        )
        val normalizedRequest = when (resolution) {
            is MemberResolution.Resolved -> request.copy(memberId = resolution.memberId.value)
            MemberResolution.LegacyMissing -> request.copy(memberId = null)
        }
        log.debug { "POST appointment scope=<redacted>" }
        val result = appointmentService.create(
            tenantGroupId = tenant.id,
            request = normalizedRequest,
            idempotencyKey = idempotencyKey,
            resolution = resolution,
            commandContext = commandContext(servletRequest),
        )
        val (timezone, locale) = timezoneService.getTimezoneAndLocale(
            TenantClinicScope(tenant.id, result.appointment.clinicId)
        )
        return ResponseEntity.status(if (result.replayed) HttpStatus.OK else HttpStatus.CREATED)
            .body(ApiResponse.ok(result.appointment.toResponse(timezone, locale)))
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
        log.debug { "GET appointment history scope=<redacted>" }
        val history = appointmentService.getStateHistory(id, tenant.id)
        return ResponseEntity.ok(ApiResponse.ok(history.map { it.toResponse() }))
    }

    @Operation(summary = "Update appointment status")
    @ApiResponses(
        OApiResponse(responseCode = "200", description = "Status mutation and durable outbox intent committed; Kafka delivery is asynchronous"),
        OApiResponse(responseCode = "400", description = "Invalid parameters"),
        OApiResponse(responseCode = "404", description = "Appointment not found"),
        OApiResponse(responseCode = "409", description = "Invalid state transition"),
        OApiResponse(responseCode = "503", description = "Notification enqueue unavailable", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
    )
    @PatchMapping("/{id}/status")
    suspend fun updateStatus(
        @PathVariable tenantCode: String,
        @PathVariable id: Long,
        @Valid @RequestBody request: UpdateStatusRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<ApiResponse<AppointmentResponse>> {
        val tenant = tenantClinicAccessChecker.requireTenant(tenantCode)
        log.debug { "PATCH appointment status scope=<redacted>, target=${request.status}" }
        val scope = appointmentService.getScope(id, tenant.id)
        val updated = appointmentService.updateStatus(
            scope = scope,
            id = id,
            targetStatus = request.status,
            reason = request.reason,
            commandContext = commandContext(servletRequest),
        )
        val (timezone, locale) = timezoneService.getTimezoneAndLocale(scope)
        return ResponseEntity.ok(ApiResponse.ok(updated.toResponse(timezone, locale)))
    }

    @Operation(summary = "Cancel an appointment")
    @ApiResponses(
        OApiResponse(responseCode = "200", description = "Cancellation and durable outbox intent committed; Kafka delivery is asynchronous"),
        OApiResponse(responseCode = "404", description = "Appointment not found"),
        OApiResponse(responseCode = "409", description = "Invalid state transition"),
        OApiResponse(responseCode = "503", description = "Notification enqueue unavailable", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
    )
    @DeleteMapping("/{id}")
    suspend fun cancel(
        @PathVariable tenantCode: String,
        @PathVariable id: Long,
        @Parameter(description = "Cancellation reason", required = false) @RequestParam(required = false) reason: String?,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<ApiResponse<AppointmentResponse>> {
        val tenant = tenantClinicAccessChecker.requireTenant(tenantCode)
        log.debug { "DELETE appointment scope=<redacted>, reasonCodePresent=${reason != null}" }
        val scope = appointmentService.getScope(id, tenant.id)
        val cancelled = appointmentService.cancel(
            scope = scope,
            id = id,
            reason = reason,
            commandContext = commandContext(servletRequest),
        )
        val (timezone, locale) = timezoneService.getTimezoneAndLocale(scope)
        return ResponseEntity.ok(ApiResponse.ok(cancelled.toResponse(timezone, locale)))
    }

    private fun commandContext(request: HttpServletRequest): AppointmentCommandContext =
        AppointmentCommandContext.root(
            CorrelationIdFilter.requireCorrelationId(request),
        )
}
