package io.bluetape4k.clinic.appointment.model.tables

import io.bluetape4k.clinic.appointment.model.policy.ActorRole
import io.bluetape4k.clinic.appointment.model.policy.PolicyLifecycle
import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import io.bluetape4k.clinic.appointment.model.policy.SchedulingPolicyKind
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * immutable policy version과 revision에 bind된 draft payload를 저장하는 table입니다.
 *
 * `clinic_scope_key`는 의도적으로 non-null입니다. `0`은 tenant scope를 나타내고
 * 양수 clinic ID는 clinic scope를 나타냅니다. 이 방식으로 H2, PostgreSQL, MySQL에서
 * 동일한 unique constraint 동작을 얻습니다.
 */
object SchedulingPolicyDefinitions : LongIdTable("scheduling_policy_definitions") {
    /** 양수 tenant owner입니다. 모든 조회 authorization은 이 값으로 scope가 제한되어야 합니다. */
    val tenantGroupId = long("tenant_group_id")

    /** 조직 정책 boundary입니다. tenant baseline 또는 clinic override를 나타냅니다. */
    val scope = enumerationByName<PolicyScope>("scope", 32)

    /** human-domain clinic identity입니다. tenant scope에서만 `null`입니다. */
    val clinicId = long("clinic_id").nullable()

    /** non-null unique-key discriminator입니다. tenant는 `0`, clinic은 양수 clinic ID입니다. */
    val clinicScopeKey = long("clinic_scope_key")

    /** 독립적으로 versioning되는 닫힌 policy 영역입니다. */
    val policyKind = enumerationByName<SchedulingPolicyKind>("policy_kind", 64)

    /** scope와 kind 안에서 양수 immutable publication version입니다. */
    val version = long("version")

    /** payload wire-schema version입니다. 양수여야 합니다. */
    val schemaVersion = integer("schema_version")

    /** 관리 lifecycle입니다. publication 이후 payload byte는 변경하지 않습니다. */
    val lifecycle = enumerationByName<PolicyLifecycle>("lifecycle", 24)

    /** UTC inclusive selection boundary입니다. */
    val effectiveFrom = timestamp("effective_from")

    /** UTC exclusive selection boundary입니다. open-ended validity면 `null`입니다. */
    val effectiveUntil = timestamp("effective_until").nullable()

    /** approval이 bind되는 양수 optimistic draft revision입니다. */
    val revision = long("revision")

    /** canonical payload의 lowercase SHA-256입니다. */
    val payloadHash = varchar("payload_hash", 64)

    /** insertion 전에 크기 제한을 검증한 schema-aware canonical payload JSON입니다. */
    val payloadJson = text("payload_json")

    /** revision을 생성한 stable trusted Gateway subject입니다. credential이 아닙니다. */
    val createdByActorId = varchar("created_by_actor_id", 160)

    /** 생성 시점 감사용으로 기록한 actor role입니다. */
    val createdByActorRole = enumerationByName<ActorRole>("created_by_actor_role", 24)

    /** secret이 아닌 bounded operator rationale입니다. */
    val changeReason = varchar("change_reason", 1000)

    /** UTC database creation instant입니다. */
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
