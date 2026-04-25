package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.clinic.appointment.api.dto.ApiResponse
import io.bluetape4k.clinic.appointment.api.dto.ConflictingAppointmentResponse
import io.bluetape4k.clinic.appointment.api.dto.CreateEquipmentUnavailabilityRequest
import io.bluetape4k.clinic.appointment.api.dto.UnavailabilityConflictResponse
import io.bluetape4k.clinic.appointment.api.dto.UnavailabilityExceptionRequest
import io.bluetape4k.clinic.appointment.api.dto.UpdateEquipmentUnavailabilityRequest
import io.bluetape4k.clinic.appointment.model.dto.AppointmentRecord
import io.bluetape4k.clinic.appointment.model.dto.EquipmentUnavailabilityExceptionRecord
import io.bluetape4k.clinic.appointment.model.dto.EquipmentUnavailabilityRecord
import io.bluetape4k.clinic.appointment.service.EquipmentUnavailabilityService
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requirePositiveNumber
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
@RestController
@RequestMapping("/api/clinics/{clinicId}/equipments/{equipmentId}/unavailabilities")
class EquipmentUnavailabilityController(
    private val service: EquipmentUnavailabilityService,
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
    @GetMapping
    fun getUnavailabilities(
        @PathVariable clinicId: Long,
        @PathVariable equipmentId: Long,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ): ResponseEntity<ApiResponse<List<EquipmentUnavailabilityRecord>>> {
        clinicId.requirePositiveNumber("clinicId")
        equipmentId.requirePositiveNumber("equipmentId")
        log.debug { "GET unavailabilities clinicId=$clinicId, equipmentId=$equipmentId, from=$from, to=$to" }
        val records = service.findUnavailabilityRecords(equipmentId, from, to)
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
    @PostMapping
    fun create(
        @PathVariable clinicId: Long,
        @PathVariable equipmentId: Long,
        @RequestBody request: CreateEquipmentUnavailabilityRequest,
    ): ResponseEntity<ApiResponse<EquipmentUnavailabilityRecord>> {
        clinicId.requirePositiveNumber("clinicId")
        equipmentId.requirePositiveNumber("equipmentId")
        log.debug { "POST unavailability clinicId=$clinicId, equipmentId=$equipmentId" }
        val record = service.create(
            equipmentId = equipmentId,
            clinicId = clinicId,
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
    @PutMapping("/{id}")
    fun update(
        @PathVariable clinicId: Long,
        @PathVariable equipmentId: Long,
        @PathVariable id: Long,
        @RequestBody request: UpdateEquipmentUnavailabilityRequest,
    ): ResponseEntity<ApiResponse<EquipmentUnavailabilityRecord>> {
        clinicId.requirePositiveNumber("clinicId")
        equipmentId.requirePositiveNumber("equipmentId")
        id.requirePositiveNumber("id")
        log.debug { "PUT unavailability id=$id, clinicId=$clinicId, equipmentId=$equipmentId" }
        service.findById(id) ?: throw NoSuchElementException("EquipmentUnavailability not found: $id")
        service.delete(id)
        val updated = service.create(
            equipmentId = equipmentId,
            clinicId = clinicId,
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
    @DeleteMapping("/{id}")
    fun delete(
        @PathVariable clinicId: Long,
        @PathVariable equipmentId: Long,
        @PathVariable id: Long,
    ): ResponseEntity<Void> {
        clinicId.requirePositiveNumber("clinicId")
        equipmentId.requirePositiveNumber("equipmentId")
        id.requirePositiveNumber("id")
        log.debug { "DELETE unavailability id=$id, clinicId=$clinicId, equipmentId=$equipmentId" }
        service.delete(id)
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
    @PostMapping("/{id}/exceptions")
    fun addException(
        @PathVariable clinicId: Long,
        @PathVariable equipmentId: Long,
        @PathVariable id: Long,
        @RequestBody request: UnavailabilityExceptionRequest,
    ): ResponseEntity<ApiResponse<EquipmentUnavailabilityExceptionRecord>> {
        clinicId.requirePositiveNumber("clinicId")
        equipmentId.requirePositiveNumber("equipmentId")
        id.requirePositiveNumber("id")
        log.debug { "POST exception unavailabilityId=$id, date=${request.originalDate}" }
        val exception = service.addException(
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
    @DeleteMapping("/{id}/exceptions/{exId}")
    fun deleteException(
        @PathVariable clinicId: Long,
        @PathVariable equipmentId: Long,
        @PathVariable id: Long,
        @PathVariable exId: Long,
    ): ResponseEntity<Void> {
        clinicId.requirePositiveNumber("clinicId")
        equipmentId.requirePositiveNumber("equipmentId")
        id.requirePositiveNumber("id")
        exId.requirePositiveNumber("exId")
        log.debug { "DELETE exception exId=$exId, unavailabilityId=$id" }
        service.deleteException(exId)
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
    @GetMapping("/{id}/conflicts")
    fun detectConflicts(
        @PathVariable clinicId: Long,
        @PathVariable equipmentId: Long,
        @PathVariable id: Long,
    ): ResponseEntity<ApiResponse<UnavailabilityConflictResponse>> {
        clinicId.requirePositiveNumber("clinicId")
        equipmentId.requirePositiveNumber("equipmentId")
        id.requirePositiveNumber("id")
        log.debug { "GET conflicts unavailabilityId=$id" }
        val conflictingAppointments = service.detectConflicts(id)
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
    @PostMapping("/preview-conflicts")
    fun previewConflicts(
        @PathVariable clinicId: Long,
        @PathVariable equipmentId: Long,
        @RequestBody request: CreateEquipmentUnavailabilityRequest,
    ): ResponseEntity<ApiResponse<UnavailabilityConflictResponse>> {
        clinicId.requirePositiveNumber("clinicId")
        equipmentId.requirePositiveNumber("equipmentId")
        log.debug { "POST preview-conflicts equipmentId=$equipmentId" }
        val conflictingAppointments = service.previewConflicts(
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
