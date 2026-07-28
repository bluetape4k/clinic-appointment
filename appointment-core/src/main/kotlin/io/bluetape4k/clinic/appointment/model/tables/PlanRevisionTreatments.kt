package io.bluetape4k.clinic.appointment.model.tables

import io.bluetape4k.clinic.appointment.model.plan.PlanTreatmentStatus
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * Plan revision별 treatment provenance와 완료 여부입니다.
 */
object PlanRevisionTreatments : LongIdTable("scheduling_plan_revision_treatments") {
    val planRevisionId = reference(
        "plan_revision_id",
        AppointmentPlanRevisions,
        onDelete = ReferenceOption.CASCADE,
    )
    val treatmentKey = varchar("treatment_key", 128)
    val componentProductId = varchar("component_product_id", 128)
    val componentProductVersionId = varchar("component_product_version_id", 128)
    val productVersionId = varchar("product_version_id", 128)
    val status = enumerationByName<PlanTreatmentStatus>("treatment_status", 16)

    init {
        uniqueIndex("uq_plan_revision_treatment", planRevisionId, treatmentKey)
    }
}
