package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.clinic.appointment.api.dto.ApiResponse
import io.bluetape4k.clinic.appointment.model.dto.TreatmentTypeRecord
import io.bluetape4k.clinic.appointment.repository.TreatmentTypeRepository
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
 * 진료 유형 조회 REST 컨트롤러.
 *
 * 병원별 진료 유형 목록, 개별 진료 유형 정보, 필요 장비 조회 API를 제공합니다.
 */
@RestController
@RequestMapping("/api")
class TreatmentTypeController(
    private val treatmentTypeRepository: TreatmentTypeRepository,
) {
    companion object : KLogging()

    /**
     * 병원의 진료 유형 목록을 조회합니다.
     *
     * @param clinicId 병원 ID
     * @return 진료 유형 목록
     */
    @GetMapping("/clinics/{clinicId}/treatment-types")
    fun getByClinic(
        @PathVariable clinicId: Long,
    ): ResponseEntity<ApiResponse<List<TreatmentTypeRecord>>> {
        clinicId.requirePositiveNumber("clinicId")
        log.debug { "GET treatment types clinicId=$clinicId" }
        val types = transaction { treatmentTypeRepository.findByClinicId(clinicId) }
        return ResponseEntity.ok(ApiResponse.ok(types))
    }

    /**
     * 특정 진료 유형 정보를 조회합니다.
     *
     * @param treatmentTypeId 진료 유형 ID
     * @return 진료 유형 정보
     */
    @GetMapping("/treatment-types/{treatmentTypeId}")
    fun getById(
        @PathVariable treatmentTypeId: Long,
    ): ResponseEntity<ApiResponse<TreatmentTypeRecord>> {
        treatmentTypeId.requirePositiveNumber("treatmentTypeId")
        log.debug { "GET treatment type id=$treatmentTypeId" }
        val type = runCatching { transaction { treatmentTypeRepository.findById(treatmentTypeId) } }
            .getOrNull() ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(ApiResponse.ok(type))
    }

}
