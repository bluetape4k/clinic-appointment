package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.clinic.appointment.api.dto.ApiResponse
import io.bluetape4k.clinic.appointment.api.dto.ConfirmWaitlistOfferRequest
import io.bluetape4k.clinic.appointment.api.dto.CreateWaitlistEntryRequest
import io.bluetape4k.clinic.appointment.api.dto.DeclineWaitlistOfferRequest
import io.bluetape4k.clinic.appointment.api.dto.WaitlistEntryResponse
import io.bluetape4k.clinic.appointment.api.dto.WaitlistOfferDecisionResponse
import io.bluetape4k.clinic.appointment.api.dto.WaitlistOfferResponse
import io.bluetape4k.clinic.appointment.api.dto.WaitlistPageResponse
import io.bluetape4k.clinic.appointment.api.dto.WithdrawWaitlistEntryRequest
import io.bluetape4k.clinic.appointment.api.waitlist.WaitlistApiError
import io.bluetape4k.clinic.appointment.api.waitlist.WaitlistApiException
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@Validated
@ConditionalOnBean(WaitlistStaffApiService::class)
@RequestMapping("/api/{tenantCode}/clinics/{clinicId}/waitlist")
class WaitlistController(
    private val service: WaitlistStaffApiService,
    private val scopeFactory: WaitlistTenantScopeFactory,
    private val idCodec: WaitlistPublicIdCodec = WaitlistPublicIdCodec(),
) {
    @PostMapping("/entries")
    fun createEntry(
        @PathVariable tenantCode: String,
        @PathVariable @Positive clinicId: Long,
        @Valid @RequestBody request: CreateWaitlistEntryRequest,
        @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) idempotencyKey: String?,
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<ApiResponse<WaitlistEntryResponse>> {
        val scope = scopeFactory.resolve(tenantCode, clinicId, authentication, servletRequest)
        return ResponseEntity.status(HttpStatus.CREATED).body(
            ApiResponse.ok(service.createEntry(scope, WaitlistIdempotencyKeys.requireValid(idempotencyKey), request)),
        )
    }

    @GetMapping("/entries")
    fun listEntries(
        @PathVariable tenantCode: String,
        @PathVariable @Positive clinicId: Long,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "50") limit: Int,
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
    ): ApiResponse<WaitlistPageResponse<WaitlistEntryResponse>> {
        val scope = scopeFactory.resolve(tenantCode, clinicId, authentication, servletRequest)
        return ApiResponse.ok(service.listEntries(scope, status, cursor, normalizeLimit(limit)))
    }

    @GetMapping("/entries/{entryRef}")
    fun getEntry(
        @PathVariable tenantCode: String,
        @PathVariable @Positive clinicId: Long,
        @PathVariable entryRef: String,
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
    ): ApiResponse<WaitlistEntryResponse> {
        val scope = scopeFactory.resolve(tenantCode, clinicId, authentication, servletRequest)
        return ApiResponse.ok(service.getEntry(scope, idCodec.decode(scope, WaitlistPublicIdKind.ENTRY, entryRef)))
    }

    @PostMapping("/entries/{entryRef}/withdraw")
    fun withdrawEntry(
        @PathVariable tenantCode: String,
        @PathVariable @Positive clinicId: Long,
        @PathVariable entryRef: String,
        @Valid @RequestBody request: WithdrawWaitlistEntryRequest,
        @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) idempotencyKey: String?,
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
    ): ApiResponse<WaitlistEntryResponse> {
        val scope = scopeFactory.resolve(tenantCode, clinicId, authentication, servletRequest)
        val entryId = idCodec.decode(scope, WaitlistPublicIdKind.ENTRY, entryRef)
        return ApiResponse.ok(
            service.withdrawEntry(scope, entryId, WaitlistIdempotencyKeys.requireValid(idempotencyKey), request),
        )
    }

    @GetMapping("/offers")
    fun listOffers(
        @PathVariable tenantCode: String,
        @PathVariable @Positive clinicId: Long,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) memberId: String?,
        @RequestParam(name = "entryId", required = false) entryRef: String?,
        @RequestParam(required = false) expiresBefore: Instant?,
        @RequestParam(required = false) deliveryState: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "50") limit: Int,
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
    ): ApiResponse<WaitlistPageResponse<WaitlistOfferResponse>> {
        val scope = scopeFactory.resolve(tenantCode, clinicId, authentication, servletRequest)
        val entryId = entryRef?.let { idCodec.decode(scope, WaitlistPublicIdKind.ENTRY, it) }
        return ApiResponse.ok(
            service.listOffers(
                scope = scope,
                status = status,
                memberId = memberId,
                entryId = entryId,
                expiresBefore = expiresBefore,
                deliveryState = deliveryState,
                cursor = cursor,
                limit = normalizeLimit(limit),
            ),
        )
    }

    @GetMapping("/offers/{offerRef}")
    fun getOffer(
        @PathVariable tenantCode: String,
        @PathVariable @Positive clinicId: Long,
        @PathVariable offerRef: String,
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
    ): ApiResponse<WaitlistOfferResponse> {
        val scope = scopeFactory.resolve(tenantCode, clinicId, authentication, servletRequest)
        return ApiResponse.ok(service.getOffer(scope, idCodec.decode(scope, WaitlistPublicIdKind.OFFER, offerRef)))
    }

    @PostMapping("/offers/{offerRef}/confirm")
    fun confirmOffer(
        @PathVariable tenantCode: String,
        @PathVariable @Positive clinicId: Long,
        @PathVariable offerRef: String,
        @Valid @RequestBody request: ConfirmWaitlistOfferRequest,
        @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) idempotencyKey: String?,
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
    ): ApiResponse<WaitlistOfferDecisionResponse> {
        val scope = scopeFactory.resolve(tenantCode, clinicId, authentication, servletRequest)
        val offerId = idCodec.decode(scope, WaitlistPublicIdKind.OFFER, offerRef)
        return ApiResponse.ok(
            service.confirmOffer(scope, offerId, WaitlistIdempotencyKeys.requireValid(idempotencyKey), request),
        )
    }

    @PostMapping("/offers/{offerRef}/decline")
    fun declineOffer(
        @PathVariable tenantCode: String,
        @PathVariable @Positive clinicId: Long,
        @PathVariable offerRef: String,
        @Valid @RequestBody request: DeclineWaitlistOfferRequest,
        @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) idempotencyKey: String?,
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
    ): ApiResponse<WaitlistOfferDecisionResponse> {
        val scope = scopeFactory.resolve(tenantCode, clinicId, authentication, servletRequest)
        val offerId = idCodec.decode(scope, WaitlistPublicIdKind.OFFER, offerRef)
        return ApiResponse.ok(
            service.declineOffer(scope, offerId, WaitlistIdempotencyKeys.requireValid(idempotencyKey), request),
        )
    }

    @GetMapping("/offers/{offerRef}/decision")
    fun offerDecision(
        @PathVariable tenantCode: String,
        @PathVariable @Positive clinicId: Long,
        @PathVariable offerRef: String,
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
    ): ApiResponse<WaitlistOfferDecisionResponse> {
        val scope = scopeFactory.resolve(tenantCode, clinicId, authentication, servletRequest)
        return ApiResponse.ok(service.offerDecision(scope, idCodec.decode(scope, WaitlistPublicIdKind.OFFER, offerRef)))
    }
}

internal const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"

internal fun normalizeLimit(limit: Int): Int =
    if (limit in 1..100) {
        limit
    } else {
        throw WaitlistApiException(WaitlistApiError.PAYLOAD_INVALID)
    }
