package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.clinic.appointment.model.plan.AppointmentPlanStatus
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlans
import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.LocalDate

/**
 * Caller-transaction keyset scans used by policy impact preview.
 *
 * Every method must execute inside a caller-owned Exposed `transaction {}`.
 * Results contain database IDs only, are strictly ordered, and are bounded to
 * at most 1,000 rows so preview cannot materialize an unbounded tenant data
 * set. Callers must re-read each aggregate through its tenant/clinic-qualified
 * repository before evaluating policy.
 */
class SchedulingPolicyImpactRepository {

    /**
     * Scans future, non-terminal appointment IDs for one tenant and clinic.
     *
     * @param tenantGroupId positive tenant boundary, enforced through `Clinics`.
     * @param clinicId positive clinic boundary.
     * @param fromDate inclusive clinic-local appointment date.
     * @param afterAppointmentId exclusive keyset cursor; `0` starts the scan.
     * @param limit requested page size in `1..1000`.
     * @return ascending appointment IDs, never more than [limit].
     */
    fun scanFutureAppointmentIds(
        tenantGroupId: Long,
        clinicId: Long,
        fromDate: LocalDate,
        afterAppointmentId: Long,
        limit: Int,
    ): List<Long> {
        validateScan(tenantGroupId, clinicId, afterAppointmentId, limit)
        return (Appointments innerJoin Clinics)
            .select(Appointments.id)
            .where {
                (Clinics.tenantGroupId eq tenantGroupId) and
                    (Appointments.clinicId eq clinicId) and
                    (Appointments.appointmentDate greaterEq fromDate) and
                    (Appointments.id greater afterAppointmentId) and
                    (Appointments.status inList IMPACT_APPOINTMENT_STATES)
            }
            .orderBy(Appointments.id, SortOrder.ASC)
            .limit(limit)
            .map { it[Appointments.id].value }
    }

    /**
     * Scans active or partially fulfilled purchase-plan IDs for one scope.
     *
     * @param tenantGroupId positive tenant boundary.
     * @param clinicId positive clinic boundary.
     * @param afterPlanId exclusive keyset cursor; `0` starts the scan.
     * @param limit requested page size in `1..1000`.
     * @return ascending plan IDs, never more than [limit].
     */
    fun scanActivePlanIds(
        tenantGroupId: Long,
        clinicId: Long,
        afterPlanId: Long,
        limit: Int,
    ): List<Long> {
        validateScan(tenantGroupId, clinicId, afterPlanId, limit)
        return AppointmentPlans
            .selectAll()
            .where {
                (AppointmentPlans.tenantGroupId eq tenantGroupId) and
                    (AppointmentPlans.clinicId eq clinicId) and
                    (AppointmentPlans.id greater afterPlanId) and
                    (AppointmentPlans.status inList IMPACT_PLAN_STATES)
            }
            .orderBy(AppointmentPlans.id, SortOrder.ASC)
            .limit(limit)
            .map { it[AppointmentPlans.id].value }
    }

    private fun validateScan(
        tenantGroupId: Long,
        clinicId: Long,
        afterId: Long,
        limit: Int,
    ) {
        require(tenantGroupId > 0) { "tenantGroupId must be positive" }
        require(clinicId > 0) { "clinicId must be positive" }
        require(afterId >= 0) { "keyset cursor must be non-negative" }
        require(limit in 1..MAX_SCAN_LIMIT) { "limit must be in 1..$MAX_SCAN_LIMIT" }
    }

    private companion object {
        const val MAX_SCAN_LIMIT = 1_000
        val IMPACT_APPOINTMENT_STATES = listOf(
            AppointmentState.PENDING,
            AppointmentState.REQUESTED,
            AppointmentState.CONFIRMED,
            AppointmentState.PENDING_RESCHEDULE,
        )
        val IMPACT_PLAN_STATES = listOf(
            AppointmentPlanStatus.ACTIVE,
            AppointmentPlanStatus.PARTIALLY_FULFILLED,
        )
    }
}
