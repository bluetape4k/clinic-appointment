package io.bluetape4k.clinic.appointment.api.config

import org.springframework.http.HttpStatus

/**
 * 스케줄링 정책 명령에서 사용하는 공개 오류 registry이다.
 *
 * enum 이름은 wire-level 오류 코드이므로 API 호환성 판단 없이 변경하면 안 된다.
 * [action]은 운영자 또는 API 호출자가 다음 조치를 판단할 수 있도록 작성된
 * 안정적인 안내 문구이며, 요청 데이터, 정책 payload, actor claim, 데이터베이스
 * 세부사항, idempotency key를 포함하지 않는다.
 *
 * @property httpStatus 정책 예외 handler가 반환할 HTTP status.
 * @property retryable 서버가 안내한 backoff 이후 동일한 의도를 변경하지 않고
 * 같은 요청을 재시도해도 되는 경우에만 `true`.
 * @property safeMessage 원인 예외나 서비스가 전달한 detail을 반사하지 않는 고정 공개 메시지.
 * @property action API 응답에 포함해도 안전한 안정적인 복구/수정 안내.
 */
enum class SchedulingPolicyErrorCode(
    val httpStatus: HttpStatus,
    val retryable: Boolean,
    val safeMessage: String,
    val action: String,
) {
    POLICY_PAYLOAD_INVALID(
        HttpStatus.BAD_REQUEST,
        false,
        "Scheduling policy payload is invalid.",
        "Correct the policy payload and submit a new request.",
    ),
    POLICY_OVERRIDE_FORBIDDEN(
        HttpStatus.BAD_REQUEST,
        false,
        "Scheduling policy override is forbidden.",
        "Remove the forbidden override or restore the required safety value.",
    ),
    POLICY_ACTOR_FORBIDDEN(
        HttpStatus.FORBIDDEN,
        false,
        "Scheduling policy actor is forbidden.",
        "Verify the actor role, scope, assurance, and tenant or clinic authority.",
    ),
    POLICY_RESOURCE_NOT_FOUND(
        HttpStatus.NOT_FOUND,
        false,
        "Scheduling policy resource was not found.",
        "Verify that the policy resource belongs to the requested scope.",
    ),
    POLICY_DRAFT_STALE(
        HttpStatus.CONFLICT,
        false,
        "Scheduling policy draft is stale.",
        "Reload the latest draft revision before retrying.",
    ),
    POLICY_PREVIEW_STALE(
        HttpStatus.CONFLICT,
        false,
        "Scheduling policy preview is stale.",
        "Run a new impact preview against the current revision and generations.",
    ),
    POLICY_ACTIVATION_CONFLICT(
        HttpStatus.CONFLICT,
        false,
        "Scheduling policy activation conflicts with the current state.",
        "Reload the current policy head and resolve the lifecycle or interval conflict.",
    ),
    POLICY_IDEMPOTENCY_CONFLICT(
        HttpStatus.CONFLICT,
        false,
        "Scheduling policy idempotency key conflicts with another command.",
        "Confirm the intended command and use a new idempotency key.",
    ),
    POLICY_ACTIVATION_MISSED(
        HttpStatus.CONFLICT,
        false,
        "Scheduled policy activation was missed.",
        "Inspect the missed activation, then create a manual replay or retire the draft.",
    ),
    POLICY_APPROVAL_INSUFFICIENT(
        HttpStatus.UNPROCESSABLE_CONTENT,
        false,
        "Scheduling policy approvals are insufficient.",
        "Collect the required distinct approvals and authentication assurance.",
    ),
    POLICY_PREVIEW_LIMITED(
        HttpStatus.TOO_MANY_REQUESTS,
        true,
        "Scheduling policy preview capacity is temporarily limited.",
        "Retry the same preview request after the server-provided Retry-After interval.",
    ),
    POLICY_EFFECTIVE_READ_CONFLICT(
        HttpStatus.CONFLICT,
        true,
        "Effective scheduling policy changed during compilation.",
        "Retry the effective policy read after a short backoff.",
    ),
    POLICY_EFFECTIVE_READ_UNAVAILABLE(
        HttpStatus.SERVICE_UNAVAILABLE,
        true,
        "Authoritative scheduling policy read is unavailable.",
        "Retry after the authoritative policy store has recovered.",
    ),
}
