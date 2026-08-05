package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.clinic.appointment.api.commitment.AppointmentCommitmentCommandError
import io.bluetape4k.clinic.appointment.api.commitment.AppointmentCommitmentCommandException
import io.bluetape4k.clinic.appointment.api.tenant.TenantCodeRules
import org.springframework.http.HttpStatus

/**
 * commitment 공개 API가 사용하는 닫힌 오류 registry이다.
 *
 * 각 항목은 HTTP status, 재시도 가능성, caller action을 고정한다. 내부 command 메시지,
 * SQL, token, 동의 원문, 자원 식별자는 공개 응답에 반사하지 않는다.
 */
enum class AppointmentCommitmentApiError(
    val httpStatus: HttpStatus,
    val safeMessage: String,
    val retryable: Boolean,
    val action: String,
) {
    SCOPE_MISMATCH(
        HttpStatus.FORBIDDEN,
        "The authenticated scope does not identify one appointment clinic.",
        false,
        "Request a clinic-scoped Gateway token.",
    ),
    SCOPE_FORBIDDEN(
        HttpStatus.FORBIDDEN,
        "The authenticated actor cannot perform this appointment action.",
        false,
        "Use an authorized patient or clinic administrator account.",
    ),
    INGRESS_DISABLED(
        HttpStatus.SERVICE_UNAVAILABLE,
        "New appointment commitment requests are temporarily unavailable.",
        false,
        "Keep existing appointments unchanged and contact the clinic for a new request.",
    ),
    CONSENT_REQUIRED(
        HttpStatus.UNPROCESSABLE_CONTENT,
        "Valid consent evidence is required for this proposal.",
        false,
        "Provide current consent evidence issued by the configured authority.",
    ),
    CONSENT_EVIDENCE_REUSED(
        HttpStatus.CONFLICT,
        "The consent evidence is already bound to another appointment decision.",
        false,
        "Obtain a new consent evidence reference for this proposal.",
    ),
    PROPOSAL_EXPIRED(
        HttpStatus.GONE,
        "The appointment proposal has expired.",
        false,
        "Request a new proposal.",
    ),
    PROPOSAL_NOT_CURRENT(
        HttpStatus.CONFLICT,
        "The appointment proposal is no longer current.",
        false,
        "Reload the commitment and act on its current proposal.",
    ),
    RESOURCE_CONFLICT(
        HttpStatus.CONFLICT,
        "The selected appointment resources are no longer available.",
        false,
        "Request another appointment proposal.",
    ),
    VERSION_CONFLICT(
        HttpStatus.PRECONDITION_FAILED,
        "The appointment commitment changed after it was read.",
        false,
        "Reload the commitment and retry with its latest ETag.",
    ),
    IDEMPOTENCY_KEY_REUSED(
        HttpStatus.CONFLICT,
        "The idempotency key was already used for a different request.",
        false,
        "Retry with the original request or use a new idempotency key.",
    ),
    DIRECT_CONFIRM_NOT_ALLOWED(
        HttpStatus.CONFLICT,
        "Current clinic policy does not allow direct confirmation.",
        false,
        "Create a provisional appointment for customer approval.",
    ),
    PLAN_LIMIT_EXCEEDED(
        HttpStatus.UNPROCESSABLE_CONTENT,
        "The appointment exceeds a scheduling limit for this plan.",
        false,
        "Review the plan size, dependency, and scheduling constraints.",
    ),
    PREDECESSOR_NOT_COMPLETED(
        HttpStatus.CONFLICT,
        "A required preceding treatment has not been completed.",
        false,
        "Complete the preceding treatment before scheduling this item.",
    ),
    NEW_APPOINTMENT_API_REQUIRED(
        HttpStatus.CONFLICT,
        "This appointment must be changed through the commitment API.",
        false,
        "Use the appointment commitment endpoint.",
    ),
    PRECONDITION_REQUIRED(
        HttpStatus.PRECONDITION_REQUIRED,
        "A required HTTP precondition header is missing or invalid.",
        false,
        "Send If-None-Match: * for creation or the current ETag in If-Match.",
    ),
    PAYLOAD_INVALID(
        HttpStatus.BAD_REQUEST,
        "The appointment request payload is invalid.",
        false,
        "Correct the request using the published API schema.",
    ),
    COMMITMENT_NOT_FOUND(
        HttpStatus.NOT_FOUND,
        "The appointment commitment was not found.",
        false,
        "Verify the appointment identifier and authenticated scope.",
    ),
    INTERNAL_ERROR(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "The appointment request could not be completed.",
        true,
        "Retry later with the same idempotency key.",
    ),
}

/**
 * 공개 오류 code만 운반하는 commitment application 예외이다.
 *
 * [detail]은 진단용이며 wire response로 노출하면 안 된다.
 */
class AppointmentCommitmentApiException(
    val error: AppointmentCommitmentApiError,
    detail: String? = null,
    cause: Throwable? = null,
) : RuntimeException(detail ?: error.name, cause)

private val APPOINTMENT_COMMITMENT_CREATE_PATH = Regex("^/api/([^/]+)/appointment-requests$")
private val APPOINTMENT_COMMITMENT_DIRECT_CREATE_PATH = Regex("^/api/([^/]+)/admin/appointments$")
private val APPOINTMENT_COMMITMENT_ITEM_PATH = Regex(
    "^/api/([^/]+)/appointments/[^/]+/(?:commitment|approve|confirm|change-proposals|cancel)$"
)
private val APPOINTMENT_COMMITMENT_PROPOSAL_DECISION_PATH = Regex(
    "^/api/([^/]+)/appointments/[^/]+/proposals/[^/]+/(?:accept|decline|expire)$"
)

/**
 * commitment 오류 envelope가 소유하는 공개 경로만 식별한다.
 *
 * 모든 `/api/{tenantCode}` 하위 경로를 예약 commitment로 분류하면 이후 추가되는 다른
 * tenant API의 인증·검증 오류까지 잘못된 registry로 직렬화된다. 따라서 현재 controller가
 * 실제로 소유하는 생성·조회·mutation 경로만 닫힌 집합으로 유지한다. tenant code는
 * canonical rule을 통과해야 하며, 예약된 `v1`/`v2` root는 legacy 경로로 분류하지 않는다.
 */
internal fun isAppointmentCommitmentRequestPath(path: String): Boolean =
    listOf(
        APPOINTMENT_COMMITMENT_CREATE_PATH,
        APPOINTMENT_COMMITMENT_DIRECT_CREATE_PATH,
        APPOINTMENT_COMMITMENT_ITEM_PATH,
        APPOINTMENT_COMMITMENT_PROPOSAL_DECISION_PATH,
    ).any { matcher ->
        matcher.matchEntire(path)?.groupValues?.getOrNull(1)?.let(TenantCodeRules::isCanonical) == true
    }

/**
 * 내부 command 오류를 외부의 안정 오류 집합으로 축약한다.
 *
 * event-worker 전용 또는 내부 불변식 오류는 parser 세부사항 없이 [AppointmentCommitmentApiError.INTERNAL_ERROR]로
 * redaction한다.
 */
internal fun AppointmentCommitmentCommandException.toApiException(): AppointmentCommitmentApiException =
    AppointmentCommitmentApiException(
        error = when (code) {
            AppointmentCommitmentCommandError.SCOPE_MISMATCH -> AppointmentCommitmentApiError.SCOPE_MISMATCH
            AppointmentCommitmentCommandError.COMMITMENT_NOT_FOUND,
            AppointmentCommitmentCommandError.PROPOSAL_NOT_FOUND,
            -> AppointmentCommitmentApiError.COMMITMENT_NOT_FOUND
            AppointmentCommitmentCommandError.PROPOSAL_NOT_CURRENT,
            AppointmentCommitmentCommandError.PROPOSAL_REVISION_CONFLICT,
            -> AppointmentCommitmentApiError.PROPOSAL_NOT_CURRENT
            AppointmentCommitmentCommandError.PROPOSAL_EXPIRED,
            AppointmentCommitmentCommandError.PROPOSAL_ALREADY_EXPIRED,
            -> AppointmentCommitmentApiError.PROPOSAL_EXPIRED
            AppointmentCommitmentCommandError.CONSENT_REQUIRED,
            AppointmentCommitmentCommandError.CONSENT_EVIDENCE_INVALID,
            -> AppointmentCommitmentApiError.CONSENT_REQUIRED
            AppointmentCommitmentCommandError.CONSENT_EVIDENCE_REUSED ->
                AppointmentCommitmentApiError.CONSENT_EVIDENCE_REUSED
            AppointmentCommitmentCommandError.DIRECT_CONFIRM_NOT_ALLOWED ->
                AppointmentCommitmentApiError.DIRECT_CONFIRM_NOT_ALLOWED
            AppointmentCommitmentCommandError.RESOURCE_CONFLICT ->
                AppointmentCommitmentApiError.RESOURCE_CONFLICT
            AppointmentCommitmentCommandError.VERSION_CONFLICT ->
                AppointmentCommitmentApiError.VERSION_CONFLICT
            AppointmentCommitmentCommandError.IDEMPOTENCY_KEY_REUSED ->
                AppointmentCommitmentApiError.IDEMPOTENCY_KEY_REUSED
            else -> AppointmentCommitmentApiError.INTERNAL_ERROR
        },
        cause = this,
    )
