package io.bluetape4k.clinic.appointment.service

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

class EquipmentUnavailabilityServiceTest {

    companion object {
        private lateinit var db: Database
        private val service = EquipmentUnavailabilityService()

        private val allTables = arrayOf(
            Clinics,
            Equipments,
            EquipmentUnavailabilities,
            EquipmentUnavailabilityExceptions,
        )

        @JvmStatic
        @BeforeAll
        fun setup() {
            db = Database.connect(
                "jdbc:h2:mem:equip_unavail_svc_test;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
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
            SchemaUtils.createMissingTablesAndColumns(*allTables)
        }
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
    fun `1 - create 후 findById로 조회`() {
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

    @Test
    fun `2 - findUnavailablePeriodsInRange - 반복 스케줄 4회 전개`() {
        // 매주 월요일 반복 — 2026-04-01부터 2026-04-30은 월요일 4회 (4/6, 4/13, 4/20, 4/27)
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

    @Test
    fun `3 - findUnavailableOnDate - 해당 날짜 장비 맵 반환`() {
        val date = LocalDate.of(2026, 4, 15) // 수요일

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

    @Test
    fun `4 - delete 후 findById null`() {
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

    @Test
    fun `5 - addException SKIP 후 findUnavailablePeriodsInRange에서 제외됨`() {
        // 매주 수요일 반복
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

        // 2026-04-01 ~ 2026-04-30 수요일: 4/1, 4/8, 4/15, 4/22, 4/29 (5회)
        // 4/8을 SKIP 처리 → 4회만 반환되어야 함
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
