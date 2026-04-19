package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.clinic.appointment.api.dto.ApiResponse
import io.bluetape4k.clinic.appointment.model.dto.DoctorAbsenceRecord
import io.bluetape4k.clinic.appointment.model.dto.DoctorRecord
import io.bluetape4k.clinic.appointment.model.dto.DoctorScheduleRecord
import io.bluetape4k.clinic.appointment.repository.DoctorRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requirePositiveNumber
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
 */
@RestController
@RequestMapping("/api")
class DoctorController(
    private val doctorRepository: DoctorRepository,
) {
    companion object : KLogging()

    /**
     * 병원의 의사 목록을 조회합니다.
     *
     * @param clinicId 병원 ID
     * @return 의사 목록
     */
    @GetMapping("/clinics/{clinicId}/doctors")
    fun getByClinic(
        @PathVariable clinicId: Long,
    ): ResponseEntity<ApiResponse<List<DoctorRecord>>> {
        clinicId.requirePositiveNumber("clinicId")
        log.debug { "GET doctors clinicId=$clinicId" }
        val doctors = transaction { doctorRepository.findByClinicId(clinicId) }
        return ResponseEntity.ok(ApiResponse.ok(doctors))
    }

    /**
     * 특정 의사 정보를 조회합니다.
     *
     * @param doctorId 의사 ID
     * @return 의사 정보
     */
    @GetMapping("/doctors/{doctorId}")
    fun getById(
        @PathVariable doctorId: Long,
    ): ResponseEntity<ApiResponse<DoctorRecord>> {
        doctorId.requirePositiveNumber("doctorId")
        log.debug { "GET doctor id=$doctorId" }
        val doctor = runCatching { transaction { doctorRepository.findById(doctorId) } }
            .getOrNull() ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(ApiResponse.ok(doctor))
    }

    /**
     * 의사의 운영 스케줄 목록을 조회합니다.
     *
     * @param doctorId 의사 ID
     * @return 요일별 운영 스케줄 목록
     */
    @GetMapping("/doctors/{doctorId}/schedules")
    fun getSchedules(
        @PathVariable doctorId: Long,
    ): ResponseEntity<ApiResponse<List<DoctorScheduleRecord>>> {
        doctorId.requirePositiveNumber("doctorId")
        log.debug { "GET schedules doctorId=$doctorId" }
        val schedules = transaction { doctorRepository.findAllSchedules(doctorId) }
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
    @GetMapping("/doctors/{doctorId}/absences")
    fun getAbsences(
        @PathVariable doctorId: Long,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ): ResponseEntity<ApiResponse<List<DoctorAbsenceRecord>>> {
        doctorId.requirePositiveNumber("doctorId")
        log.debug { "GET absences doctorId=$doctorId, from=$from, to=$to" }
        val absences = transaction { doctorRepository.findAbsencesByDateRange(doctorId, from..to) }
        return ResponseEntity.ok(ApiResponse.ok(absences))
    }
}
