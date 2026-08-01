package io.bluetape4k.clinic.appointment.model.tables

import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityVerdict
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * 정책 version과 입력 이력에 대한 immutable 평가 결과 snapshot입니다.
 */
object BookingReliabilityDecisions : LongIdTable("booking_reliability_decisions") {
    val tenantGroupId = long("tenant_group_id")
    val clinicId = long("clinic_id")
    val memberId = varchar("member_id", 255)
    val policyVersionId = long("policy_version_id").nullable()
    val policyHash = varchar("policy_hash", 64).nullable()
    val evaluatedAt = timestamp("evaluated_at")
    val verdict = enumerationByName<BookingReliabilityVerdict>("verdict", 32)
        .check("ck_booking_reliability_decision_verdict") {
            it inList BookingReliabilityVerdict.entries
        }
    val reasonCodesCsv = varchar("reason_codes_csv", 512)
    val triggerAppointmentIdsCsv = varchar("trigger_appointment_ids_csv", 2048)
    val triggerTypesCsv = varchar("trigger_types_csv", 2048)
    val noShowCount = integer("no_show_count")
    val lateCancellationCount = integer("late_cancellation_count")
    val effectiveFrom = timestamp("effective_from").nullable()
    val expiresAt = timestamp("expires_at").nullable()
    val decisionDigest = varchar("decision_digest", 64)
    val hasAdditionalTriggers = bool("has_additional_triggers").default(false)
    val auditCursor = varchar("audit_cursor", 512).nullable()
    val actorRef = varchar("actor_ref", 128)
    val correlationId = varchar("correlation_id", 160).nullable()
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex(
            "ux_booking_reliability_decision_digest",
            tenantGroupId,
            clinicId,
            memberId,
            decisionDigest,
        )
        index(
            "idx_booking_reliability_decision_member_latest",
            false,
            tenantGroupId,
            clinicId,
            memberId,
            evaluatedAt,
            id,
        )
    }
}
