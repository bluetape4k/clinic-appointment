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
 * 의사 단위 상태 집계의 행별 결과입니다.
 *
 * 두 Long 필드(doctorId, count)의 위치를 혼동하지 않도록 Triple 대신 이름 있는
 * data class를 사용합니다. 같은 타입 매개변수 규칙은 CLAUDE.md를 참고하세요.
 *
 * ## 동작 / 계약
 * - 조회 날짜 범위 안의 (doctorId, status) 조합마다 행 하나를 반환합니다.
 * - count는 항상 ≥ 1입니다.
 * - 호출자는 반드시 `transaction {}` 블록 안에서 이 결과를 사용해야 합니다.
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
 * 관리자 대시보드 통계 API를 위한 집계 query repository입니다.
 *
 * ## 동작 / 계약
 * - 모든 메서드는 `transaction {}` 블록 안에서 호출해야 합니다.
 * - SQL LIMIT이나 ORDER BY를 적용하지 않으며, 정렬과 pagination은 호출자가 담당합니다.
 */
class AppointmentStatsRepository {
    companion object : KLogging()

    /**
     * 지정한 clinic과 날짜 범위의 예약 건수를 (date, status)별로 그룹화해 반환합니다.
     *
     * ## 동작 / 계약
     * - [statuses]가 null이면 모든 상태를 포함합니다.
     * - 조건에 맞는 예약이 없으면 빈 목록을 반환합니다.
     * - 결과 행은 DB 고유 순서이며, 필요한 정렬은 호출자가 수행합니다.
     * - `transaction {}` 안에서 호출해야 합니다.
     *
     * @param clinicId 대상 clinic
     * @param dateRange 양끝을 포함하는 날짜 범위(from..to)
     * @param statuses 선택적 상태 필터이며, null이면 모든 상태를 사용합니다.
     * @return (date, status, count) Triple 목록
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
     * 지정한 clinic과 날짜 범위의 예약 건수를 (doctorId, status)별로 그룹화해 반환합니다.
     *
     * ## 동작 / 계약
     * - SQL LIMIT이나 ORDER BY를 적용하지 않습니다. 서비스 계층이
     *   `groupBy { it.doctorId }`, `sortedByDescending { totalAppointments }`, `take(limit)`으로
     *   순위를 처리합니다. 여기서 SQL LIMIT을 적용하면 서비스가 의사별 합계를 집계하기 전에
     *   (doctorId, status) 행이 잘려 잘못된 순위가 만들어집니다.
     * - `transaction {}` 안에서 호출해야 합니다.
     *
     * @param clinicId 대상 clinic
     * @param dateRange 양끝을 포함하는 날짜 범위(from..to)
     * @return [DoctorStatusCount] 목록이며, (doctorId, status) 조합마다 하나씩 포함합니다.
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
