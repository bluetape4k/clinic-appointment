package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.clinic.appointment.api.reliability.BookingReliabilityApiService
import io.bluetape4k.clinic.appointment.api.reliability.BookingReliabilityAuditPage
import io.bluetape4k.clinic.appointment.api.reliability.BookingReliabilityClearRequest
import io.bluetape4k.clinic.appointment.api.reliability.BookingReliabilityDecisionResponse
import io.bluetape4k.clinic.appointment.api.reliability.BookingReliabilityOverrideRequest
import io.bluetape4k.clinic.appointment.api.security.ActorContextResolver
import io.bluetape4k.clinic.appointment.api.security.CorrelationIdFilter
import io.bluetape4k.clinic.appointment.api.tenant.TenantClinicAccessChecker
import io.bluetape4k.clinic.appointment.api.dto.ApiResponse
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse as OpenApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Positive
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.validation.annotation.Validated
import java.time.Clock

/** 직원 전용 예약 신뢰성 preview/override/clear/audit 경계입니다. */
@RestController
@Validated
@ConditionalOnBean(
    value = [
        BookingReliabilityApiService::class,
        TenantClinicAccessChecker::class,
        ActorContextResolver::class,
    ],
)
@RequestMapping("/api/{tenantCode}/clinics/{clinicId}/members/{memberId}/booking-reliability")
@Tag(name = "Booking Reliability", description = "Privacy-safe booking eligibility decisions and audit")
class BookingReliabilityController(
    private val service: BookingReliabilityApiService,
    private val tenantClinicAccessChecker: TenantClinicAccessChecker,
    private val actorContextResolver: ActorContextResolver,
    private val clock: Clock = Clock.systemUTC(),
) {

    @GetMapping("/decision")
    @Operation(summary = "Read the current booking reliability decision")
    @ApiResponses(
        OpenApiResponse(responseCode = "200", content = [Content(schema = Schema(implementation = BookingReliabilityDecisionResponse::class))]),
        OpenApiResponse(responseCode = "403"),
        OpenApiResponse(responseCode = "503"),
    )
    fun decision(
        @PathVariable tenantCode: String,
        @PathVariable @Positive clinicId: Long,
        @PathVariable @Parameter(description = "Opaque member service identifier") memberId: String,
        @RequestParam(name = "policySnapshotId", required = false) policySnapshotId: Long?,
    ): ApiResponse<BookingReliabilityDecisionResponse> {
        val tenant = tenantClinicAccessChecker.verifyClinic(tenantCode, clinicId)
        return ApiResponse.ok(
            service.decision(
                tenantGroupId = tenant.id,
                clinicId = clinicId,
                memberId = MemberId(memberId),
                at = clock.instant(),
                requestedPolicySnapshotId = policySnapshotId,
            ),
        )
    }

    @PostMapping("/override")
    @Operation(summary = "Apply a bounded staff override")
    fun override(
        @PathVariable tenantCode: String,
        @PathVariable @Positive clinicId: Long,
        @PathVariable memberId: String,
        @Valid @RequestBody request: BookingReliabilityOverrideRequest,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<ApiResponse<BookingReliabilityDecisionResponse>> {
        val tenant = tenantClinicAccessChecker.verifyClinic(tenantCode, clinicId)
        val actor = actorContextResolver.resolve(authentication, tenantCode, clinicId, correlationId(servletRequest))
        return ResponseEntity.ok(
            ApiResponse.ok(
                service.override(
                    tenant.id,
                    clinicId,
                    MemberId(memberId),
                    actor,
                    request,
                    idempotencyKey,
                    clock.instant(),
                ),
            ),
        )
    }

    @PostMapping("/clear")
    @Operation(summary = "Clear a bounded staff override or restriction")
    fun clear(
        @PathVariable tenantCode: String,
        @PathVariable @Positive clinicId: Long,
        @PathVariable memberId: String,
        @Valid @RequestBody request: BookingReliabilityClearRequest,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<ApiResponse<BookingReliabilityDecisionResponse>> {
        val tenant = tenantClinicAccessChecker.verifyClinic(tenantCode, clinicId)
        val actor = actorContextResolver.resolve(authentication, tenantCode, clinicId, correlationId(servletRequest))
        return ResponseEntity.ok(
            ApiResponse.ok(
                service.clear(
                    tenant.id,
                    clinicId,
                    MemberId(memberId),
                    actor,
                    request,
                    idempotencyKey,
                ),
            ),
        )
    }

    @GetMapping("/audit")
    @Operation(summary = "Read bounded booking reliability audit history")
    fun audit(
        @PathVariable tenantCode: String,
        @PathVariable @Positive clinicId: Long,
        @PathVariable memberId: String,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "50") limit: Int,
    ): ApiResponse<BookingReliabilityAuditPage> {
        val tenant = tenantClinicAccessChecker.verifyClinic(tenantCode, clinicId)
        return ApiResponse.ok(service.audit(tenant.id, clinicId, MemberId(memberId), cursor, limit))
    }

    private fun correlationId(request: HttpServletRequest): String =
        request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE) as? String ?: "api"
}
