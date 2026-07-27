package io.bluetape4k.clinic.appointment.model.tables

import io.bluetape4k.clinic.appointment.model.dto.PolicyActivationCommandStatus
import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * lease fencing과 keyed idempotency를 가진 durable activation command table입니다.
 *
 * raw idempotency key는 의도적으로 컬럼이 없습니다. 저장되는 값은 검증된 key의
 * HMAC hash뿐이며, replay와 conflict 판단도 privacy-safe metadata로 수행합니다.
 */
object SchedulingPolicyActivationCommands : LongIdTable("scheduling_policy_activation_commands") {
    /** 양수 tenant owner입니다. */
    val tenantGroupId = long("tenant_group_id")

    /** tenant baseline 또는 clinic override boundary입니다. */
    val scope = enumerationByName<PolicyScope>("scope", 32)

    /**
     * [PolicyScope.CLINIC_OVERRIDE]에서는 양수 clinic identity이고
     * [PolicyScope.TENANT_DEFAULT]에서만 `null`입니다.
     *
     * dialect별 unique/join 차이를 없애기 위해 [clinicScopeKey]를 사용합니다. 그 값은
     * tenant scope에서는 `0`, clinic scope에서는 이 컬럼과 같은 양수 값이어야 합니다.
     */
    val clinicId = long("clinic_id").nullable()

    /** non-null tenant sentinel `0` 또는 양수 clinic ID입니다. */
    val clinicScopeKey = long("clinic_scope_key")

    /** activation 대상으로 선택된 definition ID입니다. */
    val definitionId = long("definition_id")

    /**
     * 수동 replay가 참조하는 원본 terminal command입니다.
     *
     * `null`은 original immediate/scheduled command를 의미합니다. 양수 값은 repository가
     * source command가 `MISSED`이고 같은 scope에 속하며 같은 definition을 선택했음을
     * 검증한 뒤에만 허용됩니다. terminal source row는 다시 쓰지 않습니다.
     */
    val replayOfCommandId = long("replay_of_command_id").nullable()

    /** approval check가 검증한 정확한 draft revision입니다. */
    val expectedDraftRevision = long("expected_draft_revision")

    /** activation CAS에서 기대하는 scope-head revision입니다. */
    val expectedActiveRevision = long("expected_active_revision")

    /**
     * schedule 또는 immediate activation 직전에 완료된 preview가 관측한 tenant generation입니다.
     *
     * worker 재시작 후에도 stale preview를 탐지할 수 있도록 command 자체에 고정합니다.
     * `0`은 tenant 정책이 아직 한 번도 활성화되지 않은 초기 generation입니다.
     */
    val expectedTenantGeneration = long("expected_tenant_generation")

    /**
     * preview가 관측한 clinic override generation입니다.
     *
     * `0`은 활성 clinic override가 아직 없다는 sentinel입니다. worker는 이 값과 현재
     * clinic generation을 비교하여 schedule 이후 변경을 조용히 덮어쓰지 않습니다.
     */
    val expectedClinicGeneration = long("expected_clinic_generation")

    /**
     * 완전히 완료된 durable preview 결과를 가리키는 opaque evidence token입니다.
     *
     * 원본 request, 환자·예약 정보, actor identity, credential, idempotency key를 포함하지
     * 않아야 합니다. 실행 시 token이 가리키는 completed job의 definition revision과 두
     * generation이 이 command의 고정값과 모두 일치해야 합니다.
     */
    val previewEvidenceToken = varchar("preview_evidence_token", 160)

    /** lowercase HMAC-SHA-256입니다. raw idempotency key는 저장하지 않습니다. */
    val idempotencyKeyHash = varchar("idempotency_key_hash", 64)

    /** key conflict 감지에 사용하는 canonical request의 lowercase SHA-256입니다. */
    val requestFingerprint = varchar("request_fingerprint", 64)

    /** 현재 durable worker lifecycle입니다. */
    val status = enumerationByName<PolicyActivationCommandStatus>("status", 24)

    /** UTC policy activation boundary입니다. */
    val effectiveFrom = timestamp("effective_from")

    /** worker가 claim할 수 있는 가장 이른 UTC instant입니다. */
    val nextAttemptAt = timestamp("next_attempt_at")

    /** 현재 worker의 opaque identity입니다. claim 전에는 `null`입니다. */
    val leaseOwner = varchar("lease_owner", 160).nullable()

    /** UTC lease expiry입니다. claim 전에는 `null`입니다. */
    val leaseUntil = timestamp("lease_until").nullable()

    /** 성공한 claim 횟수입니다. */
    val attempt = integer("attempt").default(0)

    /**
     * completion과 atomic하게 생성된 tenant generation입니다.
     *
     * [PolicyActivationCommandStatus.COMPLETED] 전에는 `null`입니다. completed row는 이
     * 컬럼, [resultClinicGeneration], [eventId]를 함께 채워야 하며, consumer는 이 값
     * 하나만으로 publish 여부를 추론하면 안 됩니다.
     */
    val resultTenantGeneration = long("result_tenant_generation").nullable()

    /**
     * completion과 atomic하게 생성된 clinic generation입니다.
     *
     * [PolicyActivationCommandStatus.COMPLETED] 전에는 `null`입니다. clinic override
     * generation이 없을 때 완료 row에서 `0`은 유효한 값입니다.
     */
    val resultClinicGeneration = long("result_clinic_generation").nullable()

    /**
     * activation transaction에서 기록되는 deterministic outbox event identity입니다.
     *
     * [PolicyActivationCommandStatus.COMPLETED] 전에는 `null`입니다. completed row가
     * publish 가능한 증빙이 되려면 이 값과 두 result generation이 모두 non-null이어야 합니다.
     */
    val eventId = varchar("event_id", 160).nullable()

    /**
     * sanitized stable error code입니다. retry 또는 terminal failure가 기록되지 않았으면 `null`입니다.
     *
     * raw exception text, request JSON, idempotency key, actor data, credential,
     * authentication claim을 포함하면 안 됩니다.
     */
    val lastErrorCode = varchar("last_error_code", 96).nullable()

    /** database insertion instant입니다. */
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)

    /** 마지막 state transition의 UTC instant입니다. */
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
        index("idx_policy_activation_replay_source", false, replayOfCommandId)
    }
}
