package io.bluetape4k.clinic.appointment.model.tables

import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * policy activation을 위한 scope-level CAS 및 row-lock 직렬화 지점입니다.
 *
 * tenant head는 tenant 정책 자체의 [generation]뿐 아니라 하위 병원 override 변경을 O(1)로
 * 감지하기 위한 [clinicGenerationEpoch]도 소유합니다. 병원 head generation을 증가시키는
 * 트랜잭션은 항상 tenant head를 먼저 잠그고 이 epoch도 함께 증가시켜야 합니다.
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

    /**
     * tenant 아래 어느 병원 override generation이라도 바뀔 때 증가하는 단조 counter입니다.
     *
     * tenant head에서만 의미가 있으며 clinic head에서는 항상 `0`을 유지합니다. tenant preview는
     * 이 값을 hash한 증적을 보관해 매 page마다 병원·head 전체를 다시 읽지 않고도 정책 변경을
     * 감지합니다.
     */
    val clinicGenerationEpoch = long("clinic_generation_epoch").default(0L)

    /** 이 행의 revision, generation 또는 [clinicGenerationEpoch]가 마지막으로 바뀐 UTC instant입니다. */
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex("uq_policy_scope_head", tenantGroupId, scope, clinicScopeKey)
    }
}
