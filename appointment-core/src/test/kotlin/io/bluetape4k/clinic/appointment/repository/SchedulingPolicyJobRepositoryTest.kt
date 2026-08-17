package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.model.dto.PolicyPreviewCursor
import io.bluetape4k.clinic.appointment.model.dto.PolicyPreviewProgress
import io.bluetape4k.clinic.appointment.model.dto.PolicyActivationCommandStatus
import io.bluetape4k.clinic.appointment.model.dto.PolicyPreviewJobStatus
import io.bluetape4k.clinic.appointment.model.dto.PolicyScopeRef
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyActivationCommandRecord
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyPreviewJobRecord
import io.bluetape4k.clinic.appointment.model.policy.PolicyGenerationVector
import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyActivationCommands
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyPreviewJobs
import io.bluetape4k.clinic.appointment.test.AbstractExposedTest
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withTables
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Instant

/**
 * preview job과 activation command의 durable claim·lease·checkpoint 계약을 PostgreSQL에서 검증한다.
 *
 * 한 worker만 claim을 획득하고, 만료된 lease만 회수하며, terminal
 * 상태는 다시 실행되지 않는지 확인한다. preview evidence token과 command 결과는 원본
 * revision·generation에 고정되어 stale 또는 부분 결과가 활성화 근거가 될 수 없어야 한다.
 */
class SchedulingPolicyJobRepositoryTest : AbstractExposedTest() {

    companion object {
        @JvmStatic
        fun enablePostgreSQL() = TestDB.ALL_POSTGRES
    }

    private val repository = SchedulingPolicyJobRepository("unit-test-signing-secret".toByteArray())

    @ParameterizedTest
    @MethodSource("enablePostgreSQL")
    fun `activation stores only a bounded hash and fingerprint for keyed idempotency`(testDB: TestDB) {
        withJobTables(testDB) {
            val rawKey = "activate-20260727-001"
            val command = repository.createActivation(
                activation(
                    idempotencyKeyHash = repository.hashIdempotencyKey(rawKey),
                    requestFingerprint = "f".repeat(64),
                )
            )
            command.id.shouldNotBeNull().shouldBeGreaterThan(0L)
            command.idempotencyKeyHash shouldBeEqualTo repository.hashIdempotencyKey(rawKey)
            command.expectedTenantGeneration shouldBeEqualTo 5L
            command.expectedClinicGeneration shouldBeEqualTo 2L
            command.previewEvidenceToken shouldBeEqualTo "preview-token-7-3-5-2"

            val rowText = SchedulingPolicyActivationCommands
                .selectAll()
                .single()
                .toString()
            rowText.contains(rawKey).shouldBeFalse()

            assertFailsWith<IllegalArgumentException> {
                repository.hashIdempotencyKey("contains whitespace")
            }
            assertFailsWith<IllegalArgumentException> {
                repository.hashIdempotencyKey("x".repeat(129))
            }
            assertFailsWith<ExposedSQLException> {
                repository.createActivation(
                    activation(
                        idempotencyKeyHash = repository.hashIdempotencyKey(rawKey),
                        requestFingerprint = "0".repeat(64),
                    )
                )
            }
        }
    }

    @ParameterizedTest
    @MethodSource("enablePostgreSQL")
    fun `new jobs cannot be forged in terminal or leased state`(testDB: TestDB) {
        withJobTables(testDB) {
            val now = Instant.parse("2026-07-27T00:00:00Z")
            val activation = activation(
                idempotencyKeyHash = repository.hashIdempotencyKey("activation-forged-state"),
                requestFingerprint = "f".repeat(64),
            )
            assertFailsWith<IllegalArgumentException> {
                repository.createActivation(
                    activation.copy(
                        status = PolicyActivationCommandStatus.COMPLETED,
                        eventId = "forged-event",
                    )
                )
            }

            val preview = SchedulingPolicyPreviewJobRecord(
                tenantGroupId = 1L,
                clinicId = 41L,
                definitionId = 7L,
                draftRevision = 3L,
                tenantGeneration = 2L,
                clinicGeneration = 1L,
                partitionCount = 4,
                deadlineAt = now.plusSeconds(300),
                nextAttemptAt = now,
            )
            assertFailsWith<IllegalArgumentException> {
                repository.createPreviewJob(
                    preview.copy(
                        status = PolicyPreviewJobStatus.COMPLETED,
                        leaseOwner = "forged-owner",
                        leaseUntil = now.plusSeconds(30),
                    )
                )
            }
        }
    }

    @ParameterizedTest
    @MethodSource("enablePostgreSQL")
    fun `expired activation lease is reclaimable and stale owner cannot complete`(testDB: TestDB) {
        withJobTables(testDB) {
            val now = Instant.parse("2026-07-27T00:00:00Z")
            val command = repository.createActivation(
                activation(
                    idempotencyKeyHash = repository.hashIdempotencyKey("activation-lease-1"),
                    requestFingerprint = "f".repeat(64),
                    nextAttemptAt = now,
                )
            )
            val commandId = command.id.shouldNotBeNull()

            repository.claimDueActivation(commandId, "worker-a", now, now.plusSeconds(30)).shouldBeTrue()
            repository.claimDueActivation(commandId, "worker-b", now.plusSeconds(10), now.plusSeconds(40)).shouldBeFalse()
            repository.completeActivation(
                commandId,
                "worker-a",
                PolicyGenerationVector(2L, 1L),
                eventId = "event-expired",
                completedAt = now.plusSeconds(31),
            ).shouldBeFalse()
            repository.claimDueActivation(commandId, "worker-b", now.plusSeconds(31), now.plusSeconds(61)).shouldBeTrue()

            repository.completeActivation(
                commandId,
                "worker-a",
                PolicyGenerationVector(2L, 1L),
                eventId = "event-stale",
                completedAt = now.plusSeconds(32),
            ).shouldBeFalse()
            repository.completeActivation(
                commandId,
                "worker-b",
                PolicyGenerationVector(2L, 1L),
                eventId = "event-current",
                completedAt = now.plusSeconds(33),
            ).shouldBeTrue()

            val completed = repository.findActivation(commandId).shouldNotBeNull()
            completed.status shouldBeEqualTo PolicyActivationCommandStatus.COMPLETED
            completed.resultTenantGeneration shouldBeEqualTo 2L
            completed.resultClinicGeneration shouldBeEqualTo 1L
            completed.eventId shouldBeEqualTo "event-current"
            completed.leaseOwner.shouldBeNull()
        }
    }

    @ParameterizedTest
    @MethodSource("enablePostgreSQL")
    fun `activation retry releases only the current lease and preserves sanitized failure evidence`(testDB: TestDB) {
        withJobTables(testDB) {
            val now = Instant.parse("2026-07-27T00:00:00Z")
            val commandId = repository.createActivation(
                activation(
                    idempotencyKeyHash = repository.hashIdempotencyKey("activation-retry-1"),
                    requestFingerprint = "a".repeat(64),
                    nextAttemptAt = now,
                )
            ).id.shouldNotBeNull()
            repository.claimDueActivation(commandId, "worker-a", now, now.plusSeconds(30)).shouldBeTrue()

            repository.markActivationRetry(
                commandId = commandId,
                owner = "worker-b",
                errorCode = "TRANSIENT_DATABASE",
                nextAttemptAt = now.plusSeconds(5),
                retryAt = now.plusSeconds(1),
            ).shouldBeFalse()
            repository.markActivationRetry(
                commandId = commandId,
                owner = "worker-a",
                errorCode = "TRANSIENT_DATABASE",
                nextAttemptAt = now.plusSeconds(5),
                retryAt = now.plusSeconds(1),
            ).shouldBeTrue()

            val retried = repository.findActivation(commandId).shouldNotBeNull()
            retried.status shouldBeEqualTo PolicyActivationCommandStatus.RETRY_WAIT
            retried.nextAttemptAt shouldBeEqualTo now.plusSeconds(5)
            retried.lastErrorCode shouldBeEqualTo "TRANSIENT_DATABASE"
            retried.leaseOwner.shouldBeNull()
            retried.leaseUntil.shouldBeNull()
        }
    }

    @ParameterizedTest
    @MethodSource("enablePostgreSQL")
    fun `preview cancellation clears lease and can no longer produce activation evidence`(testDB: TestDB) {
        withJobTables(testDB) {
            val now = Instant.parse("2026-07-27T00:00:00Z")
            val jobId = repository.createPreviewJob(preview(now)).id.shouldNotBeNull()
            repository.claimDuePreview(jobId, "worker-a", now, now.plusSeconds(30)).shouldBeTrue()

            repository.cancelPreview(jobId, now.plusSeconds(1)).shouldBeTrue()

            val cancelled = repository.findPreviewJob(jobId).shouldNotBeNull()
            cancelled.status shouldBeEqualTo PolicyPreviewJobStatus.CANCELLED
            cancelled.leaseOwner.shouldBeNull()
            cancelled.leaseUntil.shouldBeNull()
            cancelled.resultHash.shouldBeNull()
            cancelled.activationEvidenceToken.shouldBeNull()
            repository.claimDuePreview(jobId, "worker-b", now.plusSeconds(2), now.plusSeconds(32)).shouldBeFalse()
        }
    }

    @ParameterizedTest
    @MethodSource("enablePostgreSQL")
    fun `scoped idempotency lookup and manual replay preserve immutable missed evidence`(testDB: TestDB) {
        withJobTables(testDB) {
            val now = Instant.parse("2026-07-27T00:00:00Z")
            val scope = PolicyScopeRef(tenantGroupId = 1L, scope = PolicyScope.TENANT_DEFAULT)
            val originalHash = repository.hashIdempotencyKey("activation-original")
            val original = repository.createActivation(
                activation(
                    idempotencyKeyHash = originalHash,
                    requestFingerprint = "a".repeat(64),
                    nextAttemptAt = now,
                )
            )
            val originalId = original.id.shouldNotBeNull()

            repository.findActivation(scope, originalHash).shouldNotBeNull().id shouldBeEqualTo originalId
            repository.findActivation(
                PolicyScopeRef(tenantGroupId = 2L, scope = PolicyScope.TENANT_DEFAULT),
                originalHash,
            ).shouldBeNull()

            repository.claimDueActivation(originalId, "worker-a", now, now.plusSeconds(30)).shouldBeTrue()
            repository.markActivationMissed(
                commandId = originalId,
                owner = "worker-b",
                errorCode = "POLICY_ACTIVATION_MISSED",
                missedAt = now.plusSeconds(10),
            ).shouldBeFalse()
            repository.markActivationMissed(
                commandId = originalId,
                owner = "worker-a",
                errorCode = "POLICY_ACTIVATION_MISSED",
                missedAt = now.plusSeconds(30),
            ).shouldBeFalse()
            repository.findActivation(originalId).shouldNotBeNull().status shouldBeEqualTo
                PolicyActivationCommandStatus.CLAIMED
            repository.markActivationMissed(
                commandId = originalId,
                owner = "worker-a",
                errorCode = "POLICY_ACTIVATION_MISSED",
                missedAt = now.plusSeconds(10),
            ).shouldBeTrue()

            val missed = repository.findActivation(originalId).shouldNotBeNull()
            missed.status shouldBeEqualTo PolicyActivationCommandStatus.MISSED
            missed.lastErrorCode shouldBeEqualTo "POLICY_ACTIVATION_MISSED"

            val replay = repository.createActivation(
                activation(
                    idempotencyKeyHash = repository.hashIdempotencyKey("activation-replay"),
                    requestFingerprint = "b".repeat(64),
                    nextAttemptAt = now.plusSeconds(60),
                ).copy(replayOfCommandId = originalId)
            )
            replay.replayOfCommandId shouldBeEqualTo originalId

            assertFailsWith<IllegalArgumentException> {
                repository.createActivation(
                    activation(
                        idempotencyKeyHash = repository.hashIdempotencyKey("activation-invalid-replay"),
                        requestFingerprint = "c".repeat(64),
                        nextAttemptAt = now.plusSeconds(120),
                    ).copy(replayOfCommandId = replay.id.shouldNotBeNull())
                )
            }
        }
    }

    @ParameterizedTest
    @MethodSource("enablePostgreSQL")
    fun `preview checkpoint is owner fenced and can resume from persisted cursor`(testDB: TestDB) {
        withJobTables(testDB) {
            val now = Instant.parse("2026-07-27T00:00:00Z")
            val job = repository.createPreviewJob(
                SchedulingPolicyPreviewJobRecord(
                    tenantGroupId = 1L,
                    clinicId = 41L,
                    definitionId = 7L,
                    draftRevision = 3L,
                    tenantGeneration = 2L,
                    clinicGeneration = 1L,
                    partitionCount = 4,
                    deadlineAt = now.plusSeconds(300),
                    nextAttemptAt = now,
                )
            )
            val jobId = job.id.shouldNotBeNull()

            repository.claimDuePreview(jobId, "preview-a", now, now.plusSeconds(30)).shouldBeTrue()
            repository.checkpointPreview(
                jobId,
                "preview-b",
                PolicyPreviewCursor(partition = 1, lastAppointmentId = 100L),
                PolicyPreviewProgress(scannedCount = 10L, affectedCount = 3L),
            ).shouldBeFalse()
            repository.checkpointPreview(
                jobId,
                "preview-a",
                PolicyPreviewCursor(partition = 1, lastAppointmentId = 100L),
                PolicyPreviewProgress(scannedCount = 10L, affectedCount = 3L),
            ).shouldBeTrue()

            val checkpoint = repository.findPreviewJob(jobId).shouldNotBeNull()
            checkpoint.cursorPartition shouldBeEqualTo 1
            checkpoint.cursorLastAppointmentId shouldBeEqualTo 100L
            checkpoint.scannedCount shouldBeEqualTo 10L
            checkpoint.affectedCount shouldBeEqualTo 3L

            assertFailsWith<IllegalArgumentException> {
                repository.checkpointPreview(
                    jobId,
                    "preview-a",
                    PolicyPreviewCursor(partition = 4, lastAppointmentId = null),
                    PolicyPreviewProgress(scannedCount = 10L, affectedCount = 3L),
                )
            }
            repository.findPreviewJob(jobId).shouldNotBeNull()
                .cursorPartition shouldBeEqualTo 1
        }
    }

    @ParameterizedTest
    @MethodSource("enablePostgreSQL")
    fun `completed preview alone exposes immutable result hash and activation evidence`(testDB: TestDB) {
        withJobTables(testDB) {
            val now = Instant.parse("2026-07-27T00:00:00Z")
            val job = repository.createPreviewJob(
                preview(now)
            )
            val jobId = job.id.shouldNotBeNull()

            repository.claimDuePreview(jobId, "preview-a", now, now.plusSeconds(30)).shouldBeTrue()
            repository.completePreview(
                jobId = jobId,
                owner = "preview-b",
                resultHash = "a".repeat(64),
                activationEvidenceToken = "evidence-token-for-current-revision",
                completedAt = now.plusSeconds(10),
            ).shouldBeFalse()
            repository.completePreview(
                jobId = jobId,
                owner = "preview-a",
                resultHash = "a".repeat(64),
                activationEvidenceToken = "evidence-token-for-current-revision",
                completedAt = now.plusSeconds(10),
            ).shouldBeTrue()

            val completed = repository.findPreviewJob(jobId).shouldNotBeNull()
            completed.status shouldBeEqualTo PolicyPreviewJobStatus.COMPLETED
            completed.resultHash shouldBeEqualTo "a".repeat(64)
            completed.activationEvidenceToken shouldBeEqualTo "evidence-token-for-current-revision"
            completed.leaseOwner.shouldBeNull()
            completed.leaseUntil.shouldBeNull()
            completed.lastErrorCode.shouldBeNull()
            val clinicScope = PolicyScopeRef(1L, PolicyScope.CLINIC_OVERRIDE, 41L)
            repository.findCompletedPreviewByToken(
                clinicScope,
                "evidence-token-for-current-revision",
            ).shouldNotBeNull().id shouldBeEqualTo jobId
            repository.findCompletedPreviewByToken(
                clinicScope.copy(clinicId = 42L),
                "evidence-token-for-current-revision",
            ).shouldBeNull()
            repository.findCompletedPreviewByToken(
                PolicyScopeRef(2L, PolicyScope.CLINIC_OVERRIDE, 41L),
                "evidence-token-for-current-revision",
            ).shouldBeNull()
            repository.findCompletedPreviewByToken(
                PolicyScopeRef(1L, PolicyScope.TENANT_DEFAULT),
                "evidence-token-for-current-revision",
            ).shouldBeNull()

            repository.claimDuePreview(
                jobId,
                "preview-c",
                now.plusSeconds(31),
                now.plusSeconds(61),
            ).shouldBeFalse()
            repository.markPreviewTerminal(
                jobId = jobId,
                owner = "preview-a",
                status = PolicyPreviewJobStatus.STALE,
                errorCode = "POLICY_PREVIEW_STALE",
                completedAt = now.plusSeconds(11),
            ).shouldBeFalse()
        }
    }

    @ParameterizedTest
    @MethodSource("enablePostgreSQL")
    fun `non completed preview terminal states discard partial evidence and lease ownership`(testDB: TestDB) {
        withJobTables(testDB) {
            val now = Instant.parse("2026-07-27T00:00:00Z")
            val jobId = repository.createPreviewJob(preview(now)).id.shouldNotBeNull()
            repository.claimDuePreview(jobId, "preview-a", now, now.plusSeconds(30)).shouldBeTrue()
            repository.checkpointPreview(
                jobId = jobId,
                owner = "preview-a",
                cursor = PolicyPreviewCursor(partition = 1, lastAppointmentId = 100L),
                progress = PolicyPreviewProgress(scannedCount = 10L, affectedCount = 3L),
            ).shouldBeTrue()

            repository.markPreviewTerminal(
                jobId = jobId,
                owner = "preview-a",
                status = PolicyPreviewJobStatus.STALE,
                errorCode = "POLICY_PREVIEW_STALE",
                completedAt = now.plusSeconds(10),
            ).shouldBeTrue()

            val stale = repository.findPreviewJob(jobId).shouldNotBeNull()
            stale.status shouldBeEqualTo PolicyPreviewJobStatus.STALE
            stale.resultHash.shouldBeNull()
            stale.activationEvidenceToken.shouldBeNull()
            stale.leaseOwner.shouldBeNull()
            stale.leaseUntil.shouldBeNull()
            stale.lastErrorCode shouldBeEqualTo "POLICY_PREVIEW_STALE"
            repository.checkpointPreview(
                jobId = jobId,
                owner = "preview-a",
                cursor = PolicyPreviewCursor(partition = 2, lastAppointmentId = 200L),
                progress = PolicyPreviewProgress(scannedCount = 20L, affectedCount = 4L),
            ).shouldBeFalse()
        }
    }

    @ParameterizedTest
    @MethodSource("enablePostgreSQL")
    fun `due job selection is time ordered and strictly bounded`(testDB: TestDB) {
        withJobTables(testDB) {
            val now = Instant.parse("2026-07-27T00:00:00Z")
            val first = repository.createPreviewJob(preview(now)).id.shouldNotBeNull()
            val second = repository.createPreviewJob(preview(now.plusSeconds(1))).id.shouldNotBeNull()
            repository.createPreviewJob(preview(now.plusSeconds(60)))

            repository.findDuePreviewJobIds(now.plusSeconds(1), limit = 1) shouldBeEqualTo listOf(first)
            repository.findDuePreviewJobIds(now.plusSeconds(1), limit = 2) shouldBeEqualTo listOf(first, second)

            val activationFirst = repository.createActivation(
                activation(
                    idempotencyKeyHash = repository.hashIdempotencyKey("due-activation-1"),
                    requestFingerprint = "1".repeat(64),
                    nextAttemptAt = now,
                )
            ).id.shouldNotBeNull()
            val activationSecond = repository.createActivation(
                activation(
                    idempotencyKeyHash = repository.hashIdempotencyKey("due-activation-2"),
                    requestFingerprint = "2".repeat(64),
                    nextAttemptAt = now.plusSeconds(1),
                )
            ).id.shouldNotBeNull()

            repository.findDueActivationCommandIds(now.plusSeconds(1), limit = 1) shouldBeEqualTo
                listOf(activationFirst)
            repository.findDueActivationCommandIds(now.plusSeconds(1), limit = 2) shouldBeEqualTo
                listOf(activationFirst, activationSecond)
        }
    }

    @ParameterizedTest
    @MethodSource("enablePostgreSQL")
    fun `expired preview remains due so the worker can terminalize it`(testDB: TestDB) {
        withJobTables(testDB) {
            val now = Instant.parse("2026-07-27T00:00:00Z")
            val expired = repository.createPreviewJob(
                preview(now.minusSeconds(301))
            ).id.shouldNotBeNull()

            repository.findDuePreviewJobIds(now, limit = 10) shouldBeEqualTo listOf(expired)
            repository.claimDuePreview(
                expired,
                "preview-expiry-cleaner",
                now,
                now.plusSeconds(30),
            ).shouldBeTrue()
        }
    }

    @ParameterizedTest
    @MethodSource("enablePostgreSQL")
    fun `preview queue capacity is isolated by tenant baseline and clinic override scope`(testDB: TestDB) {
        withJobTables(testDB) {
            val now = Instant.parse("2026-07-27T00:00:00Z")
            repository.createPreviewJob(preview(now))
            val clinicOne = PolicyScopeRef(1L, PolicyScope.CLINIC_OVERRIDE, 41L)
            val clinicTwo = PolicyScopeRef(1L, PolicyScope.CLINIC_OVERRIDE, 42L)
            val otherTenantClinic = PolicyScopeRef(2L, PolicyScope.CLINIC_OVERRIDE, 41L)
            val tenantBaseline = PolicyScopeRef(1L, PolicyScope.TENANT_DEFAULT)

            repository.isPreviewQueueSaturated(clinicOne, capacity = 1).shouldBeTrue()
            repository.isPreviewQueueSaturated(clinicTwo, capacity = 1).shouldBeFalse()
            repository.isPreviewQueueSaturated(otherTenantClinic, capacity = 1).shouldBeFalse()
            repository.isPreviewQueueSaturated(tenantBaseline, capacity = 1).shouldBeFalse()

            repository.createPreviewJob(
                preview(now).copy(
                    scope = PolicyScope.TENANT_DEFAULT,
                    clinicId = null,
                    clinicScopeKey = 0L,
                    clinicGeneration = 0L,
                    clinicGenerationDigest = "0".repeat(64),
                )
            )

            repository.isPreviewQueueSaturated(tenantBaseline, capacity = 1).shouldBeTrue()
            repository.isPreviewQueueSaturated(clinicTwo, capacity = 1).shouldBeFalse()
        }
    }

    @ParameterizedTest
    @MethodSource("enablePostgreSQL")
    fun `preview job primary key lookup is fenced by tenant and policy scope`(testDB: TestDB) {
        withJobTables(testDB) {
            val now = Instant.parse("2026-07-27T00:00:00Z")
            val clinicScope = PolicyScopeRef(tenantGroupId = 1L, scope = PolicyScope.CLINIC_OVERRIDE, clinicId = 41L)
            val clinicJob = repository.createPreviewJob(preview(now))

            repository.findPreviewJob(clinicScope, clinicJob.id.shouldNotBeNull())
                .shouldNotBeNull()
                .id shouldBeEqualTo clinicJob.id
            repository.findPreviewJob(
                clinicScope.copy(clinicId = 42L),
                clinicJob.id.shouldNotBeNull(),
            ).shouldBeNull()
            repository.findPreviewJob(
                PolicyScopeRef(tenantGroupId = 2L, scope = PolicyScope.CLINIC_OVERRIDE, clinicId = 41L),
                clinicJob.id.shouldNotBeNull(),
            ).shouldBeNull()
            repository.findPreviewJob(
                PolicyScopeRef(tenantGroupId = 1L, scope = PolicyScope.TENANT_DEFAULT),
                clinicJob.id.shouldNotBeNull(),
            ).shouldBeNull()

            val tenantScope = PolicyScopeRef(tenantGroupId = 1L, scope = PolicyScope.TENANT_DEFAULT)
            val tenantJob = repository.createPreviewJob(
                preview(now.plusSeconds(1)).copy(
                    scope = PolicyScope.TENANT_DEFAULT,
                    clinicId = null,
                    clinicScopeKey = 0L,
                    clinicGeneration = 0L,
                    clinicGenerationDigest = "0".repeat(64),
                )
            )

            repository.findPreviewJob(tenantScope, tenantJob.id.shouldNotBeNull())
                .shouldNotBeNull()
                .id shouldBeEqualTo tenantJob.id
            repository.findPreviewJob(
                clinicScope,
                tenantJob.id.shouldNotBeNull(),
            ).shouldBeNull()
        }
    }

    private fun withJobTables(
        testDB: TestDB,
        statement: org.jetbrains.exposed.v1.jdbc.JdbcTransaction.() -> Unit,
    ) = withTables(
        testDB,
        SchedulingPolicyActivationCommands,
        SchedulingPolicyPreviewJobs,
    ) { statement() }

    private fun activation(
        idempotencyKeyHash: String,
        requestFingerprint: String,
        nextAttemptAt: Instant = Instant.parse("2026-07-27T00:00:00Z"),
    ) = SchedulingPolicyActivationCommandRecord(
        tenantGroupId = 1L,
        scope = PolicyScope.TENANT_DEFAULT,
        definitionId = 7L,
        expectedDraftRevision = 3L,
        expectedActiveRevision = 2L,
        expectedTenantGeneration = 5L,
        expectedClinicGeneration = 2L,
        previewEvidenceToken = "preview-token-7-3-5-2",
        idempotencyKeyHash = idempotencyKeyHash,
        requestFingerprint = requestFingerprint,
        effectiveFrom = nextAttemptAt,
        nextAttemptAt = nextAttemptAt,
    )

    private fun preview(now: Instant) = SchedulingPolicyPreviewJobRecord(
        tenantGroupId = 1L,
        clinicId = 41L,
        definitionId = 7L,
        draftRevision = 3L,
        tenantGeneration = 2L,
        clinicGeneration = 1L,
        partitionCount = 4,
        deadlineAt = now.plusSeconds(300),
        nextAttemptAt = now,
    )
}
