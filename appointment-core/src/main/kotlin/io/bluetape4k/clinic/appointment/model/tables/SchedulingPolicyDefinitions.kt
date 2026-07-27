package io.bluetape4k.clinic.appointment.model.tables

import io.bluetape4k.clinic.appointment.model.policy.ActorRole
import io.bluetape4k.clinic.appointment.model.policy.PolicyLifecycle
import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import io.bluetape4k.clinic.appointment.model.policy.SchedulingPolicyKind
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Immutable policy versions and revision-bound draft payloads.
 *
 * `clinic_scope_key` is deliberately non-null: `0` represents tenant scope and
 * a positive clinic ID represents clinic scope. This produces identical unique
 * constraint behavior on H2, PostgreSQL, and MySQL.
 */
object SchedulingPolicyDefinitions : LongIdTable("scheduling_policy_definitions") {
    /** Positive tenant owner; authorization must qualify every lookup by it. */
    val tenantGroupId = long("tenant_group_id")

    /** Organizational policy boundary. */
    val scope = enumerationByName<PolicyScope>("scope", 32)

    /** Nullable human-domain clinic identity; null only for tenant scope. */
    val clinicId = long("clinic_id").nullable()

    /** Non-null unique-key discriminator: tenant `0` or positive clinic ID. */
    val clinicScopeKey = long("clinic_scope_key")

    /** Closed independently versioned policy area. */
    val policyKind = enumerationByName<SchedulingPolicyKind>("policy_kind", 64)

    /** Positive immutable publication version within scope and kind. */
    val version = long("version")

    /** Positive payload wire-schema version. */
    val schemaVersion = integer("schema_version")

    /** Administrative lifecycle; publication never mutates payload bytes. */
    val lifecycle = enumerationByName<PolicyLifecycle>("lifecycle", 24)

    /** Inclusive UTC selection boundary. */
    val effectiveFrom = timestamp("effective_from")

    /** Exclusive UTC selection boundary, or null for open-ended validity. */
    val effectiveUntil = timestamp("effective_until").nullable()

    /** Positive optimistic draft revision to which approvals bind. */
    val revision = long("revision")

    /** Lowercase canonical payload SHA-256. */
    val payloadHash = varchar("payload_hash", 64)

    /** Canonical schema-aware payload JSON, bounded before insertion. */
    val payloadJson = text("payload_json")

    /** Stable trusted Gateway subject that created the revision. */
    val createdByActorId = varchar("created_by_actor_id", 160)

    /** Actor role captured for audit at creation time. */
    val createdByActorRole = enumerationByName<ActorRole>("created_by_actor_role", 24)

    /** Non-secret bounded operator rationale. */
    val changeReason = varchar("change_reason", 1000)

    /** Database creation instant in UTC. */
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex(
            "uq_policy_definition",
            tenantGroupId,
            scope,
            clinicScopeKey,
            policyKind,
            version,
        )
        index(
            "idx_policy_definition_effective",
            false,
            tenantGroupId,
            scope,
            clinicScopeKey,
            policyKind,
            lifecycle,
            effectiveFrom,
        )
    }
}
