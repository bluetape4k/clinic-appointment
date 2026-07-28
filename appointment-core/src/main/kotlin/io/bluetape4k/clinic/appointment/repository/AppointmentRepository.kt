package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.exposed.jdbc.repository.LongJdbcRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.clinic.appointment.model.dto.AppointmentRecord
import io.bluetape4k.clinic.appointment.model.dto.UnavailablePeriod
import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import io.bluetape4k.support.requireNotNull
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.LocalDate
import java.time.LocalTime

/**
 * 예약 정보 저장소.
 *
 * Exposed JDBC를 사용하여 예약 조회, 생성, 상태 업데이트를 처리합니다.
 * 동시 예약 수 및 장비 사용량 검증, 기간별/상태별 조회 등을 지원합니다.
 */
class AppointmentRepository : LongJdbcRepository<AppointmentRecord> {
    companion object : KLogging()

    override val table = Appointments
    override fun extractId(entity: AppointmentRecord): Long = entity.id.requireNotNull("id")
    override fun ResultRow.toEntity(): AppointmentRecord = toAppointmentRecord()

    /**
     * 병원이 [tenantGroupId]에 속하고 legacy DTO에 필요한 확정 projection이 완성된 예약을 조회합니다.
     *
     * 아직 proposal이 확정되지 않은 commitment v2 row는 방문 identity로는 존재하지만 기존
     * [AppointmentRecord] 계약을 충족하지 않으므로 반환하지 않습니다.
     */
    fun findByIdAndTenant(appointmentId: Long, tenantGroupId: Long): AppointmentRecord? =
        Appointments
            .selectAll()
            .where {
                (Appointments.id eq appointmentId) and
                    (Appointments.clinicId inSubQuery tenantClinicIds(tenantGroupId))
            }
            .andWhere { completeAppointmentProjection() }
            .firstOrNull()
            ?.toAppointmentRecord()

    /**
     * 의사와 시간대에 겹치는 예약 개수를 반환합니다.
     *
     * 취소되거나 미내원 상태는 제외합니다.
     *
     * @param doctorId 의사 ID
     * @param date 조회 날짜
     * @param slotStart 시간대 시작
     * @param slotEnd 시간대 종료
     * @return 겹치는 예약 개수
     */
    fun countOverlapping(
        doctorId: Long,
        date: LocalDate,
        slotStart: LocalTime,
        slotEnd: LocalTime,
    ): Int =
        Appointments
            .selectAll()
            .where { Appointments.doctorId eq doctorId }
            .andWhere { Appointments.appointmentDate eq date }
            .andWhere { Appointments.startTime less slotEnd }
            .andWhere { Appointments.endTime greater slotStart }
            .andWhere { Appointments.status neq AppointmentState.CANCELLED }
            .andWhere { Appointments.status neq AppointmentState.NO_SHOW }
            .count()
            .toInt()

    /**
     * 장비와 시간대에 사용 중인 예약 개수를 반환합니다.
     *
     * @param equipmentId 장비 ID
     * @param date 조회 날짜
     * @param slotStart 시간대 시작
     * @param slotEnd 시간대 종료
     * @return 장비 사용 중인 예약 개수
     */
    fun countEquipmentUsage(
        equipmentId: Long,
        date: LocalDate,
        slotStart: LocalTime,
        slotEnd: LocalTime,
    ): Int =
        Appointments
            .selectAll()
            .where { Appointments.equipmentId eq equipmentId }
            .andWhere { Appointments.appointmentDate eq date }
            .andWhere { Appointments.startTime less slotEnd }
            .andWhere { Appointments.endTime greater slotStart }
            .andWhere { Appointments.status neq AppointmentState.CANCELLED }
            .andWhere { Appointments.status neq AppointmentState.NO_SHOW }
            .count()
            .toInt()

    /**
     * 병원의 특정 날짜 활성 예약을 조회합니다.
     *
     * @param clinicId 병원 ID
     * @param date 조회 날짜
     * @param activeStatuses 필터링할 상태 목록 (기본값: REQUESTED, CONFIRMED)
     * @return 예약 목록
     */
    fun findActiveByClinicAndDate(
        clinicId: Long,
        date: LocalDate,
        activeStatuses: List<AppointmentState> = AppointmentState.ACTIVE_STATUSES,
    ): List<AppointmentRecord> =
        Appointments
            .selectAll()
            .where { Appointments.clinicId eq clinicId }
            .andWhere { Appointments.appointmentDate eq date }
            .andWhere { Appointments.status inList activeStatuses }
            .andWhere { completeAppointmentProjection() }
            .map { it.toAppointmentRecord() }

    /**
     * 병원의 특정 날짜 예약 상태를 일괄 변경합니다.
     *
     * @param clinicId 병원 ID
     * @param date 대상 날짜
     * @param fromStatuses 현재 상태 목록 (이 중 하나인 예약만 업데이트)
     * @param toStatus 변경할 새로운 상태
     * @return 업데이트된 예약 개수
     */
    fun updateStatusByClinicAndDate(
        clinicId: Long,
        date: LocalDate,
        fromStatuses: List<AppointmentState>,
        toStatus: AppointmentState,
    ): Int =
        Appointments.update(
            where = {
                (Appointments.clinicId eq clinicId) and
                    (Appointments.appointmentDate eq date) and
                    (Appointments.status inList fromStatuses)
            }
        ) {
            it[status] = toStatus
        }

    /**
     * 특정 날짜의 모든 활성 예약을 조회합니다.
     *
     * @param date 조회 날짜
     * @param activeStatuses 필터링할 상태 목록
     * @return 예약 목록
     */
    fun findActiveByDate(
        date: LocalDate,
        activeStatuses: List<AppointmentState> = AppointmentState.ACTIVE_STATUSES,
    ): List<AppointmentRecord> =
        Appointments
            .selectAll()
            .where { Appointments.appointmentDate eq date }
            .andWhere { Appointments.status inList activeStatuses }
            .andWhere { completeAppointmentProjection() }
            .map { it.toAppointmentRecord() }

    /**
     * 예약을 생성합니다.
     *
     * @param record 예약 레코드 (ID는 null)
     * @return 생성된 예약 (ID 포함)
     */
    fun save(record: AppointmentRecord): AppointmentRecord {
        val id = Appointments.insertAndGetId {
            it[clinicId] = record.clinicId
            it[doctorId] = record.doctorId
            it[treatmentTypeId] = record.treatmentTypeId
            it[equipmentId] = record.equipmentId
            it[consultationTopicId] = record.consultationTopicId
            it[consultationMethod] = record.consultationMethod
            it[rescheduleFromId] = record.rescheduleFromId
            it[patientName] = record.patientName
            it[patientPhone] = record.patientPhone
            it[patientExternalId] = record.patientExternalId
            it[appointmentDate] = record.appointmentDate
            it[startTime] = record.startTime
            it[endTime] = record.endTime
            it[status] = record.status
        }.value
        return record.copy(id = id)
    }

    /**
     * 예약의 상태를 변경합니다.
     *
     * @param appointmentId 예약 ID
     * @param newStatus 새로운 상태
     * @return 업데이트된 행 개수
     */
    fun updateStatus(appointmentId: Long, newStatus: AppointmentState): Int =
        Appointments.update(where = { Appointments.id eq appointmentId }) {
            it[status] = newStatus
        }

    /**
     * 특정 장비의 사용불가 기간과 겹치는 예약을 조회합니다.
     *
     * 취소 상태 예약은 제외합니다.
     *
     * @param equipmentId 장비 ID
     * @param periods 사용불가 기간 목록
     * @return 겹치는 예약 목록
     */
    fun findOverlappingByEquipment(
        equipmentId: Long,
        periods: List<UnavailablePeriod>,
    ): List<AppointmentRecord> {
        if (periods.isEmpty()) return emptyList()

        val periodConditions = periods.map { period ->
            (Appointments.appointmentDate eq period.date) and
                (Appointments.startTime less period.endTime) and
                (Appointments.endTime greater period.startTime)
        }.reduce { acc, op -> acc or op }

        return Appointments
            .selectAll()
            .where { Appointments.equipmentId eq equipmentId }
            .andWhere { Appointments.status neq AppointmentState.CANCELLED }
            .andWhere { periodConditions }
            .andWhere { completeAppointmentProjection() }
            .map { it.toAppointmentRecord() }
    }

    /**
     * 병원의 기간별 예약을 조회합니다.
     *
     * 취소 및 미내원 상태는 제외합니다.
     *
     * @param clinicId 병원 ID
     * @param dateRange 조회 기간
     * @return 예약 목록
     */
    fun findByClinicAndDateRange(clinicId: Long, dateRange: ClosedRange<LocalDate>): List<AppointmentRecord> =
        Appointments
            .selectAll()
            .where { Appointments.clinicId eq clinicId }
            .andWhere { Appointments.appointmentDate greaterEq dateRange.start }
            .andWhere { Appointments.appointmentDate lessEq dateRange.endInclusive }
            .andWhere { Appointments.status neq AppointmentState.CANCELLED }
            .andWhere { Appointments.status neq AppointmentState.NO_SHOW }
            .andWhere { completeAppointmentProjection() }
            .map { it.toAppointmentRecord() }
}

/**
 * 기존 [AppointmentRecord]로 안전하게 읽을 수 있는 확정 일정 projection 조건입니다.
 *
 * SQL `IS NOT NULL` 조건을 mapper 앞에 적용하여 미확정 commitment v2 row가 legacy 조회에
 * 섞이지 않도록 한다. Kotlin의 nullable column type은 SQL 조건으로 smart cast되지 않으므로
 * mapper도 같은 불변조건을 명시적으로 검증한다.
 */
internal fun completeAppointmentProjection(): Op<Boolean> =
    Appointments.doctorId.isNotNull() and
        Appointments.treatmentTypeId.isNotNull() and
        Appointments.appointmentDate.isNotNull() and
        Appointments.startTime.isNotNull() and
        Appointments.endTime.isNotNull()
