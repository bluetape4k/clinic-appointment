package io.bluetape4k.clinic.appointment.event.integration

import java.time.Clock

enum class SourceVersionDecision {
    ACCEPT,
    STALE_OR_DUPLICATE,
    WAITING_GAP,
}

class SourceAggregateVersionVerifier(
    private val clock: Clock,
) {
    fun verify(
        producer: String,
        event: PurchaseCompletedEvent,
        localVersion: Long?,
        proof: SourceAuthorityVersionProof?,
    ): SourceVersionDecision {
        val watermark = localVersion ?: 0L
        if (event.sourceAggregateVersion <= watermark) return SourceVersionDecision.STALE_OR_DUPLICATE
        if (event.sourceAggregateVersion == watermark + 1L) return SourceVersionDecision.ACCEPT

        val proofCoversGap =
            proof != null &&
                proof.tenantGroupId == event.tenantGroupId &&
                proof.clinicId == event.clinicId &&
                proof.producer == producer &&
                proof.sourceAuthority == event.sourcePurchaseAuthority &&
                proof.sourceAggregateId == event.sourceAggregateId &&
                proof.verifiedVersion >= event.sourceAggregateVersion &&
                !proof.verifiedAt.isAfter(clock.instant()) &&
                !proof.expiresAt.isBefore(clock.instant())
        return if (proofCoversGap) SourceVersionDecision.ACCEPT else SourceVersionDecision.WAITING_GAP
    }
}
