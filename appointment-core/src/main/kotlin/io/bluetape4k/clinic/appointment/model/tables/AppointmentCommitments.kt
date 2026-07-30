package io.bluetape4k.clinic.appointment.model.tables

import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitmentStatus
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentOrigin
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * 방문 예약별 일정 합의와 현재 확정 proposal을 저장합니다.
 *
 * [confirmedProposalId]는 새 proposal을 append할 때 변경하지 않습니다. 고객 동의와 자원
 * 교체가 성공한 같은 caller transaction에서만 CAS로 갱신합니다.
 */
object AppointmentCommitments : LongIdTable("scheduling_appointment_commitments") {
    val appointmentId = reference("appointment_id", Appointments, onDelete = ReferenceOption.CASCADE)
    val status = enumerationByName<AppointmentCommitmentStatus>("commitment_status", 32)
    val origin = enumerationByName<AppointmentOrigin>("origin", 16)
    val confirmedProposalId = long("confirmed_proposal_id").nullable()
    val effectivePolicySnapshotId = long("effective_policy_snapshot_id")
    val version = long("version")
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex("uq_commitment_appointment", appointmentId)
        index("idx_commitment_confirmed_proposal", false, confirmedProposalId)
        index("idx_commitment_profile_reevaluation", false, status, appointmentId)
    }
}
