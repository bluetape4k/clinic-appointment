package io.bluetape4k.clinic.appointment.api.waitlist

import io.bluetape4k.clinic.appointment.api.dto.ActivateWaitlistPolicyRequest
import io.bluetape4k.clinic.appointment.api.dto.ConfirmWaitlistOfferRequest
import io.bluetape4k.clinic.appointment.api.dto.CreateWaitlistBenefitGrantRequest
import io.bluetape4k.clinic.appointment.api.dto.CreateWaitlistEntryRequest
import io.bluetape4k.clinic.appointment.api.dto.CreateWaitlistRestrictionRequest
import io.bluetape4k.clinic.appointment.api.dto.DeclineWaitlistOfferRequest
import io.bluetape4k.clinic.appointment.api.dto.GrantWaitlistRecoveryCreditRequest
import io.bluetape4k.clinic.appointment.api.dto.ReleaseWaitlistRestrictionRequest
import io.bluetape4k.clinic.appointment.api.dto.RevokeWaitlistBenefitGrantRequest
import io.bluetape4k.clinic.appointment.api.dto.RevokeWaitlistRecoveryCreditRequest
import io.bluetape4k.clinic.appointment.api.dto.UpsertWaitlistPolicyRequest
import io.bluetape4k.clinic.appointment.api.dto.WaitlistAdjustmentResponse
import io.bluetape4k.clinic.appointment.api.dto.WaitlistEntryResponse
import io.bluetape4k.clinic.appointment.api.dto.WaitlistOfferDecisionResponse
import io.bluetape4k.clinic.appointment.api.dto.WaitlistOfferResponse
import io.bluetape4k.clinic.appointment.api.dto.WaitlistPageResponse
import io.bluetape4k.clinic.appointment.api.dto.WaitlistPolicyResponse
import io.bluetape4k.clinic.appointment.api.dto.WithdrawWaitlistEntryRequest
import java.time.Instant

interface WaitlistStaffApiService {
    fun createEntry(
        scope: WaitlistTenantScope,
        idempotencyKey: String,
        request: CreateWaitlistEntryRequest,
    ): WaitlistEntryResponse

    fun listEntries(
        scope: WaitlistTenantScope,
        status: String?,
        cursor: String?,
        limit: Int,
    ): WaitlistPageResponse<WaitlistEntryResponse>

    fun getEntry(scope: WaitlistTenantScope, entryId: Long): WaitlistEntryResponse

    fun withdrawEntry(
        scope: WaitlistTenantScope,
        entryId: Long,
        idempotencyKey: String,
        request: WithdrawWaitlistEntryRequest,
    ): WaitlistEntryResponse

    fun listOffers(
        scope: WaitlistTenantScope,
        status: String?,
        memberId: String?,
        entryId: Long?,
        expiresBefore: Instant?,
        deliveryState: String?,
        cursor: String?,
        limit: Int,
    ): WaitlistPageResponse<WaitlistOfferResponse>

    fun getOffer(scope: WaitlistTenantScope, offerId: Long): WaitlistOfferResponse

    fun confirmOffer(
        scope: WaitlistTenantScope,
        offerId: Long,
        idempotencyKey: String,
        request: ConfirmWaitlistOfferRequest,
    ): WaitlistOfferDecisionResponse

    fun declineOffer(
        scope: WaitlistTenantScope,
        offerId: Long,
        idempotencyKey: String,
        request: DeclineWaitlistOfferRequest,
    ): WaitlistOfferDecisionResponse

    fun offerDecision(scope: WaitlistTenantScope, offerId: Long): WaitlistOfferDecisionResponse

    fun activePolicy(scope: WaitlistTenantScope): WaitlistPolicyResponse

    fun getPolicy(scope: WaitlistTenantScope, policyId: Long): WaitlistPolicyResponse

    fun upsertPolicy(
        scope: WaitlistTenantScope,
        idempotencyKey: String,
        request: UpsertWaitlistPolicyRequest,
    ): WaitlistPolicyResponse

    fun activatePolicy(
        scope: WaitlistTenantScope,
        policyId: Long,
        idempotencyKey: String,
        request: ActivateWaitlistPolicyRequest,
    ): WaitlistPolicyResponse

    fun createRestriction(
        scope: WaitlistTenantScope,
        idempotencyKey: String,
        request: CreateWaitlistRestrictionRequest,
    ): WaitlistAdjustmentResponse

    fun releaseRestriction(
        scope: WaitlistTenantScope,
        restrictionId: Long,
        idempotencyKey: String,
        request: ReleaseWaitlistRestrictionRequest,
    ): WaitlistAdjustmentResponse

    fun grantRecoveryCredit(
        scope: WaitlistTenantScope,
        idempotencyKey: String,
        request: GrantWaitlistRecoveryCreditRequest,
    ): WaitlistAdjustmentResponse

    fun revokeRecoveryCredit(
        scope: WaitlistTenantScope,
        recoveryCreditId: Long,
        idempotencyKey: String,
        request: RevokeWaitlistRecoveryCreditRequest,
    ): WaitlistAdjustmentResponse

    fun createBenefitGrant(
        scope: WaitlistTenantScope,
        idempotencyKey: String,
        request: CreateWaitlistBenefitGrantRequest,
    ): WaitlistAdjustmentResponse

    fun revokeBenefitGrant(
        scope: WaitlistTenantScope,
        benefitGrantId: Long,
        idempotencyKey: String,
        request: RevokeWaitlistBenefitGrantRequest,
    ): WaitlistAdjustmentResponse
}
