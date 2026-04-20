package io.bluetape4k.clinic.appointment.repository

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
import org.amshove.kluent.shouldBeGreaterThan
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

class EquipmentUnavailabilityRepositoryTest : AbstractExposedTest() {

    companion object : KLogging() {
        private val repo = EquipmentUnavailabilityRepository()

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
    fun `1 - 일회성 사용불가 저장 및 날짜 범위 조회`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, equipmentId) = setupBaseData()
            val from = LocalDate.of(2026, 4, 1)
            val to = LocalDate.of(2026, 4, 30)

            val record = repo.create(
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

            record.id shouldBeGreaterThan 0L

            val found = repo.findById(record.id)
            found.shouldNotBeNull()
            found.reason shouldBeEqualTo "정기점검"
            record.equipmentId shouldBeEqualTo equipmentId
            record.isRecurring shouldBeEqualTo false

            val results = repo.findByEquipment(equipmentId, from, to)
            results shouldHaveSize 1
            results[0].unavailableDate shouldBeEqualTo LocalDate.of(2026, 4, 10)
            results[0].reason shouldBeEqualTo "정기점검"
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `2 - 반복 스케줄 저장 및 날짜 범위 포함 여부 확인`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, equipmentId) = setupBaseData()
            val from = LocalDate.of(2026, 4, 1)
            val to = LocalDate.of(2026, 4, 30)

            repo.create(
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

            val results = repo.findByEquipment(equipmentId, from, to)
            results shouldHaveSize 1
            results[0].isRecurring shouldBeEqualTo true
            results[0].recurringDayOfWeek shouldBeEqualTo DayOfWeek.MONDAY
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `3 - 예외 SKIP 추가 및 조회`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, equipmentId) = setupBaseData()

            val unavailability = repo.create(
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

            val exception = repo.addException(
                unavailabilityId = unavailability.id,
                originalDate = LocalDate.of(2026, 4, 8),
                exceptionType = ExceptionType.SKIP,
                rescheduledDate = null,
                rescheduledStartTime = null,
                rescheduledEndTime = null,
                reason = "공휴일",
            )

            exception.exceptionType shouldBeEqualTo ExceptionType.SKIP
            exception.rescheduledDate.shouldBeNull()

            val exceptions = repo.findExceptions(unavailability.id)
            exceptions shouldHaveSize 1
            exceptions[0].originalDate shouldBeEqualTo LocalDate.of(2026, 4, 8)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `4 - 예외 RESCHEDULE 추가 및 조회`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, equipmentId) = setupBaseData()

            val unavailability = repo.create(
                equipmentId = equipmentId,
                clinicId = clinicId,
                unavailableDate = null,
                isRecurring = true,
                recurringDayOfWeek = DayOfWeek.FRIDAY,
                effectiveFrom = LocalDate.of(2026, 1, 1),
                effectiveUntil = null,
                startTime = LocalTime.of(9, 0),
                endTime = LocalTime.of(10, 0),
                reason = null,
            )

            val exception = repo.addException(
                unavailabilityId = unavailability.id,
                originalDate = LocalDate.of(2026, 4, 10),
                exceptionType = ExceptionType.RESCHEDULE,
                rescheduledDate = LocalDate.of(2026, 4, 11),
                rescheduledStartTime = LocalTime.of(10, 0),
                rescheduledEndTime = LocalTime.of(11, 0),
                reason = "일정 변경",
            )

            exception.exceptionType shouldBeEqualTo ExceptionType.RESCHEDULE
            exception.rescheduledDate shouldBeEqualTo LocalDate.of(2026, 4, 11)
            exception.rescheduledStartTime shouldBeEqualTo LocalTime.of(10, 0)

            val exceptions = repo.findExceptions(unavailability.id)
            exceptions shouldHaveSize 1
            exceptions[0].reason shouldBeEqualTo "일정 변경"
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `5 - delete 후 조회 결과 없음`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, equipmentId) = setupBaseData()

            val record = repo.create(
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

            repo.delete(record.id)

            repo.findById(record.id).shouldBeNull()

            val results = repo.findByEquipment(equipmentId, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31))
            results shouldHaveSize 0
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `findByClinicOnDate - 날짜 기준 병원 전체 장비 조회`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, equipmentId) = setupBaseData()
            val date = LocalDate.of(2026, 4, 15)

            // date 범위 내
            repo.create(
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
            // date 범위 외 (미래)
            repo.create(
                equipmentId = equipmentId,
                clinicId = clinicId,
                unavailableDate = null,
                isRecurring = false,
                recurringDayOfWeek = null,
                effectiveFrom = date.plusDays(1),
                effectiveUntil = null,
                startTime = LocalTime.of(13, 0),
                endTime = LocalTime.of(14, 0),
                reason = null,
            )

            val results = repo.findByClinicOnDate(clinicId, date)
            results shouldHaveSize 1
            results[0].clinicId shouldBeEqualTo clinicId
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `deleteException - 예외 삭제 후 조회 결과 없음`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, equipmentId) = setupBaseData()

            val unavailability = repo.create(
                equipmentId = equipmentId,
                clinicId = clinicId,
                unavailableDate = null,
                isRecurring = true,
                recurringDayOfWeek = DayOfWeek.TUESDAY,
                effectiveFrom = LocalDate.of(2026, 1, 1),
                effectiveUntil = null,
                startTime = LocalTime.of(10, 0),
                endTime = LocalTime.of(11, 0),
                reason = null,
            )

            val exception = repo.addException(
                unavailabilityId = unavailability.id,
                originalDate = LocalDate.of(2026, 4, 7),
                exceptionType = ExceptionType.SKIP,
                rescheduledDate = null,
                rescheduledStartTime = null,
                rescheduledEndTime = null,
                reason = null,
            )

            repo.deleteException(exception.id)

            val exceptions = repo.findExceptions(unavailability.id)
            exceptions shouldHaveSize 0
        }
    }
}
