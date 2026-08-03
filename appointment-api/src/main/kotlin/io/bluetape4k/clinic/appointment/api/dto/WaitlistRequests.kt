package io.bluetape4k.clinic.appointment.api.dto

import com.fasterxml.jackson.annotation.JsonAnySetter
import io.bluetape4k.clinic.appointment.api.waitlist.WaitlistApiError
import io.bluetape4k.clinic.appointment.api.waitlist.WaitlistApiException
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import tools.jackson.databind.JsonNode
import java.io.Serializable
import java.time.Instant

abstract class RejectUnknownWaitlistFields : Serializable {
    @JsonAnySetter
    fun rejectUnknownField(name: String, value: JsonNode?) {
        throw WaitlistApiException(WaitlistApiError.PAYLOAD_INVALID)
    }
}

data class CreateWaitlistEntryRequest(
    @field:NotBlank
    val memberId: String,
    @field:NotBlank
    val treatmentTypeRef: String,
    val preferredDoctorRef: String? = null,
    val windowStart: Instant? = null,
    val windowEnd: Instant? = null,
    @field:Size(max = 64)
    val reasonCode: String? = null,
) : RejectUnknownWaitlistFields()

data class WithdrawWaitlistEntryRequest(
    @field:PositiveOrZero
    val expectedVersion: Long,
    @field:NotBlank
    @field:Size(max = 64)
    val reasonCode: String,
) : RejectUnknownWaitlistFields()

data class ConfirmWaitlistOfferRequest(
    @field:PositiveOrZero
    val expectedVersion: Long,
    @field:Size(max = 64)
    val confirmationSource: String? = null,
) : RejectUnknownWaitlistFields()

data class DeclineWaitlistOfferRequest(
    @field:PositiveOrZero
    val expectedVersion: Long,
    @field:NotBlank
    @field:Size(max = 64)
    val reasonCode: String,
) : RejectUnknownWaitlistFields()

data class UpsertWaitlistPolicyRequest(
    @field:NotBlank
    val policyName: String,
    val effectiveFrom: Instant? = null,
    val effectiveUntil: Instant? = null,
    val maxConcurrentOffers: Int? = null,
    @field:Min(1)
    @field:Max(100)
    val defaultOfferLimit: Int? = null,
    val rules: Map<String, Any?> = emptyMap(),
) : RejectUnknownWaitlistFields()

data class ActivateWaitlistPolicyRequest(
    @field:PositiveOrZero
    val expectedVersion: Long,
    @field:NotBlank
    @field:Size(max = 64)
    val reasonCode: String,
) : RejectUnknownWaitlistFields()

data class CreateWaitlistRestrictionRequest(
    @field:NotBlank
    val memberId: String,
    @field:NotBlank
    @field:Size(max = 64)
    val reasonCode: String,
    val effectiveFrom: Instant? = null,
    val expiresAt: Instant? = null,
) : RejectUnknownWaitlistFields()

data class ReleaseWaitlistRestrictionRequest(
    @field:PositiveOrZero
    val expectedVersion: Long,
    @field:NotBlank
    @field:Size(max = 64)
    val reasonCode: String,
) : RejectUnknownWaitlistFields()

data class GrantWaitlistRecoveryCreditRequest(
    @field:NotBlank
    val memberId: String,
    @field:Min(1)
    @field:Max(100)
    val credits: Int,
    @field:NotBlank
    @field:Size(max = 64)
    val reasonCode: String,
    val expiresAt: Instant? = null,
) : RejectUnknownWaitlistFields()

data class RevokeWaitlistRecoveryCreditRequest(
    @field:PositiveOrZero
    val expectedVersion: Long,
    @field:NotBlank
    @field:Size(max = 64)
    val reasonCode: String,
) : RejectUnknownWaitlistFields()

data class CreateWaitlistBenefitGrantRequest(
    @field:NotBlank
    val memberId: String,
    @field:NotBlank
    val benefitCode: String,
    @field:NotBlank
    @field:Size(max = 64)
    val reasonCode: String,
    val expiresAt: Instant? = null,
) : RejectUnknownWaitlistFields()

data class RevokeWaitlistBenefitGrantRequest(
    @field:PositiveOrZero
    val expectedVersion: Long,
    @field:NotBlank
    @field:Size(max = 64)
    val reasonCode: String,
) : RejectUnknownWaitlistFields()
