package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.clinic.appointment.api.dto.ApiResponse
import io.bluetape4k.clinic.appointment.api.dto.SchedulingApiErrorResponse
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

@RestControllerAdvice
class GlobalExceptionHandler {

    companion object : KLogging()

    @ExceptionHandler(PlanFoundationApiException::class)
    fun handlePlanFoundation(ex: PlanFoundationApiException): ResponseEntity<SchedulingApiErrorResponse> =
        foundationResponse(ex.error)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(
        ex: MethodArgumentNotValidException,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        if (request.isPlanFoundationRequest()) {
            log.warn { "Plan foundation request validation failed" }
            return foundationResponse(PlanFoundationError.VALIDATION_FAILED)
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
            return foundationResponse(PlanFoundationError.VALIDATION_FAILED)
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
            return foundationResponse(PlanFoundationError.VALIDATION_FAILED)
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
            return foundationResponse(PlanFoundationError.VALIDATION_FAILED)
        }
        log.warn(ex) { "Bad request: ${ex.message}" }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.error<Nothing>(ex.message ?: "Bad request"))
    }

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(
        ex: NoSuchElementException,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        if (request.isPlanFoundationRequest()) {
            log.warn { "Plan foundation resource lookup was hidden" }
            return foundationResponse(PlanFoundationError.RESOURCE_NOT_FOUND)
        }
        log.warn(ex) { "Not found: ${ex.message}" }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ApiResponse.error<Nothing>(ex.message ?: "Not found"))
    }

    @ExceptionHandler(IllegalStateException::class)
    fun handleConflict(
        ex: IllegalStateException,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        if (request.isPlanFoundationRequest()) {
            log.warn { "Plan foundation request failed unexpectedly" }
            return foundationResponse(PlanFoundationError.INTERNAL_ERROR)
        }
        log.warn(ex) { "Conflict: ${ex.message}" }
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ApiResponse.error<Nothing>(ex.message ?: "Conflict"))
    }

    @ExceptionHandler(IdempotencyKeyConflictException::class)
    fun handleIdempotencyConflict(ex: IdempotencyKeyConflictException): ResponseEntity<ApiResponse<Nothing>> {
        log.warn(ex) { "Idempotency request conflict" }
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ApiResponse.error(ex.message ?: "Conflict"))
    }

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(): ResponseEntity<Void> =
        ResponseEntity.status(HttpStatus.FORBIDDEN).build()

    @ExceptionHandler(Exception::class)
    fun handleGeneral(
        ex: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        if (request.isPlanFoundationRequest()) {
            log.warn { "Plan foundation request failed with an internal error" }
            return foundationResponse(PlanFoundationError.INTERNAL_ERROR)
        }
        log.warn(ex) { "Internal server error: ${ex.message}" }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.error<Nothing>(ex.message ?: "Internal server error"))
    }

    private fun foundationResponse(error: PlanFoundationError): ResponseEntity<SchedulingApiErrorResponse> {
        val correlationId = UUID.randomUUID().toString()
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
