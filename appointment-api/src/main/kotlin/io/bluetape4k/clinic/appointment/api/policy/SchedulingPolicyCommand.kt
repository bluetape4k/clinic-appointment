package io.bluetape4k.clinic.appointment.api.policy

import io.bluetape4k.clinic.appointment.api.security.ActorContext
import io.bluetape4k.clinic.appointment.model.dto.PolicyScopeRef
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyActivationCommandRecord
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyDefinitionRecord
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyScopeHeadRecord
import io.bluetape4k.clinic.appointment.model.policy.ActorAuditRef
import io.bluetape4k.clinic.appointment.model.policy.PolicyGenerationVector
import io.bluetape4k.clinic.appointment.model.policy.SchedulingPolicyKind
import java.time.Instant

/**
 * 새 불변 정책 버전을 `DRAFT` 상태로 생성하는 명령이다.
 *
 * payload decoding, typed validation, canonicalization, SHA-256 계산은 이 명령이
 * 만들어지기 전에 끝나야 한다. 따라서 [payloadJson]과 [payloadHash]는 임의의
 * request body field를 그대로 통과시킨 값이 아니라, 애플리케이션 codec이 만든
 * 결과로만 신뢰한다.
 *
 * @property scope 인증된 요청 경로에서 해석한 숫자 영속화 경계. `clinicId`는
 * tenant default일 때만 null이다.
 * @property kind 닫힌 payload schema이자 effective policy compilation 영역.
 * @property schemaVersion codec이 이해하는 양수 wire-schema version.
 * @property effectiveFrom 이 정책 버전이 적용 후보가 되는 inclusive UTC 경계.
 * @property effectiveUntil exclusive UTC 종료 경계. 계획된 종료가 없으면 `null`.
 * @property payloadHash canonical UTF-8 [payloadJson]의 lowercase SHA-256.
 * @property payloadJson schema version이 포함된 canonical JSON. UTF-8 기준 최대 256 KiB이며
 * credential, claim, idempotency key, actor identity를 포함하지 않아야 한다.
 * @property changeReason 1..1000자의 비밀 없는 운영자 변경 사유.
 * @property expectedScopeRevision 잠긴 scope head에서 읽은 non-negative revision.
 * 불일치하면 조용히 rebase하지 않고 명령을 거절한다.
 * @property actor Gateway에서 파생된 현재 actor. 요청 본문으로 공급하거나 덮어쓸 수 없다.
 */
data class CreateSchedulingPolicyDraftCommand(
    val scope: PolicyScopeRef,
    val kind: SchedulingPolicyKind,
    val schemaVersion: Int,
    val effectiveFrom: Instant,
    val effectiveUntil: Instant?,
    val payloadHash: String,
    val payloadJson: String,
    val changeReason: String,
    val expectedScopeRevision: Long,
    val actor: ActorContext,
)

/**
 * 정확한 draft revision 하나의 편집 가능한 내용을 교체하는 명령이다.
 *
 * 성공하면 definition revision과 administrative scope revision을 모두 증가시킨다.
 * 승인 row와 preview evidence는 불변 감사 기록으로 남지만, [expectedDraftRevision]에
 * 고정되어 있으므로 수정 이후에는 더 이상 사용할 수 없다.
 *
 * @property definitionId [scope] 내부에서 해석된 양수 데이터베이스 식별자.
 * @property expectedDraftRevision 교체 대상인 양수 optimistic revision.
 * @property expectedScopeRevision non-negative administrative head revision.
 * 나머지 필드는 [CreateSchedulingPolicyDraftCommand]와 같은 canonical/time-bound 의미를 가진다.
 */
data class ReviseSchedulingPolicyDraftCommand(
    /** 요청 경로에서 해석한 신뢰된 숫자 tenant/clinic 경계. */
    val scope: PolicyScopeRef,
    /** [scope]에 속해야 하는 양수 데이터베이스 식별자. */
    val definitionId: Long,
    /** 양수 편집 가능 revision. 불일치하면 `POLICY_DRAFT_STALE`을 반환한다. */
    val expectedDraftRevision: Long,
    /** administrative edit를 보호하는 non-negative scope-head revision. */
    val expectedScopeRevision: Long,
    /** typed payload codec이 이해하는 양수 wire schema. */
    val schemaVersion: Int,
    /** 수정된 definition의 inclusive UTC 적용 후보 경계. */
    val effectiveFrom: Instant,
    /** exclusive UTC 종료 시각. open-ended interval이면 `null`. */
    val effectiveUntil: Instant?,
    /** canonical UTF-8 [payloadJson]의 lowercase SHA-256. */
    val payloadHash: String,
    /** credential 또는 actor data가 없는 최대 256 KiB canonical JSON. */
    val payloadJson: String,
    /** 1..1000자의 비밀 없는 감사용 변경 사유. */
    val changeReason: String,
    /** Gateway에서 파생된 현재 actor. 요청 본문에서는 절대 받지 않는다. */
    val actor: ActorContext,
)

/**
 * 정확한 draft revision 하나에 대한 승인 증적을 기록하는 명령이다.
 *
 * 승인은 lifecycle 상태가 아니다. draft를 수정해도 이 증적은 삭제되지 않지만,
 * revision이 맞지 않게 되어 stale evidence가 된다. [actor]는 지금 평가되는 권한 주체이자
 * 성공 시 저장되는 감사 주체이다.
 */
data class ApproveSchedulingPolicyCommand(
    /** 소유권을 다시 확인할 신뢰된 tenant/clinic 경계. */
    val scope: PolicyScopeRef,
    /** [scope]에 속해야 하는 양수 draft 식별자. */
    val definitionId: Long,
    /** 승인자가 검토한 정확한 양수 revision. */
    val expectedDraftRevision: Long,
    /** Gateway에서 파생된 현재 승인자와 인증 assurance 증적. */
    val actor: ActorContext,
)

/**
 * 완전한 impact preview가 정확한 입력에 대해 평가되었음을 나타내는 증적이다.
 *
 * 이 값은 애플리케이션 경계의 입력이지, 자체 인증되는 proof가 아니다. 주입된
 * [PolicyPreviewEvidenceVerifier]가 durable job/token 출처를 확인해야 한다.
 * stale 또는 partial preview는 accepted instance가 되어서는 안 된다.
 *
 * @property definitionId preview가 평가한 draft 식별자.
 * @property draftRevision preview가 평가한 정확한 definition revision.
 * @property tenantGeneration scan이 관측한 tenant effective generation.
 * tenant baseline이 있으면 양수이다.
 * @property clinicGeneration scan이 관측한 clinic generation. `0`은 아직 활성화된
 * clinic override generation이 없다는 sentinel이다.
 * @property evidenceId 길이가 제한된 opaque preview job 또는 signed-token identity.
 * 안전한 metadata여야 하며 patient 또는 appointment 세부사항을 embed하면 안 된다.
 */
data class PolicyPreviewEvidence(
    val definitionId: Long,
    val draftRevision: Long,
    val tenantGeneration: Long,
    val clinicGeneration: Long,
    val evidenceId: String,
)

/**
 * preview evidence가 definition 및 잠긴 generation vector와 일치하는지 검증한다.
 *
 * 구현체는 fail-closed로 동작해야 하며, 데이터베이스 scope-head lock을 잡은 동안
 * network call을 수행하면 안 된다. 운영 preview service는 이후 작업에서 로컬에
 * 저장된 evidence로 이 계약을 만족시킬 수 있다.
 */
fun interface PolicyPreviewEvidenceVerifier {
    /**
     * 모든 입력과 일치하는 완전한 durable evidence에 대해서만 `true`를 반환한다.
     *
     * [generation]은 tenant generation을 먼저, clinic generation을 두 번째로 사용한다.
     * `clinicGeneration=0`은 override가 없다는 sentinel이다.
     */
    fun verify(
        evidence: PolicyPreviewEvidence,
        definition: SchedulingPolicyDefinitionRecord,
        generation: PolicyGenerationVector,
    ): Boolean
}

/**
 * 숫자 영속화 tenant가 신뢰된 요청 context와 일치하는지 다시 확인한다.
 *
 * [ActorContext.allowedTenantCodes]에는 데이터베이스 ID가 아니라 Gateway tenant code가
 * 들어 있다. 운영 verifier는 이 code 집합과 이미 해석된 요청 `TenantContext`를 결합하고,
 * 그 숫자 ID가 [PolicyScopeRef.tenantGroupId]와 같아야 한다. tenant-scoped 요청 밖에서
 * 호출되면 반드시 실패해야 한다.
 */
fun interface PolicyTenantBoundaryVerifier {
    /**
     * [scope]의 숫자 tenant가 신뢰된 요청 해석 결과와 같고 [actor]가 대응되는
     * Gateway tenant code를 가진 경우에만 `true`를 반환한다.
     */
    fun isAuthorized(
        scope: PolicyScopeRef,
        actor: ActorContext,
    ): Boolean
}

/**
 * 검증된 draft를 나중에 활성화되도록 예약하는 명령이다.
 *
 * @property expectedActiveRevision scheduling 전 scope-head revision. 영속화되는
 * activation command는 schedule 이후 revision을 저장한다. worker가 활성화할 때 관측해야
 * 하는 revision이 바로 그 값이기 때문이다.
 * @property preview 이 draft와 현재 head에 고정된 완료 preview evidence.
 */
data class ScheduleSchedulingPolicyCommand(
    /** 요청 경로에서 해석한 신뢰된 숫자 tenant/clinic 경계. */
    val scope: PolicyScopeRef,
    /** [scope]에 속해야 하는 양수 draft 식별자. */
    val definitionId: Long,
    /** scheduling 대상으로 승인 및 preview가 완료된 양수 revision. */
    val expectedDraftRevision: Long,
    /** schedule mutation 전 non-negative scope-head revision. */
    val expectedActiveRevision: Long,
    /** schedule 전 generation vector에 고정된 완전한 preview. */
    val preview: PolicyPreviewEvidence,
    /** Gateway에서 파생된 현재 human actor. service actor는 schedule할 수 없다. */
    val actor: ActorContext,
)

/**
 * draft를 즉시 활성화하거나 영속화된 scheduled command를 실행하는 명령이다.
 *
 * @property scheduledCommandId scheduled worker 실행 또는 manual replay를 위한
 * 양수 durable command identity. `null`은 human immediate activation을 의미하며
 * `SYSTEM` actor에는 허용되지 않는다.
 * @property replayOfCommandId manual recovery의 출처가 되는 terminal `MISSED` command ID.
 * 원본 command이면 `null`이다. replay는 새 durable row를 만든다.
 * @property idempotencyKey transaction 전에 변환되는 길이 제한 human raw key.
 * [scheduledCommandId]가 durable scheduled command를 식별할 때만 `null`이다.
 * runner에는 원래 human scheduling request key가 아니라 command ID가 필요하다.
 * @property expectedActiveRevision CAS로 보호되는 정확한 scope-head revision.
 */
data class ActivateSchedulingPolicyCommand(
    /** 요청 context에서 해석한 신뢰된 숫자 tenant/clinic 경계. */
    val scope: PolicyScopeRef,
    /** [scope]에 속해야 하는 양수 definition 식별자. */
    val definitionId: Long,
    /** activation 대상으로 승인 및 preview가 완료된 양수 revision. */
    val expectedDraftRevision: Long,
    /** activation CAS로 보호되는 정확한 non-negative scope-head revision. */
    val expectedActiveRevision: Long,
    /** human raw idempotency key. durable scheduled execution이면 `null`. */
    val idempotencyKey: String?,
    /** definition과 generation에 고정된 완전한 preview evidence. */
    val preview: PolicyPreviewEvidence,
    /** Gateway에서 파생된 human 또는 service activation authority. */
    val actor: ActorContext,
    /** durable scheduled command ID. immediate/manual activation이면 `null`. */
    val scheduledCommandId: Long? = null,
    /** 불변 terminal `MISSED` source. 원본 command이면 `null`. */
    val replayOfCommandId: Long? = null,
)

/**
 * active 또는 아직 active가 아닌 definition 하나를 이력 삭제 없이 retire한다.
 *
 * `ACTIVE` definition이 effective scheduling에 더 이상 기여하지 않게 될 때만
 * retirement가 scope generation을 증가시킨다. `DRAFT` 또는 `SCHEDULED` retirement는
 * administrative revision만 증가시킨다.
 */
data class RetireSchedulingPolicyCommand(
    /** 요청 context에서 해석한 신뢰된 숫자 tenant/clinic 경계. */
    val scope: PolicyScopeRef,
    /** retirement 이후에도 영구 보존되는 양수 definition 식별자. */
    val definitionId: Long,
    /** retire 대상인 정확한 양수 definition revision. */
    val expectedDraftRevision: Long,
    /** retirement CAS를 보호하는 non-negative scope-head revision. */
    val expectedScopeRevision: Long,
    /** Gateway에서 파생된 human administrator 또는 staff actor. */
    val actor: ActorContext,
)

/**
 * activation 또는 idempotent replay의 결과이다.
 *
 * @property command durable activation command. 성공 결과는 항상 `COMPLETED`이며
 * generation과 event identity를 함께 가진다.
 * @property definition 새로 활성화된 불변 definition.
 * @property generation definition, command result, outbox event와 함께 원자적으로
 * commit된 최신 generation vector.
 * @property idempotentReplay write가 발생하지 않았고 같은 scoped key/fingerprint의
 * 이전 completed command를 반환한 경우 `true`.
 */
data class SchedulingPolicyActivationResult(
    val command: SchedulingPolicyActivationCommandRecord,
    val definition: SchedulingPolicyDefinitionRecord,
    val generation: PolicyGenerationVector,
    val idempotentReplay: Boolean,
)

/**
 * caller transaction 안에서 redacted activation integration event를 추가한다.
 *
 * 구현체는 transaction을 새로 열거나 commit하면 안 된다. 예외가 발생하면 lifecycle,
 * head counter, durable command, outbox가 함께 rollback되어야 한다.
 */
fun interface PolicyActivationPublisher {
    /**
     * redacted outbox event 하나를 삽입하고 deterministic ID를 반환한다.
     *
     * [definition]은 이미 `ACTIVE`여야 한다. [generation]은 increment 이후 vector여야 하고,
     * [actor]는 안정적인 감사 identity만 포함해야 하며, [correlationId]는 길이가 제한된
     * 비밀 없는 trace metadata여야 한다.
     */
    fun publish(
        definition: SchedulingPolicyDefinitionRecord,
        generation: PolicyGenerationVector,
        actor: ActorAuditRef,
        correlationId: String,
    ): String
}

/**
 * definition mutation과 직렬화된 scope head를 함께 담는 결과이다.
 *
 * [head]는 transaction 안에서 읽은 post-commit 논리 값이다. 호출자는 잠금 없는 추가
 * read 없이 다음 optimistic command를 만들 수 있다.
 *
 * @property definition mutation 이후 영속화된 definition. request body에서 분리된
 * projection이 아니다.
 * @property head mutation 이후 serialization head. revision은 non-negative이며,
 * generation은 effective scheduling 동작이 바뀔 때만 변경된다.
 */
data class SchedulingPolicyMutationResult(
    val definition: SchedulingPolicyDefinitionRecord,
    val head: SchedulingPolicyScopeHeadRecord,
)
