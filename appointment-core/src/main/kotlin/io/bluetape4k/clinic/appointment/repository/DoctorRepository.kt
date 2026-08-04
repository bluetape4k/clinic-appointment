package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.exposed.jdbc.repository.LongJdbcRepository
import io.bluetape4k.exposed.core.ExposedPage
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.clinic.appointment.model.dto.DoctorAbsenceRecord
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Repository
import io.bluetape4k.clinic.appointment.model.dto.DoctorRecord
import io.bluetape4k.clinic.appointment.model.dto.DoctorScheduleRecord
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import io.bluetape4k.clinic.appointment.model.tables.DoctorAbsences
import io.bluetape4k.clinic.appointment.model.tables.DoctorSchedules
import io.bluetape4k.clinic.appointment.model.tables.Doctors
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 의사/전문상담사 정보 저장소.
 *
 * 의사의 기본 정보, 운영 스케줄, 휴무 정보를 조회합니다.
 */
@Repository
class DoctorRepository : LongJdbcRepository<DoctorRecord> {
    companion object : KLogging()

    override val table = Doctors
    override fun extractId(entity: DoctorRecord): Long = entity.id.requireNotNull("id")
    override fun ResultRow.toEntity(): DoctorRecord = toDoctorRecord()

    /**
     * Finds a doctor by ID only when the owning clinic belongs to [tenantGroupId].
     */
    fun findByIdAndTenant(doctorId: Long, tenantGroupId: Long): DoctorRecord? =
        Doctors
            .selectAll()
            .where {
                (Doctors.id eq doctorId) and (Doctors.clinicId inSubQuery tenantClinicIds(tenantGroupId))
            }
            .firstOrNull()
            ?.toDoctorRecord()

    /** 검증된 테넌트-병원 범위에 속한 의사만 조회합니다. */
    fun findByIdAndScope(doctorId: Long, scope: TenantClinicScope): DoctorRecord? =
        Doctors
            .selectAll()
            .where {
                (Doctors.id eq doctorId) and
                    (Doctors.clinicId eq scope.clinicId) and
                    (Doctors.clinicId inSubQuery tenantClinicIds(scope.tenantGroupId))
            }
            .firstOrNull()
            ?.toDoctorRecord()

    /** 검증된 범위에 속한 의사의 특정 요일 스케줄을 조회합니다. */
    fun findSchedule(scope: TenantClinicScope, doctorId: Long, dayOfWeek: DayOfWeek): DoctorScheduleRecord? =
        DoctorSchedules
            .selectAll()
            .where {
                (DoctorSchedules.doctorId eq doctorId) and
                    (DoctorSchedules.doctorId inSubQuery tenantDoctorIds(scope))
            }
            .andWhere { DoctorSchedules.dayOfWeek eq dayOfWeek }
            .firstOrNull()?.toDoctorScheduleRecord()

    /** 검증된 범위에 속한 의사의 특정 날짜 부재를 조회합니다. */
    fun findAbsences(scope: TenantClinicScope, doctorId: Long, date: LocalDate): List<DoctorAbsenceRecord> =
        DoctorAbsences
            .selectAll()
            .where {
                (DoctorAbsences.doctorId eq doctorId) and
                    (DoctorAbsences.doctorId inSubQuery tenantDoctorIds(scope))
            }
            .andWhere { DoctorAbsences.absenceDate eq date }
            .map { it.toDoctorAbsenceRecord() }

    /** 테넌트와 병원을 모두 포함하는 안정적인 캐시 키로 의사 목록을 조회합니다. */
    @Cacheable(cacheNames = ["clinic-doctors"], key = "#scope.cacheKey()", unless = "#result == null || #result.isEmpty()")
    fun findByScope(scope: TenantClinicScope): List<DoctorRecord> =
        Doctors
            .selectAll()
            .where {
                (Doctors.clinicId eq scope.clinicId) and
                    (Doctors.clinicId inSubQuery tenantClinicIds(scope.tenantGroupId))
            }
            .map { it.toDoctorRecord() }

    /** 테넌트-병원 범위를 SQL predicate에 포함한 페이징 목록을 조회합니다. */
    fun findPage(scope: TenantClinicScope, page: Int, size: Int): ExposedPage<DoctorRecord> =
        findPage(page, size) {
            (Doctors.clinicId eq scope.clinicId) and
                (Doctors.clinicId inSubQuery tenantClinicIds(scope.tenantGroupId))
        }

    fun findAllSchedules(scope: TenantClinicScope, doctorId: Long): List<DoctorScheduleRecord> =
        DoctorSchedules
            .selectAll()
            .where { DoctorSchedules.doctorId eq doctorId }
            .andWhere { DoctorSchedules.doctorId inSubQuery tenantDoctorIds(scope) }
            .map { it.toDoctorScheduleRecord() }

    fun findAbsencesByDateRange(
        scope: TenantClinicScope,
        doctorId: Long,
        dateRange: ClosedRange<LocalDate>,
    ): List<DoctorAbsenceRecord> =
        DoctorAbsences
            .selectAll()
            .where { DoctorAbsences.doctorId eq doctorId }
            .andWhere { DoctorAbsences.doctorId inSubQuery tenantDoctorIds(scope) }
            .andWhere { DoctorAbsences.absenceDate greaterEq dateRange.start }
            .andWhere { DoctorAbsences.absenceDate lessEq dateRange.endInclusive }
            .map { it.toDoctorAbsenceRecord() }
}
