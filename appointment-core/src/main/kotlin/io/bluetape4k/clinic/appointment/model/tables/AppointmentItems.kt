package io.bluetape4k.clinic.appointment.model.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * 한 방문에서 수행을 시도하는 Plan-linked 세부 진료 항목입니다.
 *
 * 당일 일부만 이행되면 완료된 row를 유지하고 미완료 세부 진료만 새 방문의 새 attempt로
 * 추가합니다. 준비·진료·회복 시간은 항목별로 보존합니다.
 */
object AppointmentItems : LongIdTable("scheduling_appointment_items") {
    val appointmentId = reference("appointment_id", Appointments, onDelete = ReferenceOption.CASCADE)
    val proposalId = reference("proposal_id", AppointmentProposals, onDelete = ReferenceOption.CASCADE)
    val planRevisionId =
        reference(
            "plan_revision_id",
            AppointmentPlanRevisions,
            onDelete = ReferenceOption.RESTRICT,
        )
    val treatmentKey = varchar("treatment_key", 128)
    val representativeTreatmentName = varchar("representative_treatment_name", 256)
    val detailedTreatmentCodesPayload = text("detailed_treatment_codes_payload")
    val preparationMinutes = integer("preparation_minutes")
    val treatmentMinutes = integer("treatment_minutes")
    val recoveryMinutes = integer("recovery_minutes")
    val attemptNumber = integer("attempt_number")

    init {
        uniqueIndex(
            "uq_appointment_item_attempt",
            proposalId,
            planRevisionId,
            treatmentKey,
            attemptNumber,
        )
        index("idx_appointment_item_proposal", false, proposalId)
        index("idx_appointment_item_treatment", false, planRevisionId, treatmentKey)
    }
}
