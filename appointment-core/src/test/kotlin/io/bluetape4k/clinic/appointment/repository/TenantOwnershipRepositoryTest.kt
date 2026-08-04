package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.Holidays
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.test.AbstractExposedTest
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withTables
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.LocalDate

class TenantOwnershipRepositoryTest : AbstractExposedTest() {

    companion object {
        private const val TENANT_A = TenantGroups.DEFAULT_TENANT_GROUP_ID
        private const val TENANT_B = 2L
    }

    private val clinicRepository = ClinicRepository()
    private val holidayRepository = HolidayRepository()

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `tenant별 clinic 목록만 ID 순서로 조회한다`(testDB: TestDB) {
        withTables(testDB, TenantGroups, Clinics) {
            insertTenantB()
            val tenantASecond = insertClinic(30L, TENANT_A, "Tenant A Second")
            val tenantB = insertClinic(20L, TENANT_B, "Tenant B Clinic")
            val tenantAFirst = insertClinic(10L, TENANT_A, "Tenant A First")

            val result = clinicRepository.findByTenant(TENANT_A)

            result shouldHaveSize 2
            result.map { it.id } shouldBeEqualTo listOf(tenantASecond, tenantAFirst).sorted()
            result.none { it.id == tenantB } shouldBeEqualTo true
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `같은 날짜의 holiday를 tenant별로 분리한다`(testDB: TestDB) {
        withTables(testDB, TenantGroups, Holidays) {
            insertTenantB()
            val date = LocalDate.of(2026, 5, 5)
            insertHoliday(TENANT_A, date, "Tenant A Holiday")
            insertHoliday(TENANT_B, date, "Tenant B Holiday")

            holidayRepository.findByTenantAndDate(TENANT_A, date)
                .shouldNotBeNull()
                .name shouldBeEqualTo "Tenant A Holiday"
            holidayRepository.findByTenantAndDate(TENANT_B, date)
                .shouldNotBeNull()
                .name shouldBeEqualTo "Tenant B Holiday"
            holidayRepository.findByTenantAndDate(TENANT_A, date.plusDays(1)).shouldBeNull()
        }
    }

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.insertTenantB() {
        TenantGroups.insert {
            it[id] = EntityID(TENANT_B, TenantGroups)
            it[tenantCode] = "tenant-b"
            it[displayName] = "Tenant B"
            it[active] = true
        }
    }

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.insertClinic(
        clinicId: Long,
        tenantGroupId: Long,
        clinicName: String,
    ): Long =
        Clinics.insertAndGetId {
            it[id] = EntityID(clinicId, Clinics)
            it[Clinics.tenantGroupId] = EntityID(tenantGroupId, TenantGroups)
            it[name] = clinicName
            it[slotDurationMinutes] = 30
            it[maxConcurrentPatients] = 1
        }.value

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.insertHoliday(
        tenantGroupId: Long,
        date: LocalDate,
        holidayName: String,
    ) {
        Holidays.insert {
            it[Holidays.tenantGroupId] = EntityID(tenantGroupId, TenantGroups)
            it[holidayDate] = date
            it[name] = holidayName
            it[recurring] = false
        }
    }
}
