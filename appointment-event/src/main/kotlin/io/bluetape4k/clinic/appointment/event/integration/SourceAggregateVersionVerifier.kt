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

        val now = clock.instant()
        val proofCoversGap =
            proof != null &&
                proof.tenantGroupId == event.tenantGroupId &&
                proof.clinicId == event.clinicId &&
                proof.producer == producer &&
                proof.sourceAuthority == event.sourcePurchaseAuthority &&
                proof.sourceAggregateId == event.sourceAggregateId &&
                proof.verifiedVersion >= event.sourceAggregateVersion &&
                !proof.verifiedAt.isAfter(now) &&
                !proof.expiresAt.isBefore(now)
        return if (proofCoversGap) SourceVersionDecision.ACCEPT else SourceVersionDecision.WAITING_GAP
    }

    /**
     * 실행 BOM event가 로컬 source watermark 다음 version인지 검증합니다.
     *
     * @param producer 서명 검증을 통과한 producer ID입니다.
     * @param event 구매 aggregate identity와 version을 가진 실행 BOM payload입니다.
     * @param localVersion 같은 tenant/clinic/producer/authority/source ID 범위에서 처리된
     * 최신 version이며 아직 없으면 `null`입니다.
     * @param proof gap을 source authority가 확인한 경우의 짧은 수명 증거입니다.
     * @return 순차 처리, stale/replay, gap 대기 중 하나의 결정입니다.
     */
    fun verify(
        producer: String,
        event: PackageExecutionEvent,
        localVersion: Long?,
        proof: SourceAuthorityVersionProof?,
    ): SourceVersionDecision {
        val watermark = localVersion ?: 0L
        if (event.sourceAggregateVersion <= watermark) return SourceVersionDecision.STALE_OR_DUPLICATE
        if (event.sourceAggregateVersion == watermark + 1L) return SourceVersionDecision.ACCEPT

        val now = clock.instant()
        val proofCoversGap =
            proof != null &&
                proof.tenantGroupId == event.tenantGroupId &&
                proof.clinicId == event.clinicId &&
                proof.producer == producer &&
                proof.sourceAuthority == event.sourcePurchaseAuthority &&
                proof.sourceAggregateId == event.sourceAggregateId &&
                proof.verifiedVersion >= event.sourceAggregateVersion &&
                !proof.verifiedAt.isAfter(now) &&
                !proof.expiresAt.isBefore(now)
        return if (proofCoversGap) SourceVersionDecision.ACCEPT else SourceVersionDecision.WAITING_GAP
    }
}
