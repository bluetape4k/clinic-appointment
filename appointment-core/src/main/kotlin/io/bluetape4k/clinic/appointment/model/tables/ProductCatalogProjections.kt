package io.bluetape4k.clinic.appointment.model.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Immutable product-catalog versions projected into the appointment service.
 */
object ProductCatalogProjections : LongIdTable("scheduling_product_catalog_projections") {
    val tenantGroupId = reference("tenant_group_id", TenantGroups, onDelete = ReferenceOption.RESTRICT)
    val clinicId = reference("clinic_id", Clinics, onDelete = ReferenceOption.RESTRICT)
    val sourceAuthority = varchar("source_authority", 128)
    val productId = varchar("product_id", 128)
    val catalogVersion = long("catalog_version")
    val productName = varchar("product_name", 256)
    val schemaVersion = integer("schema_version")
    val sourceUpdatedAt = timestamp("source_updated_at")
    val payloadHash = varchar("payload_hash", 64)
    val initialBookingRuleType = varchar("initial_booking_rule_type", 64).nullable()
    val initialBookingMaximumDays = integer("initial_booking_maximum_days").nullable()
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex("uq_catalog_scope_version", tenantGroupId, clinicId, productId, catalogVersion)
        index("idx_catalog_scope_product", false, tenantGroupId, clinicId, productId)
    }
}
