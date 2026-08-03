package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.clinic.appointment.api.dto.ApiResponse
import io.bluetape4k.clinic.appointment.api.dto.CreateWaitlistBenefitGrantRequest
import io.bluetape4k.clinic.appointment.api.dto.CreateWaitlistRestrictionRequest
import io.bluetape4k.clinic.appointment.api.dto.GrantWaitlistRecoveryCreditRequest
import io.bluetape4k.clinic.appointment.api.dto.ReleaseWaitlistRestrictionRequest
import io.bluetape4k.clinic.appointment.api.dto.RevokeWaitlistBenefitGrantRequest
import io.bluetape4k.clinic.appointment.api.dto.RevokeWaitlistRecoveryCreditRequest
import io.bluetape4k.clinic.appointment.api.dto.WaitlistAdjustmentResponse
import io.bluetape4k.clinic.appointment.api.waitlist.WaitlistIdempotencyKeys
import io.bluetape4k.clinic.appointment.api.waitlist.WaitlistPublicIdCodec
import io.bluetape4k.clinic.appointment.api.waitlist.WaitlistPublicIdKind
import io.bluetape4k.clinic.appointment.api.waitlist.WaitlistStaffApiService
import io.bluetape4k.clinic.appointment.api.waitlist.WaitlistTenantScopeFactory
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Positive
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@Validated
@ConditionalOnBean(WaitlistStaffApiService::class)
@RequestMapping("/api/{tenantCode}/clinics/{clinicId}/waitlist")
class WaitlistOperationsController(
    private val service: WaitlistStaffApiService,
    private val scopeFactory: WaitlistTenantScopeFactory,
    private val idCodec: WaitlistPublicIdCodec = WaitlistPublicIdCodec(),
) {
    @PostMapping("/restrictions")
    fun createRestriction(
        @PathVariable tenantCode: String,
        @PathVariable @Positive clinicId: Long,
        @Valid @RequestBody request: CreateWaitlistRestrictionRequest,
        @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) idempotencyKey: String?,
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<ApiResponse<WaitlistAdjustmentResponse>> {
        val scope = scopeFactory.resolve(tenantCode, clinicId, authentication, servletRequest)
        return ResponseEntity.status(HttpStatus.CREATED).body(
            ApiResponse.ok(service.createRestriction(scope, WaitlistIdempotencyKeys.requireValid(idempotencyKey), request)),
        )
    }

    @PostMapping("/restrictions/{restrictionRef}/release")
    fun releaseRestriction(
        @PathVariable tenantCode: String,
        @PathVariable @Positive clinicId: Long,
        @PathVariable restrictionRef: String,
        @Valid @RequestBody request: ReleaseWaitlistRestrictionRequest,
        @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) idempotencyKey: String?,
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
    ): ApiResponse<WaitlistAdjustmentResponse> {
        val scope = scopeFactory.resolve(tenantCode, clinicId, authentication, servletRequest)
        val restrictionId = idCodec.decode(scope, WaitlistPublicIdKind.RESTRICTION, restrictionRef)
        return ApiResponse.ok(
            service.releaseRestriction(
                scope,
                restrictionId,
                WaitlistIdempotencyKeys.requireValid(idempotencyKey),
                request,
            ),
        )
    }

    @PostMapping("/recovery-credits")
    fun grantRecoveryCredit(
        @PathVariable tenantCode: String,
        @PathVariable @Positive clinicId: Long,
        @Valid @RequestBody request: GrantWaitlistRecoveryCreditRequest,
        @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) idempotencyKey: String?,
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<ApiResponse<WaitlistAdjustmentResponse>> {
        val scope = scopeFactory.resolve(tenantCode, clinicId, authentication, servletRequest)
        return ResponseEntity.status(HttpStatus.CREATED).body(
            ApiResponse.ok(service.grantRecoveryCredit(scope, WaitlistIdempotencyKeys.requireValid(idempotencyKey), request)),
        )
    }

    @PostMapping("/recovery-credits/{recoveryCreditRef}/revoke")
    fun revokeRecoveryCredit(
        @PathVariable tenantCode: String,
        @PathVariable @Positive clinicId: Long,
        @PathVariable recoveryCreditRef: String,
        @Valid @RequestBody request: RevokeWaitlistRecoveryCreditRequest,
        @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) idempotencyKey: String?,
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
    ): ApiResponse<WaitlistAdjustmentResponse> {
        val scope = scopeFactory.resolve(tenantCode, clinicId, authentication, servletRequest)
        val recoveryCreditId = idCodec.decode(scope, WaitlistPublicIdKind.RECOVERY_CREDIT, recoveryCreditRef)
        return ApiResponse.ok(
            service.revokeRecoveryCredit(
                scope,
                recoveryCreditId,
                WaitlistIdempotencyKeys.requireValid(idempotencyKey),
                request,
            ),
        )
    }

    @PostMapping("/benefit-grants")
    fun createBenefitGrant(
        @PathVariable tenantCode: String,
        @PathVariable @Positive clinicId: Long,
        @Valid @RequestBody request: CreateWaitlistBenefitGrantRequest,
        @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) idempotencyKey: String?,
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<ApiResponse<WaitlistAdjustmentResponse>> {
        val scope = scopeFactory.resolve(tenantCode, clinicId, authentication, servletRequest)
        return ResponseEntity.status(HttpStatus.CREATED).body(
            ApiResponse.ok(service.createBenefitGrant(scope, WaitlistIdempotencyKeys.requireValid(idempotencyKey), request)),
        )
    }

    @PostMapping("/benefit-grants/{benefitGrantRef}/revoke")
    fun revokeBenefitGrant(
        @PathVariable tenantCode: String,
        @PathVariable @Positive clinicId: Long,
        @PathVariable benefitGrantRef: String,
        @Valid @RequestBody request: RevokeWaitlistBenefitGrantRequest,
        @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) idempotencyKey: String?,
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
    ): ApiResponse<WaitlistAdjustmentResponse> {
        val scope = scopeFactory.resolve(tenantCode, clinicId, authentication, servletRequest)
        val benefitGrantId = idCodec.decode(scope, WaitlistPublicIdKind.BENEFIT_GRANT, benefitGrantRef)
        return ApiResponse.ok(
            service.revokeBenefitGrant(
                scope,
                benefitGrantId,
                WaitlistIdempotencyKeys.requireValid(idempotencyKey),
                request,
            ),
        )
    }
}
