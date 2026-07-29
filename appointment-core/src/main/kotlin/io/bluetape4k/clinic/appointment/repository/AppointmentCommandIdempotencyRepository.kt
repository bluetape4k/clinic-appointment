package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.clinic.appointment.model.dto.AppointmentCommandResultRecord
import io.bluetape4k.clinic.appointment.model.dto.AppointmentCommitmentRecord
import io.bluetape4k.clinic.appointment.model.dto.AppointmentProposalRecord
import io.bluetape4k.clinic.appointment.model.dto.CommandClaimResult
import io.bluetape4k.clinic.appointment.model.tables.AppointmentCommandIdempotencies
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update

/**
 * caller transaction에서 인증 actor scope별 command를 선점하고 replay를 판정합니다.
 */
class AppointmentCommandIdempotencyRepository {
    /**
     * 정확한 scope와 HMAC-SHA-256 key hash를 처음 insert하면 `ACQUIRED`, 같은
     * command면 `REPLAY`를 반환합니다.
     *
     * @throws IllegalArgumentException 같은 scope/key에 다른 command hash가 이미 있으면
     * 발생합니다.
     */
    fun claim(
        tenantGroupId: Long,
        clinicId: Long,
        actorScopeHash: String,
        idempotencyKeyHash: String,
        commandHash: String,
    ): CommandClaimResult {
        val validTenantGroupId = tenantGroupId.requirePositiveNumber("tenantGroupId")
        val validClinicId = clinicId.requirePositiveNumber("clinicId")
        val validActorScopeHash = actorScopeHash.requireNotBlank("actorScopeHash")
        val validIdempotencyKeyHash = idempotencyKeyHash.requireNotBlank("idempotencyKeyHash")
        val validCommandHash = commandHash.requireNotBlank("commandHash")
        require(SHA256_REGEX.matches(validIdempotencyKeyHash)) {
            "idempotencyKeyHash must be a lowercase SHA-256 value"
        }
        require(SHA256_REGEX.matches(validCommandHash)) {
            "commandHash must be a lowercase SHA-256 value"
        }

        val inserted =
            AppointmentCommandIdempotencies
                .insertIgnore {
                    it[AppointmentCommandIdempotencies.tenantGroupId] = validTenantGroupId
                    it[AppointmentCommandIdempotencies.clinicId] = validClinicId
                    it[AppointmentCommandIdempotencies.actorScopeHash] = validActorScopeHash
                    it[AppointmentCommandIdempotencies.idempotencyKeyHash] = validIdempotencyKeyHash
                    it[AppointmentCommandIdempotencies.commandHash] = validCommandHash
                }.insertedCount == 1
        if (inserted) {
            return CommandClaimResult.ACQUIRED
        }

        val existingHash =
            AppointmentCommandIdempotencies
                .select(AppointmentCommandIdempotencies.commandHash)
                .where {
                    (AppointmentCommandIdempotencies.tenantGroupId eq validTenantGroupId) and
                        (AppointmentCommandIdempotencies.clinicId eq validClinicId) and
                        (AppointmentCommandIdempotencies.actorScopeHash eq validActorScopeHash) and
                        (AppointmentCommandIdempotencies.idempotencyKeyHash eq validIdempotencyKeyHash)
                }.single()[AppointmentCommandIdempotencies.commandHash]
        if (existingHash != validCommandHash) {
            throw AppointmentCommandIdempotencyConflictException(
                "idempotency key is already bound to a different command hash",
            )
        }
        return CommandClaimResult.REPLAY
    }

    /**
     * 같은 scope와 key에 transaction 마지막으로 기록된 결과 snapshot을 반환합니다.
     *
     * claim row는 보이지만 결과가 `null`이면 이전 writer가 원자적 완료 계약을 지키지
     * 못했거나 아직 지원하지 않는 상태이므로 replay 성공으로 간주하면 안 됩니다.
     */
    fun findResult(
        tenantGroupId: Long,
        clinicId: Long,
        actorScopeHash: String,
        idempotencyKeyHash: String,
    ): AppointmentCommandResultRecord? {
        val validTenantGroupId = tenantGroupId.requirePositiveNumber("tenantGroupId")
        val validClinicId = clinicId.requirePositiveNumber("clinicId")
        val validActorScopeHash = actorScopeHash.requireNotBlank("actorScopeHash")
        val validIdempotencyKeyHash = idempotencyKeyHash.requireNotBlank("idempotencyKeyHash")
        require(SHA256_REGEX.matches(validIdempotencyKeyHash)) {
            "idempotencyKeyHash must be a lowercase SHA-256 value"
        }
        val row =
            AppointmentCommandIdempotencies
                .select(
                    AppointmentCommandIdempotencies.resultType,
                    AppointmentCommandIdempotencies.resultId,
                    AppointmentCommandIdempotencies.resultCommitmentId,
                    AppointmentCommandIdempotencies.resultAppointmentId,
                    AppointmentCommandIdempotencies.resultCommitmentStatus,
                    AppointmentCommandIdempotencies.resultOrigin,
                    AppointmentCommandIdempotencies.resultConfirmedProposalId,
                    AppointmentCommandIdempotencies.resultEffectivePolicySnapshotId,
                    AppointmentCommandIdempotencies.resultCommitmentVersion,
                    AppointmentCommandIdempotencies.resultProposalRevision,
                    AppointmentCommandIdempotencies.resultProposedStartAt,
                    AppointmentCommandIdempotencies.resultProposedEndAt,
                    AppointmentCommandIdempotencies.resultProposalExpiresAt,
                    AppointmentCommandIdempotencies.resultProposalExpiredAt,
                    AppointmentCommandIdempotencies.resultRepresentativeTreatmentName,
                    AppointmentCommandIdempotencies.resultProposalHash,
                    AppointmentCommandIdempotencies.resultPolicySnapshotId,
                    AppointmentCommandIdempotencies.resultSupersedesProposalId,
                    AppointmentCommandIdempotencies.resultCreatedByActor,
                    AppointmentCommandIdempotencies.responseHash,
                ).where {
                    (AppointmentCommandIdempotencies.tenantGroupId eq validTenantGroupId) and
                        (AppointmentCommandIdempotencies.clinicId eq validClinicId) and
                        (AppointmentCommandIdempotencies.actorScopeHash eq validActorScopeHash) and
                        (AppointmentCommandIdempotencies.idempotencyKeyHash eq validIdempotencyKeyHash)
                }.singleOrNull()
                ?: return null
        return row.toCommandResultRecord()
    }

    /**
     * 선점한 command row에 durable 결과 snapshot을 정확히 한 번 기록합니다.
     *
     * 같은 caller transaction의 업무 변경, 감사, outbox가 모두 성공한 뒤 호출해야 합니다.
     * 이미 완료된 row 또는 command hash가 다른 row는 `false`를 반환합니다.
     */
    fun complete(
        tenantGroupId: Long,
        clinicId: Long,
        actorScopeHash: String,
        idempotencyKeyHash: String,
        commandHash: String,
        result: AppointmentCommandResultRecord,
    ): Boolean {
        val validTenantGroupId = tenantGroupId.requirePositiveNumber("tenantGroupId")
        val validClinicId = clinicId.requirePositiveNumber("clinicId")
        val validActorScopeHash = actorScopeHash.requireNotBlank("actorScopeHash")
        val validIdempotencyKeyHash = idempotencyKeyHash.requireNotBlank("idempotencyKeyHash")
        val validCommandHash = commandHash.requireNotBlank("commandHash")
        require(SHA256_REGEX.matches(validIdempotencyKeyHash)) {
            "idempotencyKeyHash must be a lowercase SHA-256 value"
        }
        require(SHA256_REGEX.matches(validCommandHash)) {
            "commandHash must be a lowercase SHA-256 value"
        }
        return AppointmentCommandIdempotencies.update(
            where = {
                (AppointmentCommandIdempotencies.tenantGroupId eq validTenantGroupId) and
                    (AppointmentCommandIdempotencies.clinicId eq validClinicId) and
                    (AppointmentCommandIdempotencies.actorScopeHash eq validActorScopeHash) and
                    (AppointmentCommandIdempotencies.idempotencyKeyHash eq validIdempotencyKeyHash) and
                    (AppointmentCommandIdempotencies.commandHash eq validCommandHash) and
                    AppointmentCommandIdempotencies.resultType.isNull()
            },
        ) {
            it[resultType] = result.resultType
            it[resultId] = result.resultId
            it[resultCommitmentId] = result.commitment.id
            it[resultAppointmentId] = result.commitment.appointmentId
            it[resultCommitmentStatus] = result.commitment.status
            it[resultOrigin] = result.commitment.origin
            it[resultConfirmedProposalId] = result.commitment.confirmedProposalId
            it[resultEffectivePolicySnapshotId] = result.commitment.effectivePolicySnapshotId
            it[resultCommitmentVersion] = result.commitment.version
            it[resultProposalRevision] = result.proposal.revision
            it[resultProposedStartAt] = result.proposal.proposedStartAt
            it[resultProposedEndAt] = result.proposal.proposedEndAt
            it[resultProposalExpiresAt] = result.proposal.expiresAt
            it[resultProposalExpiredAt] = result.proposal.expiredAt
            it[resultRepresentativeTreatmentName] = result.proposal.representativeTreatmentName
            it[resultProposalHash] = result.proposal.proposalHash
            it[resultPolicySnapshotId] = result.proposal.policySnapshotId
            it[resultSupersedesProposalId] = result.proposal.supersedesProposalId
            it[resultCreatedByActor] = result.proposal.createdByActor
            it[responseHash] = result.responseHash
        } == 1
    }

    private fun ResultRow.toCommandResultRecord(): AppointmentCommandResultRecord? {
        val resultType = this[AppointmentCommandIdempotencies.resultType] ?: return null
        val resultId = this[AppointmentCommandIdempotencies.resultId] ?: return null
        val commitmentId = this[AppointmentCommandIdempotencies.resultCommitmentId] ?: return null
        val appointmentId = this[AppointmentCommandIdempotencies.resultAppointmentId] ?: return null
        val commitmentStatus = this[AppointmentCommandIdempotencies.resultCommitmentStatus] ?: return null
        val origin = this[AppointmentCommandIdempotencies.resultOrigin] ?: return null
        val effectivePolicySnapshotId =
            this[AppointmentCommandIdempotencies.resultEffectivePolicySnapshotId] ?: return null
        val commitmentVersion = this[AppointmentCommandIdempotencies.resultCommitmentVersion] ?: return null
        val proposalRevision = this[AppointmentCommandIdempotencies.resultProposalRevision] ?: return null
        val proposedStartAt = this[AppointmentCommandIdempotencies.resultProposedStartAt] ?: return null
        val proposedEndAt = this[AppointmentCommandIdempotencies.resultProposedEndAt] ?: return null
        val proposalExpiresAt = this[AppointmentCommandIdempotencies.resultProposalExpiresAt] ?: return null
        val representativeTreatmentName =
            this[AppointmentCommandIdempotencies.resultRepresentativeTreatmentName] ?: return null
        val proposalHash = this[AppointmentCommandIdempotencies.resultProposalHash] ?: return null
        val policySnapshotId = this[AppointmentCommandIdempotencies.resultPolicySnapshotId] ?: return null
        val createdByActor = this[AppointmentCommandIdempotencies.resultCreatedByActor] ?: return null
        val responseHash = this[AppointmentCommandIdempotencies.responseHash] ?: return null

        val commitment =
            AppointmentCommitmentRecord(
                id = commitmentId,
                appointmentId = appointmentId,
                status = commitmentStatus,
                origin = origin,
                confirmedProposalId = this[AppointmentCommandIdempotencies.resultConfirmedProposalId],
                effectivePolicySnapshotId = effectivePolicySnapshotId,
                version = commitmentVersion,
            )
        val proposal =
            AppointmentProposalRecord(
                id = resultId,
                commitmentId = commitmentId,
                revision = proposalRevision,
                proposedStartAt = proposedStartAt,
                proposedEndAt = proposedEndAt,
                expiresAt = proposalExpiresAt,
                expiredAt = this[AppointmentCommandIdempotencies.resultProposalExpiredAt],
                representativeTreatmentName = representativeTreatmentName,
                proposalHash = proposalHash,
                policySnapshotId = policySnapshotId,
                supersedesProposalId = this[AppointmentCommandIdempotencies.resultSupersedesProposalId],
                createdByActor = createdByActor,
            )
        return AppointmentCommandResultRecord(
            resultType = resultType,
            resultId = resultId,
            commitment = commitment,
            proposal = proposal,
            responseHash = responseHash,
        )
    }
}

/**
 * 같은 actor scope와 key가 다른 command hash에 이미 결합됐음을 나타냅니다.
 */
class AppointmentCommandIdempotencyConflictException(
    message: String,
) : IllegalArgumentException(message)

private val SHA256_REGEX = Regex("[0-9a-f]{64}")
