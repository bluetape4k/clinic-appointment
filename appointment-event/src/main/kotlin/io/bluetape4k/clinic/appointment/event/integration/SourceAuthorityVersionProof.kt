package io.bluetape4k.clinic.appointment.event.integration

import java.io.Serializable
import java.time.Instant

/**
 * Bounded proof that a producer-authoritative aggregate version is current for
 * one exact tenant, clinic, producer, and source authority.
 */
data class SourceAuthorityVersionProof(
    val tenantGroupId: Long,
    val clinicId: Long,
    val producer: String,
    val sourceAuthority: String,
    val sourceAggregateId: String,
    val verifiedVersion: Long,
    val verifiedAt: Instant,
    val expiresAt: Instant,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

enum class SourceAuthorityFailureReason(
    val reasonCode: String,
) {
    TIMEOUT("SOURCE_AUTHORITY_TIMEOUT"),
    CIRCUIT_OPEN("SOURCE_AUTHORITY_CIRCUIT_OPEN"),
}

class SourceAuthorityUnavailableException(
    val failureReason: SourceAuthorityFailureReason,
) : RuntimeException(failureReason.reasonCode)
