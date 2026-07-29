package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.clinic.appointment.model.commitment.AppointmentModelVersion
import io.bluetape4k.clinic.appointment.model.dto.AppointmentRecord
import io.bluetape4k.clinic.appointment.model.dto.AppointmentVisitIdentityDraft
import io.bluetape4k.clinic.appointment.model.dto.ConfirmedAppointmentProjection
import io.bluetape4k.clinic.appointment.model.dto.UnavailablePeriod
import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.Doctors
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import io.bluetape4k.exposed.jdbc.repository.LongJdbcRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.support.requirePositiveNumber
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
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

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
    fun findByIdAndTenant(
        appointmentId: Long,
        tenantGroupId: Long,
    ): AppointmentRecord? =
        Appointments
            .selectAll()
            .where {
                (Appointments.id eq appointmentId) and
                    (Appointments.clinicId inSubQuery tenantClinicIds(tenantGroupId))
            }.andWhere { completeAppointmentProjection() }
            .firstOrNull()
            ?.toAppointmentRecord()

    /**
     * 신뢰된 command scope의 병원이 tenant에 실제로 속하는지 검증합니다.
     *
     * `clinicId`와 `tenantGroupId`는 각각 유효한 외래 키여도 서로 다른 SaaS 경계일 수
     * 있으므로 새 commitment identity를 만들기 전에 이 결합을 확인해야 합니다.
     */
    fun isClinicInTenant(
        tenantGroupId: Long,
        clinicId: Long,
    ): Boolean {
        val validTenantGroupId = tenantGroupId.requirePositiveNumber("tenantGroupId")
        val validClinicId = clinicId.requirePositiveNumber("clinicId")
        return Clinics
            .selectAll()
            .where {
                (Clinics.id eq validClinicId) and
                    (Clinics.tenantGroupId eq validTenantGroupId)
            }.count() == 1L
    }

    /**
     * legacy projection이 참조할 담당자와 대표 진료 유형이 같은 병원에 속하는지 검증합니다.
     *
     * FK가 존재한다는 사실만으로는 다른 병원 자원을 끌어올 수 있으므로 두 참조 모두
     * command의 정확한 clinic에 결합돼야 합니다.
     */
    fun areProjectionReferencesInClinic(
        clinicId: Long,
        doctorId: Long,
        treatmentTypeId: Long,
    ): Boolean {
        val validClinicId = clinicId.requirePositiveNumber("clinicId")
        val validDoctorId = doctorId.requirePositiveNumber("doctorId")
        val validTreatmentTypeId = treatmentTypeId.requirePositiveNumber("treatmentTypeId")
        val doctorExists =
            Doctors
                .selectAll()
                .where {
                    (Doctors.id eq validDoctorId) and
                        (Doctors.clinicId eq validClinicId)
                }.count() == 1L
        if (!doctorExists) {
            return false
        }
        return TreatmentTypes
            .selectAll()
            .where {
                (TreatmentTypes.id eq validTreatmentTypeId) and
                    (TreatmentTypes.clinicId eq validClinicId)
            }.count() == 1L
    }

    /**
     * tenant·clinic 경계가 일치할 때만 병원 IANA timezone을 반환합니다.
     *
     * 잘못 저장된 timezone은 내부 데이터 불변식 위반이므로 [ZoneId.of]의 예외를 숨기지
     * 않습니다. scope가 다르거나 병원이 없으면 `null`을 반환합니다.
     */
    fun findClinicTimezone(
        tenantGroupId: Long,
        clinicId: Long,
    ): ZoneId? {
        val validTenantGroupId = tenantGroupId.requirePositiveNumber("tenantGroupId")
        val validClinicId = clinicId.requirePositiveNumber("clinicId")
        val timezone =
            Clinics
                .select(Clinics.timezone)
                .where {
                    (Clinics.id eq validClinicId) and
                        (Clinics.tenantGroupId eq validTenantGroupId)
                }.singleOrNull()
                ?.get(Clinics.timezone)
                ?: return null
        return ZoneId.of(timezone)
    }

    /**
     * 방문 예약이 command의 tenant와 clinic 경계에 모두 속하는지 검증합니다.
     *
     * path의 appointment ID는 인증정보가 아니므로 Gateway actor가 검증됐더라도 이
     * 저장소 경계를 다시 통과해야 합니다.
     */
    fun isAppointmentInScope(
        appointmentId: Long,
        tenantGroupId: Long,
        clinicId: Long,
    ): Boolean {
        val validAppointmentId = appointmentId.requirePositiveNumber("appointmentId")
        val validTenantGroupId = tenantGroupId.requirePositiveNumber("tenantGroupId")
        val validClinicId = clinicId.requirePositiveNumber("clinicId")
        return Appointments
            .selectAll()
            .where {
                (Appointments.id eq validAppointmentId) and
                    (Appointments.clinicId eq validClinicId) and
                    (Appointments.clinicId inSubQuery tenantClinicIds(validTenantGroupId))
            }.count() == 1L
    }

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
            },
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
        val id =
            Appointments
                .insertAndGetId {
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
     * 확정 projection 없이 commitment v2 방문 identity를 생성합니다.
     *
     * 고객 요청 직후에는 담당자와 일정이 아직 병원 승인을 받지 않았으므로 nullable
     * projection을 임의 값으로 채우지 않습니다. caller가 소유한 transaction 안에서
     * commitment와 첫 proposal을 이어서 생성해야 고아 identity가 남지 않습니다.
     *
     * @param clinicId 방문을 소유하는 양수 병원 식별자입니다.
     * @param identity 고객 표시·연락 identity이며 인증 actor를 대신하지 않습니다.
     * @return 생성된 양수 appointment 식별자입니다.
     */
    fun createCommitmentVisitIdentity(
        clinicId: Long,
        identity: AppointmentVisitIdentityDraft,
    ): Long {
        val validClinicId = clinicId.requirePositiveNumber("clinicId")
        return Appointments
            .insertAndGetId {
                it[Appointments.clinicId] = validClinicId
                it[modelVersion] = AppointmentModelVersion.COMMITMENT_V2
                it[patientName] = identity.patientName
                it[patientPhone] = identity.patientPhone
                it[patientExternalId] = identity.patientExternalId
                it[patientReferenceFingerprint] = identity.patientReferenceFingerprint
                it[status] = AppointmentState.REQUESTED
            }.value
    }

    /**
     * commitment v2 방문에 고정된 환자 참조 fingerprint를 tenant·clinic 범위에서 찾습니다.
     *
     * legacy row 또는 다른 scope의 row는 `null`을 반환합니다. command service는 이 값을
     * 후속 proposal의 Plan-linked item 검증에 사용하며 환자 원문을 로그에 남기지 않습니다.
     */
    fun findPatientReferenceFingerprint(
        appointmentId: Long,
        tenantGroupId: Long,
        clinicId: Long,
    ): String? {
        val validAppointmentId = appointmentId.requirePositiveNumber("appointmentId")
        val validTenantGroupId = tenantGroupId.requirePositiveNumber("tenantGroupId")
        val validClinicId = clinicId.requirePositiveNumber("clinicId")
        return Appointments
            .select(Appointments.patientReferenceFingerprint)
            .where {
                (Appointments.id eq validAppointmentId) and
                    (Appointments.clinicId eq validClinicId) and
                    (Appointments.clinicId inSubQuery tenantClinicIds(validTenantGroupId))
            }.singleOrNull()
            ?.get(Appointments.patientReferenceFingerprint)
    }

    /**
     * commitment CAS가 성공한 appointment에 확정 일정 projection을 반영합니다.
     *
     * 이 메서드는 transaction을 열지 않습니다. 새 allocation 생성과 commitment CAS가
     * 성공한 뒤, 이전 allocation 해제와 같은 caller transaction 안에서 호출해야 합니다.
     * 존재하지 않거나 command의 tenant·clinic 경계와 다른 appointment는 `false`를
     * 반환합니다. 앞선 소유권 검증 뒤에도 update 조건에 scope를 반복해 잘못된 caller
     * 조합이나 데이터 복구 오류가 다른 병원의 projection을 바꾸지 못하게 합니다.
     *
     * @param appointmentId projection을 갱신할 양수 방문 식별자입니다.
     * @param tenantGroupId Gateway 인증정보가 가리키는 양수 tenant 식별자입니다.
     * @param clinicId Gateway 인증정보가 가리키는 양수 병원 식별자입니다.
     * @param projection 확정 proposal을 병원 timezone으로 표현한 legacy 조회 값입니다.
     * @param updatedAt command의 권위 있는 UTC 처리 시각입니다.
     */
    fun updateConfirmedProjection(
        appointmentId: Long,
        tenantGroupId: Long,
        clinicId: Long,
        projection: ConfirmedAppointmentProjection,
        updatedAt: Instant,
    ): Boolean {
        val validAppointmentId = appointmentId.requirePositiveNumber("appointmentId")
        val validTenantGroupId = tenantGroupId.requirePositiveNumber("tenantGroupId")
        val validClinicId = clinicId.requirePositiveNumber("clinicId")
        return Appointments.update(
            where = {
                (Appointments.id eq validAppointmentId) and
                    (Appointments.clinicId eq validClinicId) and
                    (Appointments.clinicId inSubQuery tenantClinicIds(validTenantGroupId))
            },
        ) {
            it[doctorId] = projection.doctorId
            it[treatmentTypeId] = projection.treatmentTypeId
            it[appointmentDate] = projection.appointmentDate
            it[startTime] = projection.startTime
            it[endTime] = projection.endTime
            it[status] = AppointmentState.CONFIRMED
            it[Appointments.updatedAt] = updatedAt
        } == 1
    }

    /**
     * 예약의 상태를 변경합니다.
     *
     * @param appointmentId 예약 ID
     * @param newStatus 새로운 상태
     * @return 업데이트된 행 개수
     */
    fun updateStatus(
        appointmentId: Long,
        newStatus: AppointmentState,
    ): Int =
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

        val periodConditions =
            periods
                .map { period ->
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
    fun findByClinicAndDateRange(
        clinicId: Long,
        dateRange: ClosedRange<LocalDate>,
    ): List<AppointmentRecord> =
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
