package io.bluetape4k.clinic.appointment.model.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * 표시용 room type이 아니라 실제로 점유할 수 있는 병원 공간입니다.
 */
object TreatmentSpaces : LongIdTable("scheduling_treatment_spaces") {
    val tenantGroupId = reference("tenant_group_id", TenantGroups, onDelete = ReferenceOption.RESTRICT)
    val clinicId = reference("clinic_id", Clinics, onDelete = ReferenceOption.CASCADE)
    val spaceCode = varchar("space_code", 128)
    val displayName = varchar("display_name", 256)
    val capabilitiesPayload = text("capabilities_payload")
    val nominalCapacity = integer("nominal_capacity")
    val bucketMinutes = integer("bucket_minutes")
    val active = bool("active").default(true)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex("uq_treatment_space_code", tenantGroupId, clinicId, spaceCode)
        index("idx_treatment_space_active", false, tenantGroupId, clinicId, active)
    }
}
