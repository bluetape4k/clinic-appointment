package io.bluetape4k.clinic.appointment.waitlist

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.clinic.appointment.model.commitment.ResourceType
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.WaitlistVacancyJobs
import io.bluetape4k.clinic.appointment.model.waitlist.VacancyGenerationConflict
import io.bluetape4k.clinic.appointment.model.waitlist.VacancyJobState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistContention
import io.bluetape4k.clinic.appointment.repository.waitlist.ContentionRetryPolicy
import io.bluetape4k.clinic.appointment.repository.waitlist.NewVacancyJob
import io.bluetape4k.clinic.appointment.repository.waitlist.VacancyClaimMode
import io.bluetape4k.clinic.appointment.repository.waitlist.VacancyClaimStrategies
import io.bluetape4k.clinic.appointment.repository.waitlist.WaitlistDeliveryRepository
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withTables
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.time.Duration
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
            claim.leaseVersion shouldBeEqualTo 1L
            repository.completeOffer(claim.copy(owner = "worker-b"), now = NOW.plusSeconds(1), offerId = 10L).shouldBeFalse()
            repository.completeOffer(claim.copy(version = claim.version + 1), now = NOW.plusSeconds(1), offerId = 10L).shouldBeFalse()
            repository.completeOffer(claim.copy(leaseVersion = claim.leaseVersion + 1), now = NOW.plusSeconds(1), offerId = 10L).shouldBeFalse()
            repository.completeOffer(claim, now = NOW.plusSeconds(1), offerId = 10L).shouldBeTrue()

            val completed = requireNotNull(repository.findVacancy(job.id))
            completed.status shouldBeEqualTo VacancyJobState.OFFERED
            completed.resultOfferId shouldBeEqualTo 10L
            completed.version shouldBeEqualTo claim.version + 1
        }
    }

    @Test
    fun `terminal writes reject each lease fence independently`() {
        withDeliveryTables {
            val ownerMismatchJob = repository.insertVacancy(vacancy(vacancyGeneration = 1L))
            val ownerClaim = requireNotNull(
                repository.claim(ownerMismatchJob.id, owner = "worker-a", now = NOW, leaseUntil = NOW.plusSeconds(30)),
            )
            repository.completeOffer(ownerClaim.copy(owner = "worker-b"), now = NOW.plusSeconds(1), offerId = 10L).shouldBeFalse()

            val rowVersionJob = repository.insertVacancy(
                vacancy(vacancyGeneration = 2L, activeVacancyKey = "vacancy-key-2", sourceTransitionId = "transition-2"),
            )
            val rowVersionClaim = requireNotNull(
                repository.claim(rowVersionJob.id, owner = "worker-a", now = NOW, leaseUntil = NOW.plusSeconds(30)),
            )
            repository.completeOffer(rowVersionClaim.copy(version = rowVersionClaim.version + 1), now = NOW.plusSeconds(1), offerId = 10L)
                .shouldBeFalse()

            val leaseVersionJob = repository.insertVacancy(
                vacancy(vacancyGeneration = 3L, activeVacancyKey = "vacancy-key-3", sourceTransitionId = "transition-3"),
            )
            val leaseVersionClaim = requireNotNull(
                repository.claim(leaseVersionJob.id, owner = "worker-a", now = NOW, leaseUntil = NOW.plusSeconds(30)),
            )
            repository.completeOffer(
                leaseVersionClaim.copy(leaseVersion = leaseVersionClaim.leaseVersion + 1),
                now = NOW.plusSeconds(1),
                offerId = 10L,
            ).shouldBeFalse()

            val statusJob = repository.insertVacancy(
                vacancy(vacancyGeneration = 4L, activeVacancyKey = "vacancy-key-4", sourceTransitionId = "transition-4"),
            )
            val statusClaim = requireNotNull(
                repository.claim(statusJob.id, owner = "worker-a", now = NOW, leaseUntil = NOW.plusSeconds(30)),
            )
            WaitlistVacancyJobs.update({ WaitlistVacancyJobs.id eq statusJob.id }) {
                it[status] = VacancyJobState.READY
            }
            repository.completeOffer(statusClaim, now = NOW.plusSeconds(1), offerId = 10L).shouldBeFalse()

            val expiryJob = repository.insertVacancy(
                vacancy(vacancyGeneration = 5L, activeVacancyKey = "vacancy-key-5", sourceTransitionId = "transition-5"),
            )
            val expiryClaim = requireNotNull(
                repository.claim(expiryJob.id, owner = "worker-a", now = NOW, leaseUntil = NOW.plusSeconds(30)),
            )
            WaitlistVacancyJobs.update({ WaitlistVacancyJobs.id eq expiryJob.id }) {
                it[leaseExpiresAt] = NOW.minusSeconds(1)
            }
            repository.completeOffer(expiryClaim, now = NOW, offerId = 10L).shouldBeFalse()
        }
    }

    @Test
    fun `claim strategy exposes only the PostgreSQL lock timeout contract`() {
        val postgres = VacancyClaimStrategies.forDialectName("PostgreSQL")
        postgres.mode shouldBeEqualTo VacancyClaimMode.LOCKED_SELECTION
        postgres.claimSelectionSql("scheduling_waitlist_vacancy_jobs") shouldContain "FOR UPDATE"
        requireNotNull(postgres.lockTimeoutPlan).run {
            timeout shouldBeEqualTo Duration.ofSeconds(2)
            beforeClaimSql shouldBeEqualTo listOf("SET LOCAL lock_timeout = '2s'")
            afterClaimSql shouldBeEqualTo emptyList<String>()
        }
        assertFailsWith<IllegalArgumentException> { VacancyClaimStrategies.forDialectName("H2") }
        assertFailsWith<IllegalArgumentException> { VacancyClaimStrategies.forDialectName("MySQL") }
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
        val delays = mutableListOf<Duration>()
        val retryingRepository = WaitlistDeliveryRepository(
            retryPolicy = ContentionRetryPolicy(
                maxAttempts = 3,
                jitterDelay = { attempt -> Duration.ofMillis(attempt * 10L) },
                sleeper = { delay -> delays += delay },
            ),
        )

        assertFailsWith<WaitlistContention> {
            retryingRepository.withContentionRetry {
                throw SQLException("deadlock", "40P01")
            }
        }

        delays shouldBeEqualTo listOf(Duration.ofMillis(10), Duration.ofMillis(20))
    }

    @Test
    fun `contention retry returns value after retryable serialization failure`() {
        var calls = 0
        val retryingRepository = WaitlistDeliveryRepository(
            retryPolicy = ContentionRetryPolicy(
                maxAttempts = 3,
                jitterDelay = { attempt -> Duration.ofMillis(attempt * 10L) },
                sleeper = {},
            ),
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

    @Test
    fun `contention retry rethrows nonretryable sql states unchanged without sleeping`() {
        var calls = 0
        val delays = mutableListOf<Duration>()
        val retryingRepository = WaitlistDeliveryRepository(
            retryPolicy = ContentionRetryPolicy(
                maxAttempts = 3,
                jitterDelay = { attempt -> Duration.ofMillis(attempt * 10L) },
                sleeper = { delay -> delays += delay },
            ),
        )
        val nonretryable = SQLException("not retryable", "42000")

        val failure = assertFailsWith<SQLException> {
            retryingRepository.withContentionRetry {
                calls += 1
                throw nonretryable
            }
        }

        (failure === nonretryable) shouldBeEqualTo true
        calls shouldBeEqualTo 1
        delays shouldBeEqualTo emptyList<Duration>()
    }

    @Test
    fun `contention retry exhaustion preserves original retryable cause`() {
        val retryingRepository = WaitlistDeliveryRepository(
            retryPolicy = ContentionRetryPolicy(
                maxAttempts = 2,
                jitterDelay = { Duration.ZERO },
                sleeper = {},
            ),
        )
        val retryable = SQLException("serialization", "40001")

        val failure = assertFailsWith<WaitlistContention> {
            retryingRepository.withContentionRetry {
                throw retryable
            }
        }

        failure.cause shouldBeEqualTo retryable
    }

    @Test
    fun `contention retry restores interrupt flag and exposes interruption cause`() {
        val retryingRepository = WaitlistDeliveryRepository(
            retryPolicy = ContentionRetryPolicy(
                maxAttempts = 2,
                jitterDelay = { Duration.ofMillis(1) },
            ),
        )
        val retryable = SQLException("serialization", "40001")

        val failure =
            try {
                Thread.currentThread().interrupt()
                assertFailsWith<WaitlistContention> {
                    retryingRepository.withContentionRetry {
                        throw retryable
                    }
                }
            } finally {
                Thread.interrupted()
            }

        (failure.cause is InterruptedException) shouldBeEqualTo true
        Thread.currentThread().isInterrupted shouldBeEqualTo false
    }

    @Test
    fun `vendor-specific lock wait timeout is not retried`() {
        var calls = 0
        val delays = mutableListOf<Duration>()
        val repository = WaitlistDeliveryRepository(
            retryPolicy = ContentionRetryPolicy(
                maxAttempts = 3,
                jitterDelay = { Duration.ofMillis(5) },
                sleeper = { delay -> delays += delay },
            ),
        )
        val lockWait = SQLException("lock wait timeout", "HY000", 1205)

        val failure = assertFailsWith<SQLException> {
            repository.withContentionRetry {
                calls += 1
                throw lockWait
            }
        }

        (failure === lockWait) shouldBeEqualTo true
        calls shouldBeEqualTo 1
        delays shouldBeEqualTo emptyList<Duration>()
    }

    @Test
    fun `postgresql lock timeout retries with the PostgreSQL strategy`() {
        var postgresCalls = 0
        val postgresDelays = mutableListOf<Duration>()
        val postgresRepository = WaitlistDeliveryRepository(
            claimStrategy = VacancyClaimStrategies.forDialectName("PostgreSQL"),
            retryPolicy = ContentionRetryPolicy(
                maxAttempts = 3,
                jitterDelay = { Duration.ofMillis(5) },
                sleeper = { delay -> postgresDelays += delay },
            ),
        )

        postgresRepository.withContentionRetry {
            postgresCalls += 1
            if (postgresCalls == 1) {
                throw SQLException("lock timeout", "55P03")
            }
            "ok"
        } shouldBeEqualTo "ok"

        postgresCalls shouldBeEqualTo 2
        postgresDelays shouldBeEqualTo listOf(Duration.ofMillis(5))
    }

    private fun withDeliveryTables(block: org.jetbrains.exposed.v1.jdbc.JdbcTransaction.() -> Unit) {
        withTables(
            TestDB.POSTGRESQL,
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
