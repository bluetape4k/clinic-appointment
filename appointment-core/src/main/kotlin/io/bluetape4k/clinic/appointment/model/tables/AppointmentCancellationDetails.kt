package io.bluetape4k.clinic.appointment.model.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * terminal 취소의 환자 안내 문구 snapshot과 비민감 감사 metadata를 저장한다.
 *
 * 원문 actor ID·token·환자 식별자는 저장하지 않으며 서버 소유 고정 detail은 취소
 * command와 같은 transaction에서만 기록한다. commitment 하나에는 terminal 취소
 * 하나만 존재한다.
 */
object AppointmentCancellationDetails : LongIdTable("scheduling_appointment_cancellation_details") {
    val tenantGroupId = reference("tenant_group_id", TenantGroups, onDelete = ReferenceOption.RESTRICT)
    val clinicId = reference("clinic_id", Clinics, onDelete = ReferenceOption.CASCADE)
    val appointmentId = reference("appointment_id", Appointments, onDelete = ReferenceOption.CASCADE)
    val commitmentId = reference("commitment_id", AppointmentCommitments, onDelete = ReferenceOption.CASCADE)
    val proposalId = reference("proposal_id", AppointmentProposals, onDelete = ReferenceOption.CASCADE)
    val reasonCode = varchar("reason_code", 64)
    val reasonDetail = varchar("reason_detail", 500).nullable()
    val actorRole = varchar("actor_role", 16)
    val actorScopeHash = varchar("actor_scope_hash", 128)
    val detailHash = varchar("detail_hash", 64)
    val occurredAt = timestamp("occurred_at")

    init {
        uniqueIndex("uq_cancellation_detail_commitment", commitmentId)
        index(
            "idx_cancellation_detail_scope_time",
            false,
            tenantGroupId,
            clinicId,
            occurredAt,
        )
    }
}
