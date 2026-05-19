package io.bluetape4k.clinic.appointment.api.service

import io.bluetape4k.clinic.appointment.api.dto.AppointmentStatsResponse
import io.bluetape4k.clinic.appointment.api.dto.CancellationStatsResponse
import io.bluetape4k.clinic.appointment.api.dto.DailyAppointmentBucket
import io.bluetape4k.clinic.appointment.api.dto.DailyCancellationBucket
import io.bluetape4k.clinic.appointment.api.dto.DoctorBucket
import io.bluetape4k.clinic.appointment.api.dto.DoctorStatsResponse
import io.bluetape4k.clinic.appointment.repository.AppointmentStatsRepository
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requirePositiveNumber
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Admin dashboard aggregation service.
 *
 * ## Behavior / Contract
 * - All three methods validate `clinicId > 0`, `from <= to`, and period `<= 366` days.
 * - Default period when `from`/`to` are null: `[today-29, today]` (30 days inclusive).
 * - Registered as a Spring bean via [io.bluetape4k.clinic.appointment.api.config.ServiceConfig];
 *   no `@Service` annotation — do not add it.
 */
class DashboardStatsService(
    private val statsRepository: AppointmentStatsRepository,
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
     * Returns daily appointment counts grouped by status.
     *
     * ## Behavior / Contract
     * - `statuses` filters which statuses are counted; null means all statuses.
     * - Unknown status name in `statuses` throws [IllegalArgumentException] (propagated from
     *   [AppointmentState.fromName]).
     * - An unknown `clinicId` returns an empty response (HTTP 200), not an error.
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
            statsRepository.countByDateAndStatus(clinicId, effectiveFrom..effectiveTo, statusFilter)
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
     * Returns per-doctor appointment metrics, sorted by total appointments descending.
     *
     * ## Behavior / Contract
     * - `limit` must be in `1..100`; otherwise throws [IllegalArgumentException].
     * - `completionRate` = completed / (completed + cancelled + noShow); 0.0 when denominator is 0.
     * - Sorting and `take(limit)` happen in Kotlin after DB aggregation — SQL LIMIT is not applied
     *   at the query level because SQL LIMIT would operate on (doctorId, status) rows, not on
     *   doctor-level totals.
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
     * Returns cancellation and no-show trends.
     *
     * ## Behavior / Contract
     * - Denominator for rates: CANCELLED + NO_SHOW + RESCHEDULED + COMPLETED (terminal statuses).
     *   In-progress statuses are excluded to avoid noise.
     * - `cancellationRate` = CANCELLED / denominator; 0.0 when denominator is 0.
     * - `noShowRate` = NO_SHOW / denominator; 0.0 when denominator is 0.
     * - Daily buckets contain only dates with at least one of CANCELLED, NO_SHOW, or RESCHEDULED > 0.
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
            statsRepository.countByDateAndStatus(clinicId, effectiveFrom..effectiveTo, CANCELLATION_STATUSES)
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
}
