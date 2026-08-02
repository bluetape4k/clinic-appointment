package io.bluetape4k.clinic.appointment.waitlist

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.model.commitment.ResourceType
import io.bluetape4k.clinic.appointment.model.waitlist.VacancyDescriptor
import io.bluetape4k.clinic.appointment.service.waitlist.WaitlistVacancyKeyHasher
import org.junit.jupiter.api.Test
import java.time.Instant

class WaitlistVacancyKeyHasherTest {

    @Test
    fun `same vacancy descriptor always produces the same lowercase sha256`() {
        val first = WaitlistVacancyKeyHasher.hash(vacancy())
        val second = WaitlistVacancyKeyHasher.hash(vacancy())

        first shouldBeEqualTo second
        first.length shouldBeEqualTo 64
        first shouldBeEqualTo first.lowercase()
    }

    @Test
    fun `resource time capacity treatment and doctor all participate in vacancy key`() {
        val base = WaitlistVacancyKeyHasher.hash(vacancy())

        distinctHashes(
            vacancy(resourceId = "doctor-21"),
            vacancy(startsAt = START.plusSeconds(300), endsAt = END.plusSeconds(300)),
            vacancy(capacityUnits = 2, maximumCapacity = 3, resourceType = ResourceType.CAPACITY_BUCKET),
            vacancy(treatmentTypeId = 31L),
            vacancy(doctorId = null),
        ).forEach { changed ->
            (changed == base) shouldBeEqualTo false
        }
    }

    private fun distinctHashes(vararg descriptors: VacancyDescriptor): List<String> =
        descriptors.map(WaitlistVacancyKeyHasher::hash).distinct()

    private fun vacancy(
        treatmentTypeId: Long = 30L,
        doctorId: Long? = 20L,
        startsAt: Instant = START,
        endsAt: Instant = END,
        resourceType: ResourceType = ResourceType.PRACTITIONER,
        resourceId: String = "doctor-20",
        capacityUnits: Int = 1,
        maximumCapacity: Int = 1,
    ): VacancyDescriptor =
        VacancyDescriptor(
            tenantGroupId = 1L,
            clinicId = 10L,
            treatmentTypeId = treatmentTypeId,
            doctorId = doctorId,
            startsAt = startsAt,
            endsAt = endsAt,
            resourceType = resourceType,
            resourceId = resourceId,
            capacityUnits = capacityUnits,
            maximumCapacity = maximumCapacity,
            now = NOW,
        )

    private companion object {
        private val NOW: Instant = Instant.parse("2026-08-01T08:00:00Z")
        private val START: Instant = Instant.parse("2026-08-01T09:00:00Z")
        private val END: Instant = Instant.parse("2026-08-01T09:30:00Z")
    }
}
