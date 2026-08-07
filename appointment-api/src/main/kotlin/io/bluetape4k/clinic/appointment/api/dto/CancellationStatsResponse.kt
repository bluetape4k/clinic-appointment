package io.bluetape4k.clinic.appointment.api.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDate

/**
 * GET /api/{tenantCode}/admin/stats/cancellations 응답입니다.
 *
 * ## 동작 / 계약
 * - 비율 계산의 분모는 CANCELLED + COMPLETED + NO_SHOW + RESCHEDULED입니다(종결 상태만 포함).
 *   진행 중인 상태(REQUESTED, CONFIRMED 등)는 잡음을 줄이기 위해 제외합니다.
 * - [cancellationRate] = CANCELLED / 분모이며, 분모가 0이면 0.0입니다.
 * - [noShowRate] = NO_SHOW / 분모이며, 분모가 0이면 0.0입니다.
 * - [daily]에는 추적한 예약이 하나 이상 있는 날짜마다 항목 하나를 포함합니다.
 */
@Schema(description = "Cancellation and no-show trends for the given clinic and date range")
data class CancellationStatsResponse(
    @Schema(description = "Target clinic ID")
    val clinicId: Long,
    @Schema(description = "Inclusive start date of the query range")
    val from: LocalDate,
    @Schema(description = "Inclusive end date of the query range")
    val to: LocalDate,
    @Schema(description = "Total CANCELLED appointments in the period")
    val totalCancelled: Long,
    @Schema(description = "Total NO_SHOW appointments in the period")
    val totalNoShow: Long,
    @Schema(description = "Total RESCHEDULED appointments in the period")
    val totalRescheduled: Long,
    @Schema(description = "Total COMPLETED appointments in the period")
    val totalCompleted: Long,
    @Schema(description = "CANCELLED / (CANCELLED + COMPLETED + NO_SHOW + RESCHEDULED); 0.0 when denominator is 0")
    val cancellationRate: Double,
    @Schema(description = "NO_SHOW / (CANCELLED + COMPLETED + NO_SHOW + RESCHEDULED); 0.0 when denominator is 0")
    val noShowRate: Double,
    @Schema(description = "Day-by-day breakdown of terminal-status counts")
    val daily: List<DailyCancellationBucket>,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 종결 상태 예약의 일일 건수입니다.
 *
 * ## 동작 / 계약
 * - COMPLETED는 여기서 추적하지 않으며, 이 bucket은 부정적인 결과에 초점을 둡니다.
 * - [cancelled], [noShow], [rescheduled] 중 하나 이상이 0보다 큰 날짜만 나타납니다.
 */
@Schema(description = "Daily cancellation, no-show, and rescheduled counts")
data class DailyCancellationBucket(
    @Schema(description = "The date this bucket represents")
    val date: LocalDate,
    @Schema(description = "CANCELLED count for this day")
    val cancelled: Long,
    @Schema(description = "NO_SHOW count for this day")
    val noShow: Long,
    @Schema(description = "RESCHEDULED count for this day")
    val rescheduled: Long,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
