package io.bluetape4k.clinic.appointment.model.tables

import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * policy activation을 위한 scope-level CAS 및 row-lock 직렬화 지점입니다.
 */
object SchedulingPolicyScopeHeads : LongIdTable("scheduling_policy_scope_heads") {
    /** 직렬화 대상 scope의 양수 tenant owner입니다. */
    val tenantGroupId = long("tenant_group_id")

    /** tenant baseline 또는 clinic override boundary입니다. */
    val scope = enumerationByName<PolicyScope>("scope", 32)

    /** non-null tenant sentinel `0` 또는 양수 clinic ID입니다. */
    val clinicScopeKey = long("clinic_scope_key")

    /** 단조 증가 optimistic mutation revision입니다. 초기값은 zero입니다. */
    val revision = long("revision").default(0L)

    /** effective-policy freshness를 나타내는 단조 증가 generation입니다. 초기값은 zero입니다. */
    val generation = long("generation").default(0L)

    /** 마지막 성공 scope mutation의 UTC instant입니다. */
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex("uq_policy_scope_head", tenantGroupId, scope, clinicScopeKey)
    }
}
