package io.bluetape4k.clinic.appointment.model.tables

import io.bluetape4k.clinic.appointment.model.plan.PlanTreatmentStatus
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * Plan revision별 treatment provenance, 실행 시간과 자원 요구, 완료 여부입니다.
 *
 * 상품 실행 BOM을 다시 조회하지 않고 미래 proposal을 재현할 수 있도록 구성 상품별
 * 세부 진료와 준비·진료·회복 시간, 의료진·장비·공간 capability를 함께 고정합니다.
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
    val sourceBomItemId = varchar("source_bom_item_id", 128)
    val sequence = integer("sequence_no")
    val representativeTreatmentName = varchar("representative_treatment_name", 256)
    val detailedTreatmentCodesPayload = text("detailed_treatment_codes_payload")
    val preparationMinutes = integer("preparation_minutes")
    val treatmentMinutes = integer("treatment_minutes")
    val recoveryMinutes = integer("recovery_minutes")
    val practitionerQualificationsPayload = text("practitioner_qualifications_payload")
    val equipmentTypesPayload = text("equipment_types_payload")
    val spaceCapabilitiesPayload = text("space_capabilities_payload")

    init {
        uniqueIndex("uq_plan_revision_treatment", planRevisionId, treatmentKey)
    }
}
