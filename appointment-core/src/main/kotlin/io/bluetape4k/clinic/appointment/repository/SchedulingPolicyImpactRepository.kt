package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.clinic.appointment.model.dto.PolicyScopeRef
import io.bluetape4k.clinic.appointment.model.plan.AppointmentPlanStatus
import io.bluetape4k.clinic.appointment.model.plan.PlannedTreatmentStatus
import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlans
import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.PlannedTreatments
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.io.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 영향도 미리보기 key가 가리키는 미래 업무 aggregate 종류다.
 *
 * enum 선언 순서는 keyset scan 순서다. 예약을 먼저 끝까지 읽고 아직 예약되지 않은 시술
 * 의무를 읽으므로 서로 다른 테이블을 SQL `UNION`으로 합쳐 `2 × limit`을 메모리에
 * 물리화하지 않는다.
 */
enum class PolicyImpactAggregateType {
    /** 가예약·확정·재예약 대기 등 아직 종결되지 않은 예약이다. */
    APPOINTMENT,

    /** 구매 계획에 남아 있는 예약 전 또는 예약 중 시술 의무다. */
    PLANNED_TREATMENT,
}

/**
 * 정책 영향도 계산에 필요한 최소 식별 projection이다.
 *
 * @property clinicId aggregate를 소유한 양수 병원 ID다. tenant-wide preview가 병원별
 * timezone과 재시작 순서를 보존하는 cursor를 만들 때 사용한다.
 * @property scheduledAt 정렬과 horizon 판정에 사용하는 UTC 시각이다.
 * @property aggregateType payload를 다시 읽을 저장소 종류다.
 * @property aggregateId 범위가 검증된 양수 database ID의 문자열 표현이다.
 */
data class PolicyImpactKey(
    val clinicId: Long,
    val scheduledAt: Instant,
    val aggregateType: PolicyImpactAggregateType,
    val aggregateId: String,
) : Serializable {
    private companion object {
        const val serialVersionUID: Long = 1L
    }
}

/**
 * 다음 bounded page를 시작하기 직전의 exclusive keyset 위치다.
 *
 * @property clinicId 마지막으로 반환한 aggregate를 소유한 양수 병원 ID다. tenant
 * preview는 병원 ID 오름차순으로 완전히 소진한 뒤 다음 병원으로 이동한다.
 * @property scheduledAt 마지막으로 반환한 aggregate의 UTC 시각이다.
 * @property aggregateType 현재 scan 중인 table partition이다.
 * @property aggregateId 마지막으로 반환한 양수 database ID다. [isClinicBoundary]가
 * `true`이면 실제 aggregate ID가 아니라 해당 병원을 완전히 소진했다는 예약 sentinel이다.
 */
data class PolicyImpactCursor(
    val clinicId: Long,
    val scheduledAt: Instant,
    val aggregateType: PolicyImpactAggregateType,
    val aggregateId: String,
) : Serializable {
    /**
     * tenant-wide sparse scan이 aggregate를 찾지 못해도 병원 순회 진행을 저장하는 cursor인지 나타낸다.
     *
     * 이 sentinel은 [PolicyImpactKey]로 노출되거나 정책 평가기에 전달되지 않는다.
     */
    val isClinicBoundary: Boolean
        get() =
            aggregateType == PolicyImpactAggregateType.PLANNED_TREATMENT &&
                aggregateId == CLINIC_BOUNDARY_AGGREGATE_ID

    companion object {
        /** 실제 database ID로 사용하지 않는 병원 소진 cursor의 예약 값이다. */
        const val CLINIC_BOUNDARY_AGGREGATE_ID: String = "9223372036854775807"

        const val serialVersionUID: Long = 1L
    }
}

/**
 * 한 트랜잭션에서 materialize할 수 있는 제한된 영향도 key page다.
 *
 * @property items 최대 요청 limit개의 작은 key projection이다.
 * @property nextCursor page가 row limit 또는 tenant clinic 순회 limit에 도달했을 때의
 * exclusive 재개 cursor다. sparse tenant page는 [items]가 비어 있어도 병원 경계 cursor를
 * 반환할 수 있다. `null`이면 clinic scope의 두 aggregate partition 또는 tenant scope의
 * 모든 병원을 소진했다.
 */
data class PolicyImpactPage(
    val items: List<PolicyImpactKey>,
    val nextCursor: PolicyImpactCursor?,
) : Serializable {
    private companion object {
        const val serialVersionUID: Long = 1L
    }
}

/**
 * 정책 영향도 미리보기에서 사용하는 keyset 기반 조회 저장소다.
 *
 * 모든 메서드는 호출자가 소유한 Exposed `transaction {}` 안에서 실행되어야 한다.
 * 반환값은 식별 projection만 포함하며, 한 호출에서 최대 5,000개만 materialize한다.
 * tenant scope는 병원 ID 오름차순으로 각 병원의 appointment/treatment partition을 완전히
 * 소진한다. 한 호출은 aggregate row limit과 별도로 최대 100개 병원만 확인하며, sparse
 * tenant에서는 병원 경계 cursor를 반환해 다음 트랜잭션에서 이어간다. 따라서 서로 다른
 * IANA timezone을 억지로 하나의 SQL timestamp 정렬로 합치지 않으면서도 worker 재시작 후
 * 정확한 exclusive 위치를 복원한다. 호출자는 실제 정책 평가 전에 각 식별자를 동일
 * tenant/clinic 경계를 검증하는 저장소를 통해 다시 읽어야 한다.
 */
class SchedulingPolicyImpactRepository {

    /**
     * tenant 또는 clinic scope의 미래 예약과 미이행 시술 의무를 bounded stream page로 조회한다.
     *
     * 예약과 시술 의무 table을 순차 partition으로 읽어 한 시점에 [limit]보다 많은 key를
     * 보유하지 않는다. 예약 local date/time은 저장된 병원 IANA timezone으로 UTC
     * [Instant]로 변환한다. 시술 의무는 nullable `earliestStartAt`이 있는 행만 포함한다.
     *
     * tenant scope는 병원 ID, aggregate partition, 병원 local schedule, aggregate ID
     * 순서로 진행한다. clinic scope는 해당 병원의 두 aggregate partition만 처리한다.
     *
     * @param scope 인가가 끝난 tenant baseline 또는 clinic override scope다.
     * @param horizonFrom 포함 UTC 시작 시각이다.
     * @param horizonUntil 제외 UTC 종료 시각이다.
     * @param after 마지막으로 반환한 exclusive cursor다.
     * @param limit `1..5000` 범위의 최대 materialized key 수다.
     */
    fun scanFutureWork(
        scope: PolicyScopeRef,
        horizonFrom: Instant,
        horizonUntil: Instant,
        after: PolicyImpactCursor?,
        limit: Int,
    ): PolicyImpactPage {
        require(horizonUntil > horizonFrom) { "horizonUntil must be later than horizonFrom" }
        require(limit in 1..MAX_SCAN_LIMIT) { "limit must be in 1..$MAX_SCAN_LIMIT" }
        after?.let(::validateCursor)

        return when (scope.scope) {
            PolicyScope.TENANT_DEFAULT -> scanTenantFutureWork(
                scope = scope,
                horizonFrom = horizonFrom,
                horizonUntil = horizonUntil,
                after = after,
                limit = limit,
            )
            PolicyScope.CLINIC_OVERRIDE -> {
                val clinicId = requireNotNull(scope.clinicId)
                require(after == null || after.clinicId == clinicId) {
                    "clinic preview cursor must remain inside its clinic scope"
                }
                require(after?.isClinicBoundary != true) {
                    "clinic preview cannot resume from a tenant clinic boundary"
                }
                scanClinicFutureWork(
                    tenantGroupId = scope.tenantGroupId,
                    clinicId = clinicId,
                    horizonFrom = horizonFrom,
                    horizonUntil = horizonUntil,
                    after = after,
                    limit = limit,
                )
            }
        }
    }

    private fun scanTenantFutureWork(
        scope: PolicyScopeRef,
        horizonFrom: Instant,
        horizonUntil: Instant,
        after: PolicyImpactCursor?,
        limit: Int,
    ): PolicyImpactPage {
        val items = ArrayList<PolicyImpactKey>(limit)
        val resumesAfterClinicBoundary = after?.isClinicBoundary == true
        var clinicId = when {
            after == null -> findNextClinicId(scope.tenantGroupId, 0L)
            resumesAfterClinicBoundary -> findNextClinicId(scope.tenantGroupId, after.clinicId)
            else -> after.clinicId
        } ?: return PolicyImpactPage(emptyList(), null)
        var clinicCursor = after?.takeUnless { resumesAfterClinicBoundary }
        var scannedClinics = 0

        while (items.size < limit) {
            val page = scanClinicFutureWork(
                tenantGroupId = scope.tenantGroupId,
                clinicId = clinicId,
                horizonFrom = horizonFrom,
                horizonUntil = horizonUntil,
                after = clinicCursor,
                limit = limit - items.size,
            )
            items += page.items
            scannedClinics++
            if (items.size == limit) {
                val last = items.last()
                return PolicyImpactPage(
                    items,
                    PolicyImpactCursor(last.clinicId, last.scheduledAt, last.aggregateType, last.aggregateId),
                )
            }
            val nextClinicId = findNextClinicId(scope.tenantGroupId, clinicId)
                ?: return PolicyImpactPage(items, null)
            if (scannedClinics >= MAX_TENANT_CLINICS_PER_SCAN) {
                return PolicyImpactPage(
                    items = items,
                    nextCursor = PolicyImpactCursor(
                        clinicId = clinicId,
                        scheduledAt = horizonUntil,
                        aggregateType = PolicyImpactAggregateType.PLANNED_TREATMENT,
                        aggregateId = PolicyImpactCursor.CLINIC_BOUNDARY_AGGREGATE_ID,
                    ),
                )
            }
            clinicId = nextClinicId
            clinicCursor = null
        }
        return PolicyImpactPage(items, null)
    }

    private fun findNextClinicId(
        tenantGroupId: Long,
        afterClinicId: Long,
    ): Long? =
        Clinics
            .select(Clinics.id)
            .where {
                (Clinics.tenantGroupId eq tenantGroupId) and
                    (Clinics.id greater afterClinicId)
            }
            .orderBy(Clinics.id, SortOrder.ASC)
            .limit(1)
            .singleOrNull()
            ?.get(Clinics.id)
            ?.value

    private fun scanClinicFutureWork(
        tenantGroupId: Long,
        clinicId: Long,
        horizonFrom: Instant,
        horizonUntil: Instant,
        after: PolicyImpactCursor?,
        limit: Int,
    ): PolicyImpactPage {
        val timezone = Clinics
            .select(Clinics.timezone)
            .where {
                (Clinics.id eq clinicId) and
                    (Clinics.tenantGroupId eq tenantGroupId)
            }
            .singleOrNull()
            ?.get(Clinics.timezone)
            ?.let(ZoneId::of)
            ?: return PolicyImpactPage(emptyList(), null)

        val items = ArrayList<PolicyImpactKey>(limit)
        if (after == null || after.aggregateType == PolicyImpactAggregateType.APPOINTMENT) {
            items += scanAppointments(
                tenantGroupId = tenantGroupId,
                clinicId = clinicId,
                timezone = timezone,
                horizonFrom = horizonFrom,
                horizonUntil = horizonUntil,
                after = after?.takeIf { it.aggregateType == PolicyImpactAggregateType.APPOINTMENT },
                limit = limit,
            )
        }
        if (items.size < limit) {
            items += scanPlannedTreatments(
                tenantGroupId = tenantGroupId,
                clinicId = clinicId,
                horizonFrom = horizonFrom,
                horizonUntil = horizonUntil,
                after = after?.takeIf { it.aggregateType == PolicyImpactAggregateType.PLANNED_TREATMENT },
                limit = limit - items.size,
            )
        }
        return PolicyImpactPage(
            items = items,
            nextCursor = items.lastOrNull()
                ?.takeIf { items.size == limit }
                ?.let { PolicyImpactCursor(it.clinicId, it.scheduledAt, it.aggregateType, it.aggregateId) },
        )
    }

    /**
     * 특정 테넌트와 병원의 미래 비종결 예약 식별자를 조회한다.
     *
     * 이 호환 메서드는 기존 caller를 위한 날짜 기반 appointment 전용 scan이다. 새 preview
     * 흐름은 UTC horizon과 복합 cursor를 보존하는 [scanFutureWork]를 사용해야 한다.
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
     * 특정 범위의 활성 또는 부분 이행 구매 플랜 식별자를 조회한다.
     *
     * 이 호환 메서드는 기존 plan 단위 진단용이다. 새 preview 흐름은 시간 cursor가 있는
     * [scanFutureWork]에서 미이행 시술 의무를 직접 조회한다.
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

    private fun scanAppointments(
        tenantGroupId: Long,
        clinicId: Long,
        timezone: ZoneId,
        horizonFrom: Instant,
        horizonUntil: Instant,
        after: PolicyImpactCursor?,
        limit: Int,
    ): List<PolicyImpactKey> {
        if (limit == 0) return emptyList()
        val localFrom = LocalDateTime.ofInstant(horizonFrom, timezone)
        val localUntil = LocalDateTime.ofInstant(horizonUntil, timezone)
        val lowerBound =
            (Appointments.appointmentDate greater localFrom.toLocalDate()) or
                (
                    (Appointments.appointmentDate eq localFrom.toLocalDate()) and
                        (Appointments.startTime greaterEq localFrom.toLocalTime())
                    )
        val upperBound =
            (Appointments.appointmentDate less localUntil.toLocalDate()) or
                (
                    (Appointments.appointmentDate eq localUntil.toLocalDate()) and
                        (Appointments.startTime less localUntil.toLocalTime())
                    )
        val cursorBound = after?.let { cursor ->
            val cursorId = cursor.aggregateId.toLong()
            val localCursor = LocalDateTime.ofInstant(cursor.scheduledAt, timezone)
            (Appointments.appointmentDate greater localCursor.toLocalDate()) or
                (
                    (Appointments.appointmentDate eq localCursor.toLocalDate()) and
                        (
                            (Appointments.startTime greater localCursor.toLocalTime()) or
                                (
                                    (Appointments.startTime eq localCursor.toLocalTime()) and
                                        (Appointments.id greater cursorId)
                                    )
                            )
                    )
        }
        return (Appointments innerJoin Clinics)
            .select(Appointments.id, Appointments.appointmentDate, Appointments.startTime)
            .where {
                var predicate =
                    (Clinics.tenantGroupId eq tenantGroupId) and
                        (Appointments.clinicId eq clinicId) and
                        (Appointments.status inList IMPACT_APPOINTMENT_STATES) and
                        lowerBound and upperBound
                if (cursorBound != null) predicate = predicate and cursorBound
                predicate
            }
            .orderBy(
                Appointments.appointmentDate to SortOrder.ASC,
                Appointments.startTime to SortOrder.ASC,
                Appointments.id to SortOrder.ASC,
            )
            .limit(limit)
            .map { row ->
                val scheduledAt = LocalDateTime.of(
                    row[Appointments.appointmentDate],
                    row[Appointments.startTime],
                ).atZone(timezone).toInstant()
                PolicyImpactKey(
                    clinicId = clinicId,
                    scheduledAt = scheduledAt,
                    aggregateType = PolicyImpactAggregateType.APPOINTMENT,
                    aggregateId = row[Appointments.id].value.toString(),
                )
            }
    }

    private fun scanPlannedTreatments(
        tenantGroupId: Long,
        clinicId: Long,
        horizonFrom: Instant,
        horizonUntil: Instant,
        after: PolicyImpactCursor?,
        limit: Int,
    ): List<PolicyImpactKey> {
        if (limit == 0) return emptyList()
        val cursorBound = after?.let { cursor ->
            val cursorId = cursor.aggregateId.toLong()
            (PlannedTreatments.earliestStartAt greater cursor.scheduledAt) or
                (
                    (PlannedTreatments.earliestStartAt eq cursor.scheduledAt) and
                        (PlannedTreatments.id greater cursorId)
                    )
        }
        return (PlannedTreatments innerJoin AppointmentPlans)
            .select(PlannedTreatments.id, PlannedTreatments.earliestStartAt)
            .where {
                var predicate =
                    (AppointmentPlans.tenantGroupId eq tenantGroupId) and
                        (AppointmentPlans.clinicId eq clinicId) and
                        (AppointmentPlans.status inList IMPACT_PLAN_STATES) and
                        PlannedTreatments.earliestStartAt.isNotNull() and
                        (PlannedTreatments.earliestStartAt greaterEq horizonFrom) and
                        (PlannedTreatments.earliestStartAt less horizonUntil) and
                        (PlannedTreatments.status inList IMPACT_TREATMENT_STATES)
                if (cursorBound != null) predicate = predicate and cursorBound
                predicate
            }
            .orderBy(
                PlannedTreatments.earliestStartAt to SortOrder.ASC,
                PlannedTreatments.id to SortOrder.ASC,
            )
            .limit(limit)
            .map { row ->
                PolicyImpactKey(
                    clinicId = clinicId,
                    scheduledAt = requireNotNull(row[PlannedTreatments.earliestStartAt]),
                    aggregateType = PolicyImpactAggregateType.PLANNED_TREATMENT,
                    aggregateId = row[PlannedTreatments.id].value.toString(),
                )
            }
    }

    private fun validateCursor(cursor: PolicyImpactCursor) {
        require(cursor.clinicId > 0) { "clinicId must be positive" }
        require(cursor.aggregateId.toLongOrNull()?.let { it > 0 } == true) {
            "aggregateId must be a positive database identifier"
        }
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
        const val MAX_SCAN_LIMIT = 5_000
        const val MAX_TENANT_CLINICS_PER_SCAN = 100
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
        val IMPACT_TREATMENT_STATES = listOf(
            PlannedTreatmentStatus.PLANNED,
            PlannedTreatmentStatus.SCHEDULED,
            PlannedTreatmentStatus.BLOCKED_REVIEW,
        )
    }
}
