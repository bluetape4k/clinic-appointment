package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.EquipmentUnavailabilities
import io.bluetape4k.clinic.appointment.model.tables.EquipmentUnavailabilityExceptions
import io.bluetape4k.clinic.appointment.model.tables.Equipments
import io.bluetape4k.clinic.appointment.model.tables.ExceptionType
import io.bluetape4k.clinic.appointment.test.AbstractExposedTest
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withTables
import io.bluetape4k.logging.KLogging
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldHaveSize
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class EquipmentUnavailabilityServiceTest : AbstractExposedTest() {

    companion object : KLogging() {
        private val service = EquipmentUnavailabilityService()

        private val allTables = arrayOf(
            Clinics,
            Equipments,
            EquipmentUnavailabilities,
            EquipmentUnavailabilityExceptions,
        )
    }

    private fun JdbcTransaction.setupBaseData(): Pair<Long, Long> {
        val clinicId = Clinics.insertAndGetId {
            it[name] = "Test Clinic"
            it[slotDurationMinutes] = 30
            it[maxConcurrentPatients] = 1
        }.value

        val equipmentId = Equipments.insertAndGetId {
            it[Equipments.clinicId] = clinicId
            it[name] = "MRI Machine"
            it[usageDurationMinutes] = 60
            it[quantity] = 1
        }.value

        return clinicId to equipmentId
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `1 - create 후 findById로 조회`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, equipmentId) = setupBaseData()

            val record = service.create(
                equipmentId = equipmentId,
                clinicId = clinicId,
                unavailableDate = LocalDate.of(2026, 4, 10),
                isRecurring = false,
                recurringDayOfWeek = null,
                effectiveFrom = LocalDate.of(2026, 4, 10),
                effectiveUntil = LocalDate.of(2026, 4, 10),
                startTime = LocalTime.of(10, 0),
                endTime = LocalTime.of(12, 0),
                reason = "정기점검",
            )

            val found = service.findById(record.id)
            found.shouldNotBeNull()
            found.id shouldBeEqualTo record.id
            found.equipmentId shouldBeEqualTo equipmentId
            found.reason shouldBeEqualTo "정기점검"
            found.isRecurring shouldBeEqualTo false
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `2 - findUnavailablePeriodsInRange - 반복 스케줄 4회 전개`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, equipmentId) = setupBaseData()

            service.create(
                equipmentId = equipmentId,
                clinicId = clinicId,
                unavailableDate = null,
                isRecurring = true,
                recurringDayOfWeek = DayOfWeek.MONDAY,
                effectiveFrom = LocalDate.of(2026, 3, 1),
                effectiveUntil = LocalDate.of(2026, 12, 31),
                startTime = LocalTime.of(8, 0),
                endTime = LocalTime.of(9, 0),
                reason = "주간 정비",
            )

            val periods = service.findUnavailablePeriodsInRange(
                equipmentId = equipmentId,
                from = LocalDate.of(2026, 4, 1),
                to = LocalDate.of(2026, 4, 30),
            )

            periods shouldHaveSize 4
            periods.all { it.equipmentId == equipmentId } shouldBeEqualTo true
            periods.all { it.startTime == LocalTime.of(8, 0) } shouldBeEqualTo true
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `3 - findUnavailableOnDate - 해당 날짜 장비 맵 반환`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, equipmentId) = setupBaseData()
            val date = LocalDate.of(2026, 4, 15)

            service.create(
                equipmentId = equipmentId,
                clinicId = clinicId,
                unavailableDate = date,
                isRecurring = false,
                recurringDayOfWeek = null,
                effectiveFrom = date,
                effectiveUntil = date,
                startTime = LocalTime.of(11, 0),
                endTime = LocalTime.of(12, 0),
                reason = null,
            )

            val result = service.findUnavailableOnDate(clinicId, date)

            result.shouldNotBeNull()
            result.containsKey(equipmentId) shouldBeEqualTo true
            result[equipmentId]!!.shouldHaveSize(1)
            result[equipmentId]!![0].date shouldBeEqualTo date
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `4 - delete 후 findById null`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, equipmentId) = setupBaseData()

            val record = service.create(
                equipmentId = equipmentId,
                clinicId = clinicId,
                unavailableDate = LocalDate.of(2026, 5, 1),
                isRecurring = false,
                recurringDayOfWeek = null,
                effectiveFrom = LocalDate.of(2026, 5, 1),
                effectiveUntil = LocalDate.of(2026, 5, 1),
                startTime = LocalTime.of(9, 0),
                endTime = LocalTime.of(10, 0),
                reason = null,
            )

            service.delete(record.id)

            service.findById(record.id).shouldBeNull()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `5 - addException SKIP 후 findUnavailablePeriodsInRange에서 제외됨`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, equipmentId) = setupBaseData()

            val record = service.create(
                equipmentId = equipmentId,
                clinicId = clinicId,
                unavailableDate = null,
                isRecurring = true,
                recurringDayOfWeek = DayOfWeek.WEDNESDAY,
                effectiveFrom = LocalDate.of(2026, 1, 1),
                effectiveUntil = null,
                startTime = LocalTime.of(14, 0),
                endTime = LocalTime.of(15, 0),
                reason = null,
            )

            service.addException(
                unavailabilityId = record.id,
                originalDate = LocalDate.of(2026, 4, 8),
                exceptionType = ExceptionType.SKIP,
                rescheduledDate = null,
                rescheduledStartTime = null,
                rescheduledEndTime = null,
                reason = "공휴일",
            )

            val periods = service.findUnavailablePeriodsInRange(
                equipmentId = equipmentId,
                from = LocalDate.of(2026, 4, 1),
                to = LocalDate.of(2026, 4, 30),
            )

            periods shouldHaveSize 4
            periods.none { it.date == LocalDate.of(2026, 4, 8) } shouldBeEqualTo true
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `addException RESCHEDULE 후 findUnavailablePeriodsInRange에서 재스케줄됨`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, equipmentId) = setupBaseData()

            val record = service.create(
                equipmentId = equipmentId,
                clinicId = clinicId,
                unavailableDate = null,
                isRecurring = true,
                recurringDayOfWeek = DayOfWeek.WEDNESDAY,
                effectiveFrom = LocalDate.of(2026, 1, 1),
                effectiveUntil = null,
                startTime = LocalTime.of(14, 0),
                endTime = LocalTime.of(15, 0),
                reason = "정기 점검",
            )

            service.addException(
                unavailabilityId = record.id,
                originalDate = LocalDate.of(2026, 4, 8),
                exceptionType = ExceptionType.RESCHEDULE,
                rescheduledDate = LocalDate.of(2026, 4, 9),
                rescheduledStartTime = LocalTime.of(16, 0),
                rescheduledEndTime = LocalTime.of(17, 0),
                reason = "목요일로 이동",
            )

            val periods = service.findUnavailablePeriodsInRange(
                equipmentId = equipmentId,
                from = LocalDate.of(2026, 4, 1),
                to = LocalDate.of(2026, 4, 30),
            )

            periods.none { it.date == LocalDate.of(2026, 4, 8) } shouldBeEqualTo true
            periods.any { it.date == LocalDate.of(2026, 4, 9) && it.startTime == LocalTime.of(16, 0) } shouldBeEqualTo true
        }
    }
}
