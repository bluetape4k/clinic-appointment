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
 * 정책 영향도 미리보기에서 사용하는 keyset 기반 조회 저장소입니다.
 *
 * 모든 메서드는 호출자가 소유한 Exposed `transaction {}` 안에서 실행되어야 합니다.
 * 반환값은 데이터베이스 식별자만 포함하며, 정렬 순서는 항상 오름차순이고, 한 번에 최대
 * 1,000건까지만 읽습니다. 이 제한은 정책 미리보기가 테넌트 전체 데이터를 한 번에
 * 메모리로 적재하지 않도록 하는 안전장치입니다. 호출자는 정책을 평가하기 전에 반환된
 * 식별자를 테넌트/병원 범위가 검증되는 저장소를 통해 다시 읽어야 합니다.
 */
class SchedulingPolicyImpactRepository {

    /**
     * 특정 테넌트와 병원의 미래 비종결 예약 식별자를 조회합니다.
     *
     * @param tenantGroupId 양수 테넌트 경계입니다. `Clinics` 조인을 통해 예약이 해당
     * 테넌트의 병원에 속하는지 확인합니다.
     * @param clinicId 양수 병원 경계입니다.
     * @param fromDate 병원 로컬 날짜 기준의 포함 시작일입니다.
     * @param afterAppointmentId 제외 keyset 커서입니다. `0`이면 첫 페이지부터 조회합니다.
     * @param limit 요청 페이지 크기입니다. 허용 범위는 `1..1000`입니다.
     * @return [limit]을 초과하지 않는 예약 식별자 오름차순 목록입니다.
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
     * 특정 범위의 활성 또는 부분 이행 구매 플랜 식별자를 조회합니다.
     *
     * @param tenantGroupId 양수 테넌트 경계입니다.
     * @param clinicId 양수 병원 경계입니다.
     * @param afterPlanId 제외 keyset 커서입니다. `0`이면 첫 페이지부터 조회합니다.
     * @param limit 요청 페이지 크기입니다. 허용 범위는 `1..1000`입니다.
     * @return [limit]을 초과하지 않는 플랜 식별자 오름차순 목록입니다.
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
