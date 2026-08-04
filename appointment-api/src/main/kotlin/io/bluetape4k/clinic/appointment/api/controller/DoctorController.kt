package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.clinic.appointment.api.dto.ApiResponse
import io.bluetape4k.clinic.appointment.api.tenant.TenantClinicAccessChecker
import io.bluetape4k.clinic.appointment.model.dto.DoctorAbsenceRecord
import io.bluetape4k.clinic.appointment.model.dto.DoctorRecord
import io.bluetape4k.clinic.appointment.model.dto.DoctorScheduleRecord
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import io.bluetape4k.clinic.appointment.repository.DoctorRepository
import io.bluetape4k.exposed.core.ExposedPage
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requirePositiveNumber
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse as OApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/**
 * 의사 정보 조회 REST 컨트롤러.
 *
 * 병원별 의사 목록, 개별 의사 정보, 운영 스케줄, 휴무 정보 조회 API를 제공합니다.
 *
 * @param doctorRepository 의사 Repository
 */
@Tag(name = "Doctors", description = "Doctor management")
@RestController
@RequestMapping("/api/{tenantCode}")
class DoctorController(
    private val doctorRepository: DoctorRepository,
    private val tenantClinicAccessChecker: TenantClinicAccessChecker,
) {
    companion object : KLogging()

    /**
     * 병원의 의사 목록을 페이징 조회합니다.
     *
     * @param clinicId 병원 ID
     * @param page 페이지 번호 (0-based, default 0)
     * @param size 페이지 크기 (default 20, max 100)
     * @return 페이징된 의사 목록
     */
    @Operation(summary = "Get doctors by clinic with pagination")
    @ApiResponses(
        OApiResponse(responseCode = "200", description = "Success"),
        OApiResponse(responseCode = "400", description = "Invalid parameters"),
    )
    @GetMapping("/clinics/{clinicId}/doctors")
    fun getByClinic(
        @PathVariable tenantCode: String,
        @PathVariable clinicId: Long,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ResponseEntity<ApiResponse<ExposedPage<DoctorRecord>>> {
        clinicId.requirePositiveNumber("clinicId")
        val tenant = tenantClinicAccessChecker.verifyClinic(tenantCode, clinicId)
        val pageNumber = page.coerceAtLeast(0)
        val pageSize = size.coerceIn(1, PaginationDefaults.MAX_PAGE_SIZE)
        log.debug { "GET doctors tenantCode=$tenantCode, clinicId=$clinicId, page=$pageNumber, size=$pageSize" }
        val result = transaction { doctorRepository.findPage(TenantClinicScope(tenant.id, clinicId), pageNumber, pageSize) }
        return ResponseEntity.ok(ApiResponse.ok(result))
    }

    /**
     * 특정 의사 정보를 조회합니다.
     *
     * @param doctorId 의사 ID
     * @return 의사 정보
     */
    @Operation(summary = "Get doctor by ID")
    @ApiResponses(
        OApiResponse(responseCode = "200", description = "Success"),
        OApiResponse(responseCode = "400", description = "Invalid parameters"),
        OApiResponse(responseCode = "404", description = "Doctor not found"),
    )
    @GetMapping("/doctors/{doctorId}")
    fun getById(
        @PathVariable tenantCode: String,
        @PathVariable doctorId: Long,
    ): ResponseEntity<ApiResponse<DoctorRecord>> {
        doctorId.requirePositiveNumber("doctorId")
        val tenant = tenantClinicAccessChecker.requireTenant(tenantCode)
        log.debug { "GET doctor tenantCode=$tenantCode, id=$doctorId" }
        val doctor = runCatching { transaction { doctorRepository.findByIdAndTenant(doctorId, tenant.id) } }
            .getOrNull() ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(ApiResponse.ok(doctor))
    }

    /**
     * 의사의 운영 스케줄 목록을 조회합니다.
     *
     * @param doctorId 의사 ID
     * @return 요일별 운영 스케줄 목록
     */
    @Operation(summary = "Get schedules for a doctor")
    @ApiResponses(
        OApiResponse(responseCode = "200", description = "Success"),
        OApiResponse(responseCode = "400", description = "Invalid parameters"),
    )
    @GetMapping("/doctors/{doctorId}/schedules")
    fun getSchedules(
        @PathVariable tenantCode: String,
        @PathVariable doctorId: Long,
    ): ResponseEntity<ApiResponse<List<DoctorScheduleRecord>>> {
        doctorId.requirePositiveNumber("doctorId")
        val tenant = tenantClinicAccessChecker.requireTenant(tenantCode)
        val doctor = transaction { doctorRepository.findByIdAndTenant(doctorId, tenant.id) }
            ?: return ResponseEntity.notFound().build()
        log.debug { "GET schedules tenantCode=$tenantCode, doctorId=$doctorId" }
        val schedules = transaction {
            doctorRepository.findAllSchedules(TenantClinicScope(tenant.id, doctor.clinicId), doctorId)
        }
        return ResponseEntity.ok(ApiResponse.ok(schedules))
    }

    /**
     * 의사의 휴무 정보를 조회합니다.
     *
     * @param doctorId 의사 ID
     * @param from 조회 시작 날짜
     * @param to 조회 종료 날짜
     * @return 휴무 정보 목록
     */
    @Operation(summary = "Get absences for a doctor within date range")
    @ApiResponses(
        OApiResponse(responseCode = "200", description = "Success"),
        OApiResponse(responseCode = "400", description = "Invalid parameters"),
    )
    @GetMapping("/doctors/{doctorId}/absences")
    fun getAbsences(
        @PathVariable tenantCode: String,
        @PathVariable doctorId: Long,
        @Parameter(description = "Start date (ISO format)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @Parameter(description = "End date (ISO format)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ): ResponseEntity<ApiResponse<List<DoctorAbsenceRecord>>> {
        doctorId.requirePositiveNumber("doctorId")
        val tenant = tenantClinicAccessChecker.requireTenant(tenantCode)
        val doctor = transaction { doctorRepository.findByIdAndTenant(doctorId, tenant.id) }
            ?: return ResponseEntity.notFound().build()
        log.debug { "GET absences tenantCode=$tenantCode, doctorId=$doctorId, from=$from, to=$to" }
        val absences = transaction {
            doctorRepository.findAbsencesByDateRange(TenantClinicScope(tenant.id, doctor.clinicId), doctorId, from..to)
        }
        return ResponseEntity.ok(ApiResponse.ok(absences))
    }
}
