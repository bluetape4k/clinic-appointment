package io.bluetape4k.clinic.appointment.model.tables

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * 환자 범위별로 CRM 프로필 변경의 최신 revision 하나를 유지하는 병합 지점입니다.
 */
object ProfileReevaluationHeads : LongIdTable("scheduling_profile_reevaluation_heads") {
    val tenantGroupId = long("tenant_group_id")
    val clinicId = long("clinic_id")
    val patientReferenceFingerprint = varchar("patient_reference_fingerprint", 64)
    val latestRevision = long("latest_revision")
    val latestEventId = varchar("latest_event_id", 160)
    val assessmentRef = varchar("assessment_ref", 512)
    val assessmentHash = varchar("assessment_hash", 64)
    val occurredAt = timestamp("occurred_at")
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex(
            "uq_profile_reevaluation_head_scope",
            tenantGroupId,
            clinicId,
            patientReferenceFingerprint,
        )
    }
}

