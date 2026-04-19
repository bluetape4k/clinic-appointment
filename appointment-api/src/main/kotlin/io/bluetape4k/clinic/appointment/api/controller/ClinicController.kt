package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.clinic.appointment.api.dto.ApiResponse
import io.bluetape4k.clinic.appointment.model.dto.BreakTimeRecord
import io.bluetape4k.clinic.appointment.model.dto.ClinicDefaultBreakTimeRecord
import io.bluetape4k.clinic.appointment.model.dto.ClinicRecord
import io.bluetape4k.clinic.appointment.model.dto.OperatingHoursRecord
import io.bluetape4k.clinic.appointment.repository.ClinicRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requirePositiveNumber
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 클리닉(병원) 정보 조회 REST 컨트롤러.
 *
 * 병원 목록, 개별 병원 정보, 운영 시간, 휴식 시간 조회 API를 제공합니다.
 */
@RestController
@RequestMapping("/api/clinics")
class ClinicController(
    private val clinicRepository: ClinicRepository,
) {
    companion object : KLogging()

    /**
     * 전체 클리닉 목록을 조회합니다.
     *
     * @return 클리닉 목록
     */
    @GetMapping
    fun getAll(): ResponseEntity<ApiResponse<List<ClinicRecord>>> {
        log.debug { "GET all clinics" }
        val clinics = transaction { clinicRepository.findAll() }
        return ResponseEntity.ok(ApiResponse.ok(clinics))
    }

    /**
     * 특정 클리닉 정보를 조회합니다.
     *
     * @param clinicId 클리닉 ID
     * @return 클리닉 정보
     */
    @GetMapping("/{clinicId}")
    fun getById(
        @PathVariable clinicId: Long,
    ): ResponseEntity<ApiResponse<ClinicRecord>> {
        clinicId.requirePositiveNumber("clinicId")
        log.debug { "GET clinic id=$clinicId" }
        val clinic = runCatching { transaction { clinicRepository.findById(clinicId) } }
            .getOrNull() ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(ApiResponse.ok(clinic))
    }

    /**
     * 클리닉의 운영 시간 목록을 조회합니다.
     *
     * @param clinicId 클리닉 ID
     * @return 요일별 운영 시간 목록
     */
    @GetMapping("/{clinicId}/operating-hours")
    fun getOperatingHours(
        @PathVariable clinicId: Long,
    ): ResponseEntity<ApiResponse<List<OperatingHoursRecord>>> {
        clinicId.requirePositiveNumber("clinicId")
        log.debug { "GET operating hours clinicId=$clinicId" }
        val hours = transaction { clinicRepository.findAllOperatingHours(clinicId) }
        return ResponseEntity.ok(ApiResponse.ok(hours))
    }

    /**
     * 클리닉의 기본 휴식 시간 목록을 조회합니다.
     *
     * @param clinicId 클리닉 ID
     * @return 기본 휴식 시간 목록
     */
    @GetMapping("/{clinicId}/break-times")
    fun getBreakTimes(
        @PathVariable clinicId: Long,
    ): ResponseEntity<ApiResponse<List<ClinicDefaultBreakTimeRecord>>> {
        clinicId.requirePositiveNumber("clinicId")
        log.debug { "GET break times clinicId=$clinicId" }
        val breakTimes = transaction { clinicRepository.findDefaultBreakTimes(clinicId) }
        return ResponseEntity.ok(ApiResponse.ok(breakTimes))
    }
}
