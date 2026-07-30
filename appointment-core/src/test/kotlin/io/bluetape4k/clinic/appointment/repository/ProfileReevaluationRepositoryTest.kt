package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.clinic.appointment.model.dto.ClaimProfileReevaluationJobs
import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationCursor
import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationPriorityClass
import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationScope
import io.bluetape4k.clinic.appointment.model.dto.RedriveProfileReevaluationJob
import io.bluetape4k.clinic.appointment.model.dto.UpsertProfileChange
import io.bluetape4k.clinic.appointment.model.profile.ProfileReevaluationJobStatus
import io.bluetape4k.clinic.appointment.model.tables.ProfileReevaluationHeads
import io.bluetape4k.clinic.appointment.model.tables.ProfileReevaluationJobs
import io.bluetape4k.clinic.appointment.model.tables.ProfileReevaluationOutcomes
import io.bluetape4k.clinic.appointment.test.AbstractExposedTest
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withTables
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Duration
import java.time.Instant

/**
 * 프로필 변경 inbox의 latest-revision 병합과 owner-fenced 작업 수명주기를 방언별로 검증합니다.
 */
class ProfileReevaluationRepositoryTest : AbstractExposedTest() {

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `같은 환자의 더 최신 revision만 실행 대상으로 남긴다`(testDB: TestDB) {
        withProfileReevaluationTables(testDB) {
            val repository = ProfileReevaluationRepository()
            val scope = scope()

            repository.upsertEvent(change(scope, revision = 7L, eventId = "evt-7"))
            repository.upsertEvent(change(scope, revision = 7L, eventId = "evt-7-duplicate"))
            repository.upsertEvent(change(scope, revision = 6L, eventId = "evt-6"))
            repository.upsertEvent(change(scope, revision = 8L, eventId = "evt-8"))

            repository.findHead(scope).shouldNotBeNull().latestRevision shouldBeEqualTo 8L
            repository.findRunnableJobs(scope).single().targetRevision shouldBeEqualTo 8L
            repository.findJobs(scope) shouldHaveSize 2
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `살아 있는 lease는 탈취할 수 없고 만료된 lease만 회수한다`(testDB: TestDB) {
        withProfileReevaluationTables(testDB) {
            val repository = ProfileReevaluationRepository(
                leaseDuration = Duration.ofMinutes(1),
                hasHeldAppointments = { true },
            )
            val scope = scope()
            repository.upsertEvent(change(scope, revision = 1L, eventId = "evt-1"))

            val first = repository.claimFairJobs(claim("worker-a")).single()
            first.priorityClass shouldBeEqualTo ProfileReevaluationPriorityClass.HELD_PRESENT
            repository.claimFairJobs(claim("worker-b")).shouldHaveSize(0)

            ProfileReevaluationJobs.update({ ProfileReevaluationJobs.id eq first.id }) {
                it[leaseExpiresAt] = Instant.EPOCH
            }

            val reclaimed = repository.claimFairJobs(claim("worker-b")).single()
            reclaimed.id shouldBeEqualTo first.id
            reclaimed.attemptCount shouldBeEqualTo 2

            repository.advanceCursor(
                first.id,
                first.targetRevision,
                "worker-a",
                ProfileReevaluationCursor(heldCursorAppointmentId = 101L, scannedDelta = 1L),
            ).shouldBeFalse()
            repository.scheduleRetry(first.id, first.targetRevision, "worker-a", "TIMEOUT").shouldBeFalse()
            repository.complete(first.id, first.targetRevision, "worker-a").shouldBeFalse()
            repository.markStale(first.id, first.targetRevision + 1L, "worker-a").shouldBeFalse()

            repository.renewLease(first.id, first.targetRevision, "worker-b").shouldBeTrue()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `head가 전진하면 이전 revision worker의 checkpoint와 완료를 거부한다`(testDB: TestDB) {
        withProfileReevaluationTables(testDB) {
            val repository = ProfileReevaluationRepository()
            val scope = scope()
            repository.upsertEvent(change(scope, revision = 7L, eventId = "evt-7"))
            val claimed = repository.claimFairJobs(claim("worker-a")).single()

            repository.upsertEvent(change(scope, revision = 8L, eventId = "evt-8"))

            repository.advanceCursor(
                claimed.id,
                claimed.targetRevision,
                "worker-a",
                ProfileReevaluationCursor(proposedCursorAppointmentId = 202L, scannedDelta = 1L),
            ).shouldBeFalse()
            repository.complete(claimed.id, claimed.targetRevision, "worker-a").shouldBeFalse()
            repository.markStale(claimed.id, observedRevision = 8L, leaseOwner = "worker-a").shouldBeTrue()

            repository.findJob(claimed.id).shouldNotBeNull().status shouldBeEqualTo
                ProfileReevaluationJobStatus.STALE
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `PROPOSED 전용 작업은 목표 시간을 늦추지 않고 우선순위를 한 번 고정한다`(testDB: TestDB) {
        withProfileReevaluationTables(testDB) {
            val repository = ProfileReevaluationRepository(hasHeldAppointments = { false })
            val scope = scope()
            repository.upsertEvent(
                change(
                    scope,
                    revision = 1L,
                    eventId = "evt-proposed",
                    heldTarget = Duration.ofMinutes(15),
                    proposedTarget = Duration.ofMinutes(5),
                )
            )

            val before = repository.findRunnableJobs(scope).single()
            before.dueAt shouldBeEqualTo before.occurredAt.plus(Duration.ofMinutes(5))
            val claimed = repository.claimFairJobs(claim("worker-a")).single()

            claimed.priorityClass shouldBeEqualTo ProfileReevaluationPriorityClass.PROPOSED_ONLY
            claimed.dueAt shouldBeEqualTo before.dueAt
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `짧아진 목표는 기존 due를 앞당기고 길어진 목표는 다시 늦추지 않는다`(testDB: TestDB) {
        withProfileReevaluationTables(testDB) {
            val repository = ProfileReevaluationRepository()
            val scope = scope()
            repository.upsertEvent(change(scope, revision = 1L, eventId = "evt-target"))
            val original = repository.findRunnableJobs(scope).single()

            val shortened = repository.advanceTargets(
                jobId = original.id,
                heldTarget = Duration.ofMinutes(2),
                proposedTarget = Duration.ofMinutes(10),
                targetPolicyRef = "policy/shorter",
                targetPolicyGeneration = 12L,
            ).shouldNotBeNull()
            shortened.dueAt shouldBeEqualTo original.occurredAt.plus(Duration.ofMinutes(2))

            val lengthened = repository.advanceTargets(
                jobId = original.id,
                heldTarget = Duration.ofMinutes(10),
                proposedTarget = Duration.ofMinutes(60),
                targetPolicyRef = "policy/longer",
                targetPolicyGeneration = 13L,
            ).shouldNotBeNull()
            lengthened.dueAt shouldBeEqualTo shortened.dueAt
            lengthened.heldTarget shouldBeEqualTo Duration.ofMinutes(10)
            lengthened.proposedTarget shouldBeEqualTo Duration.ofMinutes(60)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `공정 선점은 큰 병원 하나보다 서로 다른 병원을 먼저 선택한다`(testDB: TestDB) {
        withProfileReevaluationTables(testDB) {
            val repository = ProfileReevaluationRepository(hasHeldAppointments = { true })
            (1L..8L).forEach { clinicId ->
                (1L..2L).forEach { patient ->
                    val scope =
                        ProfileReevaluationScope(
                            tenantGroupId = 1L,
                            clinicId = clinicId,
                            patientReferenceFingerprint =
                                (clinicId * 100L + patient).toString().padStart(64, '0'),
                        )
                    repository.upsertEvent(change(scope, revision = 1L, eventId = "evt-$clinicId-$patient"))
                }
            }

            val claimed =
                repository.claimFairJobs(
                    ClaimProfileReevaluationJobs(
                        leaseOwner = "worker-a",
                        limit = 8,
                        perClinicLimit = 2,
                    ),
                )

            claimed shouldHaveSize 8
            claimed.map { it.scope.clinicId }.toSet() shouldHaveSize 8
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `terminal retry는 남은 attempt와 관계없이 즉시 최종 실패로 전이한다`(testDB: TestDB) {
        withProfileReevaluationTables(testDB) {
            val repository = ProfileReevaluationRepository(maxAttempts = 5)
            val scope = scope()
            repository.upsertEvent(change(scope, revision = 1L, eventId = "evt-terminal"))
            val claimed = repository.claimFairJobs(claim("worker-a")).single()

            repository.scheduleRetry(
                jobId = claimed.id,
                revision = claimed.targetRevision,
                leaseOwner = "worker-a",
                failureCode = "TERMINAL_CONTRACT",
                delay = Duration.ZERO,
                terminal = true,
            ).shouldBeTrue()

            repository.findJob(claimed.id).shouldNotBeNull().status shouldBeEqualTo
                ProfileReevaluationJobStatus.FAILED
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `redrive는 FAILED 원본을 유지하고 같은 세대의 새 작업을 한 번만 만든다`(testDB: TestDB) {
        withProfileReevaluationTables(testDB) {
            val repository = ProfileReevaluationRepository(maxAttempts = 1)
            val scope = scope()
            repository.upsertEvent(change(scope, revision = 3L, eventId = "evt-3"))
            val claimed = repository.claimFairJobs(claim("worker-a")).single()
            repository.scheduleRetry(
                claimed.id,
                claimed.targetRevision,
                "worker-a",
                "RETRY_EXHAUSTED",
            ).shouldBeTrue()

            val failed = repository.findJob(claimed.id).shouldNotBeNull()
            failed.status shouldBeEqualTo ProfileReevaluationJobStatus.FAILED

            val redrive = repository.redriveFailed(
                RedriveProfileReevaluationJob(
                    jobId = failed.id,
                    cooldown = Duration.ZERO,
                )
            ).shouldNotBeNull()
            redrive.rootJobId shouldBeEqualTo failed.id
            redrive.redriveOfJobId shouldBeEqualTo failed.id
            redrive.redriveGeneration shouldBeEqualTo 1
            repository.redriveFailed(
                RedriveProfileReevaluationJob(
                    jobId = failed.id,
                    cooldown = Duration.ZERO,
                    expectedRedriveCount = 0,
                )
            ).shouldBeNull()

            val claimedRedrive = repository.claimFairJobs(claim("worker-b")).single()
            claimedRedrive.id shouldBeEqualTo redrive.id
            repository.scheduleRetry(
                claimedRedrive.id,
                claimedRedrive.targetRevision,
                "worker-b",
                "REDRIVE_FAILED",
            ).shouldBeTrue()
            val secondRedrive =
                repository.redriveFailed(
                    RedriveProfileReevaluationJob(
                        jobId = claimedRedrive.id,
                        cooldown = Duration.ZERO,
                        expectedRedriveCount = 0,
                    ),
                ).shouldNotBeNull()
            secondRedrive.redriveGeneration shouldBeEqualTo 2
            secondRedrive.rootJobId shouldBeEqualTo failed.id

            repository.findJob(failed.id).shouldNotBeNull().status shouldBeEqualTo
                ProfileReevaluationJobStatus.FAILED
            repository.findJobs(scope) shouldHaveSize 3
        }
    }

    private fun withProfileReevaluationTables(
        testDB: TestDB,
        statement: () -> Unit,
    ) {
        withTables(
            testDB,
            ProfileReevaluationHeads,
            ProfileReevaluationJobs,
            ProfileReevaluationOutcomes,
        ) {
            statement()
        }
    }

    private fun scope() = ProfileReevaluationScope(
        tenantGroupId = 1L,
        clinicId = 41L,
        patientReferenceFingerprint = "a".repeat(64),
    )

    private fun change(
        scope: ProfileReevaluationScope,
        revision: Long,
        eventId: String,
        heldTarget: Duration = Duration.ofMinutes(5),
        proposedTarget: Duration = Duration.ofMinutes(30),
    ) = UpsertProfileChange(
        scope = scope,
        revision = revision,
        eventId = eventId,
        assessmentRef = "assessment/$revision",
        assessmentHash = revision.toString().padStart(64, '0'),
        occurredAt = Instant.EPOCH.plusSeconds(revision),
        heldTarget = heldTarget,
        proposedTarget = proposedTarget,
        targetPolicyRef = "policy/profile-reevaluation",
        targetPolicyGeneration = 11L,
    )

    private fun claim(owner: String) = ClaimProfileReevaluationJobs(
        leaseOwner = owner,
        limit = 10,
        perClinicLimit = 1,
    )
}
