package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.clinic.appointment.api.dto.ApiResponse
import io.bluetape4k.clinic.appointment.api.dto.SchedulingApiErrorResponse
import io.bluetape4k.clinic.appointment.api.policy.EffectivePolicyGenerationConflictException
import io.bluetape4k.clinic.appointment.api.policy.EffectivePolicyReadUnavailableException
import io.bluetape4k.clinic.appointment.api.security.CorrelationIdFilter
import io.bluetape4k.clinic.appointment.api.service.IdempotencyKeyConflictException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
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

    companion object : KLogging()

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

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(
        ex: IllegalArgumentException,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
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

    @ExceptionHandler(IllegalStateException::class)
    fun handleConflict(
        ex: IllegalStateException,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
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
        if (request.isSchedulingPolicyRequest()) {
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
        if (request.isSchedulingPolicyRequest()) {
            log.warn {
                "Scheduling policy request failed with an internal error: " +
                    "exception_type=${ex::class.simpleName}"
            }
            return schedulingPolicyResponse(SchedulingPolicyErrorCode.POLICY_INTERNAL_ERROR, request)
        }
        if (request.isPlanFoundationRequest()) {
            log.warn { "Plan foundation request failed with an internal error" }
            return foundationResponse(PlanFoundationError.INTERNAL_ERROR, request)
        }
        log.warn(ex) { "Internal server error: ${ex.message}" }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.error<Nothing>("Internal server error"))
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

    private fun HttpServletRequest.isPlanFoundationRequest(): Boolean =
        requestURI.contains("/catalog-products/") || requestURI.contains("/appointment-plans/")

    private fun HttpServletRequest.isSchedulingPolicyRequest(): Boolean =
        isSchedulingPolicyRequestPath(requestURI)

    /** retryable policy 오류가 요구하는 정수 초 backoff를 구성된 polling 간격에서 올림한다. */
    private fun retryAfterSeconds(): String =
        ((schedulingPolicyProperties.previewPollInterval.toMillis() + 999L) / 1_000L)
            .coerceAtLeast(1L)
            .toString()
}
