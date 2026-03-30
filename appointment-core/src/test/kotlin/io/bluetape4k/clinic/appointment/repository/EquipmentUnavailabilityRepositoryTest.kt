package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.EquipmentUnavailabilityExceptions
import io.bluetape4k.clinic.appointment.model.tables.EquipmentUnavailabilities
import io.bluetape4k.clinic.appointment.model.tables.Equipments
import io.bluetape4k.clinic.appointment.model.tables.ExceptionType
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldHaveSize
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class EquipmentUnavailabilityRepositoryTest {

    companion object {
        private lateinit var db: Database
        private val repo = EquipmentUnavailabilityRepository()

        private val allTables = arrayOf(
            Clinics,
            Equipments,
            EquipmentUnavailabilities,
            EquipmentUnavailabilityExceptions,
        )

        @JvmStatic
        @BeforeAll
        fun setup() {
            db = Database.connect("jdbc:h2:mem:equip_unavail_test;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
            transaction {
                SchemaUtils.create(*allTables)
            }
        }
    }

    private var clinicId: Long = 0L
    private var equipmentId: Long = 0L

    @BeforeEach
    fun cleanUp() {
        transaction {
            EquipmentUnavailabilityExceptions.deleteAll()
            EquipmentUnavailabilities.deleteAll()
            Equipments.deleteAll()
            Clinics.deleteAll()
        }
        transaction {
            val newClinicId = Clinics.insert {
                it[name] = "Test Clinic"
                it[slotDurationMinutes] = 30
                it[maxConcurrentPatients] = 1
            }[Clinics.id].value

            val newEquipmentId = Equipments.insert {
                it[Equipments.clinicId] = newClinicId
                it[name] = "MRI Machine"
                it[usageDurationMinutes] = 60
                it[quantity] = 1
            }[Equipments.id].value

            clinicId = newClinicId
            equipmentId = newEquipmentId
        }
    }

    @Test
    fun `1 - 일회성 사용불가 저장 및 날짜 범위 조회`() {
        val from = LocalDate.of(2026, 4, 1)
        val to = LocalDate.of(2026, 4, 30)

        val record = transaction {
            repo.create(
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
        }

        record.id shouldBeEqualTo record.id
        record.equipmentId shouldBeEqualTo equipmentId
        record.isRecurring shouldBeEqualTo false

        val results = transaction {
            repo.findByEquipment(equipmentId, from, to)
        }
        results shouldHaveSize 1
        results[0].unavailableDate shouldBeEqualTo LocalDate.of(2026, 4, 10)
        results[0].reason shouldBeEqualTo "정기점검"
    }

    @Test
    fun `2 - 반복 스케줄 저장 및 날짜 범위 포함 여부 확인`() {
        val from = LocalDate.of(2026, 4, 1)
        val to = LocalDate.of(2026, 4, 30)

        transaction {
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
        }

        val results = transaction {
            repo.findByEquipment(equipmentId, from, to)
        }
        results shouldHaveSize 1
        results[0].isRecurring shouldBeEqualTo true
        results[0].recurringDayOfWeek shouldBeEqualTo DayOfWeek.MONDAY
    }

    @Test
    fun `3 - 예외 SKIP 추가 및 조회`() {
        val unavailability = transaction {
            repo.create(
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
        }

        val exception = transaction {
            repo.addException(
                unavailabilityId = unavailability.id,
                originalDate = LocalDate.of(2026, 4, 8),
                exceptionType = ExceptionType.SKIP,
                rescheduledDate = null,
                rescheduledStartTime = null,
                rescheduledEndTime = null,
                reason = "공휴일",
            )
        }

        exception.exceptionType shouldBeEqualTo ExceptionType.SKIP
        exception.rescheduledDate.shouldBeNull()

        val exceptions = transaction {
            repo.findExceptions(unavailability.id)
        }
        exceptions shouldHaveSize 1
        exceptions[0].originalDate shouldBeEqualTo LocalDate.of(2026, 4, 8)
    }

    @Test
    fun `4 - 예외 RESCHEDULE 추가 및 조회`() {
        val unavailability = transaction {
            repo.create(
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
        }

        val exception = transaction {
            repo.addException(
                unavailabilityId = unavailability.id,
                originalDate = LocalDate.of(2026, 4, 10),
                exceptionType = ExceptionType.RESCHEDULE,
                rescheduledDate = LocalDate.of(2026, 4, 11),
                rescheduledStartTime = LocalTime.of(10, 0),
                rescheduledEndTime = LocalTime.of(11, 0),
                reason = "일정 변경",
            )
        }

        exception.exceptionType shouldBeEqualTo ExceptionType.RESCHEDULE
        exception.rescheduledDate shouldBeEqualTo LocalDate.of(2026, 4, 11)
        exception.rescheduledStartTime shouldBeEqualTo LocalTime.of(10, 0)

        val exceptions = transaction {
            repo.findExceptions(unavailability.id)
        }
        exceptions shouldHaveSize 1
        exceptions[0].reason shouldBeEqualTo "일정 변경"
    }

    @Test
    fun `5 - delete 후 조회 결과 없음`() {
        val record = transaction {
            repo.create(
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
        }

        transaction {
            repo.delete(record.id)
        }

        val found = transaction {
            repo.findById(record.id)
        }
        found.shouldBeNull()

        val results = transaction {
            repo.findByEquipment(equipmentId, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31))
        }
        results shouldHaveSize 0
    }

    @Test
    fun `findByClinicOnDate - 날짜 기준 병원 전체 장비 조회`() {
        val date = LocalDate.of(2026, 4, 15)

        transaction {
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
        }

        val results = transaction {
            repo.findByClinicOnDate(clinicId, date)
        }
        results shouldHaveSize 1
        results[0].clinicId shouldBeEqualTo clinicId
    }

    @Test
    fun `deleteException - 예외 삭제 후 조회 결과 없음`() {
        val unavailability = transaction {
            repo.create(
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
        }

        val exception = transaction {
            repo.addException(
                unavailabilityId = unavailability.id,
                originalDate = LocalDate.of(2026, 4, 7),
                exceptionType = ExceptionType.SKIP,
                rescheduledDate = null,
                rescheduledStartTime = null,
                rescheduledEndTime = null,
                reason = null,
            )
        }

        transaction {
            repo.deleteException(exception.id)
        }

        val exceptions = transaction {
            repo.findExceptions(unavailability.id)
        }
        exceptions shouldHaveSize 0
    }
}
