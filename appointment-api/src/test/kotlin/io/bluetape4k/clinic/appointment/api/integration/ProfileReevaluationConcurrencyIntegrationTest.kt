package io.bluetape4k.clinic.appointment.api.integration

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.api.commitment.ConfirmAppointmentProposalCommand
import io.bluetape4k.clinic.appointment.api.commitment.CustomerAppointmentRequestCommand
import io.bluetape4k.clinic.appointment.api.commitment.VisitCommitmentCommandTestSupport
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationDecision
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationDecisionCommand
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationDecisionService
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationPlanner
import io.bluetape4k.clinic.appointment.api.test.API_INTEGRATION_RESOURCE
import io.bluetape4k.clinic.appointment.api.test.Containers
import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxEvents
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitmentStatus
import io.bluetape4k.clinic.appointment.model.dto.ClaimProfileReevaluationJobs
import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationScope
import io.bluetape4k.clinic.appointment.model.dto.RedriveProfileReevaluationJob
import io.bluetape4k.clinic.appointment.model.dto.ResourceAllocationStatus
import io.bluetape4k.clinic.appointment.model.dto.UpsertProfileChange
import io.bluetape4k.clinic.appointment.model.profile.ProfileReevaluationJobStatus
import io.bluetape4k.clinic.appointment.model.profile.ProfileReevaluationOutcomeType
import io.bluetape4k.clinic.appointment.model.tables.ProfileReevaluationHeads
import io.bluetape4k.clinic.appointment.model.tables.ProfileReevaluationJobs
import io.bluetape4k.clinic.appointment.model.tables.ProfileReevaluationOutcomes
import io.bluetape4k.clinic.appointment.repository.AppointmentCommitmentRepository
import io.bluetape4k.clinic.appointment.repository.ProfileReevaluationRepository
import io.bluetape4k.clinic.appointment.repository.ResourceAllocationRepository
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 실제 운영 DB를 사용하는 재평가 통합 테스트의 스키마와 최소 event fixture입니다.
 */
@ResourceLock(value = API_INTEGRATION_RESOURCE, mode = ResourceAccessMode.READ_WRITE)
internal abstract class ProfileReevaluationDatabaseIntegrationTestSupport :
    VisitCommitmentCommandTestSupport() {

    @BeforeEach
    fun createProfileReevaluationTables() {
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(*PROFILE_TABLES)
            PROFILE_TABLES.reversed().forEach(Table::deleteAll)
        }
    }

    @AfterEach
    fun clearProfileReevaluationTables() {
        transaction(database) {
            PROFILE_TABLES.reversed().forEach(Table::deleteAll)
        }
    }

    protected fun commandFor(
        commitmentId: Long,
        appointmentId: Long,
    ): ProfileReevaluationDecisionCommand {
        val jobId =
            transaction(database) {
                val repository = ProfileReevaluationRepository()
                repository.upsertEvent(profileChange(revision = 7L, occurredAt = NOW))
                repository.findJobs(profileScope()).single().id
            }
        return ProfileReevaluationDecisionCommand(
            jobId = jobId,
            targetRevision = 7L,
            upstreamEventId = "profile-event-7",
            tenantGroupId = TENANT_ID,
            clinicId = clinic.clinicId,
            patientReferenceFingerprint = PATIENT_REFERENCE_FINGERPRINT,
            appointmentId = appointmentId,
            commitmentId = commitmentId,
            assessmentReference = "assessment-7",
            assessmentHash = "a".repeat(64),
        )
    }

    protected fun profileScope() =
        ProfileReevaluationScope(
            tenantGroupId = TENANT_ID,
            clinicId = clinic.clinicId,
            patientReferenceFingerprint = PATIENT_REFERENCE_FINGERPRINT,
        )

    protected fun profileChange(
        revision: Long,
        occurredAt: Instant,
    ) =
        UpsertProfileChange(
            scope = profileScope(),
            revision = revision,
            eventId = "profile-event-$revision",
            assessmentRef = "assessment-$revision",
            assessmentHash = "a".repeat(64),
            occurredAt = occurredAt,
            heldTarget = Duration.ofMinutes(5),
            proposedTarget = Duration.ofMinutes(30),
            targetPolicyRef = "platform-default",
            targetPolicyGeneration = 1L,
        )

    private companion object {
        val PROFILE_TABLES =
            arrayOf(
                ProfileReevaluationHeads,
                ProfileReevaluationJobs,
                ProfileReevaluationOutcomes,
            )
    }
}

/**
 * 운영 DB의 실제 row lock에서 재평가 결과가 정확히 한 번만 반영되는지 검증합니다.
 */
internal abstract class AbstractProfileReevaluationConcurrencyIntegrationTest :
    ProfileReevaluationDatabaseIntegrationTestSupport() {

    @Test
    fun `같은 예약에 진입한 두 worker는 outcome과 outbox를 한 번만 기록한다`() {
        val commitmentService = commandService()
        val held =
            commitmentService.requestCustomerAppointment(
                CustomerAppointmentRequestCommand(
                    context = commandContext("concurrent-held"),
                    identity = appointmentIdentity("concurrent-held"),
                    proposal = proposalInput(revision = 1L, resourceId = "doctor-concurrent-held"),
                    expiresAt = ACTIVE_EXPIRY,
                    representativeTreatmentName = "동시 재평가 선점",
                    consent = acceptedConsent("concurrent-held"),
                    holdResources = true,
                ),
            )
        val command = commandFor(held.commitment.id, held.commitment.appointmentId)
        val plannersReady = CountDownLatch(2)
        val releasePlanners = CountDownLatch(1)
        val headLocked = CountDownLatch(1)
        val releaseHead = CountDownLatch(1)
        val service =
            ProfileReevaluationDecisionService(
                database = database,
                planner =
                    ProfileReevaluationPlanner {
                        plannersReady.countDown()
                        check(releasePlanners.await(5, TimeUnit.SECONDS))
                        ProfileReevaluationDecision.FallbackToProposed
                    },
                clock = CLOCK,
            )
        val executor = Executors.newFixedThreadPool(3)
        val workers =
            List(2) {
                executor.submit(Callable { runCatching { service.reevaluate(command) } })
            }
        plannersReady.await(5, TimeUnit.SECONDS).shouldBeTrue()
        val lock =
            executor.submit {
                transaction(database) {
                    ProfileReevaluationRepository()
                        .lockCurrentRevision(command.jobId, command.targetRevision)
                        .shouldBeTrue()
                    headLocked.countDown()
                    check(releaseHead.await(5, TimeUnit.SECONDS))
                }
            }
        headLocked.await(5, TimeUnit.SECONDS).shouldBeTrue()
        releasePlanners.countDown()
        Thread.sleep(150)
        releaseHead.countDown()

        val results = workers.map { it.get(15, TimeUnit.SECONDS) }
        lock.get(15, TimeUnit.SECONDS)
        executor.shutdownNow()

        results.all { it.isSuccess }.shouldBeTrue()
        transaction(database) {
            val current =
                checkNotNull(
                    AppointmentCommitmentRepository().findById(held.commitment.id),
                )
            current.status shouldBeEqualTo AppointmentCommitmentStatus.PROPOSED
            ResourceAllocationRepository()
                .findByProposal(held.proposal.id)
                .count { it.status == ResourceAllocationStatus.ACTIVE } shouldBeEqualTo 0
            ProfileReevaluationOutcomes.selectAll().count() shouldBeEqualTo 1L
            SchedulingOutboxEvents
                .selectAll()
                .count { it[SchedulingOutboxEvents.eventType].startsWith("PROFILE_REEVALUATION_") }
                .shouldBeEqualTo(1)
        }
    }

    @Test
    fun `재평가 계산 중 고객이 확정하면 확정 예약과 기존 점유를 유지한다`() {
        val commitmentService = commandService()
        val proposal = proposalInput(revision = 1L, resourceId = "doctor-confirm-race")
        val held =
            commitmentService.requestCustomerAppointment(
                CustomerAppointmentRequestCommand(
                    context = commandContext("confirm-race-held"),
                    identity = appointmentIdentity("confirm-race-held"),
                    proposal = proposal,
                    expiresAt = ACTIVE_EXPIRY,
                    representativeTreatmentName = "확정 경쟁 선점",
                    consent = acceptedConsent("confirm-race-held"),
                    holdResources = true,
                ),
            )
        val command = commandFor(held.commitment.id, held.commitment.appointmentId)
        val plannerEntered = CountDownLatch(1)
        val releasePlanner = CountDownLatch(1)
        val reevaluation =
            ProfileReevaluationDecisionService(
                database = database,
                planner =
                    ProfileReevaluationPlanner {
                        plannerEntered.countDown()
                        check(releasePlanner.await(5, TimeUnit.SECONDS))
                        ProfileReevaluationDecision.FallbackToProposed
                    },
                clock = CLOCK,
            )
        val executor = Executors.newSingleThreadExecutor()
        val reevaluationResult =
            executor.submit(Callable { runCatching { reevaluation.reevaluate(command) } })
        plannerEntered.await(5, TimeUnit.SECONDS).shouldBeTrue()

        val confirmed =
            commitmentService.approveCustomerProposal(
                ConfirmAppointmentProposalCommand(
                    context = commandContext("confirm-race-approve"),
                    appointmentId = held.commitment.appointmentId,
                    proposalId = held.proposal.id,
                    expectedVersion = held.commitment.version,
                    proposal = proposal,
                    expectedProposalHash = held.proposal.proposalHash,
                    projectionTarget = confirmedProjectionTarget("doctor-confirm-race"),
                ),
            )
        releasePlanner.countDown()
        val result = reevaluationResult.get(15, TimeUnit.SECONDS).getOrThrow()
        executor.shutdownNow()

        result.outcomeType shouldBeEqualTo ProfileReevaluationOutcomeType.SKIPPED_INELIGIBLE
        transaction(database) {
            val current =
                checkNotNull(
                    AppointmentCommitmentRepository().findById(held.commitment.id),
                )
            current.status shouldBeEqualTo AppointmentCommitmentStatus.CONFIRMED
            current.confirmedProposalId shouldBeEqualTo confirmed.proposal.id
            ResourceAllocationRepository()
                .findByProposal(held.proposal.id)
                .count { it.status == ResourceAllocationStatus.ACTIVE } shouldBeEqualTo 1
            ProfileReevaluationOutcomes.selectAll().count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `revision 8이 도착하면 계산 중이던 revision 7은 예약을 변경하지 않는다`() {
        val commitmentService = commandService()
        val held =
            commitmentService.requestCustomerAppointment(
                CustomerAppointmentRequestCommand(
                    context = commandContext("revision-race-held"),
                    identity = appointmentIdentity("revision-race-held"),
                    proposal = proposalInput(revision = 1L, resourceId = "doctor-revision-race"),
                    expiresAt = ACTIVE_EXPIRY,
                    representativeTreatmentName = "revision 경쟁 선점",
                    consent = acceptedConsent("revision-race-held"),
                    holdResources = true,
                ),
            )
        val command = commandFor(held.commitment.id, held.commitment.appointmentId)
        val plannerEntered = CountDownLatch(1)
        val releasePlanner = CountDownLatch(1)
        val reevaluation =
            ProfileReevaluationDecisionService(
                database = database,
                planner =
                    ProfileReevaluationPlanner {
                        plannerEntered.countDown()
                        check(releasePlanner.await(5, TimeUnit.SECONDS))
                        ProfileReevaluationDecision.FallbackToProposed
                    },
                clock = CLOCK,
            )
        val executor = Executors.newSingleThreadExecutor()
        val reevaluationResult =
            executor.submit(Callable { runCatching { reevaluation.reevaluate(command) } })
        plannerEntered.await(5, TimeUnit.SECONDS).shouldBeTrue()

        transaction(database) {
            ProfileReevaluationRepository().upsertEvent(
                profileChange(revision = 8L, occurredAt = NOW.plusSeconds(1)),
            )
        }
        releasePlanner.countDown()
        val result = reevaluationResult.get(15, TimeUnit.SECONDS)
        executor.shutdownNow()

        (result.exceptionOrNull() is IllegalArgumentException).shouldBeTrue()
        transaction(database) {
            val current =
                checkNotNull(
                    AppointmentCommitmentRepository().findById(held.commitment.id),
                )
            current.status shouldBeEqualTo AppointmentCommitmentStatus.HELD
            ResourceAllocationRepository()
                .findByProposal(held.proposal.id)
                .count { it.status == ResourceAllocationStatus.ACTIVE } shouldBeEqualTo 1
            ProfileReevaluationOutcomes.selectAll().count() shouldBeEqualTo 0L
            ProfileReevaluationRepository()
                .findJobs(profileScope())
                .map { it.status } shouldBeEqualTo
                listOf(
                    ProfileReevaluationJobStatus.STALE,
                    ProfileReevaluationJobStatus.PENDING,
                )
        }
    }

    @Test
    fun `만료된 lease만 다른 worker가 같은 작업을 다시 선점한다`() {
        val repository = ProfileReevaluationRepository(leaseDuration = Duration.ofSeconds(1))
        transaction(database) {
            repository.upsertEvent(
                profileChange(revision = 7L, occurredAt = Instant.now().minusSeconds(3_600)),
            )
        }

        val first =
            transaction(database) {
                repository.claimFairJobs(
                    ClaimProfileReevaluationJobs(
                        leaseOwner = "worker-one",
                        limit = 1,
                        perClinicLimit = 1,
                    ),
                ).single()
            }
        transaction(database) {
            ProfileReevaluationJobs.update({ ProfileReevaluationJobs.id eq first.id }) {
                it[leaseExpiresAt] = Instant.EPOCH
            }
        }
        val reclaimed =
            transaction(database) {
                repository.claimFairJobs(
                    ClaimProfileReevaluationJobs(
                        leaseOwner = "worker-two",
                        limit = 1,
                        perClinicLimit = 1,
                    ),
                ).single()
            }

        reclaimed.id shouldBeEqualTo first.id
        reclaimed.leaseOwner shouldBeEqualTo "worker-two"
        reclaimed.attemptCount shouldBeEqualTo first.attemptCount + 1
    }

    @Test
    fun `동시 redrive는 실패 원본을 보존하고 새 attempt를 하나만 만든다`() {
        val repository = ProfileReevaluationRepository(leaseDuration = Duration.ofSeconds(5))
        transaction(database) {
            repository.upsertEvent(
                profileChange(revision = 7L, occurredAt = Instant.now().minusSeconds(3_600)),
            )
        }
        val failed =
            transaction(database) {
                val claimed =
                    repository.claimFairJobs(
                        ClaimProfileReevaluationJobs(
                            leaseOwner = "redrive-owner",
                            limit = 1,
                            perClinicLimit = 1,
                        ),
                    ).single()
                repository.scheduleRetry(
                    jobId = claimed.id,
                    revision = claimed.targetRevision,
                    leaseOwner = checkNotNull(claimed.leaseOwner),
                    failureCode = "INJECTED_CRASH",
                    delay = Duration.ZERO,
                    terminal = true,
                ).shouldBeTrue()
                checkNotNull(repository.findJob(claimed.id))
            }
        val executor = Executors.newFixedThreadPool(2)
        val futures =
            List(2) {
                executor.submit(Callable {
                    transaction(database) {
                        repository.redriveFailed(
                            RedriveProfileReevaluationJob(
                                jobId = failed.id,
                                cooldown = Duration.ZERO,
                                expectedRedriveCount = 0,
                            ),
                        )
                    }
                })
            }
        val created = futures.mapNotNull { it.get(15, TimeUnit.SECONDS) }
        executor.shutdownNow()

        created.size shouldBeEqualTo 1
        transaction(database) {
            val jobs = repository.findJobs(profileScope())
            jobs.size shouldBeEqualTo 2
            jobs.single { it.id == failed.id }.status shouldBeEqualTo ProfileReevaluationJobStatus.FAILED
            jobs.single { it.id != failed.id }.let { redriven ->
                redriven.status shouldBeEqualTo ProfileReevaluationJobStatus.PENDING
                redriven.redriveOfJobId shouldBeEqualTo failed.id
            }
        }
    }

}

/**
 * PostgreSQL row lock와 unique 제약에서 재평가 동시성 계약을 검증합니다.
 */
@ResourceLock(value = API_INTEGRATION_RESOURCE, mode = ResourceAccessMode.READ_WRITE)
internal class ProfileReevaluationConcurrencyIntegrationTest :
    AbstractProfileReevaluationConcurrencyIntegrationTest() {
    override fun createDatabase(): Database {
        val postgres = Containers.Postgres
        return Database.connect(
            url = postgres.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = postgres.username ?: "test",
            password = postgres.password ?: "",
        )
    }

    private companion object {
        @JvmStatic
        @AfterAll
        fun cleanSharedSchema() = Containers.cleanPostgresSchema()
    }
}

/**
 * MySQL row lock와 unique 제약에서 PostgreSQL과 같은 재평가 동시성 계약을 검증합니다.
 */
@ResourceLock(value = API_INTEGRATION_RESOURCE, mode = ResourceAccessMode.READ_WRITE)
internal class ProfileReevaluationConcurrencyMySqlIntegrationTest :
    AbstractProfileReevaluationConcurrencyIntegrationTest() {
    override fun createDatabase(): Database {
        val mysql = Containers.MySql8
        return Database.connect(
            url = mysql.jdbcUrl,
            driver = "com.mysql.cj.jdbc.Driver",
            user = mysql.username ?: "test",
            password = mysql.password ?: "",
        )
    }

    private companion object {
        @JvmStatic
        @AfterAll
        fun cleanSharedSchema() = Containers.cleanMySqlSchema()
    }
}
