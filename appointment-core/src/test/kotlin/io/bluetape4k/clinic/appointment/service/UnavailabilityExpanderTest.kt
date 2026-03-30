package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.clinic.appointment.model.dto.EquipmentUnavailabilityExceptionRecord
import io.bluetape4k.clinic.appointment.model.dto.EquipmentUnavailabilityRecord
import io.bluetape4k.clinic.appointment.model.tables.ExceptionType
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldHaveSize
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class UnavailabilityExpanderTest {

    private val equipmentId = 1L
    private val clinicId = 1L

    // 2026-04-01(수) ~ 2026-04-30(목) — 화요일은 4/7, 4/14, 4/21, 4/28
    private val april = LocalDate.of(2026, 4, 1)..LocalDate.of(2026, 4, 30)

    private fun recurringRule(
        effectiveFrom: LocalDate = LocalDate.of(2026, 1, 1),
        effectiveUntil: LocalDate? = null,
        dow: DayOfWeek = DayOfWeek.TUESDAY,
        startTime: LocalTime = LocalTime.of(10, 0),
        endTime: LocalTime = LocalTime.of(12, 0),
    ) = EquipmentUnavailabilityRecord(
        id = 1L,
        equipmentId = equipmentId,
        clinicId = clinicId,
        unavailableDate = null,
        isRecurring = true,
        recurringDayOfWeek = dow,
        effectiveFrom = effectiveFrom,
        effectiveUntil = effectiveUntil,
        startTime = startTime,
        endTime = endTime,
        reason = null,
    )

    private fun oneTimeRule(date: LocalDate) = EquipmentUnavailabilityRecord(
        id = 2L,
        equipmentId = equipmentId,
        clinicId = clinicId,
        unavailableDate = date,
        isRecurring = false,
        recurringDayOfWeek = null,
        effectiveFrom = date,
        effectiveUntil = date,
        startTime = LocalTime.of(14, 0),
        endTime = LocalTime.of(16, 0),
        reason = null,
    )

    private fun skipException(originalDate: LocalDate) = EquipmentUnavailabilityExceptionRecord(
        id = 100L,
        unavailabilityId = 1L,
        originalDate = originalDate,
        exceptionType = ExceptionType.SKIP,
        rescheduledDate = null,
        rescheduledStartTime = null,
        rescheduledEndTime = null,
        reason = null,
    )

    private fun rescheduleException(
        originalDate: LocalDate,
        rescheduledDate: LocalDate,
        rescheduledStart: LocalTime,
        rescheduledEnd: LocalTime,
    ) = EquipmentUnavailabilityExceptionRecord(
        id = 200L,
        unavailabilityId = 1L,
        originalDate = originalDate,
        exceptionType = ExceptionType.RESCHEDULE,
        rescheduledDate = rescheduledDate,
        rescheduledStartTime = rescheduledStart,
        rescheduledEndTime = rescheduledEnd,
        reason = null,
    )

    @Test
    fun `1 - 반복 규칙 예외 없이 해당 요일만 전개 — 4월 화요일 4회`() {
        val rule = recurringRule()
        val result = UnavailabilityExpander.expand(rule, emptyList<EquipmentUnavailabilityExceptionRecord>(), april)

        result shouldHaveSize 4
        result.map { it.date } shouldBeEqualTo listOf(
            LocalDate.of(2026, 4, 7),
            LocalDate.of(2026, 4, 14),
            LocalDate.of(2026, 4, 21),
            LocalDate.of(2026, 4, 28),
        )
        result.all { period -> period.equipmentId == equipmentId }.shouldBeTrue()
        result.all { period -> period.startTime == LocalTime.of(10, 0) }.shouldBeTrue()
        result.all { period -> period.endTime == LocalTime.of(12, 0) }.shouldBeTrue()
    }

    @Test
    fun `2 - SKIP 예외 — 4월 14일 제외하여 3회 반환`() {
        val rule = recurringRule()
        val exceptions = listOf(skipException(LocalDate.of(2026, 4, 14)))
        val result = UnavailabilityExpander.expand(rule, exceptions, april)

        result shouldHaveSize 3
        result.map { period -> period.date } shouldBeEqualTo listOf(
            LocalDate.of(2026, 4, 7),
            LocalDate.of(2026, 4, 21),
            LocalDate.of(2026, 4, 28),
        )
    }

    @Test
    fun `3 - RESCHEDULE 예외 — 4월 7일을 4월 8일 09 00 ~ 11 00로 변경`() {
        val rule = recurringRule()
        val exceptions = listOf(
            rescheduleException(
                originalDate = LocalDate.of(2026, 4, 7),
                rescheduledDate = LocalDate.of(2026, 4, 8),
                rescheduledStart = LocalTime.of(9, 0),
                rescheduledEnd = LocalTime.of(11, 0),
            )
        )
        val result = UnavailabilityExpander.expand(rule, exceptions, april)

        result shouldHaveSize 4
        val rescheduled = result.first { period -> period.date == LocalDate.of(2026, 4, 8) }
        rescheduled.startTime shouldBeEqualTo LocalTime.of(9, 0)
        rescheduled.endTime shouldBeEqualTo LocalTime.of(11, 0)
    }

    @Test
    fun `4 - 일회성 규칙 — 해당 날짜만 반환`() {
        val date = LocalDate.of(2026, 4, 15)
        val rule = oneTimeRule(date)
        val result = UnavailabilityExpander.expand(rule, emptyList<EquipmentUnavailabilityExceptionRecord>(), april)

        result shouldHaveSize 1
        result[0].date shouldBeEqualTo date
        result[0].startTime shouldBeEqualTo LocalTime.of(14, 0)
        result[0].endTime shouldBeEqualTo LocalTime.of(16, 0)
    }

    @Test
    fun `5 - 일회성 규칙 — 날짜가 range 밖이면 emptyList 반환`() {
        val date = LocalDate.of(2026, 5, 5)
        val rule = oneTimeRule(date)
        val result = UnavailabilityExpander.expand(rule, emptyList<EquipmentUnavailabilityExceptionRecord>(), april)

        result.shouldBeEmpty()
    }

    @Test
    fun `6 - effectiveUntil로 반복 종료 — effectiveUntil 2026-04-14이면 2회`() {
        val rule = recurringRule(effectiveUntil = LocalDate.of(2026, 4, 14))
        val result = UnavailabilityExpander.expand(rule, emptyList<EquipmentUnavailabilityExceptionRecord>(), april)

        result shouldHaveSize 2
        result.map { period -> period.date } shouldBeEqualTo listOf(
            LocalDate.of(2026, 4, 7),
            LocalDate.of(2026, 4, 14),
        )
    }
}
