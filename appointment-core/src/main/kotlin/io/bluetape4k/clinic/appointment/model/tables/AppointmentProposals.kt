package io.bluetape4k.clinic.appointment.model.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * commitment에 append되는 불변 일정 제안 revision입니다.
 *
 * 날짜·항목·자원·정책이 달라지면 기존 row를 수정하지 않고 새 [revision]을 추가합니다.
 * [proposalHash]는 고객 동의가 실제 제안 전체에 결합됐는지 검증하는 값입니다.
 * [expiredAt]은 만료 command가 정확히 한 번 기록한 시각이며 제안 본문을 수정하지 않습니다.
 */
object AppointmentProposals : LongIdTable("scheduling_appointment_proposals") {
    val commitmentId = reference("commitment_id", AppointmentCommitments, onDelete = ReferenceOption.CASCADE)
    val revision = long("revision")
    val proposedStartAt = timestamp("proposed_start_at")
    val proposedEndAt = timestamp("proposed_end_at")
    val expiresAt = timestamp("expires_at")
    val expiredAt = timestamp("expired_at").nullable()
    val representativeTreatmentName = varchar("representative_treatment_name", 256)
    val proposalHash = varchar("proposal_hash", 64)
    val policySnapshotId = long("policy_snapshot_id")
    val supersedesProposalId = long("supersedes_proposal_id").nullable()
    val createdByActor = varchar("created_by_actor", 128)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex("uq_proposal_commitment_revision", commitmentId, revision)
        index("idx_proposal_current", false, commitmentId, revision)
        index("idx_proposal_hash", false, commitmentId, proposalHash)
    }
}
