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

class SchedulingPolicyJobRepositoryTest : AbstractExposedTest() {

    private val repository = SchedulingPolicyJobRepository("unit-test-signing-secret".toByteArray())

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
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
    @MethodSource(ENABLE_DIALECTS_METHOD)
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
    @MethodSource(ENABLE_DIALECTS_METHOD)
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
    @MethodSource(ENABLE_DIALECTS_METHOD)
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
        idempotencyKeyHash = idempotencyKeyHash,
        requestFingerprint = requestFingerprint,
        effectiveFrom = nextAttemptAt,
        nextAttemptAt = nextAttemptAt,
    )
}
