package io.bluetape4k.clinic.appointment.model.tables

import io.bluetape4k.clinic.appointment.model.plan.AppointmentPlanStatus
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * 구매 정보에서 파생한 불변 appointment plan입니다.
 */
object AppointmentPlans : LongIdTable("scheduling_appointment_plans") {
    val tenantGroupId = reference("tenant_group_id", TenantGroups, onDelete = ReferenceOption.RESTRICT)
    val clinicId = reference("clinic_id", Clinics, onDelete = ReferenceOption.RESTRICT)
    val catalogProjectionId = reference(
        "catalog_projection_id",
        ProductCatalogProjections,
        onDelete = ReferenceOption.RESTRICT,
    )
    val sourcePurchaseAuthority = varchar("source_purchase_authority", 128)
    val sourcePurchaseId = varchar("source_purchase_id", 128)
    val patientReferenceCiphertext = text("patient_reference_ciphertext")
    val patientReferenceKeyId = varchar("patient_reference_key_id", 128)
    val patientReferenceFingerprint = varchar("patient_reference_fingerprint", 128)
    val catalogSourceAuthority = varchar("catalog_source_authority", 128)
    val productId = varchar("product_id", 128)
    val catalogVersion = long("catalog_version")
    val catalogPayloadHash = varchar("catalog_payload_hash", 64)
    val productName = varchar("product_name", 256)
    val bookingPreferenceType = varchar("booking_preference_type", 64)
    val bookingPreferencePayload = text("booking_preference_payload")
    val status = enumerationByName<AppointmentPlanStatus>("status", 32)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex(
            "uq_plan_source_purchase",
            tenantGroupId,
            clinicId,
            sourcePurchaseAuthority,
            sourcePurchaseId,
        )
        index("idx_plan_tenant_clinic_status", false, tenantGroupId, clinicId, status)
    }
}
