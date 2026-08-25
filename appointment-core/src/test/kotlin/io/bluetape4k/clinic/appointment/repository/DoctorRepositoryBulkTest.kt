package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.DoctorAbsences
import io.bluetape4k.clinic.appointment.model.tables.DoctorSchedules
import io.bluetape4k.clinic.appointment.model.tables.Doctors
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.test.AbstractExposedTest
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withTables
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.core.statements.api.PreparedStatementApi
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/** 의사 planning fact bulk 조회의 범위·순서·SQL budget 계약을 고정합니다. */
class DoctorRepositoryBulkTest : AbstractExposedTest() {

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `여러 의사의 스케줄과 부재를 두 번의 범위 조회로 읽고 tenant 경계를 지킨다`(testDB: TestDB) {
        withTables(testDB, Clinics, Doctors, DoctorSchedules, DoctorAbsences) {
            val fixture = insertFixture()
            val capture = SqlStatementCapture()
            registerInterceptor(capture)
            capture.statements.clear()

            val repository = DoctorRepository()
            val scope = TenantClinicScope(TenantGroups.DEFAULT_TENANT_GROUP_ID, fixture.clinicId)
            val scheduleByDoctor = repository.findAllSchedulesByDoctorIds(
                scope = scope,
                doctorIds = fixture.doctorIds + fixture.doctorIds.first() + fixture.foreignDoctorId,
            )
            val absenceByDoctor = repository.findAbsencesByDoctorIdsAndDateRange(
                scope = scope,
                doctorIds = fixture.doctorIds + fixture.foreignDoctorId,
                dateRange = LocalDate.of(2026, 3, 23)..LocalDate.of(2026, 3, 27),
            )

            scheduleByDoctor.keys.toList() shouldBeEqualTo fixture.doctorIds
            scheduleByDoctor.values.forEach { it shouldHaveSize 2 }
            scheduleByDoctor[fixture.doctorIds.first()]!!.mapNotNull { it.id } shouldBeEqualTo
                scheduleByDoctor[fixture.doctorIds.first()]!!.mapNotNull { it.id }.sorted()
            absenceByDoctor.keys.toList() shouldBeEqualTo listOf(fixture.doctorIds.first())
            absenceByDoctor[fixture.doctorIds.first()]!!.map { it.absenceDate }
                .shouldBeEqualTo(listOf(LocalDate.of(2026, 3, 24)))
            absenceByDoctor[fixture.doctorIds.last()].orEmpty().shouldBeEmpty()
            scheduleByDoctor[fixture.foreignDoctorId].shouldBeNull()
            absenceByDoctor[fixture.foreignDoctorId].shouldBeNull()

            capture.statements.count { it.contains("from scheduling_doctor_schedules") }
                .shouldBeEqualTo(1)
            capture.statements.count { it.contains("from scheduling_doctor_absences") }
                .shouldBeEqualTo(1)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `의사 수가 늘어도 planning fact bulk 조회의 SQL 수는 고정된다`(testDB: TestDB) {
        withTables(testDB, Clinics, Doctors, DoctorSchedules, DoctorAbsences) {
            val fixture = insertFixture(doctorCount = 100)
            val capture = SqlStatementCapture()
            registerInterceptor(capture)
            capture.statements.clear()

            val repository = DoctorRepository()
            val scope = TenantClinicScope(TenantGroups.DEFAULT_TENANT_GROUP_ID, fixture.clinicId)
            val startedAt = System.nanoTime()
            val schedules = repository.findAllSchedulesByDoctorIds(scope, fixture.doctorIds)
            val absences = repository.findAbsencesByDoctorIdsAndDateRange(
                scope,
                fixture.doctorIds,
                LocalDate.of(2026, 3, 23)..LocalDate.of(2026, 3, 27),
            )
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

            schedules.size.shouldBeEqualTo(fixture.doctorIds.size)
            absences.size.shouldBeEqualTo(1)
            capture.statements.count { it.contains("from scheduling_doctor_schedules") }
                .shouldBeEqualTo(1)
            capture.statements.count { it.contains("from scheduling_doctor_absences") }
                .shouldBeEqualTo(1)
            (elapsedMillis >= 0L).shouldBeTrue()
            println(
                "DOCTOR_PLANNING_FACT_BULK_BENCHMARK " +
                    "db=${testDB.name} doctors=${fixture.doctorIds.size} elapsedMs=$elapsedMillis " +
                    "scheduleQueries=1 absenceQueries=1",
            )
        }
    }

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.insertFixture(doctorCount: Int = 3): Fixture {
        val clinicId = Clinics.insertAndGetId {
            it[Clinics.tenantGroupId] = EntityID(TenantGroups.DEFAULT_TENANT_GROUP_ID, TenantGroups)
            it[name] = "Bulk Clinic"
            it[slotDurationMinutes] = 30
            it[maxConcurrentPatients] = 1
        }.value

        val doctorIds = (1..doctorCount).map { index ->
            Doctors.insertAndGetId {
                it[Doctors.clinicId] = clinicId
                it[name] = "Doctor $index"
            }.value
        }
        doctorIds.forEachIndexed { index, doctorId ->
            DoctorSchedules.insert {
                it[DoctorSchedules.doctorId] = doctorId
                it[dayOfWeek] = DayOfWeek.MONDAY
                it[startTime] = LocalTime.of(9, 0)
                it[endTime] = LocalTime.of(12, 0)
            }
            DoctorSchedules.insert {
                it[DoctorSchedules.doctorId] = doctorId
                it[dayOfWeek] = DayOfWeek.TUESDAY
                it[startTime] = LocalTime.of(13, 0)
                it[endTime] = LocalTime.of(18, 0)
            }
            if (index == 0) {
                DoctorAbsences.insert {
                    it[DoctorAbsences.doctorId] = doctorId
                    it[absenceDate] = LocalDate.of(2026, 3, 24)
                    it[startTime] = LocalTime.of(10, 0)
                    it[endTime] = LocalTime.of(11, 0)
                    it[reason] = "Training"
                }
                DoctorAbsences.insert {
                    it[DoctorAbsences.doctorId] = doctorId
                    it[absenceDate] = LocalDate.of(2026, 4, 1)
                    it[startTime] = null
                    it[endTime] = null
                    it[reason] = "Out of range"
                }
            }
        }

        val foreignTenantId = 2L
        TenantGroups.insert {
            it[id] = EntityID(foreignTenantId, TenantGroups)
            it[tenantCode] = "foreign"
            it[displayName] = "Foreign Tenant"
            it[active] = true
        }
        val foreignClinicId = Clinics.insertAndGetId {
            it[Clinics.tenantGroupId] = EntityID(foreignTenantId, TenantGroups)
            it[name] = "Foreign Clinic"
            it[slotDurationMinutes] = 30
            it[maxConcurrentPatients] = 1
        }.value
        val foreignDoctorId = Doctors.insertAndGetId {
            it[Doctors.clinicId] = foreignClinicId
            it[name] = "Foreign Doctor"
        }.value
        DoctorSchedules.insert {
            it[DoctorSchedules.doctorId] = foreignDoctorId
            it[dayOfWeek] = DayOfWeek.MONDAY
            it[startTime] = LocalTime.of(9, 0)
            it[endTime] = LocalTime.of(12, 0)
        }
        DoctorAbsences.insert {
            it[DoctorAbsences.doctorId] = foreignDoctorId
            it[absenceDate] = LocalDate.of(2026, 3, 24)
            it[startTime] = null
            it[endTime] = null
            it[reason] = "Foreign"
        }

        return Fixture(clinicId, doctorIds, foreignDoctorId)
    }

    private data class Fixture(
        val clinicId: Long,
        val doctorIds: List<Long>,
        val foreignDoctorId: Long,
    )

    private class SqlStatementCapture : StatementInterceptor {
        val statements = mutableListOf<String>()

        override fun afterExecution(
            transaction: Transaction,
            contexts: List<StatementContext>,
            executedStatement: PreparedStatementApi,
        ) {
            contexts.firstOrNull()?.let { context ->
                statements += context.sql(transaction).lowercase()
            }
        }
    }
}
