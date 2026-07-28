package io.bluetape4k.clinic.appointment.api.dto

import com.fasterxml.jackson.annotation.JsonAnySetter
import io.bluetape4k.clinic.appointment.model.policy.SchedulingPolicyKind
import io.swagger.v3.oas.annotations.media.Schema
import tools.jackson.databind.JsonNode
import java.time.Instant

/**
 * 정책 관리 요청 DTO가 선언하지 않은 필드를 fail-closed로 거부하는 공통 경계다.
 *
 * 애플리케이션 공용 Jackson 설정이 알 수 없는 필드를 허용하더라도 정책 요청은 actor,
 * tenant, clinic, role, assurance 같은 권한 상승 입력을 조용히 버리면 안 된다. 상속된
 * [rejectUnknown]은 모든 미등록 필드를 역직렬화 단계에서 실패시켜
 * `POLICY_PAYLOAD_INVALID`로 정규화되게 한다.
 */
abstract class StrictSchedulingPolicyRequest {
    /**
     * 선언되지 않은 JSON 속성을 안전한 검증 실패로 바꾼다.
     *
     * 값은 로그나 예외 메시지에 포함하지 않는다. 정책 payload나 인증정보가 오류 경로에서
     * 반사되지 않도록 속성명만 제한된 진단에 사용한다.
     */
    @JsonAnySetter
    @Suppress("UNUSED_PARAMETER")
    fun rejectUnknown(name: String, value: JsonNode): Nothing =
        throw IllegalArgumentException("Unknown scheduling-policy request field: $name")
}

/**
 * 정책 명령과 미리보기가 관측한 두 권위 세대를 명시적으로 전달한다.
 *
 * @property tenantGeneration tenant baseline의 non-negative effective generation.
 * @property clinicGeneration clinic override의 non-negative generation. tenant route에서는
 * 반드시 `0`이어야 하며, 아직 override가 없는 clinic에서도 `0`이 유효하다.
 */
@Schema(description = "Pinned tenant and clinic policy generations")
data class PolicyGenerationRequest(
    @field:Schema(minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
    val tenantGeneration: Long,
    @field:Schema(minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
    val clinicGeneration: Long,
) : StrictSchedulingPolicyRequest()

/**
 * 새 immutable 정책 버전의 editable draft를 만드는 요청이다.
 *
 * scope와 행위자 정보는 URL과 검증된 Gateway principal에서만 결정한다.
 *
 * @property kind 폐쇄형 정책 종류.
 * @property schemaVersion payload wire schema. 현재 `1`만 허용한다.
 * @property effectiveFrom 정책 선택 반개구간의 포함 UTC 시작 시각.
 * @property effectiveUntil 선택 반개구간의 제외 UTC 종료 시각. 끝이 없으면 `null`.
 * @property payload [kind]와 URL scope가 선택한 폐쇄형 schema-one JSON.
 * @property expectedScopeRevision draft 생성 전 caller가 관측한 non-negative scope revision.
 * @property changeReason 1..1000자의 비밀 없는 운영 감사 사유.
 */
@Schema(description = "Create a new scheduling-policy draft in the path scope")
data class CreateSchedulingPolicyDraftRequest(
    val kind: SchedulingPolicyKind,
    val schemaVersion: Int,
    val effectiveFrom: Instant,
    val effectiveUntil: Instant?,
    val payload: JsonNode,
    val expectedScopeRevision: Long,
    val changeReason: String,
) : StrictSchedulingPolicyRequest()

/**
 * 현재 draft revision을 다시 strict decode/업무 검증하는 요청이다.
 *
 * @property expectedDraftRevision 검증 대상인 정확한 양수 definition revision.
 */
@Schema(description = "Validate the current scheduling-policy draft revision")
data class ValidateSchedulingPolicyRequest(
    val expectedDraftRevision: Long,
) : StrictSchedulingPolicyRequest()

/**
 * 현재 draft와 세대 벡터에 고정된 bounded 영향도 미리보기를 요청한다.
 *
 * @property expectedDraftRevision preview가 평가할 정확한 양수 draft revision.
 * @property expectedGeneration scan 시작 전에 caller가 관측한 tenant/clinic generation.
 */
@Schema(description = "Submit a bounded impact preview pinned to a draft and generation vector")
data class PreviewSchedulingPolicyRequest(
    val expectedDraftRevision: Long,
    val expectedGeneration: PolicyGenerationRequest,
) : StrictSchedulingPolicyRequest()

/**
 * 검토한 draft revision을 승인하는 요청이다.
 *
 * @property expectedDraftRevision 승인자가 검토한 정확한 양수 revision.
 * @property previewEvidenceToken 같은 revision·generation에 대해 완료된 preview의 opaque token.
 * @property changeReason 승인 판단의 1..1000자 비밀 없는 운영 사유.
 */
@Schema(description = "Approve a draft revision after a completed impact preview")
data class ApproveSchedulingPolicyRequest(
    val expectedDraftRevision: Long,
    val previewEvidenceToken: String,
    val changeReason: String,
) : StrictSchedulingPolicyRequest()

/**
 * 미래 시각에 실행될 durable activation command를 생성하는 요청이다.
 *
 * @property expectedDraftRevision 예약할 정확한 양수 draft revision.
 * @property expectedActiveRevision schedule mutation 전 non-negative scope-head revision.
 * @property expectedGeneration 완료 preview가 관측한 세대 벡터.
 * @property previewEvidenceToken 완료 preview의 opaque activation evidence.
 * @property effectiveFrom draft에 저장된 활성 시작 시각과 정확히 같아야 한다.
 * @property changeReason 1..1000자의 비밀 없는 예약 사유.
 */
@Schema(description = "Schedule a validated policy draft for future activation")
data class ScheduleSchedulingPolicyRequest(
    val expectedDraftRevision: Long,
    val expectedActiveRevision: Long,
    val expectedGeneration: PolicyGenerationRequest,
    val previewEvidenceToken: String,
    val effectiveFrom: Instant,
    val changeReason: String,
) : StrictSchedulingPolicyRequest()

/**
 * 현재 시각에 정책을 활성화하는 요청이다.
 *
 * raw idempotency key는 본문이 아니라 `Idempotency-Key` header로만 받는다.
 *
 * @property expectedDraftRevision 활성화할 정확한 양수 draft revision.
 * @property expectedActiveRevision 활성화 전 non-negative scope-head revision.
 * @property expectedGeneration 완료 preview가 관측한 세대 벡터.
 * @property previewEvidenceToken 완료 preview의 opaque activation evidence.
 * @property changeReason 1..1000자의 비밀 없는 활성화 사유.
 */
@Schema(description = "Activate a policy draft immediately")
data class ActivateSchedulingPolicyRequest(
    val expectedDraftRevision: Long,
    val expectedActiveRevision: Long,
    val expectedGeneration: PolicyGenerationRequest,
    val previewEvidenceToken: String,
    val changeReason: String,
) : StrictSchedulingPolicyRequest()

/**
 * 정책 정의를 이력 삭제 없이 퇴역시키는 요청이다.
 *
 * @property expectedActiveRevision retirement 전 non-negative scope-head revision.
 * @property expectedGeneration retirement 전 caller가 관측한 generation vector.
 * @property changeReason 1..1000자의 비밀 없는 퇴역 사유.
 */
@Schema(description = "Retire a policy definition without deleting its audit history")
data class RetireSchedulingPolicyRequest(
    val expectedActiveRevision: Long,
    val expectedGeneration: PolicyGenerationRequest,
    val changeReason: String,
) : StrictSchedulingPolicyRequest()

/**
 * terminal `MISSED` 활성화 명령을 불변 원본으로 남기고 새 명령으로 재실행하는 요청이다.
 *
 * 새 raw idempotency key는 본문이 아니라 `Idempotency-Key` header로만 받는다.
 *
 * @property expectedGeneration replay 전에 caller가 관측한 현재 세대 벡터.
 * @property changeReason 1..1000자의 비밀 없는 수동 복구 사유.
 */
@Schema(description = "Replay a missed activation as a new durable command")
data class ReplaySchedulingPolicyRequest(
    val expectedGeneration: PolicyGenerationRequest,
    val changeReason: String,
) : StrictSchedulingPolicyRequest()
