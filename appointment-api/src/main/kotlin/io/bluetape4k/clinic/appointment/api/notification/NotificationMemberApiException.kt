package io.bluetape4k.clinic.appointment.api.notification

import org.springframework.http.HttpStatus

/**
 * 회원 식별 과정에서 외부에 공개할 수 있는 오류 목록입니다.
 *
 * 이름, 전화번호, 회원 ID, Plan의 보호된 회원 참조와 회원 서비스의 원문 오류는
 * 응답이나 예외 메시지에 포함하지 않습니다.
 */
enum class NotificationMemberApiError(
    val httpStatus: HttpStatus,
    val safeMessage: String,
    val retryable: Boolean,
    val action: String,
) {
    MEMBER_ID_REQUIRED(
        HttpStatus.UNPROCESSABLE_CONTENT,
        "A verified member identifier is required for this appointment.",
        false,
        "Select a member and retry the appointment request.",
    ),
    MEMBER_NOT_FOUND(
        HttpStatus.NOT_FOUND,
        "The selected member was not found.",
        false,
        "Use the latest member search result and retry.",
    ),
    MEMBER_SCOPE_MISMATCH(
        HttpStatus.FORBIDDEN,
        "The selected member is not available in this appointment scope.",
        false,
        "Verify the clinic scope and selected member.",
    ),
    MEMBER_REFERENCE_AMBIGUOUS(
        HttpStatus.CONFLICT,
        "The appointment member reference is ambiguous.",
        false,
        "Correct the Plan or member mapping before retrying.",
    ),
    MEMBER_DIRECTORY_UNAVAILABLE(
        HttpStatus.SERVICE_UNAVAILABLE,
        "The member directory is temporarily unavailable.",
        true,
        "Retry with the same idempotency key after the Retry-After interval.",
    ),
}

/**
 * 공개 가능한 오류 코드만 운반하는 회원 식별 예외입니다.
 *
 * 디렉터리 요청값이나 원문 예외는 이 예외에 연결하지 않습니다.
 */
class NotificationMemberApiException(
    val error: NotificationMemberApiError,
) : RuntimeException(error.name)
