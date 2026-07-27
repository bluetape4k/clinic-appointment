package io.bluetape4k.clinic.appointment.api.config

import org.springframework.http.HttpStatus

enum class PlanFoundationError(
    val status: HttpStatus,
    val code: String,
    val safeMessage: String,
) {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Appointment plan foundation request validation failed"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "FORBIDDEN", "The authenticated caller is not permitted"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Requested scheduling resource was not found"),
    FEATURE_DISABLED(HttpStatus.NOT_FOUND, "FEATURE_DISABLED", "Appointment plan foundation feature is disabled"),
    CATALOG_VERSION_CONFLICT(
        HttpStatus.CONFLICT,
        "CATALOG_VERSION_CONFLICT",
        "Catalog version conflicts with an existing definition",
    ),
    PAYLOAD_TOO_LARGE(
        HttpStatus.CONTENT_TOO_LARGE,
        "PAYLOAD_TOO_LARGE",
        "Catalog sync payload exceeds the allowed size",
    ),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An internal scheduling error occurred"),
}

open class PlanFoundationApiException(
    val error: PlanFoundationError,
) : RuntimeException(error.code)

class PlanFoundationValidationException : PlanFoundationApiException(PlanFoundationError.VALIDATION_FAILED)

class PlanFoundationFeatureDisabledException : PlanFoundationApiException(PlanFoundationError.FEATURE_DISABLED)

class CatalogVersionConflictException : PlanFoundationApiException(PlanFoundationError.CATALOG_VERSION_CONFLICT)
