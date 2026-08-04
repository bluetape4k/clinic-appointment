package io.bluetape4k.clinic.appointment.api.controller

import jakarta.validation.Valid
import io.bluetape4k.clinic.appointment.api.dto.ApiResponse
import io.bluetape4k.clinic.appointment.api.dto.ConflictingAppointmentResponse
import io.bluetape4k.clinic.appointment.api.dto.CreateEquipmentUnavailabilityRequest
import io.bluetape4k.clinic.appointment.api.dto.UnavailabilityConflictResponse
import io.bluetape4k.clinic.appointment.api.dto.UnavailabilityExceptionRequest
import io.bluetape4k.clinic.appointment.api.dto.UpdateEquipmentUnavailabilityRequest
import io.bluetape4k.clinic.appointment.api.tenant.TenantClinicAccessChecker
import io.bluetape4k.clinic.appointment.model.dto.AppointmentRecord
import io.bluetape4k.clinic.appointment.model.dto.EquipmentUnavailabilityExceptionRecord
import io.bluetape4k.clinic.appointment.model.dto.EquipmentUnavailabilityRecord
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import io.bluetape4k.clinic.appointment.service.EquipmentUnavailabilityService
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requirePositiveNumber
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse as OApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/**
 * 장비 사용불가 스케줄 REST 컨트롤러.
 *
 * 장비의 사용불가 기간 등록, 조회, 수정, 삭제 API와
 * 예외 처리 및 충돌 예약 감지 API를 제공합니다.
 *
 * @param service 장비 사용불가 서비스
 */
@Tag(name = "Equipment Unavailability", description = "Equipment unavailability schedule management")
@RestController
@RequestMapping("/api/{tenantCode}/clinics/{clinicId}/equipments/{equipmentId}/unavailabilities")
class EquipmentUnavailabilityController(
    private val service: EquipmentUnavailabilityService,
    private val tenantClinicAccessChecker: TenantClinicAccessChecker,
) {
    companion object : KLogging()

    /**
     * 장비의 사용불가 스케줄 목록 조회.
     *
     * @param clinicId 병원 ID
     * @param equipmentId 장비 ID
     * @param from 조회 시작 날짜
     * @param to 조회 종료 날짜
     * @return 사용불가 스케줄 목록
     */
    @Operation(summary = "Get equipment unavailability schedules")
    @ApiResponses(
        OApiResponse(responseCode = "200", description = "Success"),
        OApiResponse(responseCode = "400", description = "Invalid parameters"),
    )
    @GetMapping
    fun getUnavailabilities(
        @PathVariable tenantCode: String,
        @PathVariable clinicId: Long,
        @PathVariable equipmentId: Long,
        @Parameter(description = "Start date (ISO format)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @Parameter(description = "End date (ISO format)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ): ResponseEntity<ApiResponse<List<EquipmentUnavailabilityRecord>>> {
        clinicId.requirePositiveNumber("clinicId")
        equipmentId.requirePositiveNumber("equipmentId")
        val tenant = tenantClinicAccessChecker.verifyEquipment(tenantCode, clinicId, equipmentId)
        val scope = TenantClinicScope(tenant.id, clinicId)
        log.debug { "GET unavailabilities tenantCode=$tenantCode, clinicId=$clinicId, equipmentId=$equipmentId, from=$from, to=$to" }
        val records = service.findUnavailabilityRecords(scope, equipmentId, from, to)
        return ResponseEntity.ok(ApiResponse.ok(records))
    }

    /**
     * 장비 사용불가 스케줄 등록.
     *
     * @param clinicId 병원 ID
     * @param equipmentId 장비 ID
     * @param request 사용불가 스케줄 생성 요청
     * @return 생성된 사용불가 스케줄
     */
    @Operation(summary = "Create equipment unavailability schedule")
    @ApiResponses(
        OApiResponse(responseCode = "201", description = "Created"),
        OApiResponse(responseCode = "400", description = "Invalid parameters"),
    )
    @PostMapping
    fun create(
        @PathVariable tenantCode: String,
        @PathVariable clinicId: Long,
        @PathVariable equipmentId: Long,
        @Valid @RequestBody request: CreateEquipmentUnavailabilityRequest,
    ): ResponseEntity<ApiResponse<EquipmentUnavailabilityRecord>> {
        clinicId.requirePositiveNumber("clinicId")
        equipmentId.requirePositiveNumber("equipmentId")
        val tenant = tenantClinicAccessChecker.verifyEquipment(tenantCode, clinicId, equipmentId)
        val scope = TenantClinicScope(tenant.id, clinicId)
        log.debug { "POST unavailability tenantCode=$tenantCode, clinicId=$clinicId, equipmentId=$equipmentId" }
        val record = service.create(
            scope = scope,
            equipmentId = equipmentId,
            unavailableDate = request.unavailableDate,
            isRecurring = request.isRecurring,
            recurringDayOfWeek = request.recurringDayOfWeek,
            effectiveFrom = request.effectiveFrom,
            effectiveUntil = request.effectiveUntil,
            startTime = request.startTime,
            endTime = request.endTime,
            reason = request.reason,
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(record))
    }

    /**
     * 장비 사용불가 스케줄 수정.
     *
     * 기존 스케줄을 삭제하고 새로 생성합니다.
     *
     * @param clinicId 병원 ID
     * @param equipmentId 장비 ID
     * @param id 사용불가 스케줄 ID
     * @param request 수정 요청
     * @return 수정된 사용불가 스케줄
     */
    @Operation(summary = "Update equipment unavailability schedule")
    @ApiResponses(
        OApiResponse(responseCode = "200", description = "Success"),
        OApiResponse(responseCode = "400", description = "Invalid parameters"),
        OApiResponse(responseCode = "404", description = "Unavailability not found"),
    )
    @PutMapping("/{id}")
    fun update(
        @PathVariable tenantCode: String,
        @PathVariable clinicId: Long,
        @PathVariable equipmentId: Long,
        @PathVariable id: Long,
        @Valid @RequestBody request: UpdateEquipmentUnavailabilityRequest,
    ): ResponseEntity<ApiResponse<EquipmentUnavailabilityRecord>> {
        clinicId.requirePositiveNumber("clinicId")
        equipmentId.requirePositiveNumber("equipmentId")
        id.requirePositiveNumber("id")
        val tenant = tenantClinicAccessChecker.verifyEquipment(tenantCode, clinicId, equipmentId)
        val scope = TenantClinicScope(tenant.id, clinicId)
        log.debug { "PUT unavailability tenantCode=$tenantCode, id=$id, clinicId=$clinicId, equipmentId=$equipmentId" }
        requireUnavailability(scope, equipmentId, id)
        service.delete(scope, id)
        val updated = service.create(
            scope = scope,
            equipmentId = equipmentId,
            unavailableDate = request.unavailableDate,
            isRecurring = request.isRecurring,
            recurringDayOfWeek = request.recurringDayOfWeek,
            effectiveFrom = request.effectiveFrom,
            effectiveUntil = request.effectiveUntil,
            startTime = request.startTime,
            endTime = request.endTime,
            reason = request.reason,
        )
        return ResponseEntity.ok(ApiResponse.ok(updated))
    }

    /**
     * 장비 사용불가 스케줄 삭제.
     *
     * @param clinicId 병원 ID
     * @param equipmentId 장비 ID
     * @param id 사용불가 스케줄 ID
     * @return 204 No Content
     */
    @Operation(summary = "Delete equipment unavailability schedule")
    @ApiResponses(
        OApiResponse(responseCode = "204", description = "Deleted"),
        OApiResponse(responseCode = "400", description = "Invalid parameters"),
        OApiResponse(responseCode = "404", description = "Unavailability not found"),
    )
    @DeleteMapping("/{id}")
    fun delete(
        @PathVariable tenantCode: String,
        @PathVariable clinicId: Long,
        @PathVariable equipmentId: Long,
        @PathVariable id: Long,
    ): ResponseEntity<Void> {
        clinicId.requirePositiveNumber("clinicId")
        equipmentId.requirePositiveNumber("equipmentId")
        id.requirePositiveNumber("id")
        val tenant = tenantClinicAccessChecker.verifyEquipment(tenantCode, clinicId, equipmentId)
        val scope = TenantClinicScope(tenant.id, clinicId)
        log.debug { "DELETE unavailability tenantCode=$tenantCode, id=$id, clinicId=$clinicId, equipmentId=$equipmentId" }
        requireUnavailability(scope, equipmentId, id)
        if (!service.delete(scope, id)) {
            throw NoSuchElementException("EquipmentUnavailability not found: $id")
        }
        return ResponseEntity.noContent().build()
    }

    /**
     * 장비 사용불가 예외 추가.
     *
     * @param clinicId 병원 ID
     * @param equipmentId 장비 ID
     * @param id 사용불가 스케줄 ID
     * @param request 예외 처리 요청
     * @return 생성된 예외 레코드
     */
    @Operation(summary = "Add exception to unavailability schedule")
    @ApiResponses(
        OApiResponse(responseCode = "201", description = "Created"),
        OApiResponse(responseCode = "400", description = "Invalid parameters"),
        OApiResponse(responseCode = "404", description = "Unavailability not found"),
    )
    @PostMapping("/{id}/exceptions")
    fun addException(
        @PathVariable tenantCode: String,
        @PathVariable clinicId: Long,
        @PathVariable equipmentId: Long,
        @PathVariable id: Long,
        @Valid @RequestBody request: UnavailabilityExceptionRequest,
    ): ResponseEntity<ApiResponse<EquipmentUnavailabilityExceptionRecord>> {
        clinicId.requirePositiveNumber("clinicId")
        equipmentId.requirePositiveNumber("equipmentId")
        id.requirePositiveNumber("id")
        val tenant = tenantClinicAccessChecker.verifyEquipment(tenantCode, clinicId, equipmentId)
        val scope = TenantClinicScope(tenant.id, clinicId)
        requireUnavailability(scope, equipmentId, id)
        log.debug { "POST exception tenantCode=$tenantCode, unavailabilityId=$id, date=${request.originalDate}" }
        val exception = service.addException(
            scope = scope,
            unavailabilityId = id,
            originalDate = request.originalDate,
            exceptionType = request.exceptionType,
            rescheduledDate = request.rescheduledDate,
            rescheduledStartTime = request.rescheduledStartTime,
            rescheduledEndTime = request.rescheduledEndTime,
            reason = request.reason,
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(exception))
    }

    /**
     * 장비 사용불가 예외 삭제.
     *
     * @param clinicId 병원 ID
     * @param equipmentId 장비 ID
     * @param id 사용불가 스케줄 ID
     * @param exId 예외 ID
     * @return 204 No Content
     */
    @Operation(summary = "Delete unavailability exception")
    @ApiResponses(
        OApiResponse(responseCode = "204", description = "Deleted"),
        OApiResponse(responseCode = "400", description = "Invalid parameters"),
        OApiResponse(responseCode = "404", description = "Exception not found"),
    )
    @DeleteMapping("/{id}/exceptions/{exId}")
    fun deleteException(
        @PathVariable tenantCode: String,
        @PathVariable clinicId: Long,
        @PathVariable equipmentId: Long,
        @PathVariable id: Long,
        @PathVariable exId: Long,
    ): ResponseEntity<Void> {
        clinicId.requirePositiveNumber("clinicId")
        equipmentId.requirePositiveNumber("equipmentId")
        id.requirePositiveNumber("id")
        exId.requirePositiveNumber("exId")
        val tenant = tenantClinicAccessChecker.verifyEquipment(tenantCode, clinicId, equipmentId)
        val scope = TenantClinicScope(tenant.id, clinicId)
        requireUnavailability(scope, equipmentId, id)
        log.debug { "DELETE exception tenantCode=$tenantCode, exId=$exId, unavailabilityId=$id" }
        if (!service.deleteException(scope, id, exId)) {
            throw NoSuchElementException("EquipmentUnavailabilityException not found: $exId")
        }
        return ResponseEntity.noContent().build()
    }

    /**
     * 등록된 사용불가 스케줄과 충돌하는 예약 조회.
     *
     * @param clinicId 병원 ID
     * @param equipmentId 장비 ID
     * @param id 사용불가 스케줄 ID
     * @return 충돌 예약 목록
     */
    @Operation(summary = "Detect conflicting appointments")
    @ApiResponses(
        OApiResponse(responseCode = "200", description = "Success"),
        OApiResponse(responseCode = "400", description = "Invalid parameters"),
        OApiResponse(responseCode = "404", description = "Unavailability not found"),
    )
    @GetMapping("/{id}/conflicts")
    fun detectConflicts(
        @PathVariable tenantCode: String,
        @PathVariable clinicId: Long,
        @PathVariable equipmentId: Long,
        @PathVariable id: Long,
    ): ResponseEntity<ApiResponse<UnavailabilityConflictResponse>> {
        clinicId.requirePositiveNumber("clinicId")
        equipmentId.requirePositiveNumber("equipmentId")
        id.requirePositiveNumber("id")
        val tenant = tenantClinicAccessChecker.verifyEquipment(tenantCode, clinicId, equipmentId)
        val scope = TenantClinicScope(tenant.id, clinicId)
        requireUnavailability(scope, equipmentId, id)
        log.debug { "GET conflicts tenantCode=$tenantCode, unavailabilityId=$id" }
        val conflictingAppointments = service.detectConflicts(scope, id)
        val response = conflictingAppointments.toConflictResponse(id)
        return ResponseEntity.ok(ApiResponse.ok(response))
    }

    /**
     * 사용불가 스케줄 등록 전 충돌 미리보기.
     *
     * @param clinicId 병원 ID
     * @param equipmentId 장비 ID
     * @param request 사용불가 스케줄 생성 요청
     * @return 충돌 예약 미리보기
     */
    @Operation(summary = "Preview conflicts before creating unavailability")
    @ApiResponses(
        OApiResponse(responseCode = "200", description = "Success"),
        OApiResponse(responseCode = "400", description = "Invalid parameters"),
    )
    @PostMapping("/preview-conflicts")
    fun previewConflicts(
        @PathVariable tenantCode: String,
        @PathVariable clinicId: Long,
        @PathVariable equipmentId: Long,
        @Valid @RequestBody request: CreateEquipmentUnavailabilityRequest,
    ): ResponseEntity<ApiResponse<UnavailabilityConflictResponse>> {
        clinicId.requirePositiveNumber("clinicId")
        equipmentId.requirePositiveNumber("equipmentId")
        val tenant = tenantClinicAccessChecker.verifyEquipment(tenantCode, clinicId, equipmentId)
        val scope = TenantClinicScope(tenant.id, clinicId)
        log.debug { "POST preview-conflicts tenantCode=$tenantCode, equipmentId=$equipmentId" }
        val conflictingAppointments = service.previewConflicts(
            scope = scope,
            equipmentId = equipmentId,
            unavailableDate = request.unavailableDate,
            isRecurring = request.isRecurring,
            recurringDayOfWeek = request.recurringDayOfWeek,
            effectiveFrom = request.effectiveFrom,
            effectiveUntil = request.effectiveUntil,
            startTime = request.startTime,
            endTime = request.endTime,
        )
        val response = conflictingAppointments.toConflictResponse(unavailabilityId = 0L)
        return ResponseEntity.ok(ApiResponse.ok(response))
    }

    private fun requireUnavailability(
        scope: TenantClinicScope,
        equipmentId: Long,
        id: Long,
    ): EquipmentUnavailabilityRecord {
        val record = service.findById(scope, id)
            ?: throw NoSuchElementException("EquipmentUnavailability not found: $id")

        if (record.equipmentId != equipmentId) {
            throw NoSuchElementException("EquipmentUnavailability not found: $id")
        }

        return record
    }
}

private fun List<AppointmentRecord>.toConflictResponse(unavailabilityId: Long): UnavailabilityConflictResponse {
    val conflicts = map { appointment ->
        ConflictingAppointmentResponse(
            appointmentId = appointment.id!!,
            patientName = appointment.patientName,
            appointmentDate = appointment.appointmentDate,
            startTime = appointment.startTime,
            endTime = appointment.endTime,
            doctorId = appointment.doctorId,
            equipmentId = appointment.equipmentId ?: 0L,
        )
    }
    return UnavailabilityConflictResponse(
        unavailabilityId = unavailabilityId,
        conflictCount = conflicts.size,
        conflicts = conflicts,
    )
}
