package io.bluetape4k.clinic.appointment.model.tables

import io.bluetape4k.clinic.appointment.model.identity.PatientLoginIdentifierKey
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/** 환자 계정의 정규화된 tenant-scoped login identifier 테이블입니다. */
object PatientLoginIdentities : LongIdTable("scheduling_patient_login_identities") {
    val patientAccountId = reference("patient_account_id", PatientAccounts, onDelete = ReferenceOption.CASCADE)
    val tenantGroupId = reference("tenant_group_id", TenantGroups, onDelete = ReferenceOption.RESTRICT)
    val key = enumerationByName<PatientLoginIdentifierKey>("identifier_key", 16)
    val normalizedValue = varchar("normalized_value", 254)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex(
            "uq_patient_login_identities_tenant_key_value",
            tenantGroupId,
            key,
            normalizedValue,
        )
        uniqueIndex("uq_patient_login_identities_account_key", patientAccountId, key)
        index("idx_patient_login_identities_account", false, patientAccountId)
    }
}
