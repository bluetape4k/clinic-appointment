package io.bluetape4k.clinic.appointment.api.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDate

/**
 * GET /api/{tenantCode}/admin/stats/doctors 응답입니다.
 *
 * ## 동작 / 계약
 * - [doctors]는 [DoctorBucket.totalAppointments] 내림차순으로 정렬합니다(서비스 계층의 책임).
 * - 목록은 `limit` query parameter로 지정한 상위 N명의 의사로 제한합니다.
 * - Doctor names are not included; callers resolve ID→name via DoctorService.
 */
@Schema(description = "Per-doctor appointment counts and completion rates for the given clinic and date range")
data class DoctorStatsResponse(
    @Schema(description = "Target clinic ID")
    val clinicId: Long,
    @Schema(description = "Inclusive start date of the query range")
    val from: LocalDate,
    @Schema(description = "Inclusive end date of the query range")
    val to: LocalDate,
    @Schema(description = "Top-N doctors sorted by totalAppointments descending")
    val doctors: List<DoctorBucket>,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 조회 기간 동안 한 명의 의사에 대한 예약 지표입니다.
 *
 * ## 동작 / 계약
 * - [completionRate] = completed / (completed + cancelled + noShow)입니다.
 * - 분모가 0이면(종결 예약이 없으면) [completionRate]는 0.0입니다.
 * - [totalAppointments]에는 종결 상태뿐 아니라 모든 상태가 포함됩니다.
 */
@Schema(description = "Appointment metrics for a single doctor")
data class DoctorBucket(
    @Schema(description = "Doctor ID")
    val doctorId: Long,
    @Schema(description = "Total appointments across all statuses in the period")
    val totalAppointments: Long,
    @Schema(description = "Count of COMPLETED appointments")
    val completed: Long,
    @Schema(description = "Count of CANCELLED appointments")
    val cancelled: Long,
    @Schema(description = "Count of NO_SHOW appointments")
    val noShow: Long,
    @Schema(description = "completed / (completed + cancelled + noShow); 0.0 when denominator is 0")
    val completionRate: Double,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
