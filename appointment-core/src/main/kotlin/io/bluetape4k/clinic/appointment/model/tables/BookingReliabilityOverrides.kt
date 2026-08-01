package io.bluetape4k.clinic.appointment.model.tables

import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityOverrideAction
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityReasonCode
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityVerdict
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * 직원 override와 clear 명령을 append-only로 남기는 감사 ledger입니다.
 */
object BookingReliabilityOverrides : LongIdTable("booking_reliability_overrides") {
    val tenantGroupId = long("tenant_group_id")
    val clinicId = long("clinic_id")
    val memberId = varchar("member_id", 255)
    val decisionId = long("decision_id").nullable()
    val policyVersionId = long("policy_version_id").nullable()
    val previousDecisionDigest = varchar("previous_decision_digest", 64).nullable()
    val action = enumerationByName<BookingReliabilityOverrideAction>("action", 16)
        .check("ck_booking_reliability_override_action") {
            it inList BookingReliabilityOverrideAction.entries
        }
    val verdict = enumerationByName<BookingReliabilityVerdict>("verdict", 32).nullable()
    val reasonCode = enumerationByName<BookingReliabilityReasonCode>("reason_code", 64)
        .check("ck_booking_reliability_override_reason") {
            it inList BookingReliabilityReasonCode.entries
        }
    val actorId = varchar("actor_id", 128)
    val actorType = varchar("actor_type", 32)
    val idempotencyKeyHash = varchar("idempotency_key_hash", 64)
    val commandHash = varchar("command_hash", 64)
    val resultDigest = varchar("result_digest", 64)
    val expectedVersion = long("expected_version")
    val effectiveFrom = timestamp("effective_from")
    val expiresAt = timestamp("expires_at").nullable()
    val correlationId = varchar("correlation_id", 160).nullable()
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex(
            "ux_booking_reliability_override_idempotency",
            tenantGroupId,
            clinicId,
            memberId,
            idempotencyKeyHash,
        )
        index(
            "idx_booking_reliability_override_active",
            false,
            tenantGroupId,
            clinicId,
            memberId,
            effectiveFrom,
            expiresAt,
            id,
        )
    }
}
