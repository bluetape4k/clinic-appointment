package io.bluetape4k.clinic.appointment.event.policy

import io.bluetape4k.clinic.appointment.model.policy.ActorAuditRef
import io.bluetape4k.clinic.appointment.model.policy.PolicyGenerationVector
import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import io.bluetape4k.clinic.appointment.model.policy.SchedulingPolicyKind
import java.io.Serializable
import java.time.Instant

/**
 * 정책 definition 하나가 활성화된 뒤 발행되는 redacted integration contract이다.
 *
 * 이 event는 정책 문서 자체가 아니라 policy identity와 freshness를 설명한다. consumer는
 * 지정된 boundary를 통해 policy state를 조회하거나 compile하고, [payloadHash]로 같은 불변
 * definition을 관측했는지 확인한다. 이렇게 해서 raw operational rule, change rationale,
 * credential, idempotency key, patient data를 outbox 밖에 둔다.
 *
 * @property eventId definition identity, version, effective start, 두 committed generation에서
 * 파생한 deterministic UUID. 같은 completed activation을 retry하면 같은 event를 가리킨다.
 * @property definitionId 양수 immutable policy-definition database ID.
 * @property policyKind active definition이 변경된 닫힌 policy area.
 * @property policyVersion tenant/clinic scope와 [policyKind] 안에서의 양수 immutable publication version.
 * @property policyScope activated definition의 조직 경계. `TENANT_DEFAULT`는 outbox clinic ID가
 * `null`이어야 하고, `CLINIC_OVERRIDE`는 outbox envelope에 양수 clinic ID가 필요하다.
 * @property effectiveFrom definition이 effective-policy compilation 후보가 되는 inclusive UTC instant.
 * @property effectiveUntil exclusive UTC eligibility boundary. open-ended definition이면 `null`.
 * @property generation commit된 tenant/clinic freshness counter. tenant generation은 양수이고,
 * clinic override generation이 아직 없으면 clinic generation은 `0`일 수 있다.
 * @property payloadHash canonical policy payload JSON의 lowercase 64자 SHA-256.
 * canonical payload 자체는 의도적으로 제외된다.
 * @property actor activation을 승인하거나 실행한 최소 trusted Gateway actor reference.
 * display name, token, mutable claim을 포함하지 않는다.
 * @property correlationId 길이가 제한된 request/workflow trace ID. upstream causation event가 아니며,
 * 그렇게 사용하면 안 된다.
 * @property schemaVersion 이 redacted event의 양수 wire-schema version.
 */
data class SchedulingPolicyActivatedEvent(
    val eventId: String,
    val definitionId: Long,
    val policyKind: SchedulingPolicyKind,
    val policyVersion: Long,
    val policyScope: PolicyScope,
    val effectiveFrom: Instant,
    val effectiveUntil: Instant?,
    val generation: PolicyGenerationVector,
    val payloadHash: String,
    val actor: ActorAuditRef,
    val correlationId: String,
    val schemaVersion: Int = 1,
) : Serializable {
    private companion object {
        const val serialVersionUID = 1L
    }
}
