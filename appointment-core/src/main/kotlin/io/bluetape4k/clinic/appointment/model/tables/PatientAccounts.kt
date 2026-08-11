package io.bluetape4k.clinic.appointment.model.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/** tenant별 opaque patient subject와 password hash를 보관하는 계정 테이블입니다. */
object PatientAccounts : LongIdTable("scheduling_patient_accounts") {
    val tenantGroupId = reference("tenant_group_id", TenantGroups, onDelete = ReferenceOption.RESTRICT)
    val patientSubject = varchar("patient_subject", 160)
    val displayName = varchar("display_name", 100)
    val passwordHash = varchar("password_hash", 255)
    val active = bool("active").default(true)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex("uq_patient_accounts_tenant_subject", tenantGroupId, patientSubject)
        index("idx_patient_accounts_tenant_active", false, tenantGroupId, active)
    }
}
