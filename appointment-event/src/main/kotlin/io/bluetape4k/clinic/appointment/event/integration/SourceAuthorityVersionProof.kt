package io.bluetape4k.clinic.appointment.event.integration

import java.time.Instant

data class SourceAuthorityVersionProof(
    val sourceAggregateId: String,
    val verifiedVersion: Long,
    val verifiedAt: Instant,
    val expiresAt: Instant,
)
