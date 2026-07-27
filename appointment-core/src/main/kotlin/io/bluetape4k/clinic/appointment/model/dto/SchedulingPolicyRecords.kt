package io.bluetape4k.clinic.appointment.model.dto

import io.bluetape4k.clinic.appointment.model.policy.ActorRole
import io.bluetape4k.clinic.appointment.model.policy.PolicyLifecycle
import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import io.bluetape4k.clinic.appointment.model.policy.SchedulingPolicyKind
import java.io.Serializable
import java.time.Instant

/**
 * tenant 또는 clinic policy scope 하나를 표현하는 canonical database boundary입니다.
 *
 * @property tenantGroupId 정책을 소유한 양수 tenant identity입니다. 신뢰된 command
 * context에서 와야 하며 payload에서 추론하지 않습니다.
 * @property scope tenant baseline 또는 clinic override boundary입니다.
 * @property clinicId clinic override에서는 양수 clinic identity이고 tenant baseline에서는
 * `null`입니다. persistence는 tenant `null`을 `clinic_scope_key` 컬럼에서만 non-null
 * sentinel `0`으로 변환합니다.
 */
data class PolicyScopeRef(
    val tenantGroupId: Long,
    val scope: PolicyScope,
    val clinicId: Long? = null,
) : Serializable {
    /** H2, PostgreSQL, MySQL에서 동일한 unique constraint를 만들기 위한 non-null key입니다. */
    val clinicScopeKey: Long
        get() = when (scope) {
            PolicyScope.TENANT_DEFAULT -> 0L
            PolicyScope.CLINIC_OVERRIDE -> requireNotNull(clinicId) {
                "clinicId is required for CLINIC_OVERRIDE"
            }
        }

    init {
        require(tenantGroupId > 0) { "tenantGroupId must be positive" }
        when (scope) {
            PolicyScope.TENANT_DEFAULT -> require(clinicId == null) {
                "clinicId must be null for TENANT_DEFAULT"
            }
            PolicyScope.CLINIC_OVERRIDE -> require(clinicId != null && clinicId > 0) {
                "clinicId must be positive for CLINIC_OVERRIDE"
            }
        }
    }
}

/**
 * immutable scheduling-policy definition의 persistence projection입니다.
 *
 * @property id database identity입니다. insertion 전에는 `null`입니다.
 * @property tenantGroupId 양수 tenant owner입니다.
 * @property scope tenant default 또는 clinic override입니다.
 * @property clinicId override의 clinic identity입니다. tenant default에서는 `null`입니다.
 * @property clinicScopeKey 저장되는 non-null scope discriminator입니다. `0`은 tenant
 * scope이고 양수 값은 [clinicId]와 같아야 합니다.
 * @property kind payload가 저장된 닫힌 정책 영역입니다.
 * @property version scope와 kind 안에서 양수 immutable publication version입니다.
 * @property schemaVersion payload wire schema version입니다. 양수여야 합니다.
 * @property lifecycle 현재 관리 lifecycle입니다. 발행된 payload row는 retirement 후에도
 * immutable하게 보존됩니다.
 * @property effectiveFrom 선택 가능한 UTC inclusive boundary입니다.
 * @property effectiveUntil 선택 종료 UTC exclusive boundary입니다. open ended이면 `null`입니다.
 * @property revision approval이 bind되는 양수 draft revision입니다.
 * @property payloadHash canonical payload의 lowercase 64-character SHA-256입니다.
 * @property payloadJson schema-versioned canonical JSON입니다. H2, PostgreSQL, MySQL에서
 * UTF-8 byte 기준 256 KiB로 제한됩니다. actor credential이나 idempotency key를 포함하면 안 됩니다.
 * @property createdByActorId stable trusted Gateway subject입니다. display name, access
 * token, request-body identity가 아니어야 합니다.
 * @property createdByActorRole 생성 시점 감사에 기록된 role입니다.
 * @property changeReason revision에 대한 secret 없는 운영자 사유입니다.
 * @property createdAt UTC database creation instant입니다.
 */
data class SchedulingPolicyDefinitionRecord(
    val id: Long? = null,
    val tenantGroupId: Long,
    val scope: PolicyScope,
    val clinicId: Long? = null,
    val clinicScopeKey: Long = if (scope == PolicyScope.TENANT_DEFAULT) 0L else requireNotNull(clinicId),
    val kind: SchedulingPolicyKind,
    val version: Long,
    val schemaVersion: Int,
    val lifecycle: PolicyLifecycle,
    val effectiveFrom: Instant,
    val effectiveUntil: Instant?,
    val revision: Long,
    val payloadHash: String,
    val payloadJson: String,
    val createdByActorId: String,
    val createdByActorRole: ActorRole,
    val changeReason: String,
    val createdAt: Instant = Instant.EPOCH,
) : Serializable

/**
 * 정확한 draft revision 하나에 bind되는 승인 증빙입니다.
 *
 * @property id database identity입니다. insertion 전에는 `null`입니다.
 * @property definitionId 승인 대상 policy definition입니다.
 * @property draftRevision actor가 검토한 정확한 revision입니다. 이후 draft revision이
 * 생기면 이 row는 감사용으로 남지만 activation에는 사용할 수 없습니다.
 * @property actorId 승인자의 stable trusted Gateway subject입니다.
 * @property actorRole 승인 권한을 평가할 때 사용한 role입니다.
 * @property assuranceLevel `MFA` 같은 제한된 non-secret authentication assurance label입니다.
 * 증빙 metadata이지 credential이 아닙니다.
 * @property approvedAt 승인이 기록된 UTC instant입니다.
 */
data class SchedulingPolicyApprovalRecord(
    val id: Long? = null,
    val definitionId: Long,
    val draftRevision: Long,
    val actorId: String,
    val actorRole: ActorRole,
    val assuranceLevel: String,
    val approvedAt: Instant,
) : Serializable

/**
 * 하나의 scope 안에서 모든 policy-kind activation을 직렬화하는 지점입니다.
 *
 * @property id database identity입니다.
 * @property tenantGroupId scope를 소유한 tenant입니다.
 * @property scope tenant baseline 또는 clinic override입니다.
 * @property clinicScopeKey tenant scope이면 `0`, clinic scope이면 양수 clinic ID입니다.
 * @property revision optimistic command revision입니다. 성공한 scope mutation마다 한 번 증가합니다.
 * @property generation freshness를 나타내는 단조 증가 counter입니다. 어떤 policy kind든
 * active가 되면 [revision]과 함께 증가합니다.
 * @property updatedAt 마지막 성공 mutation의 UTC instant입니다.
 */
data class SchedulingPolicyScopeHeadRecord(
    val id: Long,
    val tenantGroupId: Long,
    val scope: PolicyScope,
    val clinicScopeKey: Long,
    val revision: Long,
    val generation: Long,
    val updatedAt: Instant,
) : Serializable

/**
 * 하나의 clinic에 대해 저장된 immutable compiled policy snapshot입니다.
 *
 * @property id database identity입니다.
 * @property tenantGroupId compiled result의 tenant boundary입니다.
 * @property clinicId policy가 resolve된 clinic입니다.
 * @property decisionAt UTC policy decision instant입니다.
 * @property serviceAt UTC planned service instant입니다.
 * @property tenantGeneration 관찰하고 다시 확인한 tenant head generation입니다.
 * @property clinicGeneration 관찰하고 다시 확인한 clinic head generation입니다.
 * @property sourceVersionsJson canonical source-version map JSON입니다.
 * @property sourceByPathJson canonical leaf-source map JSON입니다.
 * @property disabledFeaturesJson canonical sorted disabled-path array JSON입니다.
 * @property warningsJson 순서가 보존되는 고객-safe warning array JSON입니다.
 * @property payloadJson canonical compiled-policy JSON입니다.
 * @property snapshotHash complete compiled contract에 대한 lowercase 64-character SHA-256입니다.
 * identity는 tenant와 clinic scope 안에서만 unique합니다.
 * @property createdAt database creation instant입니다. 더 최신 policy가 active되어도 기존
 * snapshot은 update하지 않습니다.
 */
data class EffectiveSchedulingPolicySnapshotRecord(
    val id: Long,
    val tenantGroupId: Long,
    val clinicId: Long,
    val decisionAt: Instant,
    val serviceAt: Instant,
    val tenantGeneration: Long,
    val clinicGeneration: Long,
    val sourceVersionsJson: String,
    val sourceByPathJson: String,
    val disabledFeaturesJson: String,
    val warningsJson: String,
    val payloadJson: String,
    val snapshotHash: String,
    val createdAt: Instant,
) : Serializable

/** 예약된 policy activation command의 durable state입니다. */
enum class PolicyActivationCommandStatus {
    /** 첫 eligible worker claim을 기다리는 상태입니다. */
    PENDING,
    /** lease column에 기록된 worker가 임시로 소유한 상태입니다. */
    CLAIMED,
    /** 이전 시도가 실패했고 `nextAttemptAt` 이후 다시 실행할 수 있는 상태입니다. */
    RETRY_WAIT,
    /** activation이 commit되었고 결과 metadata가 immutable한 상태입니다. */
    COMPLETED,
    /** effective deadline이 지나 의도적으로 activate하지 않은 terminal 상태입니다. */
    MISSED,
}

/**
 * privacy-safe keyed idempotency를 가진 durable activation command입니다.
 *
 * @property id database identity입니다. insertion 전에는 `null`입니다.
 * @property tenantGroupId command를 소유한 tenant입니다.
 * @property scope tenant baseline 또는 clinic override입니다.
 * @property clinicId override scope의 양수 clinic입니다. 그 외 scope에서는 `null`입니다.
 * @property clinicScopeKey scope에서 파생한 non-null uniqueness sentinel입니다.
 * @property definitionId activation 대상으로 선택된 definition입니다.
 * @property replayOfCommandId 수동 replay가 참조하는 immutable source command입니다.
 * original immediate/scheduled activation이면 `null`입니다. non-null 값은 같은 tenant와
 * policy scope의 terminal `MISSED` command를 가리켜야 합니다. audit lineage일 뿐 source
 * row를 다시 쓰라는 지시가 아닙니다.
 * @property expectedDraftRevision caller가 검증한 draft revision입니다.
 * @property expectedActiveRevision activation 시 기대하는 scope-head revision입니다.
 * @property idempotencyKeyHash 검증된 raw key의 lowercase HMAC-SHA-256입니다. raw key는
 * 저장, log, 반환하지 않습니다.
 * @property requestFingerprint canonical request의 lowercase SHA-256입니다. 같은 key로
 * 다른 fingerprint가 오면 conflict입니다.
 * @property status 현재 worker lifecycle입니다.
 * @property effectiveFrom UTC activation boundary입니다.
 * @property nextAttemptAt worker가 claim할 수 있는 가장 이른 UTC instant입니다.
 * @property leaseOwner claim 중인 opaque bounded worker ID입니다.
 * @property leaseUntil UTC lease expiry입니다. stale owner는 write authority를 잃습니다.
 * @property attempt 성공한 worker claim 횟수입니다.
 * @property resultTenantGeneration completion으로 생성된 tenant generation입니다.
 * non-completed 상태에서는 `null`이고, status가 `COMPLETED`가 될 때 [resultClinicGeneration],
 * [eventId]와 함께 atomic하게 채워져야 합니다.
 * @property resultClinicGeneration completion으로 생성된 clinic generation입니다.
 * non-completed 상태에서는 `null`입니다. clinic override generation이 없는 완료 상태에서는 `0`도 유효합니다.
 * @property eventId activation transaction에서 생성된 deterministic outbox event identity입니다.
 * completion 전에는 `null`입니다. consumer는 publication evidence로 `COMPLETED`, 이 값,
 * 두 result generation이 모두 있음을 요구해야 합니다.
 * @property lastErrorCode stable sanitized retry 또는 terminal error code입니다. 실패가 없으면
 * `null`입니다. raw exception, request JSON, idempotency key, actor data, credential,
 * claim을 포함하면 안 됩니다.
 * @property createdAt database creation instant입니다.
 * @property updatedAt database last-transition instant입니다.
 */
data class SchedulingPolicyActivationCommandRecord(
    val id: Long? = null,
    val tenantGroupId: Long,
    val scope: PolicyScope,
    val clinicId: Long? = null,
    val clinicScopeKey: Long = if (scope == PolicyScope.TENANT_DEFAULT) 0L else requireNotNull(clinicId),
    val definitionId: Long,
    val replayOfCommandId: Long? = null,
    val expectedDraftRevision: Long,
    val expectedActiveRevision: Long,
    val idempotencyKeyHash: String,
    val requestFingerprint: String,
    val status: PolicyActivationCommandStatus = PolicyActivationCommandStatus.PENDING,
    val effectiveFrom: Instant,
    val nextAttemptAt: Instant,
    val leaseOwner: String? = null,
    val leaseUntil: Instant? = null,
    val attempt: Int = 0,
    val resultTenantGeneration: Long? = null,
    val resultClinicGeneration: Long? = null,
    val eventId: String? = null,
    val lastErrorCode: String? = null,
    val createdAt: Instant = Instant.EPOCH,
    val updatedAt: Instant = Instant.EPOCH,
) : Serializable

/** 비동기 scheduling-policy impact preview의 durable state입니다. */
enum class PolicyPreviewJobStatus {
    /** worker claim을 기다리는 상태입니다. */
    PENDING,
    /** 현재 lease owner가 active scan 중인 상태입니다. */
    RUNNING,
    /** 제한된 전체 scan이 성공적으로 완료된 상태입니다. */
    COMPLETED,
    /** scan 중 definition revision 또는 generation이 변경되어 stale이 된 상태입니다. */
    STALE,
    /** retry policy가 소진된 뒤 scan이 실패한 상태입니다. */
    FAILED,
    /** 완료 전에 명시적으로 취소된 상태입니다. */
    CANCELLED,
}

/**
 * bounded preview scan을 재개하기 위한 keyset cursor입니다.
 *
 * @property partition 현재 scan 중인 zero-based partition number입니다.
 * @property lastAppointmentId 마지막으로 처리한 appointment ID입니다. 해당 partition에서
 * 아직 첫 row를 처리하기 전이면 `null`입니다.
 */
data class PolicyPreviewCursor(
    val partition: Int,
    val lastAppointmentId: Long?,
) : Serializable

/**
 * preview checkpoint와 함께 기록되는 단조 증가 counter입니다.
 *
 * @property scannedCount 지금까지 검사한 미래 appointment 총수입니다.
 * @property affectedCount effective policy 또는 schedule이 바뀔 수 있는 subset count입니다.
 */
data class PolicyPreviewProgress(
    val scannedCount: Long,
    val affectedCount: Long,
) : Serializable

/**
 * durable asynchronous preview job입니다.
 *
 * @property id database identity입니다. insertion 전에는 `null`입니다.
 * @property tenantGroupId scan 대상 모든 appointment의 tenant boundary입니다.
 * @property clinicId preview의 clinic boundary입니다.
 * @property definitionId 평가 중인 draft definition입니다.
 * @property draftRevision 정확한 draft revision입니다. mismatch가 있으면 job은 stale입니다.
 * @property tenantGeneration resume 때마다 기대하는 tenant generation입니다.
 * @property clinicGeneration resume 때마다 기대하는 clinic generation입니다.
 * @property partitionCount deterministic resume을 위한 양수 fixed partition count입니다.
 * @property cursorPartition 저장된 zero-based partition cursor입니다.
 * @property cursorLastAppointmentId partition 안에서 마지막으로 처리한 양수 appointment ID입니다.
 * [cursorPartition]이 증가한 직후를 포함해 해당 partition에서 아직 row를 처리하지 않았으면 `null`입니다.
 * @property scannedCount 검사한 row 총수입니다. 단조 증가합니다.
 * @property affectedCount 영향을 받는 row 수입니다. 단조 증가하며 [scannedCount]를 넘을 수 없습니다.
 * @property status 현재 preview lifecycle입니다.
 * @property deadlineAt 이 시각 이후 partial result를 사용할 수 없는 UTC hard deadline입니다.
 * @property nextAttemptAt worker가 claim할 수 있는 가장 이른 UTC instant입니다.
 * @property leaseOwner opaque current worker ID입니다. [status]가 `RUNNING`일 때만 non-null이고
 * [leaseUntil]과 함께 있어야 합니다.
 * @property leaseUntil exclusive UTC fencing deadline입니다. [status]가 `RUNNING`일 때만 non-null이며,
 * 이 instant 이후의 owner는 stale입니다.
 * @property lastErrorCode stable sanitized retry 또는 terminal error code입니다. 실패 기록이 없으면
 * `null`입니다. raw exception, appointment data, request/policy payload, credential, claim을
 * 포함하면 안 됩니다.
 * @property createdAt database creation instant입니다.
 * @property updatedAt database last-transition/checkpoint instant입니다.
 */
data class SchedulingPolicyPreviewJobRecord(
    val id: Long? = null,
    val tenantGroupId: Long,
    val clinicId: Long,
    val definitionId: Long,
    val draftRevision: Long,
    val tenantGeneration: Long,
    val clinicGeneration: Long,
    val partitionCount: Int,
    val cursorPartition: Int = 0,
    val cursorLastAppointmentId: Long? = null,
    val scannedCount: Long = 0,
    val affectedCount: Long = 0,
    val status: PolicyPreviewJobStatus = PolicyPreviewJobStatus.PENDING,
    val deadlineAt: Instant,
    val nextAttemptAt: Instant,
    val leaseOwner: String? = null,
    val leaseUntil: Instant? = null,
    val lastErrorCode: String? = null,
    val createdAt: Instant = Instant.EPOCH,
    val updatedAt: Instant = Instant.EPOCH,
) : Serializable
