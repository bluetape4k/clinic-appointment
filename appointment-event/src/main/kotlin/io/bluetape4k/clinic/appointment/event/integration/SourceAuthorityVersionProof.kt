package io.bluetape4k.clinic.appointment.event.integration

import java.time.Instant

data class SourceAuthorityVersionProof(
    val sourceAggregateId: String,
    val verifiedVersion: Long,
    val verifiedAt: Instant,
    val expiresAt: Instant,
)

enum class SourceAuthorityFailureReason(
    val reasonCode: String,
) {
    TIMEOUT("SOURCE_AUTHORITY_TIMEOUT"),
    CIRCUIT_OPEN("SOURCE_AUTHORITY_CIRCUIT_OPEN"),
}

class SourceAuthorityUnavailableException(
    val failureReason: SourceAuthorityFailureReason,
) : RuntimeException(failureReason.reasonCode)
