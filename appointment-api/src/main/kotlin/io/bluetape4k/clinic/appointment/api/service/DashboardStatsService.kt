package io.bluetape4k.clinic.appointment.api.service

import io.bluetape4k.clinic.appointment.api.dto.AppointmentStatsResponse
import io.bluetape4k.clinic.appointment.api.dto.CancellationStatsResponse
import io.bluetape4k.clinic.appointment.api.dto.DailyAppointmentBucket
import io.bluetape4k.clinic.appointment.api.dto.DailyCancellationBucket
import io.bluetape4k.clinic.appointment.api.dto.DoctorBucket
import io.bluetape4k.clinic.appointment.api.dto.DoctorStatsResponse
import io.bluetape4k.clinic.appointment.api.stats.AppointmentStatsProjectionRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentStatsRepository
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requirePositiveNumber
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 관리자 대시보드 집계 서비스입니다.
 *
 * ## 동작 / 계약
 * - 세 메서드 모두 `clinicId > 0`, `from <= to`, 기간 `<= 366`일을 검증합니다.
 * - `from`/`to`가 null이면 `[today-29, today]`(양끝 포함 30일)를 기본 기간으로 사용합니다.
 * - [io.bluetape4k.clinic.appointment.api.config.ServiceConfig]를 통해 Spring bean으로 등록하며,
 *   `@Service` annotation은 사용하지 않습니다.
 */
class DashboardStatsService(
    private val statsRepository: AppointmentStatsRepository,
    private val projectionRepository: AppointmentStatsProjectionRepository? = null,
) {
    companion object : KLogging() {
        private const val DEFAULT_DAYS = 29L
        private const val MAX_PERIOD_DAYS = 366L
        private val ALLOWED_LIMIT = 1..100

        private val CANCELLATION_STATUSES = listOf(
            AppointmentState.CANCELLED,
            AppointmentState.NO_SHOW,
            AppointmentState.RESCHEDULED,
            AppointmentState.COMPLETED,
        )
    }

    /**
     * 상태별 일일 예약 건수를 반환합니다.
     *
     * ## 동작 / 계약
     * - `statuses`는 집계할 상태를 필터링하며, null이면 모든 상태를 사용합니다.
     * - `statuses`에 알 수 없는 상태 이름이 있으면 [IllegalArgumentException]을 던집니다
     *   ([AppointmentState.fromName]에서 전파됩니다).
     * - 알 수 없는 `clinicId`에는 오류가 아닌 빈 응답(HTTP 200)을 반환합니다.
     */
    fun getAppointmentStats(
        clinicId: Long,
        from: LocalDate?,
        to: LocalDate?,
        statuses: List<String>? = null,
    ): AppointmentStatsResponse {
        clinicId.requirePositiveNumber("clinicId")
        val effectiveTo = to ?: LocalDate.now()
        val effectiveFrom = from ?: effectiveTo.minusDays(DEFAULT_DAYS)

        require(!effectiveFrom.isAfter(effectiveTo)) { "from must be on or before to" }
        require(ChronoUnit.DAYS.between(effectiveFrom, effectiveTo) <= MAX_PERIOD_DAYS) {
            "period exceeds 366 days"
        }

        val statusFilter = statuses?.map { AppointmentState.fromName(it) }

        log.debug { "getAppointmentStats: clinicId=$clinicId, $effectiveFrom..$effectiveTo, statuses=$statuses" }

        val rows = transaction {
            projectionRows(clinicId, effectiveFrom..effectiveTo, statusFilter)
                ?: statsRepository.countByDateAndStatus(clinicId, effectiveFrom..effectiveTo, statusFilter)
        }

        val byDate = rows.groupBy { it.first }
        val daily = byDate.entries
            .sortedBy { it.key }
            .map { (date, entries) ->
                val countsByStatus = entries.associate { it.second.name to it.third }
                DailyAppointmentBucket(
                    date = date,
                    countsByStatus = countsByStatus,
                    total = countsByStatus.values.sum(),
                )
            }

        val totals = rows.groupBy { it.second.name }
            .mapValues { (_, entries) -> entries.sumOf { it.third } }

        return AppointmentStatsResponse(
            clinicId = clinicId,
            from = effectiveFrom,
            to = effectiveTo,
            totals = totals,
            daily = daily,
        )
    }

    /**
     * 의사별 예약 지표를 전체 예약 건수 내림차순으로 정렬해 반환합니다.
     *
     * ## 동작 / 계약
     * - `limit`은 `1..100` 범위여야 하며, 그렇지 않으면 [IllegalArgumentException]을 던집니다.
     * - `completionRate` = completed / (completed + cancelled + noShow)이며, 분모가 0이면 0.0입니다.
     * - 정렬과 `take(limit)`은 DB 집계 후 Kotlin에서 수행합니다. SQL LIMIT은
     *   의사별 전체 합계가 아닌 (doctorId, status) 행에 적용되므로 query level에서 사용하지 않습니다.
     */
    fun getDoctorStats(
        clinicId: Long,
        from: LocalDate?,
        to: LocalDate?,
        limit: Int = 20,
    ): DoctorStatsResponse {
        clinicId.requirePositiveNumber("clinicId")
        require(limit in ALLOWED_LIMIT) { "limit must be between 1 and 100, got $limit" }
        val effectiveTo = to ?: LocalDate.now()
        val effectiveFrom = from ?: effectiveTo.minusDays(DEFAULT_DAYS)

        require(!effectiveFrom.isAfter(effectiveTo)) { "from must be on or before to" }
        require(ChronoUnit.DAYS.between(effectiveFrom, effectiveTo) <= MAX_PERIOD_DAYS) {
            "period exceeds 366 days"
        }

        log.debug { "getDoctorStats: clinicId=$clinicId, $effectiveFrom..$effectiveTo, limit=$limit" }

        val rows = transaction {
            statsRepository.countByDoctorAndStatus(clinicId, effectiveFrom..effectiveTo)
        }

        val doctors = rows.groupBy { it.doctorId }
            .map { (doctorId, entries) ->
                val countByStatus = entries.associate { it.status to it.count }
                val completed = countByStatus[AppointmentState.COMPLETED] ?: 0L
                val cancelled = countByStatus[AppointmentState.CANCELLED] ?: 0L
                val noShow = countByStatus[AppointmentState.NO_SHOW] ?: 0L
                val total = entries.sumOf { it.count }
                val denominator = completed + cancelled + noShow
                val completionRate = if (denominator == 0L) 0.0 else completed.toDouble() / denominator

                DoctorBucket(
                    doctorId = doctorId,
                    totalAppointments = total,
                    completed = completed,
                    cancelled = cancelled,
                    noShow = noShow,
                    completionRate = completionRate,
                )
            }
            .sortedByDescending { it.totalAppointments }
            .take(limit)

        return DoctorStatsResponse(
            clinicId = clinicId,
            from = effectiveFrom,
            to = effectiveTo,
            doctors = doctors,
        )
    }

    /**
     * 취소 및 no-show 추이를 반환합니다.
     *
     * ## 동작 / 계약
     * - 비율의 분모는 CANCELLED + NO_SHOW + RESCHEDULED + COMPLETED(종결 상태)입니다.
     *   진행 중인 상태는 잡음을 줄이기 위해 제외합니다.
     * - `cancellationRate` = CANCELLED / 분모이며, 분모가 0이면 0.0입니다.
     * - `noShowRate` = NO_SHOW / 분모이며, 분모가 0이면 0.0입니다.
     * - 일일 bucket에는 CANCELLED, NO_SHOW, RESCHEDULED 중 하나 이상이 0보다 큰 날짜만 포함합니다.
     */
    fun getCancellationStats(
        clinicId: Long,
        from: LocalDate?,
        to: LocalDate?,
    ): CancellationStatsResponse {
        clinicId.requirePositiveNumber("clinicId")
        val effectiveTo = to ?: LocalDate.now()
        val effectiveFrom = from ?: effectiveTo.minusDays(DEFAULT_DAYS)

        require(!effectiveFrom.isAfter(effectiveTo)) { "from must be on or before to" }
        require(ChronoUnit.DAYS.between(effectiveFrom, effectiveTo) <= MAX_PERIOD_DAYS) {
            "period exceeds 366 days"
        }

        log.debug { "getCancellationStats: clinicId=$clinicId, $effectiveFrom..$effectiveTo" }

        val rows = transaction {
            projectionRows(clinicId, effectiveFrom..effectiveTo, CANCELLATION_STATUSES)
                ?: statsRepository.countByDateAndStatus(clinicId, effectiveFrom..effectiveTo, CANCELLATION_STATUSES)
        }

        val countByStatus = rows.groupBy { it.second }
            .mapValues { (_, entries) -> entries.sumOf { it.third } }

        val totalCancelled = countByStatus[AppointmentState.CANCELLED] ?: 0L
        val totalNoShow = countByStatus[AppointmentState.NO_SHOW] ?: 0L
        val totalRescheduled = countByStatus[AppointmentState.RESCHEDULED] ?: 0L
        val totalCompleted = countByStatus[AppointmentState.COMPLETED] ?: 0L

        val denominator = totalCancelled + totalNoShow + totalRescheduled + totalCompleted
        val cancellationRate = if (denominator == 0L) 0.0 else totalCancelled.toDouble() / denominator
        val noShowRate = if (denominator == 0L) 0.0 else totalNoShow.toDouble() / denominator

        val daily = rows.groupBy { it.first }
            .entries
            .sortedBy { it.key }
            .mapNotNull { (date, entries) ->
                val byStatus = entries.associate { it.second to it.third }
                val cancelled = byStatus[AppointmentState.CANCELLED] ?: 0L
                val noShow = byStatus[AppointmentState.NO_SHOW] ?: 0L
                val rescheduled = byStatus[AppointmentState.RESCHEDULED] ?: 0L
                if (cancelled == 0L && noShow == 0L && rescheduled == 0L) null
                else DailyCancellationBucket(date = date, cancelled = cancelled, noShow = noShow, rescheduled = rescheduled)
            }

        return CancellationStatsResponse(
            clinicId = clinicId,
            from = effectiveFrom,
            to = effectiveTo,
            totalCancelled = totalCancelled,
            totalNoShow = totalNoShow,
            totalRescheduled = totalRescheduled,
            totalCompleted = totalCompleted,
            cancellationRate = cancellationRate,
            noShowRate = noShowRate,
            daily = daily,
        )
    }

    /**
     * 대시보드의 권위 있는 집계는 현재 예약 row의 `Appointments.appointmentDate`와
     * `Appointments.status`를 읽는 `AppointmentStatsRepository`입니다.
     *
     * Kafka 통계 projection은 envelope의 `occurredAt` 날짜와 마지막 event 상태만
     * 보유하며 현재 예약의 appointmentDate를 증명하지 않습니다. 따라서 projection
     * row가 하나라도 있다는 이유로 current-state 집계를 대체하지 않습니다. 이
     * fail-closed 경계는 향후 appointmentDate를 포함한 권위 있는 read model이
     * 준비될 때까지 유지합니다.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun projectionRows(
        clinicId: Long,
        dateRange: ClosedRange<LocalDate>,
        statuses: List<AppointmentState>?,
    ): List<Triple<LocalDate, AppointmentState, Long>>? = null
}
