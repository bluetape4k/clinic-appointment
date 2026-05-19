package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.clinic.appointment.api.dto.ApiResponse
import io.bluetape4k.clinic.appointment.api.tenant.TenantClinicAccessChecker
import io.bluetape4k.clinic.appointment.model.dto.TreatmentTypeRecord
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import io.bluetape4k.clinic.appointment.repository.TreatmentTypeRepository
import io.bluetape4k.exposed.core.ExposedPage
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requirePositiveNumber
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse as OApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 진료 유형 조회 REST 컨트롤러.
 *
 * 병원별 진료 유형 목록, 개별 진료 유형 정보, 필요 장비 조회 API를 제공합니다.
 *
 * @param treatmentTypeRepository 진료 유형 Repository
 */
@Tag(name = "Treatment Types", description = "Treatment type management")
@RestController
@RequestMapping("/api/{tenantCode}")
class TreatmentTypeController(
    private val treatmentTypeRepository: TreatmentTypeRepository,
    private val tenantClinicAccessChecker: TenantClinicAccessChecker,
) {
    companion object : KLogging()

    /**
     * 병원의 진료 유형 목록을 페이징 조회합니다.
     *
     * @param clinicId 병원 ID
     * @param page 페이지 번호 (0-based, default 0)
     * @param size 페이지 크기 (default 20, max 100)
     * @return 페이징된 진료 유형 목록
     */
    @Operation(summary = "Get treatment types by clinic with pagination")
    @ApiResponses(
        OApiResponse(responseCode = "200", description = "Success"),
        OApiResponse(responseCode = "400", description = "Invalid parameters"),
    )
    @GetMapping("/clinics/{clinicId}/treatment-types")
    fun getByClinic(
        @PathVariable tenantCode: String,
        @PathVariable clinicId: Long,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ResponseEntity<ApiResponse<ExposedPage<TreatmentTypeRecord>>> {
        clinicId.requirePositiveNumber("clinicId")
        tenantClinicAccessChecker.verifyClinic(tenantCode, clinicId)
        val pageNumber = page.coerceAtLeast(0)
        val pageSize = size.coerceIn(1, PaginationDefaults.MAX_PAGE_SIZE)
        log.debug { "GET treatment types tenantCode=$tenantCode, clinicId=$clinicId, page=$pageNumber, size=$pageSize" }
        val result = transaction { treatmentTypeRepository.findPage(pageNumber, pageSize) { TreatmentTypes.clinicId eq clinicId } }
        return ResponseEntity.ok(ApiResponse.ok(result))
    }

    /**
     * 특정 진료 유형 정보를 조회합니다.
     *
     * @param treatmentTypeId 진료 유형 ID
     * @return 진료 유형 정보
     */
    @Operation(summary = "Get treatment type by ID")
    @ApiResponses(
        OApiResponse(responseCode = "200", description = "Success"),
        OApiResponse(responseCode = "400", description = "Invalid parameters"),
        OApiResponse(responseCode = "404", description = "Treatment type not found"),
    )
    @GetMapping("/treatment-types/{treatmentTypeId}")
    fun getById(
        @PathVariable tenantCode: String,
        @PathVariable treatmentTypeId: Long,
    ): ResponseEntity<ApiResponse<TreatmentTypeRecord>> {
        treatmentTypeId.requirePositiveNumber("treatmentTypeId")
        val tenant = tenantClinicAccessChecker.requireTenant(tenantCode)
        log.debug { "GET treatment type tenantCode=$tenantCode, id=$treatmentTypeId" }
        val type = runCatching { transaction { treatmentTypeRepository.findByIdAndTenant(treatmentTypeId, tenant.id) } }
            .getOrNull() ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(ApiResponse.ok(type))
    }
}
