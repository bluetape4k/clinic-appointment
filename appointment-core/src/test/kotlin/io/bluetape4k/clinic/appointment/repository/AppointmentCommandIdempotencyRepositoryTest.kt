package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitmentStatus
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentOrigin
import io.bluetape4k.clinic.appointment.model.dto.AppointmentCommandResultRecord
import io.bluetape4k.clinic.appointment.model.dto.AppointmentCommitmentRecord
import io.bluetape4k.clinic.appointment.model.dto.AppointmentProposalRecord
import io.bluetape4k.clinic.appointment.model.dto.CommandClaimResult
import org.junit.jupiter.api.Test
import java.time.Instant

class AppointmentCommandIdempotencyRepositoryTest {
    private val repository = AppointmentCommandIdempotencyRepository()

    @Test
    fun `actor scope별 command를 선점하고 같은 hash replay만 허용한다`() {
        withCommitmentTables { seed ->
            repository.claim(
                tenantGroupId = 1L,
                clinicId = seed.clinicId,
                actorScopeHash = "actor-a",
                idempotencyKeyHash = "b".repeat(64),
                commandHash = "a".repeat(64),
            ) shouldBeEqualTo CommandClaimResult.ACQUIRED
            repository.claim(
                1L,
                seed.clinicId,
                "actor-a",
                "b".repeat(64),
                "a".repeat(64),
            ) shouldBeEqualTo CommandClaimResult.REPLAY
            assertFailsWith<IllegalArgumentException> {
                repository.claim(
                    1L,
                    seed.clinicId,
                    "actor-a",
                    "b".repeat(64),
                    "b".repeat(64),
                )
            }
            repository.claim(
                1L,
                seed.clinicId,
                "actor-b",
                "b".repeat(64),
                "b".repeat(64),
            ) shouldBeEqualTo CommandClaimResult.ACQUIRED
        }
    }

    @Test
    fun `완료된 command replay는 최초 immutable 결과 snapshot을 반환한다`() {
        withCommitmentTables { seed ->
            val idempotencyKeyHash = "c".repeat(64)
            val commandHash = "d".repeat(64)
            val original =
                commandResult(
                    appointmentId = seed.appointmentId,
                    commitmentVersion = 1L,
                    proposalId = 101L,
                    proposalRevision = 1L,
                    responseHash = "e".repeat(64),
                )
            val mutatedCurrentState =
                commandResult(
                    appointmentId = seed.appointmentId,
                    commitmentVersion = 2L,
                    proposalId = 102L,
                    proposalRevision = 2L,
                    responseHash = "f".repeat(64),
                )

            repository.claim(
                tenantGroupId = 1L,
                clinicId = seed.clinicId,
                actorScopeHash = "actor-snapshot",
                idempotencyKeyHash = idempotencyKeyHash,
                commandHash = commandHash,
            ) shouldBeEqualTo CommandClaimResult.ACQUIRED
            repository.findResult(1L, seed.clinicId, "actor-snapshot", idempotencyKeyHash).shouldBeNull()

            repository
                .complete(
                    tenantGroupId = 1L,
                    clinicId = seed.clinicId,
                    actorScopeHash = "actor-snapshot",
                    idempotencyKeyHash = idempotencyKeyHash,
                    commandHash = commandHash,
                    result = original,
                ).shouldBeTrue()
            repository
                .complete(
                    tenantGroupId = 1L,
                    clinicId = seed.clinicId,
                    actorScopeHash = "actor-snapshot",
                    idempotencyKeyHash = idempotencyKeyHash,
                    commandHash = commandHash,
                    result = mutatedCurrentState,
                ).shouldBeFalse()

            val replayed =
                repository
                    .findResult(1L, seed.clinicId, "actor-snapshot", idempotencyKeyHash)
                    .shouldNotBeNull()
            replayed.resultType shouldBeEqualTo original.resultType
            replayed.resultId shouldBeEqualTo original.resultId
            replayed.commitment shouldBeEqualTo original.commitment
            replayed.proposal shouldBeEqualTo original.proposal
            replayed.responseHash shouldBeEqualTo original.responseHash
        }
    }

    private fun commandResult(
        appointmentId: Long,
        commitmentVersion: Long,
        proposalId: Long,
        proposalRevision: Long,
        responseHash: String,
    ): AppointmentCommandResultRecord {
        val commitment =
            AppointmentCommitmentRecord(
                id = 11L,
                appointmentId = appointmentId,
                status = AppointmentCommitmentStatus.PROPOSED,
                origin = AppointmentOrigin.CLINIC,
                confirmedProposalId = null,
                effectivePolicySnapshotId = 7L,
                version = commitmentVersion,
            )
        val proposal =
            AppointmentProposalRecord(
                id = proposalId,
                commitmentId = commitment.id,
                revision = proposalRevision,
                proposedStartAt = Instant.parse("2026-08-10T01:00:00Z"),
                proposedEndAt = Instant.parse("2026-08-10T01:30:00Z"),
                expiresAt = Instant.parse("2026-08-09T01:00:00Z"),
                expiredAt = null,
                representativeTreatmentName = "Treatment",
                proposalHash = "a".repeat(64),
                policySnapshotId = 7L,
                supersedesProposalId = null,
                createdByActor = "clinic-user",
            )
        return AppointmentCommandResultRecord(
            resultType = "APPOINTMENT_PROPOSAL",
            resultId = proposal.id,
            commitment = commitment,
            proposal = proposal,
            responseHash = responseHash,
        )
    }
}
