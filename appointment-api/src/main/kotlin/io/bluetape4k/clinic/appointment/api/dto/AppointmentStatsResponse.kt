package io.bluetape4k.clinic.appointment.api.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDate

/**
 * GET /api/{tenantCode}/admin/stats/appointments 응답입니다.
 *
 * ## 동작 / 계약
 * - [daily]는 날짜 오름차순입니다.
 * - [totals]의 키는 [AppointmentState][io.bluetape4k.clinic.appointment.statemachine.AppointmentState] 이름입니다.
 * - 해당 기간에 예약이 없으면 [daily]는 비어 있고 [totals]의 모든 값은 0입니다.
 */
@Schema(description = "Daily appointment counts grouped by status for the given clinic and date range")
data class AppointmentStatsResponse(
    @Schema(description = "Target clinic ID")
    val clinicId: Long,
    @Schema(description = "Inclusive start date of the query range")
    val from: LocalDate,
    @Schema(description = "Inclusive end date of the query range")
    val to: LocalDate,
    @Schema(description = "Aggregated totals per status across the entire period; keys are AppointmentState names")
    val totals: Map<String, Long>,
    @Schema(description = "Day-by-day breakdown; one entry per date that has at least one appointment")
    val daily: List<DailyAppointmentBucket>,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 상태별 일일 예약 건수입니다.
 *
 * ## 동작 / 계약
 * - [countsByStatus]에는 해당 날짜에 건수가 0보다 큰 상태만 포함합니다.
 * - [total]은 [countsByStatus]의 모든 값의 합과 같습니다.
 */
@Schema(description = "Appointment counts for a single day, broken down by status")
data class DailyAppointmentBucket(
    @Schema(description = "The date this bucket represents")
    val date: LocalDate,
    @Schema(description = "Count per status; keys are AppointmentState names, values are counts (> 0 only)")
    val countsByStatus: Map<String, Long>,
    @Schema(description = "Sum of all status counts for this day")
    val total: Long,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
