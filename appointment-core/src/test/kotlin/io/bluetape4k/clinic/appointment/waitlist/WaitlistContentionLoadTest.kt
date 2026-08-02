package io.bluetape4k.clinic.appointment.waitlist

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeLessOrEqualTo
import io.bluetape4k.clinic.appointment.model.commitment.ResourceType
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.Doctors
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import io.bluetape4k.clinic.appointment.model.tables.WaitlistCapacityHolds
import io.bluetape4k.clinic.appointment.model.tables.WaitlistEntries
import io.bluetape4k.clinic.appointment.model.tables.WaitlistOffers
import io.bluetape4k.clinic.appointment.model.waitlist.ActorRef
import io.bluetape4k.clinic.appointment.model.waitlist.CorrelationId
import io.bluetape4k.clinic.appointment.model.waitlist.DecisionStamp
import io.bluetape4k.clinic.appointment.model.waitlist.NoEligibleCandidate
import io.bluetape4k.clinic.appointment.model.waitlist.OfferAlreadyExists
import io.bluetape4k.clinic.appointment.model.waitlist.SlotOccupied
import io.bluetape4k.clinic.appointment.model.waitlist.VacancyDescriptor
import io.bluetape4k.clinic.appointment.model.waitlist.VersionConflict
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistEntryState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistOfferState
import io.bluetape4k.clinic.appointment.repository.CommitmentSeed
import io.bluetape4k.clinic.appointment.repository.ResourceAllocationConflictException
import io.bluetape4k.clinic.appointment.repository.ResourceAllocationRepository
import io.bluetape4k.clinic.appointment.repository.waitlist.WaitlistRepository
import io.bluetape4k.clinic.appointment.repository.withCommitmentTables
import io.bluetape4k.clinic.appointment.service.waitlist.WaitlistCandidateMatcher
import io.bluetape4k.clinic.appointment.service.waitlist.WaitlistOfferService
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

/**
 * bounded JDBC pool에서 동일 vacancy를 동시에 승격할 때의 DB 경계를 검증합니다.
 *
 * 테스트는 `MultithreadingTester` 대신 실제 Exposed [transaction]과 bounded
 * [Executors]를 사용합니다. 그래야 각 시도가 별도 JDBC transaction을 소유하고,
 * resource mutex와 active-key unique 제약의 실제 경합 결과를 관찰할 수 있습니다.
 */
class WaitlistContentionLoadTest {

    @Test
    fun `one hundred concurrent offers produce one winner and bounded conflicts`() {
        withCommitmentTables { seed ->
            val doctorId = seed.doctorId()
            val treatmentTypeId = seed.treatmentTypeId()
            val decisions = (0 until ATTEMPT_COUNT).associate { index ->
                MemberId(memberId(index)) to decision(seed, memberId(index))
            }
            repeat(ATTEMPT_COUNT) { index ->
                insertWaitingEntry(
                    clinicId = seed.clinicId,
                    doctorId = doctorId,
                    treatmentTypeId = treatmentTypeId,
                    memberId = memberId(index),
                    waitingSince = NOW.minusSeconds(index.toLong()),
                )
            }
            commit()

            val matcher = WaitlistCandidateMatcher(
                repository = WaitlistRepository(),
                decisionPort = { _, memberIds, _ -> decisions.filterKeys { it in memberIds } },
            )
            val service = WaitlistOfferService(
                matcher = matcher,
                waitlistRepository = WaitlistRepository(),
                resourceAllocationRepository = ResourceAllocationRepository(),
            )
            val database = TestDB.H2_COMMITMENT.db ?: error("H2 commitment database must be connected")
            val executor = Executors.newFixedThreadPool(POOL_SIZE)
            val start = CountDownLatch(1)
            val done = CountDownLatch(ATTEMPT_COUNT)
            val durationsNanos = ConcurrentLinkedQueue<Long>()
            val unexpectedFailures = ConcurrentLinkedQueue<String>()
            val winners = AtomicInteger(0)
            val stableConflicts = AtomicInteger(0)

            try {
                repeat(ATTEMPT_COUNT) { index ->
                    executor.submit {
                        val startedAt = System.nanoTime()
                        try {
                            start.await(10, TimeUnit.SECONDS) shouldBeEqualTo true
                            transaction(database) {
                                service.selectAndOffer(
                                    vacancy = vacancy(seed, doctorId, treatmentTypeId),
                                    correlationId = CorrelationId("contention:$index"),
                                    actorRef = ActorRef("SYSTEM"),
                                )
                            }
                            winners.incrementAndGet()
                        } catch (failure: Throwable) {
                            when (failure) {
                                is SlotOccupied,
                                is OfferAlreadyExists,
                                is NoEligibleCandidate,
                                is VersionConflict,
                                is ResourceAllocationConflictException,
                                -> stableConflicts.incrementAndGet()

                                else -> unexpectedFailures += (failure::class.qualifiedName ?: "unknown")
                            }
                        } finally {
                            durationsNanos += System.nanoTime() - startedAt
                            done.countDown()
                        }
                    }
                }
                start.countDown()
                done.await(30, TimeUnit.SECONDS) shouldBeEqualTo true
            } finally {
                executor.shutdownNow()
                executor.awaitTermination(30, TimeUnit.SECONDS)
            }

            val durations = durationsNanos.toList().sorted()
            durations.size shouldBeEqualTo ATTEMPT_COUNT
            val p50Millis = percentileMillis(durations, 50)
            val p95Millis = percentileMillis(durations, 95)
            val p99Millis = percentileMillis(durations, 99)
            p95Millis shouldBeLessOrEqualTo P95_BUDGET_MILLIS
            p99Millis shouldBeLessOrEqualTo P99_BUDGET_MILLIS

            winners.get() shouldBeEqualTo 1
            stableConflicts.get() shouldBeEqualTo ATTEMPT_COUNT - 1
            unexpectedFailures.toList() shouldBeEqualTo emptyList()
            WaitlistOffers.selectAll().where { WaitlistOffers.status eq WaitlistOfferState.OFFERED }.count() shouldBeEqualTo 1L
            WaitlistCapacityHolds.selectAll().count() shouldBeEqualTo 1L

            log.info {
                "waitlist contention pool=$POOL_SIZE attempts=$ATTEMPT_COUNT " +
                    "p50=${p50Millis}ms p95=${p95Millis}ms p99=${p99Millis}ms"
            }
        }
    }

    private fun CommitmentSeed.doctorId(): Long =
        Doctors
            .selectAll()
            .where { Doctors.clinicId eq clinicId }
            .single()[Doctors.id]
            .value

    private fun CommitmentSeed.treatmentTypeId(): Long =
        TreatmentTypes
            .selectAll()
            .where { TreatmentTypes.clinicId eq clinicId }
            .single()[TreatmentTypes.id]
            .value

    private fun JdbcTransaction.insertWaitingEntry(
        clinicId: Long,
        doctorId: Long,
        treatmentTypeId: Long,
        memberId: String,
        waitingSince: Instant,
    ): Long =
        WaitlistEntries.insertAndGetId {
            it[tenantGroupId] = EntityID(TenantGroups.DEFAULT_TENANT_GROUP_ID, TenantGroups)
            it[WaitlistEntries.clinicId] = EntityID(clinicId, Clinics)
            it[WaitlistEntries.memberId] = memberId
            it[WaitlistEntries.treatmentTypeId] = EntityID(treatmentTypeId, TreatmentTypes)
            it[WaitlistEntries.doctorId] = EntityID(doctorId, Doctors)
            it[preferredDateFrom] = LocalDate.of(2026, 8, 1)
            it[preferredDateTo] = LocalDate.of(2026, 8, 1)
            it[preferredStartTime] = LocalTime.of(8, 0)
            it[preferredEndTime] = LocalTime.of(12, 0)
            it[priorityRank] = 1
            it[status] = WaitlistEntryState.WAITING
            it[WaitlistEntries.waitingSince] = waitingSince
            it[version] = 0L
            it[createdAt] = NOW
            it[updatedAt] = NOW
        }.value

    private fun vacancy(seed: CommitmentSeed, doctorId: Long, treatmentTypeId: Long): VacancyDescriptor =
        VacancyDescriptor(
            tenantGroupId = TenantGroups.DEFAULT_TENANT_GROUP_ID,
            clinicId = seed.clinicId,
            treatmentTypeId = treatmentTypeId,
            doctorId = doctorId,
            startsAt = START,
            endsAt = END,
            resourceType = ResourceType.PRACTITIONER,
            resourceId = "waitlist-contention-doctor-$doctorId",
            capacityUnits = 1,
            maximumCapacity = 1,
            now = NOW,
        )

    private fun decision(seed: CommitmentSeed, memberId: String): DecisionStamp =
        DecisionStamp(
            scope = io.bluetape4k.clinic.appointment.model.waitlist.WaitlistScope(
                tenantGroupId = TenantGroups.DEFAULT_TENANT_GROUP_ID,
                clinicId = seed.clinicId,
                memberId = MemberId(memberId),
            ),
            decisionId = 700L,
            policyVersionId = 800L,
            policyHash = "a".repeat(64),
            evaluationDigest = "b".repeat(64),
            expiresAt = NOW.plusSeconds(3_600),
        )

    private fun memberId(index: Int): String = "waitlist-contention-member-$index"

    private fun percentileMillis(sortedNanos: List<Long>, percentile: Int): Long {
        val rank = ceil(sortedNanos.size * percentile / 100.0).toInt().coerceAtLeast(1)
        return sortedNanos[rank - 1] / NANOS_PER_MILLISECOND
    }

    private companion object : KLogging() {
        private const val ATTEMPT_COUNT = 100
        private const val POOL_SIZE = 16
        private const val P95_BUDGET_MILLIS = 2_000L
        private const val P99_BUDGET_MILLIS = 5_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private val NOW: Instant = Instant.parse("2026-08-01T08:00:00Z")
        private val START: Instant = Instant.parse("2026-08-01T09:00:00Z")
        private val END: Instant = Instant.parse("2026-08-01T09:30:00Z")
    }
}
