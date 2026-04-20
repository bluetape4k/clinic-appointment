package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.clinic.appointment.api.dto.ApiResponse
import io.bluetape4k.clinic.appointment.api.dto.AppointmentResponse
import io.bluetape4k.clinic.appointment.api.dto.CreateAppointmentRequest
import io.bluetape4k.clinic.appointment.api.dto.UpdateStatusRequest
import io.bluetape4k.clinic.appointment.api.dto.toResponse
import io.bluetape4k.clinic.appointment.api.service.AppointmentService
import io.bluetape4k.clinic.appointment.timezone.ClinicTimezoneService
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
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

@RestController
@RequestMapping("/api/appointments")
class AppointmentController(
    private val appointmentService: AppointmentService,
    private val timezoneService: ClinicTimezoneService,
) {
    companion object : KLogging()

    @GetMapping
    fun getByDateRange(
        @RequestParam clinicId: Long,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) startDate: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) endDate: LocalDate,
    ): ResponseEntity<ApiResponse<List<AppointmentResponse>>> {
        log.debug { "GET /api/appointments?clinicId=$clinicId&startDate=$startDate&endDate=$endDate" }
        val records = appointmentService.getByDateRange(clinicId, startDate, endDate)
        val (timezone, locale) = timezoneService.getTimezoneAndLocale(clinicId)
        return ResponseEntity.ok(ApiResponse.ok(records.map { it.toResponse(timezone, locale) }))
    }

    @GetMapping("/{id}")
    fun getById(@PathVariable id: Long): ResponseEntity<ApiResponse<AppointmentResponse>> {
        log.debug { "GET /api/appointments/$id" }
        val record = appointmentService.getById(id)
        val (timezone, locale) = timezoneService.getTimezoneAndLocale(record.clinicId)
        return ResponseEntity.ok(ApiResponse.ok(record.toResponse(timezone, locale)))
    }

    @PostMapping
    fun create(@RequestBody request: CreateAppointmentRequest): ResponseEntity<ApiResponse<AppointmentResponse>> {
        log.debug { "POST /api/appointments - patient=${request.patientName}" }
        val saved = appointmentService.create(request)
        val (timezone, locale) = timezoneService.getTimezoneAndLocale(saved.clinicId)
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(saved.toResponse(timezone, locale)))
    }

    @PatchMapping("/{id}/status")
    suspend fun updateStatus(
        @PathVariable id: Long,
        @RequestBody request: UpdateStatusRequest,
    ): ResponseEntity<ApiResponse<AppointmentResponse>> {
        log.debug { "PATCH /api/appointments/$id/status - target=${request.status}" }
        val updated = appointmentService.updateStatus(id, request.status, request.reason)
        val (timezone, locale) = timezoneService.getTimezoneAndLocale(updated.clinicId)
        return ResponseEntity.ok(ApiResponse.ok(updated.toResponse(timezone, locale)))
    }

    @DeleteMapping("/{id}")
    suspend fun cancel(@PathVariable id: Long): ResponseEntity<ApiResponse<AppointmentResponse>> {
        log.debug { "DELETE /api/appointments/$id" }
        val cancelled = appointmentService.cancel(id)
        val (timezone, locale) = timezoneService.getTimezoneAndLocale(cancelled.clinicId)
        return ResponseEntity.ok(ApiResponse.ok(cancelled.toResponse(timezone, locale)))
    }
}
