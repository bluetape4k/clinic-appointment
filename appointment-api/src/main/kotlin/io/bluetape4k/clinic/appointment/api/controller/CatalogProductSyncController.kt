package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.clinic.appointment.api.config.CatalogVersionConflictException
import io.bluetape4k.clinic.appointment.api.config.PlanFoundationFeatureDisabledException
import io.bluetape4k.clinic.appointment.api.config.PlanFoundationProperties
import io.bluetape4k.clinic.appointment.api.config.PlanFoundationValidationException
import io.bluetape4k.clinic.appointment.api.dto.ApiResponse
import io.bluetape4k.clinic.appointment.api.dto.CatalogProductVersionRequest
import io.bluetape4k.clinic.appointment.api.dto.CatalogSyncResponse
import io.bluetape4k.clinic.appointment.api.security.SchedulingUserPrincipal
import io.bluetape4k.clinic.appointment.api.tenant.TenantClinicAccessChecker
import io.bluetape4k.clinic.appointment.model.catalog.CatalogSyncStatus
import io.bluetape4k.clinic.appointment.service.CatalogSyncApplicationService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse as OpenApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Catalog Products", description = "Immutable product catalog synchronization for appointment planning")
@RestController
@RequestMapping("/api/{tenantCode}/clinics/{clinicId}/catalog-products")
class CatalogProductSyncController(
    private val catalogSyncApplicationService: CatalogSyncApplicationService,
    private val tenantClinicAccessChecker: TenantClinicAccessChecker,
    private val properties: PlanFoundationProperties,
) {

    @Operation(
        summary = "Synchronize one immutable catalog product version",
        description = "The payload hash is computed from the documented typed canonical form after bounded DAG validation.",
    )
    @ApiResponses(
        OpenApiResponse(responseCode = "201", description = "Version created"),
        OpenApiResponse(responseCode = "200", description = "Identical version already exists"),
        OpenApiResponse(responseCode = "202", description = "Older version ignored"),
        OpenApiResponse(responseCode = "400", description = "Invalid identity, bounds, DAG, timestamp, or canonical hash"),
        OpenApiResponse(responseCode = "404", description = "Clinic hidden by tenant boundary or feature disabled"),
        OpenApiResponse(responseCode = "409", description = "The version exists with different canonical content"),
    )
    @PutMapping("/{productId}/versions/{catalogVersion}")
    fun synchronize(
        @PathVariable tenantCode: String,
        @PathVariable clinicId: Long,
        @PathVariable productId: String,
        @PathVariable catalogVersion: Long,
        @Valid @RequestBody request: CatalogProductVersionRequest,
        authentication: Authentication?,
    ): ResponseEntity<ApiResponse<CatalogSyncResponse>> {
        if (!properties.catalogSyncEnabled) {
            throw PlanFoundationFeatureDisabledException()
        }

        val tenant = tenantClinicAccessChecker.verifyClinic(tenantCode, clinicId)
        requireIdentityMatches(
            tenantGroupId = tenant.id,
            clinicId = clinicId,
            productId = productId,
            catalogVersion = catalogVersion,
            request = request,
        )
        val principal = authentication?.principal as? SchedulingUserPrincipal
        if (principal != null && request.sourceAuthority !in principal.catalogSourceAuthorities) {
            throw AccessDeniedException("Catalog source authority is not permitted")
        }

        val result = runCatching {
            catalogSyncApplicationService.synchronize(request.toDefinition(), request.payloadHash)
        }.getOrElse { failure ->
            if (failure is IllegalArgumentException) {
                throw PlanFoundationValidationException()
            }
            throw failure
        }
        if (result.status == CatalogSyncStatus.VERSION_CONFLICT) {
            throw CatalogVersionConflictException()
        }

        val status = when (result.status) {
            CatalogSyncStatus.CREATED -> HttpStatus.CREATED
            CatalogSyncStatus.UNCHANGED -> HttpStatus.OK
            CatalogSyncStatus.STALE_IGNORED -> HttpStatus.ACCEPTED
            CatalogSyncStatus.VERSION_CONFLICT -> error("Conflict handled above")
        }
        return ResponseEntity.status(status).body(ApiResponse.ok(CatalogSyncResponse.from(result)))
    }

    private fun requireIdentityMatches(
        tenantGroupId: Long,
        clinicId: Long,
        productId: String,
        catalogVersion: Long,
        request: CatalogProductVersionRequest,
    ) {
        if (
            request.tenantGroupId != tenantGroupId ||
            request.clinicId != clinicId ||
            request.productId != productId ||
            request.catalogVersion != catalogVersion
        ) {
            throw PlanFoundationValidationException()
        }
    }
}
