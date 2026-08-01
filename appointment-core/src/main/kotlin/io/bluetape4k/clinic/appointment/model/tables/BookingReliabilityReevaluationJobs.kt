package io.bluetape4k.clinic.appointment.model.tables

import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityReevaluationJobStatus
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * 신뢰성 정책/이력 변경 후 member 단위 재평가를 이어가기 위한 durable worker job입니다.
 */
object BookingReliabilityReevaluationJobs : LongIdTable("booking_reliability_reevaluation_jobs") {
    val tenantGroupId = long("tenant_group_id")
    val clinicId = long("clinic_id")
    val memberId = varchar("member_id", 255)
    val policyVersionId = long("policy_version_id").nullable()
    val idempotencyKeyHash = varchar("idempotency_key_hash", 64)
    val commandHash = varchar("command_hash", 64)
    val status = enumerationByName<BookingReliabilityReevaluationJobStatus>("status", 24)
        .check("ck_booking_reliability_reevaluation_status") {
            it inList BookingReliabilityReevaluationJobStatus.entries
        }
    val nextAttemptAt = timestamp("next_attempt_at")
    val leaseOwner = varchar("lease_owner", 160).nullable()
    val leaseExpiresAt = timestamp("lease_expires_at").nullable()
    val attemptCount = integer("attempt_count").default(0)
    val cursorOccurredAt = timestamp("cursor_occurred_at").nullable()
    val cursorEventId = varchar("cursor_event_id", 160).nullable()
    val scannedCount = long("scanned_count").default(0L)
    val decisionCount = long("decision_count").default(0L)
    val lastFailureCode = varchar("last_failure_code", 96).nullable()
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex(
            "ux_booking_reliability_reevaluation_idempotency",
            tenantGroupId,
            clinicId,
            memberId,
            idempotencyKeyHash,
        )
        index(
            "idx_booking_reliability_reevaluation_due",
            false,
            status,
            nextAttemptAt,
            clinicId,
            id,
        )
        index(
            "idx_booking_reliability_reevaluation_lease",
            false,
            status,
            leaseExpiresAt,
            clinicId,
            id,
        )
    }
}
