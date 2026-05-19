package io.bluetape4k.clinic.appointment.model.tables

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Tenant group table.
 *
 * A tenant group is the data-isolation owner for clinics and tenant-scoped
 * holidays. User locale and tenant identity are intentionally separate.
 */
object TenantGroups : LongIdTable("scheduling_tenant_groups") {
    const val DEFAULT_TENANT_GROUP_ID = 1L
    const val DEFAULT_TENANT_CODE = "tenant-default"
    const val DEFAULT_TENANT_NAME = "Default Tenant"

    val tenantCode = varchar("tenant_code", 64).uniqueIndex("uq_tenant_groups_code")
    val displayName = varchar("display_name", 255)
    val active = bool("active").default(true)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
}
