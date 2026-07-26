package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.clinic.appointment.api.config.PlanFoundationFeatureDisabledException
import io.bluetape4k.clinic.appointment.api.config.PlanFoundationProperties
import io.bluetape4k.clinic.appointment.api.dto.ApiResponse
import io.bluetape4k.clinic.appointment.api.dto.AppointmentPlanResponse
import io.bluetape4k.clinic.appointment.api.tenant.TenantClinicAccessChecker
import io.bluetape4k.clinic.appointment.service.AppointmentPlanQueryService
import io.swagger.v3.oas.annotations.Operation
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
    private val properties: PlanFoundationProperties,
) {
    @Operation(summary = "Read one appointment plan in an exact tenant and clinic scope")
    @ApiResponses(
        OpenApiResponse(responseCode = "200", description = "Plan found"),
        OpenApiResponse(responseCode = "400", description = "Invalid path parameter"),
        OpenApiResponse(responseCode = "404", description = "Plan hidden by scope or feature disabled"),
    )
    @GetMapping("/{planId}")
    fun getById(
        @PathVariable tenantCode: String,
        @PathVariable clinicId: Long,
        @PathVariable planId: Long,
    ): ApiResponse<AppointmentPlanResponse> {
        requireEnabled()
        require(planId > 0) { "planId must be positive" }
        val tenant = tenantClinicAccessChecker.verifyClinic(tenantCode, clinicId)
        val plan = queryService.findById(tenant.id, clinicId, planId)
            ?: throw NoSuchElementException("Appointment plan not found")
        return ApiResponse.ok(AppointmentPlanResponse.from(plan))
    }

    @Operation(summary = "Read one appointment plan by authority-qualified source purchase")
    @ApiResponses(
        OpenApiResponse(responseCode = "200", description = "Plan found"),
        OpenApiResponse(responseCode = "400", description = "Invalid path parameter"),
        OpenApiResponse(responseCode = "404", description = "Plan hidden by scope or feature disabled"),
    )
    @GetMapping("/by-purchase/{sourcePurchaseAuthority}/{sourcePurchaseId}")
    fun getBySourcePurchase(
        @PathVariable tenantCode: String,
        @PathVariable clinicId: Long,
        @PathVariable sourcePurchaseAuthority: String,
        @PathVariable sourcePurchaseId: String,
    ): ApiResponse<AppointmentPlanResponse> {
        requireEnabled()
        requireSafeIdentifier(sourcePurchaseAuthority)
        requireSafeIdentifier(sourcePurchaseId)
        val tenant = tenantClinicAccessChecker.verifyClinic(tenantCode, clinicId)
        val plan = queryService.findBySourcePurchase(
            tenantGroupId = tenant.id,
            clinicId = clinicId,
            sourcePurchaseAuthority = sourcePurchaseAuthority,
            sourcePurchaseId = sourcePurchaseId,
        ) ?: throw NoSuchElementException("Appointment plan not found")
        return ApiResponse.ok(AppointmentPlanResponse.from(plan))
    }

    private fun requireEnabled() {
        if (!properties.planReadEnabled) {
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
