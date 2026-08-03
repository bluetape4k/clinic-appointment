package io.bluetape4k.clinic.appointment.api.waitlist

import org.springframework.http.HttpStatus

enum class WaitlistApiError(
    val httpStatus: HttpStatus,
    val safeMessage: String,
    val retryable: Boolean,
    val action: String,
    val retryAfterSeconds: Long? = null,
) {
    INVALID_IDEMPOTENCY_KEY(
        HttpStatus.BAD_REQUEST,
        "A valid Idempotency-Key header is required for this waitlist command.",
        false,
        "Send a printable ASCII Idempotency-Key between 16 and 128 characters.",
    ),
    WAITLIST_REFERENCE_NOT_FOUND(
        HttpStatus.NOT_FOUND,
        "The waitlist reference was not found.",
        false,
        "Reload the waitlist resource and retry with the current reference.",
    ),
    PAYLOAD_INVALID(
        HttpStatus.BAD_REQUEST,
        "The waitlist request payload is invalid.",
        false,
        "Correct the request using the published API schema.",
    ),
    CURSOR_INVALID(
        HttpStatus.BAD_REQUEST,
        "The waitlist cursor is invalid.",
        false,
        "Restart listing without the cursor or use the latest returned cursor.",
    ),
    WAITLIST_CONFLICT(
        HttpStatus.CONFLICT,
        "The waitlist resource changed before the command could be applied.",
        false,
        "Reload the current resource version before retrying.",
    ),
    WAITLIST_FORBIDDEN(
        HttpStatus.FORBIDDEN,
        "The authenticated actor cannot access waitlist data.",
        false,
        "Use an authorized clinic-scoped waitlist capability.",
    ),
    WAITLIST_UNAVAILABLE(
        HttpStatus.SERVICE_UNAVAILABLE,
        "The waitlist service is temporarily unavailable.",
        true,
        "Retry with the same idempotency key after the Retry-After interval.",
        retryAfterSeconds = 5,
    ),
}

class WaitlistApiException(
    val error: WaitlistApiError,
    cause: Throwable? = null,
) : RuntimeException(error.name, cause)
