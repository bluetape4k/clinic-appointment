package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.exposed.jdbc.repository.LongJdbcRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.clinic.appointment.model.dto.BreakTimeRecord
import io.bluetape4k.clinic.appointment.model.dto.ClinicClosureRecord
import io.bluetape4k.clinic.appointment.model.dto.ClinicDefaultBreakTimeRecord
import io.bluetape4k.clinic.appointment.model.dto.ClinicRecord
import io.bluetape4k.clinic.appointment.model.dto.OperatingHoursRecord
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import io.bluetape4k.clinic.appointment.model.tables.BreakTimes
import io.bluetape4k.clinic.appointment.model.tables.ClinicClosures
import io.bluetape4k.clinic.appointment.model.tables.ClinicDefaultBreakTimes
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.OperatingHoursTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 병원 정보 저장소.
 *
 * 병원의 기본 정보, 운영 시간, 휴시간, 휴진 정보를 조회합니다.
 */
class ClinicRepository : LongJdbcRepository<ClinicRecord> {
    companion object : KLogging()

    override val table = Clinics
    override fun extractId(entity: ClinicRecord): Long = entity.id.requireNotNull("id")
    override fun ResultRow.toEntity(): ClinicRecord = toClinicRecord()

    /**
     * [tenantGroupId]에 속한 clinic만 조회합니다.
     */
    fun findByIdAndTenant(clinicId: Long, tenantGroupId: Long): ClinicRecord? =
        Clinics
            .selectAll()
            .where {
                (Clinics.id eq clinicId) and (Clinics.tenantGroupId eq tenantGroupId)
            }
            .firstOrNull()
            ?.toClinicRecord()

    /**
     * 지정한 tenant group에 속한 병원 목록을 ID 오름차순으로 조회합니다.
     *
     * 호출자는 Exposed `transaction {}` 안에서 실행해야 합니다.
     */
    fun findByTenant(tenantGroupId: Long): List<ClinicRecord> =
        Clinics
            .selectAll()
            .where { Clinics.tenantGroupId eq tenantGroupId }
            .orderBy(Clinics.id, SortOrder.ASC)
            .map { it.toClinicRecord() }

    /** 검증된 테넌트-병원 범위의 운영 시간을 조회합니다. */
    fun findOperatingHours(scope: TenantClinicScope, dayOfWeek: DayOfWeek): OperatingHoursRecord? =
        OperatingHoursTable
            .selectAll()
            .where {
                (OperatingHoursTable.clinicId eq scope.clinicId) and
                    (OperatingHoursTable.clinicId inSubQuery tenantClinicIds(scope.tenantGroupId))
            }
            .andWhere { OperatingHoursTable.dayOfWeek eq dayOfWeek }
            .andWhere { OperatingHoursTable.isActive eq true }
            .firstOrNull()?.toOperatingHoursRecord()

    /** 검증된 테넌트-병원 범위의 기본 휴식 시간을 조회합니다. */
    fun findDefaultBreakTimes(scope: TenantClinicScope): List<ClinicDefaultBreakTimeRecord> =
        ClinicDefaultBreakTimes
            .selectAll()
            .where {
                (ClinicDefaultBreakTimes.clinicId eq scope.clinicId) and
                    (ClinicDefaultBreakTimes.clinicId inSubQuery tenantClinicIds(scope.tenantGroupId))
            }
            .map { it.toClinicDefaultBreakTimeRecord() }

    /** 검증된 테넌트-병원 범위의 요일별 휴식 시간을 조회합니다. */
    fun findBreakTimes(scope: TenantClinicScope, dayOfWeek: DayOfWeek): List<BreakTimeRecord> =
        BreakTimes
            .selectAll()
            .where {
                (BreakTimes.clinicId eq scope.clinicId) and
                    (BreakTimes.clinicId inSubQuery tenantClinicIds(scope.tenantGroupId))
            }
            .andWhere { BreakTimes.dayOfWeek eq dayOfWeek }
            .map { it.toBreakTimeRecord() }

    /** 검증된 테넌트-병원 범위의 특정 날짜 휴진을 조회합니다. */
    fun findClosures(scope: TenantClinicScope, date: LocalDate): List<ClinicClosureRecord> =
        ClinicClosures
            .selectAll()
            .where {
                (ClinicClosures.clinicId eq scope.clinicId) and
                    (ClinicClosures.clinicId inSubQuery tenantClinicIds(scope.tenantGroupId))
            }
            .andWhere { ClinicClosures.closureDate eq date }
            .map { it.toClinicClosureRecord() }

    /** 검증된 테넌트-병원 범위의 기간별 휴진을 조회합니다. */
    fun findClosuresByDateRange(
        scope: TenantClinicScope,
        dateRange: ClosedRange<LocalDate>,
    ): List<ClinicClosureRecord> =
        ClinicClosures
            .selectAll()
            .where {
                (ClinicClosures.clinicId eq scope.clinicId) and
                    (ClinicClosures.clinicId inSubQuery tenantClinicIds(scope.tenantGroupId))
            }
            .andWhere { ClinicClosures.closureDate greaterEq dateRange.start }
            .andWhere { ClinicClosures.closureDate lessEq dateRange.endInclusive }
            .map { it.toClinicClosureRecord() }

    fun findAllOperatingHours(scope: TenantClinicScope): List<OperatingHoursRecord> =
        OperatingHoursTable
            .selectAll()
            .where {
                (OperatingHoursTable.clinicId eq scope.clinicId) and
                    (OperatingHoursTable.clinicId inSubQuery tenantClinicIds(scope.tenantGroupId))
            }
            .map { it.toOperatingHoursRecord() }

    fun findAllBreakTimes(scope: TenantClinicScope): List<BreakTimeRecord> =
        BreakTimes
            .selectAll()
            .where {
                (BreakTimes.clinicId eq scope.clinicId) and
                    (BreakTimes.clinicId inSubQuery tenantClinicIds(scope.tenantGroupId))
            }
            .map { it.toBreakTimeRecord() }

}
