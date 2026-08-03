package io.bluetape4k.clinic.appointment.api.dto

import java.io.Serializable
import java.time.Instant

data class WaitlistPageResponse<T : Serializable>(
    val items: List<T>,
    val nextCursor: String? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

data class WaitlistEntryResponse(
    val entryRef: String,
    val memberId: String,
    val status: String,
    val version: Long,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
) : Serializable

data class WaitlistOfferResponse(
    val offerRef: String,
    val entryRef: String,
    val memberId: String,
    val status: String,
    val version: Long,
    val expiresAt: Instant? = null,
    val deliveryState: String? = null,
) : Serializable

data class WaitlistOfferDecisionResponse(
    val offerRef: String,
    val decisionState: String,
    val appointmentRef: String? = null,
    val decidedAt: Instant? = null,
) : Serializable

data class WaitlistPolicyResponse(
    val policyRef: String,
    val status: String,
    val version: Long,
    val effectiveFrom: Instant? = null,
    val effectiveUntil: Instant? = null,
) : Serializable

data class WaitlistAdjustmentResponse(
    val adjustmentRef: String,
    val memberId: String,
    val adjustmentType: String,
    val status: String,
    val version: Long,
    val effectiveFrom: Instant? = null,
    val expiresAt: Instant? = null,
) : Serializable
