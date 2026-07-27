package io.bluetape4k.clinic.appointment.model.policy

import java.io.Serializable
import java.time.Instant

/**
 * 정책 정의가 적용되는 조직 수준을 식별합니다.
 *
 * tenant default는 tenant group의 모든 clinic이 공유하는 필수 baseline입니다.
 * clinic override는 [OverrideValue]로 노출된 field만 좁히거나 대체할 수 있으며,
 * tenant 또는 platform 안전 ceiling을 약화할 수 없습니다.
 */
enum class PolicyScope {
    /** tenant 전체 baseline입니다. 이 scope의 definition은 clinic ID를 가지면 안 됩니다. */
    TENANT_DEFAULT,

    /** clinic별 override입니다. 이 scope의 definition은 양수 clinic ID가 필요합니다. */
    CLINIC_OVERRIDE,
}

/**
 * 독립적으로 versioning되는 scheduling policy 영역의 닫힌 집합입니다.
 *
 * 이 집합을 닫아 두면 payload decoding, validation, hashing, compilation이 모두
 * 명시적으로 유지됩니다. 새 kind를 추가하려면 schema, validator, canonical hash writer,
 * compiler contribution, compatibility test를 함께 추가해야 합니다.
 */
enum class SchedulingPolicyKind {
    /** 예약 출처, 가예약 생존 시간, capacity hold, 승인, 동의 규칙입니다. */
    BOOKING_COMMITMENT,

    /** hold 중 사용하는 동의 증빙 보존 및 유효성 규칙입니다. */
    HOLD_AND_CONSENT,

    /** 정상 수용량, 의도적 overbooking, hard booking ceiling입니다. */
    CAPACITY_AND_OVERBOOKING,

    /** 객관적 고객 신뢰도 signal과 scheduling weight입니다. */
    PRIORITY_AND_RELIABILITY,

    /** 예약 방문 전 재확인 timing과 retry 한도입니다. */
    RECONFIRMATION,

    /** 휴무, 휴진, 장비 고장 등 운영 장애 후 회복 제안 동작입니다. */
    DISRUPTION_RECOVERY,

    /** 정상 영업시간을 넘는 통제된 진료 시간 연장 규칙입니다. */
    OPERATING_EXTENSION,

    /** 통지 channel과 장애 대응 필수 service level입니다. */
    NOTIFICATION_AND_SLA,
}

/**
 * immutable policy version의 관리 lifecycle입니다.
 *
 * 발행된 version의 payload를 수정하는 것은 금지됩니다. 변경된 payload는 새 version 또는
 * draft revision으로 표현하고 별도로 activation합니다.
 */
enum class PolicyLifecycle {
    /** scheduling decision에 사용되지 않는 편집 가능한 제안입니다. */
    DRAFT,

    /** 승인되었고 effective time을 기다리는 version입니다. */
    SCHEDULED,

    /** effective policy compilation에 선택될 수 있는 version입니다. */
    ACTIVE,

    /** 감사와 snapshot 재현성을 위해 보관되는 과거 version입니다. */
    RETIRED,
}

/**
 * 정책 감사 증빙에 기록되는 안정적인 actor category입니다.
 *
 * role은 신뢰된 gateway 인증에서 파생되어야 하며, 정책 request body에서 받은 값을
 * 그대로 신뢰하면 안 됩니다.
 */
enum class ActorRole {
    /** 정책 definition을 관리할 수 있는 tenant 또는 clinic 관리자입니다. */
    ADMIN,

    /** 예약 승인 또는 운영 조정을 수행할 수 있는 staff입니다. */
    STAFF,

    /** 예약 가능 시간을 제약할 수 있는 의료진입니다. */
    DOCTOR,

    /** 진료를 받는 고객입니다. administrative policy author가 될 수 없습니다. */
    PATIENT,

    /** 자동 전이를 수행하는 인증된 service identity입니다. */
    SYSTEM,
}

/**
 * 정책 감사 record에 저장되는 최소 immutable actor reference입니다.
 *
 * @property actorId 신뢰된 gateway principal에서 얻은 stable subject identifier입니다.
 * secret이 아니어야 하며 display name, credential, token, 변경 가능한 authorization
 * claim을 포함하면 안 됩니다.
 * @property actorRole 명령 시점의 authorization과 separation-of-duties 검사에 사용한
 * normalized role입니다.
 */
data class ActorAuditRef(
    val actorId: String,
    val actorRole: ActorRole,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 닫힌 집합의 직렬화 가능한 scheduling-policy payload marker입니다.
 *
 * 구현체는 정확히 하나의 [kind]에 대한 완전한 tenant policy 또는 clinic override를
 * 표현합니다. envelope kind와 payload kind는 항상 일치해야 합니다.
 */
sealed interface SchedulingPolicyPayload : Serializable {
    /** 이 payload를 처리할 schema, validator, hash writer, compiler가 담당하는 정책 영역입니다. */
    val kind: SchedulingPolicyKind
}

/**
 * version이 부여된 immutable scheduling-policy definition과 감사 metadata입니다.
 *
 * validation은 의도적으로 `SchedulingPolicyValidator`가 소유합니다. strict JSON decoding
 * 결과나 data class `copy()`로 만들어진 값도 같은 신뢰 경계 검사를 우회할 수 없어야 합니다.
 *
 * @property id database identity입니다. `null`이면 아직 persistence 전 상태입니다.
 * canonical payload hash에는 의도적으로 포함하지 않습니다.
 * @property tenantGroupId 신뢰된 command context가 제공한 양수 tenant boundary입니다.
 * payload 내부 값에서 추론하지 않습니다.
 * @property scope tenant baseline인지 clinic override인지 나타냅니다.
 * @property clinicId [PolicyScope.CLINIC_OVERRIDE]에서는 양수 clinic identity이고,
 * [PolicyScope.TENANT_DEFAULT]에서는 반드시 `null`입니다.
 * @property kind 닫힌 정책 영역입니다. [SchedulingPolicyPayload.kind]와 같아야 합니다.
 * @property version `1`부터 시작하는 단조 증가 immutable publication version입니다.
 * effective contract가 바뀌면 history를 수정하지 않고 version을 올립니다.
 * @property schemaVersion [payload] wire schema version입니다. 이 foundation에서는 schema `1`만 허용합니다.
 * @property lifecycle 관리 상태입니다. active definition만 effective snapshot에 기여하며,
 * lifecycle transition 규칙은 command service가 강제합니다.
 * @property effectiveFrom 이 version이 compilation에 선택될 수 있는 UTC inclusive instant입니다.
 * @property effectiveUntil 이 version이 더 이상 선택될 수 없는 UTC exclusive instant입니다.
 * `null`이면 open-ended interval이고, 값이 있으면 [effectiveFrom]보다 뒤여야 합니다.
 * @property revision 이 definition의 optimistic-concurrency revision입니다. `1`부터
 * 시작하며 승인 증빙은 이 정확한 revision에 bind됩니다.
 * @property payloadHash canonical payload의 lowercase 64-character SHA-256입니다.
 * database ID, actor data, timestamp는 제외합니다.
 * @property payload [kind]와 [scope]에 의해 선택된 typed tenant policy 또는 clinic override입니다.
 * @property createdBy 이 revision 생성 시점에 기록된 신뢰된 actor reference입니다.
 * authorization은 여전히 현재 gateway principal을 사용합니다.
 * @property changeReason 사람이 읽을 수 있는 감사 사유입니다. 1..1000자의 공백 아닌
 * 문자열이어야 하며 secret이나 raw authentication data를 포함하면 안 됩니다.
 */
data class SchedulingPolicyDefinition(
    val id: Long?,
    val tenantGroupId: Long,
    val scope: PolicyScope,
    val clinicId: Long?,
    val kind: SchedulingPolicyKind,
    val version: Long,
    val schemaVersion: Int,
    val lifecycle: PolicyLifecycle,
    val effectiveFrom: Instant,
    val effectiveUntil: Instant?,
    val revision: Long,
    val payloadHash: String,
    val payload: SchedulingPolicyPayload,
    val createdBy: ActorAuditRef,
    val changeReason: String,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
