package io.bluetape4k.clinic.appointment.model.tables

import io.bluetape4k.clinic.appointment.model.commitment.ConsentDecisionType
import io.bluetape4k.clinic.appointment.model.commitment.ConsentSubjectType
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * proposal 또는 상품 version 전환에 결합된 append-only 동의 증빙입니다.
 *
 * 원문과 개인정보를 저장하지 않고 [evidenceHash], [evidenceType], [termsHash]와
 * 원본 권위·식별자만 보존합니다. [subjectPayload]는 식별자, revision, hash만 담는
 * 정규화된 내부 표현입니다.
 */
object ConsentDecisions : LongIdTable("scheduling_consent_decisions") {
    val commitmentId = reference("commitment_id", AppointmentCommitments, onDelete = ReferenceOption.CASCADE)
    val subjectType = enumerationByName<ConsentSubjectType>("subject_type", 48)
    val subjectPayload = text("subject_payload")
    val decision = enumerationByName<ConsentDecisionType>("decision", 16)
    val evidenceAuthority = varchar("evidence_authority", 128)
    val evidenceId = varchar("evidence_id", 128)
    val evidenceHash = varchar("evidence_hash", 64)
    val evidenceType = varchar("evidence_type", 64).nullable()
    val termsHash = varchar("terms_hash", 64).nullable()
    val decidedAt = timestamp("decided_at")
    val actorRef = varchar("actor_ref", 128)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex("uq_consent_evidence", evidenceAuthority, evidenceId)
        index("idx_consent_commitment_subject", false, commitmentId, subjectType)
    }
}
