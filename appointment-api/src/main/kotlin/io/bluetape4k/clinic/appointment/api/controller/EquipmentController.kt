package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.clinic.appointment.api.dto.ApiResponse
import io.bluetape4k.clinic.appointment.api.dto.KeysetPageResponse
import io.bluetape4k.clinic.appointment.api.service.ClinicKeysetCursorCodec
import io.bluetape4k.clinic.appointment.api.tenant.TenantClinicAccessChecker
import io.bluetape4k.clinic.appointment.model.dto.EquipmentRecord
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import io.bluetape4k.clinic.appointment.repository.EquipmentRepository
import io.bluetape4k.exposed.core.ExposedPage
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requirePositiveNumber
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse as OApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
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
@Tag(name = "Equipments", description = "Equipment management")
@RestController
@RequestMapping("/api/{tenantCode}")
class EquipmentController(
    private val equipmentRepository: EquipmentRepository,
    private val tenantClinicAccessChecker: TenantClinicAccessChecker,
) {
    companion object : KLogging()

    /**
     * 병원의 장비 목록을 페이징 조회합니다.
     *
     * @param clinicId 병원 ID
     * @param page 페이지 번호 (0-based, default 0)
     * @param size 페이지 크기 (default 20, max 100)
     * @return 페이징된 장비 목록
     */
    @Operation(summary = "Get equipments by clinic with pagination")
    @ApiResponses(
        OApiResponse(responseCode = "200", description = "Success"),
        OApiResponse(responseCode = "400", description = "Invalid parameters"),
    )
    @GetMapping("/clinics/{clinicId}/equipments")
    fun getByClinic(
        @PathVariable tenantCode: String,
        @PathVariable clinicId: Long,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ResponseEntity<ApiResponse<ExposedPage<EquipmentRecord>>> {
        clinicId.requirePositiveNumber("clinicId")
        val tenant = tenantClinicAccessChecker.verifyClinic(tenantCode, clinicId)
        val pageNumber = page.coerceAtLeast(0)
        val pageSize = size.coerceIn(1, PaginationDefaults.MAX_PAGE_SIZE)
        log.debug { "GET equipments tenantCode=$tenantCode, clinicId=$clinicId, page=$pageNumber, size=$pageSize" }
        val result = transaction { equipmentRepository.findPage(TenantClinicScope(tenant.id, clinicId), pageNumber, pageSize) }
        return ResponseEntity.ok(ApiResponse.ok(result))
    }

    /** 병원의 장비 목록을 `(clinic_id, id)` keyset cursor로 조회합니다. */
    @Operation(summary = "Get equipments by clinic with keyset cursor")
    @ApiResponses(
        OApiResponse(responseCode = "200", description = "Success"),
        OApiResponse(responseCode = "400", description = "Invalid parameters or cursor"),
    )
    @GetMapping("/clinics/{clinicId}/equipments/cursor")
    fun getByClinicWithCursor(
        @PathVariable tenantCode: String,
        @PathVariable clinicId: Long,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): ResponseEntity<ApiResponse<KeysetPageResponse<EquipmentRecord>>> {
        clinicId.requirePositiveNumber("clinicId")
        val tenant = tenantClinicAccessChecker.verifyClinic(tenantCode, clinicId)
        val decodedCursor = cursor?.let { token -> ClinicKeysetCursorCodec.decode(token) }
        require(decodedCursor == null || decodedCursor.clinicId == clinicId) {
            "cursor clinicId must match path clinicId"
        }
        val pageLimit = limit.coerceIn(1, PaginationDefaults.MAX_PAGE_SIZE)
        log.debug { "GET equipments cursor tenantCode=$tenantCode, clinicId=$clinicId, limit=$pageLimit" }
        val result = transaction {
            equipmentRepository.findKeysetPage(TenantClinicScope(tenant.id, clinicId), decodedCursor, pageLimit)
        }
        return ResponseEntity.ok(
            ApiResponse.ok(
                KeysetPageResponse(
                    items = result.content,
                    nextCursor = result.nextCursor?.let(ClinicKeysetCursorCodec::encode),
                )
            )
        )
    }

    /**
     * 특정 장비 정보를 조회합니다.
     *
     * @param equipmentId 장비 ID
     * @return 장비 정보
     */
    @Operation(summary = "Get equipment by ID")
    @ApiResponses(
        OApiResponse(responseCode = "200", description = "Success"),
        OApiResponse(responseCode = "400", description = "Invalid parameters"),
        OApiResponse(responseCode = "404", description = "Equipment not found"),
    )
    @GetMapping("/equipments/{equipmentId}")
    fun getById(
        @PathVariable tenantCode: String,
        @PathVariable equipmentId: Long,
    ): ResponseEntity<ApiResponse<EquipmentRecord>> {
        equipmentId.requirePositiveNumber("equipmentId")
        val tenant = tenantClinicAccessChecker.requireTenant(tenantCode)
        log.debug { "GET equipment tenantCode=$tenantCode, id=$equipmentId" }
        val equipment = transaction { equipmentRepository.findByIdAndTenant(equipmentId, tenant.id) }
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(ApiResponse.ok(equipment))
    }
}
