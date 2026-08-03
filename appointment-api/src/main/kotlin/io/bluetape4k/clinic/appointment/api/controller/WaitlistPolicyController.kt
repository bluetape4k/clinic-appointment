package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.clinic.appointment.api.dto.ActivateWaitlistPolicyRequest
import io.bluetape4k.clinic.appointment.api.dto.ApiResponse
import io.bluetape4k.clinic.appointment.api.dto.UpsertWaitlistPolicyRequest
import io.bluetape4k.clinic.appointment.api.dto.WaitlistPolicyResponse
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
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@Validated
@ConditionalOnBean(WaitlistStaffApiService::class)
@RequestMapping("/api/{tenantCode}/clinics/{clinicId}/waitlist/policies")
class WaitlistPolicyController(
    private val service: WaitlistStaffApiService,
    private val scopeFactory: WaitlistTenantScopeFactory,
    private val idCodec: WaitlistPublicIdCodec = WaitlistPublicIdCodec(),
) {
    @GetMapping("/active")
    fun activePolicy(
        @PathVariable tenantCode: String,
        @PathVariable @Positive clinicId: Long,
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
    ): ApiResponse<WaitlistPolicyResponse> {
        val scope = scopeFactory.resolve(tenantCode, clinicId, authentication, servletRequest)
        return ApiResponse.ok(service.activePolicy(scope))
    }

    @GetMapping("/{policyRef}")
    fun getPolicy(
        @PathVariable tenantCode: String,
        @PathVariable @Positive clinicId: Long,
        @PathVariable policyRef: String,
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
    ): ApiResponse<WaitlistPolicyResponse> {
        val scope = scopeFactory.resolve(tenantCode, clinicId, authentication, servletRequest)
        return ApiResponse.ok(service.getPolicy(scope, idCodec.decode(scope, WaitlistPublicIdKind.POLICY, policyRef)))
    }

    @PostMapping
    fun upsertPolicy(
        @PathVariable tenantCode: String,
        @PathVariable @Positive clinicId: Long,
        @Valid @RequestBody request: UpsertWaitlistPolicyRequest,
        @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) idempotencyKey: String?,
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<ApiResponse<WaitlistPolicyResponse>> {
        val scope = scopeFactory.resolve(tenantCode, clinicId, authentication, servletRequest)
        return ResponseEntity.status(HttpStatus.CREATED).body(
            ApiResponse.ok(service.upsertPolicy(scope, WaitlistIdempotencyKeys.requireValid(idempotencyKey), request)),
        )
    }

    @PostMapping("/{policyRef}/activate")
    fun activatePolicy(
        @PathVariable tenantCode: String,
        @PathVariable @Positive clinicId: Long,
        @PathVariable policyRef: String,
        @Valid @RequestBody request: ActivateWaitlistPolicyRequest,
        @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) idempotencyKey: String?,
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
    ): ApiResponse<WaitlistPolicyResponse> {
        val scope = scopeFactory.resolve(tenantCode, clinicId, authentication, servletRequest)
        val policyId = idCodec.decode(scope, WaitlistPublicIdKind.POLICY, policyRef)
        return ApiResponse.ok(
            service.activatePolicy(scope, policyId, WaitlistIdempotencyKeys.requireValid(idempotencyKey), request),
        )
    }
}
