package io.bluetape4k.clinic.appointment.model.tables

import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Scope-level CAS and row-lock serialization point for policy activation.
 */
object SchedulingPolicyScopeHeads : LongIdTable("scheduling_policy_scope_heads") {
    /** Positive tenant owner of the serialized scope. */
    val tenantGroupId = long("tenant_group_id")

    /** Tenant baseline or clinic override boundary. */
    val scope = enumerationByName<PolicyScope>("scope", 32)

    /** Non-null tenant sentinel `0` or positive clinic ID. */
    val clinicScopeKey = long("clinic_scope_key")

    /** Monotonic optimistic mutation revision, initially zero. */
    val revision = long("revision").default(0L)

    /** Monotonic effective-policy freshness generation, initially zero. */
    val generation = long("generation").default(0L)

    /** UTC instant of the latest successful scope mutation. */
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex("uq_policy_scope_head", tenantGroupId, scope, clinicScopeKey)
    }
}
