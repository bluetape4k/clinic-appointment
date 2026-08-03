package io.bluetape4k.clinic.appointment.waitlist

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.model.commitment.ResourceType
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.WaitlistVacancyJobs
import io.bluetape4k.clinic.appointment.model.waitlist.VacancyGenerationConflict
import io.bluetape4k.clinic.appointment.model.waitlist.VacancyJobState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistContention
import io.bluetape4k.clinic.appointment.repository.waitlist.NewVacancyJob
import io.bluetape4k.clinic.appointment.repository.waitlist.WaitlistDeliveryRepository
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withTables
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.time.Instant

class WaitlistDeliveryRepositoryTest {
    private val repository = WaitlistDeliveryRepository()

    @Test
    fun `expired worker cannot complete an offer`() {
        withDeliveryTables {
            val job = repository.insertVacancy(vacancy(vacancyGeneration = 1L))
            val claim = requireNotNull(repository.claim(job.id, owner = "worker-a", now = NOW, leaseUntil = NOW.plusSeconds(30)))
            repository.completeOffer(claim, now = NOW.plusSeconds(31), offerId = 10L).shouldBeFalse()
            repository.findVacancy(job.id)?.status shouldBeEqualTo VacancyJobState.PROCESSING
        }
    }

    @Test
    fun `lease owner and version fence terminal updates`() {
        withDeliveryTables {
            val job = repository.insertVacancy(vacancy(vacancyGeneration = 1L))
            val claim = requireNotNull(repository.claim(job.id, owner = "worker-a", now = NOW, leaseUntil = NOW.plusSeconds(30)))
            repository.completeOffer(claim.copy(owner = "worker-b"), now = NOW.plusSeconds(1), offerId = 10L).shouldBeFalse()
            repository.completeOffer(claim.copy(version = claim.version + 1), now = NOW.plusSeconds(1), offerId = 10L).shouldBeFalse()
            repository.completeOffer(claim, now = NOW.plusSeconds(1), offerId = 10L).shouldBeTrue()

            val completed = requireNotNull(repository.findVacancy(job.id))
            completed.status shouldBeEqualTo VacancyJobState.OFFERED
            completed.resultOfferId shouldBeEqualTo 10L
            completed.version shouldBeEqualTo claim.version + 1
        }
    }

    @Test
    fun `next generation requires previous generation to be terminal`() {
        withDeliveryTables {
            val job = repository.insertVacancy(vacancy(vacancyGeneration = 1L))

            assertFailsWith<VacancyGenerationConflict> {
                repository.nextGeneration(job.id, now = NOW)
            }

            val claim = requireNotNull(repository.claim(job.id, owner = "worker-a", now = NOW, leaseUntil = NOW.plusSeconds(30)))
            repository.markNoCandidate(claim, now = NOW.plusSeconds(1)).shouldBeTrue()

            val next = repository.nextGeneration(job.id, now = NOW.plusSeconds(2))
            next.vacancyGeneration shouldBeEqualTo 2L
            next.activeVacancyKey shouldBeEqualTo "vacancy-key"
        }
    }

    @Test
    fun `active vacancy and source transition authorities reject duplicates`() {
        withDeliveryTables {
            repository.insertVacancy(vacancy(vacancyGeneration = 1L))

            assertFailsWith<ExposedSQLException> {
                repository.insertVacancy(vacancy(vacancyGeneration = 2L, sourceTransitionId = "transition-2"))
            }
            assertFailsWith<ExposedSQLException> {
                repository.insertVacancy(vacancy(vacancyGeneration = 1L, activeVacancyKey = "vacancy-key-2"))
            }
            assertFailsWith<ExposedSQLException> {
                repository.insertVacancy(vacancy(vacancyGeneration = 3L, activeVacancyKey = "vacancy-key-3"))
            }
        }
    }

    @Test
    fun `contention retry accepts retryable SQL states and stops after configured attempts`() {
        val attempts = mutableListOf<Int>()
        val retryingRepository = WaitlistDeliveryRepository(
            maxContentionRetries = 3,
            retryDelay = { attempt -> attempts += attempt },
        )

        assertFailsWith<WaitlistContention> {
            retryingRepository.withContentionRetry {
                throw SQLException("deadlock", "40P01")
            }
        }

        attempts shouldBeEqualTo listOf(1, 2)
    }

    @Test
    fun `contention retry returns value after retryable serialization failure`() {
        var calls = 0
        val retryingRepository = WaitlistDeliveryRepository(
            maxContentionRetries = 3,
            retryDelay = {},
        )

        val result = retryingRepository.withContentionRetry {
            calls += 1
            if (calls == 1) {
                throw SQLException("serialization", "40001")
            }
            "ok"
        }

        result shouldBeEqualTo "ok"
        calls shouldBeEqualTo 2
    }

    private fun withDeliveryTables(block: org.jetbrains.exposed.v1.jdbc.JdbcTransaction.() -> Unit) {
        withTables(
            TestDB.H2,
            Clinics,
            WaitlistVacancyJobs,
        ) {
            Clinics.insert {
                it[id] = EntityID(CLINIC_ID, Clinics)
                it[tenantGroupId] = EntityID(TenantGroups.DEFAULT_TENANT_GROUP_ID, TenantGroups)
                it[name] = "Waitlist Delivery Clinic"
                it[slotDurationMinutes] = 30
                it[maxConcurrentPatients] = 1
            }
            block()
        }
    }

    private fun vacancy(
        vacancyGeneration: Long,
        activeVacancyKey: String? = "vacancy-key",
        sourceTransitionId: String = "transition-1",
    ): NewVacancyJob =
        NewVacancyJob(
            tenantGroupId = TenantGroups.DEFAULT_TENANT_GROUP_ID,
            clinicId = CLINIC_ID,
            vacancyKey = "vacancy-key",
            vacancyGeneration = vacancyGeneration,
            activeVacancyKey = activeVacancyKey,
            sourceAppointmentId = 100L,
            sourceTransitionId = sourceTransitionId,
            resourceType = ResourceType.PRACTITIONER,
            resourceId = "doctor-20",
            capacityUnits = 1,
            maximumCapacity = 1,
            treatmentTypeId = 30L,
            doctorId = 20L,
            policyVersion = 1L,
            nextAttemptAt = NOW,
            vacancyStartsAt = Instant.parse("2026-08-01T09:00:00Z"),
            vacancyEndsAt = Instant.parse("2026-08-01T09:30:00Z"),
            now = NOW,
        )

    private companion object {
        private const val CLINIC_ID = 10L
        private val NOW: Instant = Instant.parse("2026-08-01T08:00:00Z")
    }
}
