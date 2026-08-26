package io.bluetape4k.clinic.appointment.waitlist

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.clinic.appointment.model.commitment.ResourceType
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.WaitlistVacancyJobs
import io.bluetape4k.clinic.appointment.model.waitlist.VacancyJobState
import io.bluetape4k.clinic.appointment.waitlist.WaitlistFencingToken
import io.bluetape4k.clinic.appointment.repository.waitlist.ContentionRetryPolicy
import io.bluetape4k.clinic.appointment.repository.waitlist.NewVacancyJob
import io.bluetape4k.clinic.appointment.repository.waitlist.VacancyClaimStrategies
import io.bluetape4k.clinic.appointment.repository.waitlist.WaitlistDeliveryRepository
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withTables
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.inTopLevelTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** 실제 PostgreSQL lock timeout이 aborted transaction을 재사용하지 않는지 검증합니다. */
class WaitlistDeliveryPostgreSqlContentionTest {

    @Test
    fun `expired PostgreSQL worker cannot terminalize after a higher fenced takeover`() {
        withTables(TestDB.POSTGRESQL, Clinics, WaitlistVacancyJobs) {
            Clinics.insert {
                it[id] = EntityID(CLINIC_ID, Clinics)
                it[tenantGroupId] = EntityID(TENANT_GROUP_ID, TenantGroups)
                it[name] = "PostgreSQL fenced takeover clinic"
                it[slotDurationMinutes] = 30
                it[maxConcurrentPatients] = 1
            }
            val repository = WaitlistDeliveryRepository(
                claimStrategy = VacancyClaimStrategies.forDialectName("PostgreSQL"),
            )
            val job = insertFixture(repository, vacancyKey = "postgres-fenced-takeover", sourceAppointmentId = 103L)
            val first = repository.claimFenced(
                jobId = job.id,
                owner = "worker-a",
                now = NOW,
                leaseUntil = NOW.plusSeconds(5),
                token = WaitlistFencingToken(epoch = 7L, sequence = 1L),
            ).shouldNotBeNull()
            WaitlistVacancyJobs.update({ WaitlistVacancyJobs.id eq job.id }) {
                it[WaitlistVacancyJobs.leaseExpiresAt] = NOW.minusSeconds(1)
            }

            val second = repository.claimFenced(
                jobId = job.id,
                owner = "worker-b",
                now = NOW,
                leaseUntil = NOW.plusSeconds(30),
                token = WaitlistFencingToken(epoch = 7L, sequence = 2L),
            ).shouldNotBeNull()

            repository.completeOffer(first, now = NOW.plusSeconds(1), offerId = 10L).shouldBeFalse()
            repository.completeOffer(second, now = NOW.plusSeconds(1), offerId = 11L).shouldBeTrue()
            val persisted = repository.findVacancy(job.id).shouldNotBeNull()
            persisted.fenceEpoch shouldBeEqualTo 7L
            persisted.fenceSequence shouldBeEqualTo 2L
            persisted.status shouldBeEqualTo VacancyJobState.OFFERED
        }
    }

    @Test
    fun `PostgreSQL lock timeout aborts one attempt then retries in a fresh transaction`() {
        withTables(TestDB.POSTGRESQL, Clinics, WaitlistVacancyJobs) {
            val database = TestDB.POSTGRESQL.db ?: error("PostgreSQL database is not connected")
            val blockerAcquired = CountDownLatch(1)
            val releaseBlocker = CountDownLatch(1)
            val firstAttemptStarted = CountDownLatch(1)
            val transactionIdentities = CopyOnWriteArrayList<Int>()
            val retrySleeps = AtomicInteger()
            val firstAttemptFailure = AtomicReference<Throwable?>()
            val repository = WaitlistDeliveryRepository(
                claimStrategy = VacancyClaimStrategies.forDialectName("PostgreSQL"),
                retryPolicy = ContentionRetryPolicy(
                    maxAttempts = 3,
                    jitterDelay = { Duration.ZERO },
                    sleeper = {
                        retrySleeps.incrementAndGet()
                        releaseBlocker.countDown()
                    },
                ),
            )
            Clinics.insert {
                it[id] = EntityID(CLINIC_ID, Clinics)
                it[tenantGroupId] = EntityID(TENANT_GROUP_ID, TenantGroups)
                it[name] = "PostgreSQL contention clinic"
                it[slotDurationMinutes] = 30
                it[maxConcurrentPatients] = 1
            }
            val job = insertFixture(repository)
            commit()

            val executor = Executors.newFixedThreadPool(2)
            val blockerFuture = executor.submit<Throwable?> {
                try {
                    inTopLevelTransaction(db = database) {
                        maxAttempts = 1
                        WaitlistVacancyJobs
                            .selectAll()
                            .where { WaitlistVacancyJobs.id eq job.id }
                            .forUpdate()
                            .single()
                        blockerAcquired.countDown()
                        releaseBlocker.await(15, TimeUnit.SECONDS).shouldBeTrue()
                    }
                    null
                } catch (failure: Throwable) {
                    failure
                }
            }
            var retryFuture: Future<Throwable?>? = null

            try {
                blockerAcquired.await(10, TimeUnit.SECONDS).shouldBeTrue()
                retryFuture = executor.submit<Throwable?> {
                    try {
                        repository.withContentionRetry {
                            inTopLevelTransaction(db = database) {
                                maxAttempts = 1
                                transactionIdentities += System.identityHashCode(TransactionManager.current())
                                firstAttemptStarted.countDown()
                                try {
                                    requireNotNull(
                                        repository.claim(
                                            jobId = job.id,
                                            owner = OWNER,
                                            now = NOW,
                                            leaseUntil = NOW.plusSeconds(30),
                                        ),
                                    )
                                } catch (failure: Throwable) {
                                    firstAttemptFailure.compareAndSet(null, failure)
                                    throw failure
                                }
                            }
                        }
                        null
                    } catch (failure: Throwable) {
                        failure
                    }
                }
                firstAttemptStarted.await(10, TimeUnit.SECONDS).shouldBeTrue()

                val retryFailure = retryFuture.get(15, TimeUnit.SECONDS).also { failure ->
                    if (failure != null) {
                        throw failure
                    }
                }
                retryFailure shouldBeEqualTo null
                blockerFuture.get(10, TimeUnit.SECONDS) shouldBeEqualTo null

                firstAttemptFailure.get()?.sqlState() shouldBeEqualTo "55P03"
                retrySleeps.get() shouldBeEqualTo 1
                transactionIdentities.size shouldBeEqualTo 2
                transactionIdentities.toSet().size shouldBeEqualTo 2

                val completed = requireNotNull(repository.findVacancy(job.id))
                completed.status shouldBeEqualTo VacancyJobState.PROCESSING
                completed.leaseOwner shouldBeEqualTo OWNER
                completed.version shouldBeEqualTo 1L
                completed.leaseVersion shouldBeEqualTo 1L
            } finally {
                releaseBlocker.countDown()
                retryFuture?.cancel(true)
                blockerFuture.cancel(true)
                executor.shutdownNow()
                executor.awaitTermination(10, TimeUnit.SECONDS).shouldBeTrue()
            }
        }
    }

    @Test
    fun `PostgreSQL serializable contention retries in a fresh transaction`() {
        withTables(TestDB.POSTGRESQL, Clinics, WaitlistVacancyJobs) {
            val database = TestDB.POSTGRESQL.db ?: error("PostgreSQL database is not connected")
            val firstRead = CountDownLatch(1)
            val conflictCommitted = CountDownLatch(1)
            val callbackAttempts = AtomicInteger()
            val transactionIdentities = CopyOnWriteArrayList<Int>()
            val firstAttemptFailure = AtomicReference<Throwable?>()
            val repository = WaitlistDeliveryRepository(
                claimStrategy = VacancyClaimStrategies.forDialectName("PostgreSQL"),
                retryPolicy = ContentionRetryPolicy(
                    maxAttempts = 3,
                    jitterDelay = { Duration.ZERO },
                    sleeper = {},
                ),
            )
            Clinics.insert {
                it[id] = EntityID(CLINIC_ID, Clinics)
                it[tenantGroupId] = EntityID(TENANT_GROUP_ID, TenantGroups)
                it[name] = "PostgreSQL serializable clinic"
                it[slotDurationMinutes] = 30
                it[maxConcurrentPatients] = 1
            }
            val firstJob = insertFixture(
                repository = repository,
                vacancyKey = "postgres-serializable-a",
                sourceAppointmentId = 101L,
            )
            val secondJob = insertFixture(
                repository = repository,
                vacancyKey = "postgres-serializable-b",
                sourceAppointmentId = 102L,
            )
            commit()

            val executor = Executors.newFixedThreadPool(2)
            val conflictFuture = executor.submit<Throwable?> {
                try {
                    firstRead.await(10, TimeUnit.SECONDS).shouldBeTrue()
                    inTopLevelTransaction(
                        db = database,
                        transactionIsolation = Connection.TRANSACTION_SERIALIZABLE,
                    ) {
                        maxAttempts = 1
                        WaitlistVacancyJobs.selectAll().toList().size shouldBeEqualTo 2
                        WaitlistVacancyJobs.update({ WaitlistVacancyJobs.id eq firstJob.id }) {
                            it[WaitlistVacancyJobs.attempt] = 1
                        }
                    }
                    conflictCommitted.countDown()
                    null
                } catch (failure: Throwable) {
                    conflictCommitted.countDown()
                    failure
                }
            }
            val retryFuture = executor.submit<Throwable?> {
                try {
                    repository.withContentionRetry {
                        val attempt = callbackAttempts.incrementAndGet()
                        try {
                            inTopLevelTransaction(
                                db = database,
                                transactionIsolation = Connection.TRANSACTION_SERIALIZABLE,
                            ) {
                                maxAttempts = 1
                                transactionIdentities += System.identityHashCode(TransactionManager.current())
                                WaitlistVacancyJobs.selectAll().toList().size shouldBeEqualTo 2
                                if (attempt == 1) {
                                    firstRead.countDown()
                                    conflictCommitted.await(10, TimeUnit.SECONDS).shouldBeTrue()
                                }
                                requireNotNull(
                                    repository.claim(
                                        jobId = firstJob.id,
                                        owner = OWNER,
                                        now = NOW,
                                        leaseUntil = NOW.plusSeconds(30),
                                    ),
                                )
                                WaitlistVacancyJobs.update({ WaitlistVacancyJobs.id eq secondJob.id }) {
                                    it[WaitlistVacancyJobs.attempt] = 1
                                }
                            }
                        } catch (failure: Throwable) {
                            firstAttemptFailure.compareAndSet(null, failure)
                            throw failure
                        }
                    }
                    null
                } catch (failure: Throwable) {
                    failure
                }
            }

            try {
                val retryFailure = retryFuture.get(15, TimeUnit.SECONDS)
                if (retryFailure != null) {
                    throw retryFailure
                }
                retryFailure shouldBeEqualTo null
                conflictFuture.get(10, TimeUnit.SECONDS) shouldBeEqualTo null

                firstAttemptFailure.get()?.sqlState() shouldBeEqualTo "40001"
                callbackAttempts.get() shouldBeEqualTo 2
                transactionIdentities.size shouldBeEqualTo 2
                transactionIdentities.toSet().size shouldBeEqualTo 2

                val claimed = requireNotNull(repository.findVacancy(firstJob.id))
                claimed.status shouldBeEqualTo VacancyJobState.PROCESSING
                claimed.leaseOwner shouldBeEqualTo OWNER
                claimed.version shouldBeEqualTo 1L
                inTopLevelTransaction(db = database) {
                    WaitlistVacancyJobs
                        .selectAll()
                        .where { WaitlistVacancyJobs.id eq firstJob.id }
                        .single()[WaitlistVacancyJobs.attempt] shouldBeEqualTo 1
                    WaitlistVacancyJobs
                        .selectAll()
                        .where { WaitlistVacancyJobs.id eq secondJob.id }
                        .single()[WaitlistVacancyJobs.attempt] shouldBeEqualTo 1
                }
            } finally {
                conflictCommitted.countDown()
                retryFuture.cancel(true)
                conflictFuture.cancel(true)
                executor.shutdownNow()
                executor.awaitTermination(10, TimeUnit.SECONDS).shouldBeTrue()
            }
        }
    }

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.insertFixture(
        repository: WaitlistDeliveryRepository,
        vacancyKey: String = "postgres-lock-timeout",
        sourceAppointmentId: Long = 100L,
    ) = repository.insertVacancy(
        NewVacancyJob(
            tenantGroupId = TENANT_GROUP_ID,
            clinicId = CLINIC_ID,
            vacancyKey = vacancyKey,
            vacancyGeneration = 1L,
            activeVacancyKey = vacancyKey,
            sourceAppointmentId = sourceAppointmentId,
            sourceTransitionId = "$vacancyKey-transition",
            resourceType = ResourceType.PRACTITIONER,
            resourceId = "doctor-$DOCTOR_ID",
            capacityUnits = 1,
            maximumCapacity = 1,
            treatmentTypeId = TREATMENT_TYPE_ID,
            doctorId = DOCTOR_ID,
            policyVersion = 1L,
            nextAttemptAt = NOW,
            vacancyStartsAt = NOW.plusSeconds(60),
            vacancyEndsAt = NOW.plusSeconds(3_600),
            now = NOW,
        ),
    )

    private fun Throwable.sqlState(): String? {
        var current: Throwable? = this
        while (current != null) {
            if (current is SQLException) {
                return current.sqlState
            }
            current = current.cause
        }
        return null
    }

    private companion object {
        private const val TENANT_GROUP_ID = TenantGroups.DEFAULT_TENANT_GROUP_ID
        private const val CLINIC_ID = 30L
        private const val DOCTOR_ID = 40L
        private const val TREATMENT_TYPE_ID = 50L
        private const val OWNER = "postgres-lock-worker"
        private val NOW: Instant = Instant.parse("2026-08-17T00:00:00Z")
    }
}
