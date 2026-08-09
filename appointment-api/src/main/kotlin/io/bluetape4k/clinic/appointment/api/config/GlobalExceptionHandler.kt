package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.clinic.appointment.api.commitment.ProposalFailureCode
import io.bluetape4k.clinic.appointment.api.commitment.ProposalGenerationException
import io.bluetape4k.clinic.appointment.api.controller.NotificationOperationUnavailableException
import io.bluetape4k.clinic.appointment.api.dto.ApiResponse
import io.bluetape4k.clinic.appointment.api.dto.SchedulingApiErrorResponse
import io.bluetape4k.clinic.appointment.api.policy.EffectivePolicyGenerationConflictException
import io.bluetape4k.clinic.appointment.api.policy.EffectivePolicyReadUnavailableException
import io.bluetape4k.clinic.appointment.api.notification.NotificationMemberApiError
import io.bluetape4k.clinic.appointment.api.notification.NotificationMemberApiException
import io.bluetape4k.clinic.appointment.event.notification.NotificationContractException
import io.bluetape4k.clinic.appointment.api.security.CorrelationIdFilter
import io.bluetape4k.clinic.appointment.api.service.IdempotencyKeyConflictException
import io.bluetape4k.clinic.appointment.api.reliability.BookingReliabilityApiError
import io.bluetape4k.clinic.appointment.api.reliability.BookingReliabilityApiException
import io.bluetape4k.clinic.appointment.api.dto.WaitlistApiErrorResponse
import io.bluetape4k.clinic.appointment.api.waitlist.WaitlistApiError
import io.bluetape4k.clinic.appointment.api.waitlist.WaitlistApiException
import io.bluetape4k.clinic.appointment.messaging.AppointmentMessagingContractException
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.util.UUID

/**
 * 예약 API 전역 예외를 공개 가능한 응답 계약으로 정규화한다.
 *
 * 상품 카탈로그, 예약 플랜, 스케줄링 정책 경로는 고객에게 노출되는 안정적인 오류
 * envelope를 공유한다. 도메인 검증 오류, 요청 디코딩 오류, 예상하지 못한 예외의
 * 내부 세부사항은 공개 코드와 안전한 메시지로 축약하여 요청 payload, 영속화 식별자,
 * stack trace가 응답에 드러나지 않도록 한다.
 *
 * 인증보다 먼저 수립된 correlation ID는 오류 본문에서도 재사용한다. Spring Security
 * filter chain 내부에서 발생한 인증/인가 실패는 같은 correlation/privacy 계약 아래
 * [io.bluetape4k.clinic.appointment.api.security.SecurityErrorResponseWriter]가 별도로 처리한다.
 */
@RestControllerAdvice
class GlobalExceptionHandler(
    private val schedulingPolicyProperties: SchedulingPolicyProperties = SchedulingPolicyProperties(),
) {

    companion object : KLogging() {
        private const val APPOINTMENT_COMMITMENT_RETRY_AFTER_SECONDS = "5"
        private const val MEMBER_DIRECTORY_RETRY_AFTER_SECONDS = "5"
        private const val NOTIFICATION_ENQUEUE_RETRY_AFTER_SECONDS = "5"
        private const val NOTIFICATION_OPERATION_RETRY_AFTER_SECONDS = "5"
        private const val WAITLIST_RETRY_AFTER_SECONDS = "5"
    }

    /**
     * legacy와 v2 예약 진입점의 회원 식별 오류를 같은 공개 계약으로 변환한다.
     *
     * 회원 ID, 이름, 전화번호, Plan 참조와 디렉터리 원문 오류는 응답과 로그에 남기지 않는다.
     */
    @ExceptionHandler(NotificationMemberApiException::class)
    fun handleNotificationMember(
        ex: NotificationMemberApiException,
        request: HttpServletRequest,
    ): ResponseEntity<SchedulingApiErrorResponse> =
        notificationMemberResponse(ex.error, request)

    /**
     * HMAC key registry나 outbox 계약 장애를 privacy-safe한 일시 오류로 변환한다.
     *
     * command transaction은 이미 rollback되었으므로 caller는 같은 idempotency key로
     * 재시도할 수 있다. 내부 key ID, SQL, 예약·회원 식별자는 응답과 로그에 남기지
     * 않는다.
     */
    @ExceptionHandler(NotificationContractException::class)
    fun handleNotificationContract(
        ex: NotificationContractException,
        request: HttpServletRequest,
    ): ResponseEntity<SchedulingApiErrorResponse> {
        val correlationId = request.correlationId()
        log.warn {
            "Notification enqueue rejected: failure_code=${ex.failureCode.name}, correlation_id=$correlationId"
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header(HttpHeaders.RETRY_AFTER, NOTIFICATION_ENQUEUE_RETRY_AFTER_SECONDS)
            .body(
                SchedulingApiErrorResponse(
                    error = "Notification enqueue is temporarily unavailable.",
                    errorCode = "NOTIFICATION_ENQUEUE_UNAVAILABLE",
                    correlationId = correlationId,
                    retryable = true,
                    action = "Retry with the same idempotency key after the Retry-After interval.",
                )
            )
    }

    /** 예약 messaging outbox 저장소 장애를 내부 정보 없는 재시도 가능 503으로 변환한다. */
    @ExceptionHandler(AppointmentMessagingContractException::class)
    fun handleAppointmentMessagingContract(
        ex: AppointmentMessagingContractException,
        request: HttpServletRequest,
    ): ResponseEntity<SchedulingApiErrorResponse> {
        val correlationId = request.correlationId()
        log.warn {
            "Appointment messaging outbox unavailable: " +
                "failure_code=${ex.failureCode.name}, correlation_id=$correlationId"
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header(HttpHeaders.RETRY_AFTER, NOTIFICATION_ENQUEUE_RETRY_AFTER_SECONDS)
            .body(
                SchedulingApiErrorResponse(
                    error = "Appointment messaging is temporarily unavailable.",
                    errorCode = "APPOINTMENT_MESSAGING_UNAVAILABLE",
                    correlationId = correlationId,
                    retryable = true,
                    action = "After the Retry-After interval, reconcile appointment state and outbox " +
                        "using the correlation ID; retry only when no mutation was committed.",
                )
            )
    }

    /**
     * rollout 또는 adapter 구성이 끝나지 않은 알림 운영 기능을 안정적인 503으로 변환합니다.
     */
    @ExceptionHandler(NotificationOperationUnavailableException::class)
    fun handleNotificationOperationUnavailable(
        request: HttpServletRequest,
    ): ResponseEntity<SchedulingApiErrorResponse> {
        val correlationId = request.correlationId()
        log.warn { "Notification operation unavailable: correlation_id=$correlationId" }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header(HttpHeaders.RETRY_AFTER, NOTIFICATION_OPERATION_RETRY_AFTER_SECONDS)
            .body(
                SchedulingApiErrorResponse(
                    error = "Notification operation is temporarily unavailable.",
                    errorCode = "NOTIFICATION_OPERATION_UNAVAILABLE",
                    correlationId = correlationId,
                    retryable = true,
                    action = "Retry after the Retry-After interval or verify notification operation wiring.",
                )
            )
    }

    /**
     * commitment v2 application 오류를 닫힌 public registry로 직렬화한다.
     *
     * 예외 메시지는 내부 command, 정책, 증빙, 자원 정보를 포함할 수 있으므로 응답과
     * warning log에는 registry code와 correlation ID만 기록한다.
     */
    @ExceptionHandler(AppointmentCommitmentApiException::class)
    fun handleAppointmentCommitment(
        ex: AppointmentCommitmentApiException,
        request: HttpServletRequest,
    ): ResponseEntity<SchedulingApiErrorResponse> =
        appointmentCommitmentResponse(ex.error, request)

    @ExceptionHandler(BookingReliabilityApiException::class)
    fun handleBookingReliability(
        ex: BookingReliabilityApiException,
        request: HttpServletRequest,
    ): ResponseEntity<SchedulingApiErrorResponse> =
        bookingReliabilityResponse(ex.error, request)

    @ExceptionHandler(WaitlistApiException::class)
    fun handleWaitlist(
        ex: WaitlistApiException,
        request: HttpServletRequest,
    ): ResponseEntity<WaitlistApiErrorResponse> =
        waitlistResponse(ex.error, request)

    @ExceptionHandler(PlanFoundationApiException::class)
    fun handlePlanFoundation(
        ex: PlanFoundationApiException,
        request: HttpServletRequest,
    ): ResponseEntity<SchedulingApiErrorResponse> =
        foundationResponse(ex.error, request)

    /**
     * 스케줄링 정책 전용 오류 registry를 공용 wire DTO로 변환한다.
     *
     * 서비스가 제공한 [SchedulingPolicyApiException.detail]은 응답에 반사하지 않는다.
     * registry의 고정 [SchedulingPolicyErrorCode.safeMessage]만 공개하여 미래 호출자가
     * 실수로 원본 예외, 요청 본문, JWT, claim, SQL, idempotency 관련 값을 detail에
     * 넣더라도 wire 응답으로 유출되지 않게 한다.
     */
    @ExceptionHandler(SchedulingPolicyApiException::class)
    fun handleSchedulingPolicy(
        ex: SchedulingPolicyApiException,
        request: HttpServletRequest,
    ): ResponseEntity<SchedulingApiErrorResponse> =
        schedulingPolicyResponse(ex.errorCode, request)

    /**
     * 정책 활성화가 계속 겹쳐 하나의 세대 스냅샷을 만들지 못한 경우를 재시도 가능한
     * 안정 오류로 변환한다. 내부 시도 횟수와 관측 세대는 응답에 노출하지 않는다.
     */
    @ExceptionHandler(EffectivePolicyGenerationConflictException::class)
    fun handleEffectivePolicyGenerationConflict(
        ex: EffectivePolicyGenerationConflictException,
        request: HttpServletRequest,
    ): ResponseEntity<SchedulingApiErrorResponse> =
        schedulingPolicyResponse(
            SchedulingPolicyErrorCode.POLICY_EFFECTIVE_READ_CONFLICT,
            request,
        )

    /**
     * 권위 정책 저장소 장애를 `503` 안정 오류로 변환한다. 원인 예외의 메시지, SQL,
     * 테넌트·병원 식별자는 응답에 포함하지 않는다.
     */
    @ExceptionHandler(EffectivePolicyReadUnavailableException::class)
    fun handleEffectivePolicyReadUnavailable(
        ex: EffectivePolicyReadUnavailableException,
        request: HttpServletRequest,
    ): ResponseEntity<SchedulingApiErrorResponse> =
        schedulingPolicyResponse(
            SchedulingPolicyErrorCode.POLICY_EFFECTIVE_READ_UNAVAILABLE,
            request,
        )

    private fun schedulingPolicyResponse(
        errorCode: SchedulingPolicyErrorCode,
        request: HttpServletRequest,
    ): ResponseEntity<SchedulingApiErrorResponse> {
        val correlationId = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE) as? String
            ?: UUID.randomUUID().toString()
        log.warn {
            "Scheduling policy request rejected: error_code=${errorCode.name}, correlation_id=$correlationId"
        }
        val builder = ResponseEntity.status(errorCode.httpStatus)
        if (errorCode.retryable) {
            builder.header(
                org.springframework.http.HttpHeaders.RETRY_AFTER,
                retryAfterSeconds(),
            )
        }
        return builder.body(
            SchedulingApiErrorResponse(
                error = errorCode.safeMessage,
                errorCode = errorCode.name,
                correlationId = correlationId,
                retryable = errorCode.retryable,
                action = errorCode.action,
            )
        )
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(
        ex: MethodArgumentNotValidException,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        if (request.isAppointmentCommitmentRequest()) {
            return appointmentCommitmentResponse(AppointmentCommitmentApiError.PAYLOAD_INVALID, request)
        }
        if (request.isBookingReliabilityRequest()) {
            return bookingReliabilityResponse(BookingReliabilityApiError.BOOKING_PAYLOAD_INVALID, request)
        }
        if (request.isWaitlistRequest()) {
            return waitlistResponse(WaitlistApiError.PAYLOAD_INVALID, request)
        }
        if (request.isSchedulingPolicyRequest()) {
            log.warn { "Scheduling policy request validation failed" }
            return schedulingPolicyResponse(SchedulingPolicyErrorCode.POLICY_PAYLOAD_INVALID, request)
        }
        if (request.isPlanFoundationRequest()) {
            log.warn { "Plan foundation request validation failed" }
            return foundationResponse(PlanFoundationError.VALIDATION_FAILED, request)
        }
        val message = ex.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
        log.warn(ex) { "Validation failed: $message" }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.error<Nothing>(message.ifBlank { "Validation failed" }))
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(
        ex: MethodArgumentTypeMismatchException,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        if (request.isAppointmentCommitmentRequest()) {
            return appointmentCommitmentResponse(AppointmentCommitmentApiError.PAYLOAD_INVALID, request)
        }
        if (request.isBookingReliabilityRequest()) {
            return bookingReliabilityResponse(BookingReliabilityApiError.BOOKING_PAYLOAD_INVALID, request)
        }
        if (request.isWaitlistRequest()) {
            return waitlistResponse(WaitlistApiError.PAYLOAD_INVALID, request)
        }
        if (request.isSchedulingPolicyRequest()) {
            log.warn { "Scheduling policy path parameter validation failed" }
            return schedulingPolicyResponse(SchedulingPolicyErrorCode.POLICY_PAYLOAD_INVALID, request)
        }
        if (request.isPlanFoundationRequest()) {
            log.warn { "Plan foundation path parameter validation failed" }
            return foundationResponse(PlanFoundationError.VALIDATION_FAILED, request)
        }
        val message = "Invalid value '${ex.value}' for parameter '${ex.name}'"
        log.warn(ex) { "Type mismatch: $message" }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.error<Nothing>(message))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleMessageNotReadable(
        ex: HttpMessageNotReadableException,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        if (request.isAppointmentCommitmentRequest()) {
            return appointmentCommitmentResponse(AppointmentCommitmentApiError.PAYLOAD_INVALID, request)
        }
        if (request.isBookingReliabilityRequest()) {
            return bookingReliabilityResponse(BookingReliabilityApiError.BOOKING_PAYLOAD_INVALID, request)
        }
        if (request.isWaitlistRequest()) {
            return waitlistResponse(WaitlistApiError.PAYLOAD_INVALID, request)
        }
        if (request.isSchedulingPolicyRequest()) {
            log.warn { "Scheduling policy request body could not be decoded" }
            return schedulingPolicyResponse(SchedulingPolicyErrorCode.POLICY_PAYLOAD_INVALID, request)
        }
        if (request.isPlanFoundationRequest()) {
            log.warn { "Plan foundation request body could not be decoded" }
            return foundationResponse(PlanFoundationError.VALIDATION_FAILED, request)
        }
        log.warn(ex) { "Malformed request body: ${ex.mostSpecificCause.message}" }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.error<Nothing>("Invalid request body"))
    }

    /**
     * 제안 계산의 안정 실패 코드를 일반 payload 오류로 축약하지 않고 caller action이
     * 포함된 commitment registry로 변환한다.
     */
    @ExceptionHandler(ProposalGenerationException::class)
    fun handleProposalGeneration(
        ex: ProposalGenerationException,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        if (!request.isAppointmentCommitmentRequest()) {
            return handleIllegalArgument(ex, request)
        }
        val error = when (ex.code) {
            ProposalFailureCode.PLAN_LIMIT_EXCEEDED -> AppointmentCommitmentApiError.PLAN_LIMIT_EXCEEDED
            ProposalFailureCode.NO_FEASIBLE_SLOT -> AppointmentCommitmentApiError.RESOURCE_CONFLICT
        }
        return appointmentCommitmentResponse(error, request)
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(
        ex: IllegalArgumentException,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        if (request.isAppointmentCommitmentRequest()) {
            return appointmentCommitmentResponse(AppointmentCommitmentApiError.PAYLOAD_INVALID, request)
        }
        if (request.isBookingReliabilityRequest()) {
            return bookingReliabilityResponse(BookingReliabilityApiError.BOOKING_PAYLOAD_INVALID, request)
        }
        if (request.isWaitlistRequest()) {
            return waitlistResponse(WaitlistApiError.PAYLOAD_INVALID, request)
        }
        if (request.isSchedulingPolicyRequest()) {
            log.warn { "Scheduling policy request failed domain validation" }
            return schedulingPolicyResponse(SchedulingPolicyErrorCode.POLICY_PAYLOAD_INVALID, request)
        }
        if (request.isPlanFoundationRequest()) {
            log.warn { "Plan foundation request failed domain validation" }
            return foundationResponse(PlanFoundationError.VALIDATION_FAILED, request)
        }
        log.warn(ex) { "Bad request: ${ex.message}" }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.error<Nothing>("Bad request"))
    }

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(
        ex: NoSuchElementException,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        if (request.isAppointmentCommitmentRequest()) {
            return appointmentCommitmentResponse(AppointmentCommitmentApiError.COMMITMENT_NOT_FOUND, request)
        }
        if (request.isBookingReliabilityRequest()) {
            return bookingReliabilityResponse(BookingReliabilityApiError.BOOKING_DECISION_UNAVAILABLE, request)
        }
        if (request.isWaitlistRequest()) {
            return waitlistResponse(WaitlistApiError.WAITLIST_REFERENCE_NOT_FOUND, request)
        }
        if (request.isSchedulingPolicyRequest()) {
            log.warn { "Scheduling policy resource lookup was hidden" }
            return schedulingPolicyResponse(SchedulingPolicyErrorCode.POLICY_RESOURCE_NOT_FOUND, request)
        }
        if (request.isPlanFoundationRequest()) {
            log.warn { "Plan foundation resource lookup was hidden" }
            return foundationResponse(PlanFoundationError.RESOURCE_NOT_FOUND, request)
        }
        log.warn(ex) { "Not found: ${ex.message}" }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ApiResponse.error<Nothing>("Not found"))
    }

    /**
     * 비활성 endpoint가 static-resource fallback으로 해석되어도 일반 `500`으로 승격하지 않는다.
     */
    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResource(
        ex: NoResourceFoundException,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        if (request.isAppointmentCommitmentRequest()) {
            return appointmentCommitmentResponse(AppointmentCommitmentApiError.COMMITMENT_NOT_FOUND, request)
        }
        if (request.isBookingReliabilityRequest()) {
            return bookingReliabilityResponse(BookingReliabilityApiError.BOOKING_DECISION_UNAVAILABLE, request)
        }
        if (request.isWaitlistRequest()) {
            return waitlistResponse(WaitlistApiError.WAITLIST_REFERENCE_NOT_FOUND, request)
        }
        log.warn { "Request path was not mapped: exception_type=${ex::class.simpleName}" }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ApiResponse.error<Nothing>("Not found"))
    }

    @ExceptionHandler(IllegalStateException::class)
    fun handleConflict(
        ex: IllegalStateException,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        if (request.isAppointmentCommitmentRequest()) {
            return appointmentCommitmentInternalError(ex, request)
        }
        if (request.isBookingReliabilityRequest()) {
            return bookingReliabilityResponse(BookingReliabilityApiError.BOOKING_DECISION_UNAVAILABLE, request)
        }
        if (request.isWaitlistRequest()) {
            return waitlistResponse(WaitlistApiError.WAITLIST_CONFLICT, request)
        }
        if (request.isSchedulingPolicyRequest()) {
            return schedulingPolicyInternalError(ex, request)
        }
        if (request.isPlanFoundationRequest()) {
            log.warn { "Plan foundation request failed unexpectedly" }
            return foundationResponse(PlanFoundationError.INTERNAL_ERROR, request)
        }
        log.warn(ex) { "Conflict: ${ex.message}" }
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ApiResponse.error<Nothing>("Conflict"))
    }

    @ExceptionHandler(IdempotencyKeyConflictException::class)
    fun handleIdempotencyConflict(ex: IdempotencyKeyConflictException): ResponseEntity<ApiResponse<Nothing>> {
        log.warn(ex) { "Idempotency request conflict" }
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ApiResponse.error(ex.message ?: "Conflict"))
    }

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(request: HttpServletRequest): ResponseEntity<*> =
        if (request.isAppointmentCommitmentRequest()) {
            appointmentCommitmentResponse(AppointmentCommitmentApiError.SCOPE_FORBIDDEN, request)
        } else if (request.isBookingReliabilityRequest()) {
            bookingReliabilityResponse(BookingReliabilityApiError.BOOKING_RELIABILITY_FORBIDDEN, request)
        } else if (request.isWaitlistRequest()) {
            waitlistResponse(WaitlistApiError.WAITLIST_FORBIDDEN, request)
        } else if (request.isSchedulingPolicyRequest()) {
            schedulingPolicyResponse(SchedulingPolicyErrorCode.POLICY_ACTOR_FORBIDDEN, request)
        } else if (request.isPlanFoundationRequest()) {
            foundationResponse(PlanFoundationError.FORBIDDEN, request)
        } else {
            ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        }

    @ExceptionHandler(Exception::class)
    fun handleGeneral(
        ex: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        if (request.isAppointmentCommitmentRequest()) {
            return appointmentCommitmentInternalError(ex, request)
        }
        if (request.isBookingReliabilityRequest()) {
            return bookingReliabilityResponse(BookingReliabilityApiError.BOOKING_DECISION_UNAVAILABLE, request)
        }
        if (request.isWaitlistRequest()) {
            return waitlistResponse(WaitlistApiError.WAITLIST_UNAVAILABLE, request)
        }
        if (request.isSchedulingPolicyRequest()) {
            return schedulingPolicyInternalError(ex, request)
        }
        if (request.isPlanFoundationRequest()) {
            log.warn { "Plan foundation request failed with an internal error" }
            return foundationResponse(PlanFoundationError.INTERNAL_ERROR, request)
        }
        log.warn(ex) { "Internal server error: ${ex.message}" }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.error<Nothing>("Internal server error"))
    }

    /**
     * 예상하지 못한 정책 내부 예외를 원인 detail이 없는 안정적인 공개 계약으로 변환한다.
     *
     * Spring MVC가 더 구체적인 `IllegalStateException` handler를 선택하는 경우와 일반
     * `Exception` handler를 선택하는 경우가 동일한 응답·로그 규칙을 사용해야 한다.
     */
    private fun schedulingPolicyInternalError(
        ex: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<SchedulingApiErrorResponse> {
        log.warn {
            "Scheduling policy request failed with an internal error: " +
                "exception_type=${ex::class.simpleName}"
        }
        return schedulingPolicyResponse(SchedulingPolicyErrorCode.POLICY_INTERNAL_ERROR, request)
    }

    private fun foundationResponse(
        error: PlanFoundationError,
        request: HttpServletRequest,
    ): ResponseEntity<SchedulingApiErrorResponse> {
        val correlationId = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE) as? String
            ?: UUID.randomUUID().toString()
        return ResponseEntity.status(error.status).body(
            SchedulingApiErrorResponse(
                error = error.safeMessage,
                errorCode = error.code,
                correlationId = correlationId,
            )
        )
    }

    private fun appointmentCommitmentResponse(
        error: AppointmentCommitmentApiError,
        request: HttpServletRequest,
    ): ResponseEntity<SchedulingApiErrorResponse> {
        val correlationId = request.correlationId()
        log.warn {
            "Appointment commitment request rejected: error_code=${error.name}, correlation_id=$correlationId"
        }
        val builder = ResponseEntity.status(error.httpStatus)
        if (error.retryable) {
            builder.header(HttpHeaders.RETRY_AFTER, APPOINTMENT_COMMITMENT_RETRY_AFTER_SECONDS)
        }
        return builder.body(
            SchedulingApiErrorResponse(
                error = error.safeMessage,
                errorCode = error.name,
                correlationId = correlationId,
                retryable = error.retryable,
                action = error.action,
            )
        )
    }

    private fun notificationMemberResponse(
        error: NotificationMemberApiError,
        request: HttpServletRequest,
    ): ResponseEntity<SchedulingApiErrorResponse> {
        val correlationId = request.correlationId()
        log.warn {
            "Appointment member resolution rejected: error_code=${error.name}, correlation_id=$correlationId"
        }
        val builder = ResponseEntity.status(error.httpStatus)
        if (error.retryable) {
            builder.header(HttpHeaders.RETRY_AFTER, MEMBER_DIRECTORY_RETRY_AFTER_SECONDS)
        }
        return builder.body(
            SchedulingApiErrorResponse(
                error = error.safeMessage,
                errorCode = error.name,
                correlationId = correlationId,
                retryable = error.retryable,
                action = error.action,
            )
        )
    }

    /**
     * 예상하지 못한 commitment 구현 예외를 내부 detail 없이 correlation 가능한 오류로 축약한다.
     *
     * 예외 message와 stack trace는 patient, resource, SQL 정보를 포함할 수 있으므로 기록하지
     * 않고 type만 남긴다. caller에는 같은 correlation ID와 제한된 retry backoff를 반환한다.
     */
    private fun appointmentCommitmentInternalError(
        ex: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<SchedulingApiErrorResponse> {
        val correlationId = request.correlationId()
        log.warn {
            "Appointment commitment request failed with an internal error: " +
                "exception_type=${ex::class.simpleName}, correlation_id=$correlationId"
        }
        request.setAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE, correlationId)
        return appointmentCommitmentResponse(AppointmentCommitmentApiError.INTERNAL_ERROR, request)
    }

    private fun HttpServletRequest.isPlanFoundationRequest(): Boolean =
        requestURI.contains("/catalog-products/") || requestURI.contains("/appointment-plans/")

    private fun HttpServletRequest.isSchedulingPolicyRequest(): Boolean =
        isSchedulingPolicyRequestPath(requestURI)

    private fun HttpServletRequest.isAppointmentCommitmentRequest(): Boolean =
        isAppointmentCommitmentRequestPath(requestURI)

    private fun HttpServletRequest.isBookingReliabilityRequest(): Boolean =
        isBookingReliabilityRequestPath(requestURI)

    private fun HttpServletRequest.isWaitlistRequest(): Boolean =
        isWaitlistRequestPath(requestURI)

    private fun waitlistResponse(
        error: WaitlistApiError,
        request: HttpServletRequest,
    ): ResponseEntity<WaitlistApiErrorResponse> {
        val correlationId = request.correlationId()
        log.warn { "Waitlist request rejected: error_code=${error.name}, correlation_id=$correlationId" }
        val builder = ResponseEntity.status(error.httpStatus)
        val retryAfterSeconds = error.retryAfterSeconds ?: WAITLIST_RETRY_AFTER_SECONDS.toLong()
        if (error.retryable) {
            builder.header(HttpHeaders.RETRY_AFTER, retryAfterSeconds.toString())
        }
        return builder.body(
            WaitlistApiErrorResponse(
                error = error.safeMessage,
                reasonCode = error.name,
                correlationId = correlationId,
                retryable = error.retryable,
                action = error.action,
                retryAfterSeconds = if (error.retryable) retryAfterSeconds else null,
            ),
        )
    }

    private fun bookingReliabilityResponse(
        error: BookingReliabilityApiError,
        request: HttpServletRequest,
    ): ResponseEntity<SchedulingApiErrorResponse> {
        val correlationId = request.correlationId()
        log.warn { "Booking reliability request rejected: error_code=${error.name}, correlation_id=$correlationId" }
        val builder = ResponseEntity.status(error.httpStatus)
        if (error.retryable) builder.header(HttpHeaders.RETRY_AFTER, "5")
        return builder.body(
            SchedulingApiErrorResponse(
                error = error.safeMessage,
                errorCode = error.name,
                correlationId = correlationId,
                retryable = error.retryable,
                action = error.action,
            ),
        )
    }

    private fun HttpServletRequest.correlationId(): String =
        getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE) as? String
            ?: UUID.randomUUID().toString()

    /** retryable policy 오류가 요구하는 정수 초 backoff를 구성된 polling 간격에서 올림한다. */
    private fun retryAfterSeconds(): String =
        ((schedulingPolicyProperties.previewPollInterval.toMillis() + 999L) / 1_000L)
            .coerceAtLeast(1L)
            .toString()

}
