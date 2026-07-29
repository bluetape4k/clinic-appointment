package io.bluetape4k.clinic.appointment.model.tables

import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitmentStatus
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentOrigin
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * 인증 actor scope별 command 선점과 replay 결과를 저장합니다.
 *
 * 같은 key hash라도 tenant, clinic, actor scope가 다르면 독립 command입니다. 같은
 * scope와 hash에 다른 [commandHash]가 오면 안전한 replay가 아니므로 거부합니다.
 * DB 물리 컬럼명은 최초 migration 호환 때문에 `idempotency_key`를 유지하지만 raw
 * header가 아니라 adapter가 생성한 HMAC-SHA-256만 저장합니다.
 */
object AppointmentCommandIdempotencies : LongIdTable("scheduling_appointment_command_idempotencies") {
    val tenantGroupId = reference("tenant_group_id", TenantGroups, onDelete = ReferenceOption.RESTRICT)
    val clinicId = reference("clinic_id", Clinics, onDelete = ReferenceOption.CASCADE)
    val actorScopeHash = varchar("actor_scope_hash", 128)
    val idempotencyKeyHash = varchar("idempotency_key", 255)
    val commandHash = varchar("command_hash", 64)
    val resultType = varchar("result_type", 64).nullable()
    val resultId = long("result_id").nullable()
    val resultCommitmentId = long("result_commitment_id").nullable()
    val resultAppointmentId = long("result_appointment_id").nullable()
    val resultCommitmentStatus =
        enumerationByName<AppointmentCommitmentStatus>("result_commitment_status", 32).nullable()
    val resultOrigin = enumerationByName<AppointmentOrigin>("result_origin", 16).nullable()
    val resultConfirmedProposalId = long("result_confirmed_proposal_id").nullable()
    val resultEffectivePolicySnapshotId = long("result_effective_policy_snapshot_id").nullable()
    val resultCommitmentVersion = long("result_commitment_version").nullable()
    val resultProposalRevision = long("result_proposal_revision").nullable()
    val resultProposedStartAt = timestamp("result_proposed_start_at").nullable()
    val resultProposedEndAt = timestamp("result_proposed_end_at").nullable()
    val resultProposalExpiresAt = timestamp("result_proposal_expires_at").nullable()
    val resultProposalExpiredAt = timestamp("result_proposal_expired_at").nullable()
    val resultRepresentativeTreatmentName = varchar("result_representative_treatment_name", 256).nullable()
    val resultProposalHash = varchar("result_proposal_hash", 64).nullable()
    val resultPolicySnapshotId = long("result_policy_snapshot_id").nullable()
    val resultSupersedesProposalId = long("result_supersedes_proposal_id").nullable()
    val resultCreatedByActor = varchar("result_created_by_actor", 128).nullable()
    val responseHash = varchar("response_hash", 64).nullable()
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex(
            "uq_appointment_command_idempotency",
            tenantGroupId,
            clinicId,
            actorScopeHash,
            idempotencyKeyHash,
        )
        index(
            "idx_appointment_idempotency_retention",
            false,
            tenantGroupId,
            clinicId,
            createdAt,
            id,
        )
    }
}
