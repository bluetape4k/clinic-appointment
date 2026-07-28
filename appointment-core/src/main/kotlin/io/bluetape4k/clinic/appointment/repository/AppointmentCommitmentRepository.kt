package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitment
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitmentStatus
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentProposalDraft
import io.bluetape4k.clinic.appointment.model.commitment.ConsentDecision
import io.bluetape4k.clinic.appointment.model.commitment.ProductVersionMigrationConsentSubject
import io.bluetape4k.clinic.appointment.model.commitment.ProposalConsentSubject
import io.bluetape4k.clinic.appointment.model.dto.AppointmentCommitmentRecord
import io.bluetape4k.clinic.appointment.model.dto.AppointmentProposalRecord
import io.bluetape4k.clinic.appointment.model.tables.AppointmentCommitments
import io.bluetape4k.clinic.appointment.model.tables.AppointmentProposals
import io.bluetape4k.clinic.appointment.model.tables.ConsentDecisions
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

/**
 * caller-owned Exposed transaction에서 commitment, proposal, consent primitive를 제공합니다.
 *
 * 이 repository는 transaction을 열거나 commit하지 않습니다. application service가
 * 고객 동의, 자원 점유, outbox와 같은 transaction 안에서 이 primitive를 조합해야
 * `confirmedProposalId`와 allocation 교체가 원자적으로 보입니다.
 */
class AppointmentCommitmentRepository {

    /** 방문 예약 하나에 commitment를 생성합니다. */
    fun create(commitment: AppointmentCommitment): AppointmentCommitmentRecord {
        val id = AppointmentCommitments.insertAndGetId {
            it[appointmentId] = commitment.appointmentId
            it[status] = commitment.status
            it[origin] = commitment.origin
            it[confirmedProposalId] = commitment.confirmedProposalId
            it[effectivePolicySnapshotId] = commitment.effectivePolicySnapshotId
            it[version] = commitment.version
        }.value
        return AppointmentCommitmentRecord(
            id = id,
            appointmentId = commitment.appointmentId,
            status = commitment.status,
            origin = commitment.origin,
            confirmedProposalId = commitment.confirmedProposalId,
            effectivePolicySnapshotId = commitment.effectivePolicySnapshotId,
            version = commitment.version,
        )
    }

    /** 정확한 방문 예약의 commitment를 반환합니다. */
    fun findByAppointmentId(appointmentId: Long): AppointmentCommitmentRecord? {
        appointmentId.requirePositiveNumber("appointmentId")
        return AppointmentCommitments
            .selectAll()
            .where { AppointmentCommitments.appointmentId eq appointmentId }
            .singleOrNull()
            ?.let { row ->
                AppointmentCommitmentRecord(
                    id = row[AppointmentCommitments.id].value,
                    appointmentId = row[AppointmentCommitments.appointmentId].value,
                    status = row[AppointmentCommitments.status],
                    origin = row[AppointmentCommitments.origin],
                    confirmedProposalId = row[AppointmentCommitments.confirmedProposalId],
                    effectivePolicySnapshotId = row[AppointmentCommitments.effectivePolicySnapshotId],
                    version = row[AppointmentCommitments.version],
                )
            }
    }

    /**
     * 수정 불가능한 새 proposal revision을 append합니다.
     *
     * [proposalHash]는 draft와 별도로 caller가 canonical 계산한 값이며 빈 값이나 64자가
     * 아닌 값은 storage에 쓰지 않습니다.
     */
    fun appendProposal(
        commitmentId: Long,
        draft: AppointmentProposalDraft,
        proposalHash: String,
        expiresAt: Instant,
        representativeTreatmentName: String,
        createdByActor: String,
    ): AppointmentProposalRecord {
        commitmentId.requirePositiveNumber("commitmentId")
        proposalHash.requireNotBlank("proposalHash")
        representativeTreatmentName.requireNotBlank("representativeTreatmentName")
        createdByActor.requireNotBlank("createdByActor")
        require(proposalHash.length == 64) { "proposalHash must be a 64-character SHA-256 hex value" }
        require(expiresAt <= draft.endsAt) { "expiresAt must not be after proposed end" }
        val owner = AppointmentCommitments
            .selectAll()
            .where { AppointmentCommitments.id eq commitmentId }
            .singleOrNull()
        requireNotNull(owner) { "commitment does not exist" }
        require(owner[AppointmentCommitments.appointmentId].value == draft.appointmentId) {
            "proposal appointmentId must match commitment appointmentId"
        }

        val proposalId = AppointmentProposals.insertAndGetId {
            it[AppointmentProposals.commitmentId] = commitmentId
            it[revision] = draft.revision
            it[proposedStartAt] = draft.startsAt
            it[proposedEndAt] = draft.endsAt
            it[AppointmentProposals.expiresAt] = expiresAt
            it[AppointmentProposals.representativeTreatmentName] = representativeTreatmentName
            it[AppointmentProposals.proposalHash] = proposalHash
            it[policySnapshotId] = draft.policySnapshotId
            it[supersedesProposalId] = draft.supersedesProposalId
            it[AppointmentProposals.createdByActor] = createdByActor
        }.value
        return AppointmentProposalRecord(
            id = proposalId,
            commitmentId = commitmentId,
            revision = draft.revision,
            proposedStartAt = draft.startsAt,
            proposedEndAt = draft.endsAt,
            expiresAt = expiresAt,
            representativeTreatmentName = representativeTreatmentName,
            proposalHash = proposalHash,
            policySnapshotId = draft.policySnapshotId,
            supersedesProposalId = draft.supersedesProposalId,
            createdByActor = createdByActor,
        )
    }

    /** 동의 증빙을 수정 없이 append하고 생성된 양수 식별자를 반환합니다. */
    fun appendConsent(
        commitmentId: Long,
        decision: ConsentDecision,
    ): Long {
        commitmentId.requirePositiveNumber("commitmentId")
        val subjectPayload = when (val subject = decision.subject) {
            is ProposalConsentSubject ->
                "${subject.proposalId}|${subject.proposalRevision}|${subject.proposalHash}"

            is ProductVersionMigrationConsentSubject ->
                "${subject.migrationId}|${subject.fromProductVersionId}|" +
                    "${subject.toProductVersionId}|${subject.mappingHash}"

            else -> throw IllegalArgumentException("unsupported consent subject")
        }
        return ConsentDecisions.insertAndGetId {
            it[ConsentDecisions.commitmentId] = commitmentId
            it[subjectType] = decision.subject.type
            it[ConsentDecisions.subjectPayload] = subjectPayload
            it[ConsentDecisions.decision] = decision.decision
            it[evidenceAuthority] = decision.evidenceAuthority
            it[evidenceId] = decision.evidenceId
            it[evidenceHash] = decision.evidenceHash
            it[decidedAt] = decision.decidedAt
            it[actorRef] = decision.actorRef
        }.value
    }

    /**
     * 현재 [expectedVersion]일 때만 commitment를 확정 proposal로 이동합니다.
     *
     * proposal 소유권을 먼저 검증하며 CAS 실패는 예외 대신 `false`를 반환합니다.
     */
    fun confirmByVersion(
        commitmentId: Long,
        expectedVersion: Long,
        proposalId: Long,
    ): Boolean {
        commitmentId.requirePositiveNumber("commitmentId")
        expectedVersion.requirePositiveNumber("expectedVersion")
        proposalId.requirePositiveNumber("proposalId")
        require(
            AppointmentProposals.selectAll().where {
                (AppointmentProposals.id eq proposalId) and
                    (AppointmentProposals.commitmentId eq commitmentId)
            }.count() == 1L,
        ) {
            "proposal must belong to commitment"
        }
        return AppointmentCommitments.update(
            where = {
                (AppointmentCommitments.id eq commitmentId) and
                    (AppointmentCommitments.version eq expectedVersion) and
                    (AppointmentCommitments.status neq AppointmentCommitmentStatus.CONFIRMED)
            },
        ) {
            it[status] = AppointmentCommitmentStatus.CONFIRMED
            it[confirmedProposalId] = proposalId
            it[version] = expectedVersion + 1
            it[updatedAt] = Instant.now()
        } == 1
    }
}
