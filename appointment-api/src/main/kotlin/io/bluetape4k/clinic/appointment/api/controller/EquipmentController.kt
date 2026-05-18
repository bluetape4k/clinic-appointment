package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.clinic.appointment.api.dto.ApiResponse
import io.bluetape4k.clinic.appointment.model.dto.EquipmentRecord
import io.bluetape4k.clinic.appointment.model.tables.Equipments
import io.bluetape4k.clinic.appointment.repository.EquipmentRepository
import io.bluetape4k.exposed.core.ExposedPage
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requirePositiveNumber
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
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
    companion object : KLogging() {
        private const val MAX_PAGE_SIZE = 100
    }

    /**
     * 병원의 장비 목록을 페이징 조회합니다.
     *
     * @param clinicId 병원 ID
     * @param page 페이지 번호 (0-based, default 0)
     * @param size 페이지 크기 (default 20, max 100)
     * @return 페이징된 장비 목록
     */
    @GetMapping("/clinics/{clinicId}/equipments")
    fun getByClinic(
        @PathVariable clinicId: Long,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ResponseEntity<ApiResponse<ExposedPage<EquipmentRecord>>> {
        clinicId.requirePositiveNumber("clinicId")
        val pageSize = size.coerceIn(1, MAX_PAGE_SIZE)
        log.debug { "GET equipments clinicId=$clinicId, page=$page, size=$pageSize" }
        val result = transaction { equipmentRepository.findPage(page, pageSize) { Equipments.clinicId eq clinicId } }
        return ResponseEntity.ok(ApiResponse.ok(result))
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
