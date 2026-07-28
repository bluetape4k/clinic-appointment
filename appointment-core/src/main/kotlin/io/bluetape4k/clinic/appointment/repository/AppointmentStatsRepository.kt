package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotNull
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select
import java.io.Serializable
import java.time.LocalDate

/**
 * Per-row result of a doctor-level status aggregation.
 *
 * Uses a named data class instead of Triple to prevent positional confusion
 * between two Long fields (doctorId, count). See CLAUDE.md same-type parameter rule.
 *
 * ## Behavior / Contract
 * - One row per (doctorId, status) combination within the query date range.
 * - count is always ≥ 1.
 * - Callers must invoke this inside a `transaction {}` block.
 */
data class DoctorStatusCount(
    val doctorId: Long,
    val status: AppointmentState,
    val count: Long,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * Aggregate-query repository for the admin dashboard stats API.
 *
 * ## Behavior / Contract
 * - All methods must be called inside a `transaction {}` block.
 * - No SQL LIMIT or ORDER BY is applied — callers own sorting and pagination.
 */
class AppointmentStatsRepository {
    companion object : KLogging()

    /**
     * Returns appointment counts grouped by (date, status) for a given clinic and date range.
     *
     * ## Behavior / Contract
     * - When [statuses] is null all status values are included.
     * - Returns an empty list when no appointments match the criteria.
     * - Result rows are in database-natural order; callers sort as needed.
     * - Must be called inside `transaction {}`.
     *
     * @param clinicId target clinic
     * @param dateRange inclusive date range (from..to)
     * @param statuses optional status filter; null means all statuses
     * @return list of (date, status, count) triples
     */
    fun countByDateAndStatus(
        clinicId: Long,
        dateRange: ClosedRange<LocalDate>,
        statuses: List<AppointmentState>? = null,
    ): List<Triple<LocalDate, AppointmentState, Long>> {
        val countExpr = Appointments.id.count()
        return Appointments
            .select(Appointments.appointmentDate, Appointments.status, countExpr)
            .where { Appointments.clinicId eq clinicId }
            .andWhere { Appointments.appointmentDate greaterEq dateRange.start }
            .andWhere { Appointments.appointmentDate lessEq dateRange.endInclusive }
            .andWhere { Appointments.appointmentDate.isNotNull() }
            .let { query ->
                if (statuses != null) query.andWhere { Appointments.status inList statuses }
                else query
            }
            .groupBy(Appointments.appointmentDate, Appointments.status)
            .map { row ->
                Triple(
                    row[Appointments.appointmentDate].requireNotNull("appointmentDate"),
                    row[Appointments.status],
                    row[countExpr],
                )
            }
    }

    /**
     * Returns appointment counts grouped by (doctorId, status) for a given clinic and date range.
     *
     * ## Behavior / Contract
     * - No SQL LIMIT or ORDER BY is applied. The service layer handles ranking via
     *   `groupBy { it.doctorId }`, `sortedByDescending { totalAppointments }`, and `take(limit)`.
     *   Applying SQL LIMIT here would truncate (doctorId, status) rows before the service
     *   can aggregate per-doctor totals, producing incorrect rankings.
     * - Must be called inside `transaction {}`.
     *
     * @param clinicId target clinic
     * @param dateRange inclusive date range (from..to)
     * @return list of [DoctorStatusCount] — one entry per (doctorId, status) combination
     */
    fun countByDoctorAndStatus(
        clinicId: Long,
        dateRange: ClosedRange<LocalDate>,
    ): List<DoctorStatusCount> {
        val countExpr = Appointments.id.count()
        return Appointments
            .select(Appointments.doctorId, Appointments.status, countExpr)
            .where { Appointments.clinicId eq clinicId }
            .andWhere { Appointments.appointmentDate greaterEq dateRange.start }
            .andWhere { Appointments.appointmentDate lessEq dateRange.endInclusive }
            .andWhere { Appointments.doctorId.isNotNull() }
            .groupBy(Appointments.doctorId, Appointments.status)
            .map { row ->
                DoctorStatusCount(
                    doctorId = row[Appointments.doctorId].requireNotNull("doctorId").value,
                    status = row[Appointments.status],
                    count = row[countExpr],
                )
            }
    }
}
