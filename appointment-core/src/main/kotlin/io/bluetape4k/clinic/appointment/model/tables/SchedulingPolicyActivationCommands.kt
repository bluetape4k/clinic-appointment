package io.bluetape4k.clinic.appointment.model.tables

import io.bluetape4k.clinic.appointment.model.dto.PolicyActivationCommandStatus
import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Durable activation commands with lease fencing and keyed idempotency.
 *
 * The raw idempotency key has intentionally no column.
 */
object SchedulingPolicyActivationCommands : LongIdTable("scheduling_policy_activation_commands") {
    /** Positive tenant owner. */
    val tenantGroupId = long("tenant_group_id")

    /** Tenant baseline or clinic override boundary. */
    val scope = enumerationByName<PolicyScope>("scope", 32)

    /**
     * Positive clinic identity for [PolicyScope.CLINIC_OVERRIDE], and `null`
     * only for [PolicyScope.TENANT_DEFAULT].
     *
     * Cross-dialect uniqueness and joins use [clinicScopeKey], where `0`
     * represents tenant scope and a positive value must equal this column.
     */
    val clinicId = long("clinic_id").nullable()

    /** Non-null tenant sentinel `0` or positive clinic ID. */
    val clinicScopeKey = long("clinic_scope_key")

    /** Definition selected for activation. */
    val definitionId = long("definition_id")

    /** Exact draft revision validated by approval checks. */
    val expectedDraftRevision = long("expected_draft_revision")

    /** Expected scope-head revision for activation CAS. */
    val expectedActiveRevision = long("expected_active_revision")

    /** Lowercase HMAC-SHA-256; the raw idempotency key is never stored. */
    val idempotencyKeyHash = varchar("idempotency_key_hash", 64)

    /** Lowercase canonical request SHA-256 used to detect key conflicts. */
    val requestFingerprint = varchar("request_fingerprint", 64)

    /** Current durable worker lifecycle. */
    val status = enumerationByName<PolicyActivationCommandStatus>("status", 24)

    /** UTC policy activation boundary. */
    val effectiveFrom = timestamp("effective_from")

    /** Earliest UTC worker claim instant. */
    val nextAttemptAt = timestamp("next_attempt_at")

    /** Opaque current worker identity, or null while unclaimed. */
    val leaseOwner = varchar("lease_owner", 160).nullable()

    /** UTC lease expiry, or null while unclaimed. */
    val leaseUntil = timestamp("lease_until").nullable()

    /** Number of successful claims. */
    val attempt = integer("attempt").default(0)

    /**
     * Tenant generation produced atomically with completion.
     *
     * It is `null` before [PolicyActivationCommandStatus.COMPLETED]. A completed
     * row must populate this column, [resultClinicGeneration], and [eventId]
     * together; consumers must not infer publication from this value alone.
     */
    val resultTenantGeneration = long("result_tenant_generation").nullable()

    /**
     * Clinic generation produced atomically with completion.
     *
     * It is `null` before [PolicyActivationCommandStatus.COMPLETED]. `0` is a
     * valid completed value when no clinic override generation exists.
     */
    val resultClinicGeneration = long("result_clinic_generation").nullable()

    /**
     * Deterministic outbox event identity written in the activation transaction.
     *
     * It is `null` before [PolicyActivationCommandStatus.COMPLETED]. A completed
     * row is publishable evidence only when this value and both result
     * generations are non-null.
     */
    val eventId = varchar("event_id", 160).nullable()

    /**
     * Sanitized stable error code, or `null` when no retry or terminal failure
     * has been recorded.
     *
     * It must never contain raw exception text, request JSON, an idempotency
     * key, actor data, credentials, or authentication claims.
     */
    val lastErrorCode = varchar("last_error_code", 96).nullable()

    /** Database insertion instant. */
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)

    /** UTC instant of the latest state transition. */
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex(
            "uq_policy_activation_idempotency",
            tenantGroupId,
            scope,
            clinicScopeKey,
            idempotencyKeyHash,
        )
        index("idx_policy_activation_due", false, status, nextAttemptAt, leaseUntil)
    }
}
