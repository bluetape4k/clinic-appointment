package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.clinic.appointment.api.config.PlanFoundationFeatureDisabledException
import io.bluetape4k.clinic.appointment.api.config.PlanFoundationFeatureControlResolver
import io.bluetape4k.clinic.appointment.api.dto.ApiResponse
import io.bluetape4k.clinic.appointment.api.dto.AppointmentPlanApiResponse
import io.bluetape4k.clinic.appointment.api.dto.AppointmentPlanResponse
import io.bluetape4k.clinic.appointment.api.dto.SchedulingApiErrorResponse
import io.bluetape4k.clinic.appointment.api.tenant.TenantClinicAccessChecker
import io.bluetape4k.clinic.appointment.service.AppointmentPlanQueryService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse as OpenApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Appointment Plans", description = "Read-only purchased treatment obligations before visit scheduling")
@RestController
@RequestMapping("/api/{tenantCode}/clinics/{clinicId}/appointment-plans")
class AppointmentPlanController(
    private val queryService: AppointmentPlanQueryService,
    private val tenantClinicAccessChecker: TenantClinicAccessChecker,
    private val featureControlResolver: PlanFoundationFeatureControlResolver,
) {
    @Operation(summary = "Read one appointment plan in an exact tenant and clinic scope")
    @ApiResponses(
        OpenApiResponse(responseCode = "200", description = "Plan found", content = [Content(mediaType = "application/json", schema = Schema(implementation = AppointmentPlanApiResponse::class))]),
        OpenApiResponse(responseCode = "400", description = "Invalid path parameter", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        OpenApiResponse(responseCode = "401", description = "Missing or invalid bearer token", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        OpenApiResponse(responseCode = "403", description = "Authenticated caller lacks tenant or clinic read authority", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        OpenApiResponse(responseCode = "404", description = "Plan hidden by scope or feature disabled", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        OpenApiResponse(responseCode = "500", description = "Internal scheduling error", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
    )
    @GetMapping("/{planId}")
    fun getById(
        @PathVariable tenantCode: String,
        @PathVariable clinicId: Long,
        @PathVariable planId: Long,
    ): ApiResponse<AppointmentPlanResponse> {
        require(planId > 0) { "planId must be positive" }
        val tenant = tenantClinicAccessChecker.verifyClinic(tenantCode, clinicId)
        requireEnabled(tenant.id, clinicId)
        val plan = queryService.findById(tenant.id, clinicId, planId)
            ?: throw NoSuchElementException("Appointment plan not found")
        return ApiResponse.ok(AppointmentPlanResponse.from(plan))
    }

    @Operation(summary = "Read one appointment plan by authority-qualified source purchase")
    @ApiResponses(
        OpenApiResponse(responseCode = "200", description = "Plan found", content = [Content(mediaType = "application/json", schema = Schema(implementation = AppointmentPlanApiResponse::class))]),
        OpenApiResponse(responseCode = "400", description = "Invalid path parameter", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        OpenApiResponse(responseCode = "401", description = "Missing or invalid bearer token", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        OpenApiResponse(responseCode = "403", description = "Authenticated caller lacks tenant or clinic read authority", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        OpenApiResponse(responseCode = "404", description = "Plan hidden by scope or feature disabled", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        OpenApiResponse(responseCode = "500", description = "Internal scheduling error", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
    )
    @GetMapping("/by-purchase/{sourcePurchaseAuthority}/{sourcePurchaseId}")
    fun getBySourcePurchase(
        @PathVariable tenantCode: String,
        @PathVariable clinicId: Long,
        @PathVariable sourcePurchaseAuthority: String,
        @PathVariable sourcePurchaseId: String,
    ): ApiResponse<AppointmentPlanResponse> {
        requireSafeIdentifier(sourcePurchaseAuthority)
        requireSafeIdentifier(sourcePurchaseId)
        val tenant = tenantClinicAccessChecker.verifyClinic(tenantCode, clinicId)
        requireEnabled(tenant.id, clinicId)
        val plan = queryService.findBySourcePurchase(
            tenantGroupId = tenant.id,
            clinicId = clinicId,
            sourcePurchaseAuthority = sourcePurchaseAuthority,
            sourcePurchaseId = sourcePurchaseId,
        ) ?: throw NoSuchElementException("Appointment plan not found")
        return ApiResponse.ok(AppointmentPlanResponse.from(plan))
    }

    private fun requireEnabled(tenantGroupId: Long, clinicId: Long) {
        if (!featureControlResolver.resolve(tenantGroupId, clinicId).planReadEnabled) {
            throw PlanFoundationFeatureDisabledException()
        }
    }

    private fun requireSafeIdentifier(value: String) {
        require(value.length in 1..128 && SAFE_IDENTIFIER.matches(value)) {
            "source purchase identifier is invalid"
        }
    }

    companion object {
        private val SAFE_IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9._:-]*")
    }
}
