package io.bluetape4k.clinic.appointment.model.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * BOM items owned by one immutable catalog projection.
 */
object ProductCatalogBomItems : LongIdTable("scheduling_product_catalog_bom_items") {
    val catalogProjectionId = reference(
        "catalog_projection_id",
        ProductCatalogProjections,
        onDelete = ReferenceOption.CASCADE,
    )
    val bomItemId = varchar("bom_item_id", 128)
    val bomOrder = integer("bom_order")
    val representativeTreatmentName = varchar("representative_treatment_name", 256)
    val detailedTreatmentCodesJson = text("detailed_treatment_codes_json")
    val repeatCount = integer("repeat_count")
    val durationMinutes = integer("duration_minutes")
    val minimumIntervalDays = integer("minimum_interval_days").nullable()
    val preferredIntervalDays = integer("preferred_interval_days").nullable()
    val maximumIntervalDays = integer("maximum_interval_days").nullable()
    val practitionerQualificationsJson = text("practitioner_qualifications_json")
    val equipmentTypesJson = text("equipment_types_json")
    val roomTypesJson = text("room_types_json")

    init {
        uniqueIndex("uq_catalog_bom_item", catalogProjectionId, bomItemId)
    }
}
