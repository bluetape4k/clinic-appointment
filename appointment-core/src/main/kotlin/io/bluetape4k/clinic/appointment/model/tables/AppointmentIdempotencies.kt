package io.bluetape4k.clinic.appointment.model.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * 예약 생성 요청 재시도를 위한 멱등성 키 저장소.
 */
object AppointmentIdempotencies : LongIdTable("scheduling_appointment_idempotency") {
    val tenantGroupId = reference("tenant_group_id", TenantGroups, onDelete = ReferenceOption.RESTRICT)
    val clinicId = reference("clinic_id", Clinics, onDelete = ReferenceOption.CASCADE)
    val idempotencyKey = varchar("idempotency_key", 255)
    val requestFingerprint = varchar("request_fingerprint", 64)
    val appointmentId = reference("appointment_id", Appointments, onDelete = ReferenceOption.CASCADE)
    val expiresAt = timestamp("expires_at")
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex("uq_appointment_idempotency_scope_key", tenantGroupId, clinicId, idempotencyKey)
        index("idx_appointment_idempotency_expires_at", false, expiresAt)
    }
}
