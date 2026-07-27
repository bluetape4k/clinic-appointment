package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.clinic.appointment.api.dto.ApiResponse
import io.bluetape4k.clinic.appointment.api.dto.SchedulingApiErrorResponse
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
 * Global exception normalization for scheduling APIs.
 *
 * Catalog-product, appointment-plan, and scheduling-policy paths use the same
 * stable customer-facing error envelope. Domain, decoding, and unexpected
 * exception details are reduced to public codes and messages so request
 * payloads, persistence identifiers, and stack details are not exposed.
 *
 * The correlation ID established before authentication is reused in the error
 * body. Security failures raised inside the Spring Security filter chain are
 * handled separately by [io.bluetape4k.clinic.appointment.api.security.SecurityErrorResponseWriter]
 * under the same correlation and privacy contract.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    companion object : KLogging()

    @ExceptionHandler(PlanFoundationApiException::class)
    fun handlePlanFoundation(
        ex: PlanFoundationApiException,
        request: HttpServletRequest,
    ): ResponseEntity<SchedulingApiErrorResponse> =
        foundationResponse(ex.error, request)

    /**
     * Maps the sole scheduling-policy error registry to the shared wire DTO.
     *
     * [SchedulingPolicyApiException.detail] is already sanitized by the
     * application service. The raw exception, request body, JWT, claims, SQL,
     * and idempotency material are deliberately excluded.
     */
    @ExceptionHandler(SchedulingPolicyApiException::class)
    fun handleSchedulingPolicy(
        ex: SchedulingPolicyApiException,
        request: HttpServletRequest,
    ): ResponseEntity<SchedulingApiErrorResponse> {
        val correlationId = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE) as? String
            ?: UUID.randomUUID().toString()
        return ResponseEntity.status(ex.errorCode.httpStatus).body(
            SchedulingApiErrorResponse(
                error = ex.detail,
                errorCode = ex.errorCode.name,
                correlationId = correlationId,
                retryable = ex.errorCode.retryable,
                action = ex.errorCode.action,
            )
        )
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(
        ex: MethodArgumentNotValidException,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
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
        if (request.isPlanFoundationRequest()) {
            foundationResponse(PlanFoundationError.FORBIDDEN, request)
        } else {
            ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        }

    @ExceptionHandler(Exception::class)
    fun handleGeneral(
        ex: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
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
}
