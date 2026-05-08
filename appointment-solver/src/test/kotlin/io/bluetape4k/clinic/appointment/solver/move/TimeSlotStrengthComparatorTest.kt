package io.bluetape4k.clinic.appointment.solver.move

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeLessThan
import org.junit.jupiter.api.Test
import java.time.LocalTime

class TimeSlotStrengthComparatorTest {

    private val comparator = TimeSlotStrengthComparator()

    @Test
    fun `earlier time is less than later time`() {
        val early = LocalTime.of(9, 0)
        val late = LocalTime.of(14, 0)

        comparator.compare(early, late) shouldBeLessThan 0
    }

    @Test
    fun `later time is greater than earlier time`() {
        val early = LocalTime.of(9, 0)
        val late = LocalTime.of(14, 0)

        comparator.compare(late, early) shouldBeGreaterThan 0
    }

    @Test
    fun `same time compares as equal`() {
        val time = LocalTime.of(10, 30)

        comparator.compare(time, time) shouldBeEqualTo 0
    }

    @Test
    fun `midnight is less than morning`() {
        val midnight = LocalTime.MIDNIGHT
        val morning = LocalTime.of(8, 0)

        comparator.compare(midnight, morning) shouldBeLessThan 0
    }

    @Test
    fun `comparison result is inverse of reversed arguments`() {
        val a = LocalTime.of(9, 0)
        val b = LocalTime.of(12, 0)

        val forward = comparator.compare(a, b)
        val reverse = comparator.compare(b, a)

        (forward < 0) shouldBeEqualTo (reverse > 0)
    }
}
