package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.exposed.jdbc.repository.LongJdbcRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.clinic.appointment.model.dto.HolidayRecord
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import io.bluetape4k.clinic.appointment.model.tables.Holidays
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.LocalDate

class HolidayRepository : LongJdbcRepository<HolidayRecord> {
    companion object : KLogging()

    override val table = Holidays
    override fun extractId(entity: HolidayRecord): Long = entity.id.requireNotNull("id")
    override fun ResultRow.toEntity(): HolidayRecord = toHolidayRecord()

    internal fun existsByDate(date: LocalDate): Boolean =
        Holidays.selectAll().where { Holidays.holidayDate eq date }.count() > 0

    /** 검증된 테넌트 범위에서만 공휴일 존재 여부를 확인합니다. */
    fun existsByDate(tenantGroupId: Long, date: LocalDate): Boolean =
        Holidays
            .selectAll()
            .where {
                (Holidays.tenantGroupId eq tenantGroupId) and
                    (Holidays.holidayDate eq date)
            }
            .count() > 0

    /** [TenantClinicScope]의 테넌트 범위에서 공휴일 존재 여부를 확인합니다. */
    fun existsByDate(scope: TenantClinicScope, date: LocalDate): Boolean =
        existsByDate(scope.tenantGroupId, date)

    /**
     * 지정한 tenant group과 날짜에 해당하는 공휴일을 조회합니다.
     *
     * 호출자는 Exposed `transaction {}` 안에서 실행해야 합니다.
     */
    fun findByTenantAndDate(tenantGroupId: Long, date: LocalDate): HolidayRecord? =
        Holidays
            .selectAll()
            .where { Holidays.tenantGroupId eq tenantGroupId }
            .andWhere { Holidays.holidayDate eq date }
            .firstOrNull()
            ?.toHolidayRecord()

    internal fun findByDateRange(dateRange: ClosedRange<LocalDate>): List<HolidayRecord> =
        Holidays
            .selectAll()
            .where { Holidays.holidayDate greaterEq dateRange.start }
            .andWhere { Holidays.holidayDate lessEq dateRange.endInclusive }
            .map { it.toHolidayRecord() }

    /** 검증된 테넌트 범위의 기간별 공휴일을 조회합니다. */
    fun findByDateRange(tenantGroupId: Long, dateRange: ClosedRange<LocalDate>): List<HolidayRecord> =
        Holidays
            .selectAll()
            .where {
                (Holidays.tenantGroupId eq tenantGroupId) and
                    (Holidays.holidayDate greaterEq dateRange.start)
            }
            .andWhere { Holidays.holidayDate lessEq dateRange.endInclusive }
            .map { it.toHolidayRecord() }

    /** [TenantClinicScope]의 테넌트 범위에서 기간별 공휴일을 조회합니다. */
    fun findByDateRange(scope: TenantClinicScope, dateRange: ClosedRange<LocalDate>): List<HolidayRecord> =
        findByDateRange(scope.tenantGroupId, dateRange)
}
