package io.bluetape4k.clinic.appointment.solver.move

import io.bluetape4k.clinic.appointment.solver.domain.AppointmentPlanning
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeLessThan
import org.junit.jupiter.api.Test
import java.time.LocalDate

class AppointmentDifficultyComparatorTest {

    private val comparator = AppointmentDifficultyComparator()

    private fun planning(
        requiresEquipment: Boolean = false,
        durationMinutes: Int = 30,
        requestedDate: LocalDate? = null,
    ) = AppointmentPlanning(
        id = 1L,
        durationMinutes = durationMinutes,
        requiresEquipment = requiresEquipment,
        requestedDate = requestedDate,
    )

    @Test
    fun `equipment required ranks higher than no equipment`() {
        val withEquipment = planning(requiresEquipment = true)
        val withoutEquipment = planning(requiresEquipment = false)

        comparator.compare(withEquipment, withoutEquipment) shouldBeGreaterThan 0
    }

    @Test
    fun `longer duration ranks higher within same equipment requirement`() {
        val longer = planning(durationMinutes = 60)
        val shorter = planning(durationMinutes = 30)

        comparator.compare(longer, shorter) shouldBeGreaterThan 0
    }

    @Test
    fun `requested date ranks higher when equipment and duration equal`() {
        val withDate = planning(requestedDate = LocalDate.of(2026, 4, 6))
        val withoutDate = planning(requestedDate = null)

        comparator.compare(withDate, withoutDate) shouldBeGreaterThan 0
    }

    @Test
    fun `identical appointments compare as equal`() {
        val a = planning()
        val b = planning()

        comparator.compare(a, b) shouldBeEqualTo 0
    }

    @Test
    fun `equipment requirement dominates over duration`() {
        val shortWithEquipment = planning(requiresEquipment = true, durationMinutes = 10)
        val longWithoutEquipment = planning(requiresEquipment = false, durationMinutes = 120)

        comparator.compare(shortWithEquipment, longWithoutEquipment) shouldBeGreaterThan 0
    }

    @Test
    fun `reverse comparison is symmetric`() {
        val a = planning(requiresEquipment = true)
        val b = planning(requiresEquipment = false)

        val forward = comparator.compare(a, b)
        val reverse = comparator.compare(b, a)

        (forward > 0) shouldBeEqualTo (reverse < 0)
    }

    @Test
    fun `no date is less than with date`() {
        val withDate = planning(requestedDate = LocalDate.of(2026, 5, 1))
        val withoutDate = planning(requestedDate = null)

        comparator.compare(withoutDate, withDate) shouldBeLessThan 0
    }
}
