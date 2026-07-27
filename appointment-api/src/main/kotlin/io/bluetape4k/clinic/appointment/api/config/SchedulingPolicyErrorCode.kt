package io.bluetape4k.clinic.appointment.api.config

import org.springframework.http.HttpStatus

/**
 * Stable public error registry for scheduling-policy commands.
 *
 * Enum names are wire-level error codes and therefore must not be renamed
 * without an API compatibility decision. [action] is deliberately written for
 * an operator or API caller and never contains request data, policy payload,
 * actor claims, database details, or an idempotency key.
 *
 * @property httpStatus HTTP status returned by the policy exception handler.
 * @property retryable `true` only when the identical request can be retried
 * without changing caller intent after server-advised backoff.
 * @property action Stable remediation guidance safe for an API response.
 */
enum class SchedulingPolicyErrorCode(
    val httpStatus: HttpStatus,
    val retryable: Boolean,
    val action: String,
) {
    POLICY_PAYLOAD_INVALID(
        HttpStatus.BAD_REQUEST,
        false,
        "Correct the policy payload and submit a new request.",
    ),
    POLICY_OVERRIDE_FORBIDDEN(
        HttpStatus.BAD_REQUEST,
        false,
        "Remove the forbidden override or restore the required safety value.",
    ),
    POLICY_ACTOR_FORBIDDEN(
        HttpStatus.FORBIDDEN,
        false,
        "Verify the actor role, scope, assurance, and tenant or clinic authority.",
    ),
    POLICY_RESOURCE_NOT_FOUND(
        HttpStatus.NOT_FOUND,
        false,
        "Verify that the policy resource belongs to the requested scope.",
    ),
    POLICY_DRAFT_STALE(
        HttpStatus.CONFLICT,
        false,
        "Reload the latest draft revision before retrying.",
    ),
    POLICY_PREVIEW_STALE(
        HttpStatus.CONFLICT,
        false,
        "Run a new impact preview against the current revision and generations.",
    ),
    POLICY_ACTIVATION_CONFLICT(
        HttpStatus.CONFLICT,
        false,
        "Reload the current policy head and resolve the lifecycle or interval conflict.",
    ),
    POLICY_IDEMPOTENCY_CONFLICT(
        HttpStatus.CONFLICT,
        false,
        "Confirm the intended command and use a new idempotency key.",
    ),
    POLICY_ACTIVATION_MISSED(
        HttpStatus.CONFLICT,
        false,
        "Inspect the missed activation, then create a manual replay or retire the draft.",
    ),
    POLICY_APPROVAL_INSUFFICIENT(
        HttpStatus.UNPROCESSABLE_CONTENT,
        false,
        "Collect the required distinct approvals and authentication assurance.",
    ),
    POLICY_PREVIEW_LIMITED(
        HttpStatus.TOO_MANY_REQUESTS,
        true,
        "Retry the same preview request after the server-provided Retry-After interval.",
    ),
}
