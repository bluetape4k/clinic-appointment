package io.bluetape4k.clinic.appointment.model.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * 하나의 불변 catalog projection이 소유하는 dependency row입니다.
 */
object ProductCatalogBomDependencies : LongIdTable("scheduling_product_catalog_bom_dependencies") {
    val catalogProjectionId = reference(
        "catalog_projection_id",
        ProductCatalogProjections,
        onDelete = ReferenceOption.CASCADE,
    )
    val predecessorBomItemId = varchar("predecessor_bom_item_id", 128)
    val predecessorSequenceNo = integer("predecessor_sequence_no").default(0)
    val successorBomItemId = varchar("successor_bom_item_id", 128)
    val successorSequenceNo = integer("successor_sequence_no").default(0)
    val minimumIntervalDays = integer("minimum_interval_days")
    val preferredIntervalDays = integer("preferred_interval_days")
    val maximumIntervalDays = integer("maximum_interval_days")

    init {
        uniqueIndex(
            "uq_catalog_bom_dependency",
            catalogProjectionId,
            predecessorBomItemId,
            predecessorSequenceNo,
            successorBomItemId,
            successorSequenceNo,
        )
    }
}
