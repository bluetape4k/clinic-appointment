package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.clinic.appointment.api.dto.ApiResponse
import io.bluetape4k.clinic.appointment.model.dto.EquipmentRecord
import io.bluetape4k.clinic.appointment.repository.EquipmentRepository
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
 * 장비 정보 조회 REST 컨트롤러.
 *
 * 병원별 장비 목록 및 개별 장비 정보 조회 API를 제공합니다.
 *
 * @param equipmentRepository 장비 Repository
 */
@RestController
@RequestMapping("/api")
class EquipmentController(
    private val equipmentRepository: EquipmentRepository,
) {
    companion object : KLogging()

    /**
     * 병원의 장비 목록을 조회합니다.
     *
     * @param clinicId 병원 ID
     * @return 장비 목록
     */
    @GetMapping("/clinics/{clinicId}/equipments")
    fun getByClinic(
        @PathVariable clinicId: Long,
    ): ResponseEntity<ApiResponse<List<EquipmentRecord>>> {
        clinicId.requirePositiveNumber("clinicId")
        log.debug { "GET equipments clinicId=$clinicId" }
        val equipments = transaction { equipmentRepository.findByClinicId(clinicId) }
        return ResponseEntity.ok(ApiResponse.ok(equipments))
    }

    /**
     * 특정 장비 정보를 조회합니다.
     *
     * @param equipmentId 장비 ID
     * @return 장비 정보
     */
    @GetMapping("/equipments/{equipmentId}")
    fun getById(
        @PathVariable equipmentId: Long,
    ): ResponseEntity<ApiResponse<EquipmentRecord>> {
        equipmentId.requirePositiveNumber("equipmentId")
        log.debug { "GET equipment id=$equipmentId" }
        val equipment = runCatching { transaction { equipmentRepository.findById(equipmentId) } }
            .getOrNull() ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(ApiResponse.ok(equipment))
    }
}
