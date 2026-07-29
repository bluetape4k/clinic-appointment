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
    ): SourceVersionDecision =
        verify(
            producer = producer,
            tenantGroupId = event.tenantGroupId,
            clinicId = event.clinicId,
            sourceAuthority = event.sourcePurchaseAuthority,
            sourceAggregateId = event.sourceAggregateId,
            sourceAggregateVersion = event.sourceAggregateVersion,
            localVersion = localVersion,
            proof = proof,
        )

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
    ): SourceVersionDecision =
        verify(
            producer = producer,
            tenantGroupId = event.tenantGroupId,
            clinicId = event.clinicId,
            sourceAuthority = event.sourcePurchaseAuthority,
            sourceAggregateId = event.sourceAggregateId,
            sourceAggregateVersion = event.sourceAggregateVersion,
            localVersion = localVersion,
            proof = proof,
        )

    /** 승인된 상품 version 전환의 source 순서를 공통 watermark 계약으로 검증합니다. */
    fun verify(
        producer: String,
        event: ProductVersionMigrationApprovedEvent,
        localVersion: Long?,
        proof: SourceAuthorityVersionProof?,
    ): SourceVersionDecision =
        verify(
            producer = producer,
            tenantGroupId = event.tenantGroupId,
            clinicId = event.clinicId,
            sourceAuthority = event.sourcePurchaseAuthority,
            sourceAggregateId = event.sourceAggregateId,
            sourceAggregateVersion = event.sourceAggregateVersion,
            localVersion = localVersion,
            proof = proof,
        )

    /** 상품 전환 뒤 일정 거부 사실의 source 순서를 공통 watermark 계약으로 검증합니다. */
    fun verify(
        producer: String,
        event: ProductVersionMigrationRescheduleDeclinedEvent,
        localVersion: Long?,
        proof: SourceAuthorityVersionProof?,
    ): SourceVersionDecision =
        verify(
            producer = producer,
            tenantGroupId = event.tenantGroupId,
            clinicId = event.clinicId,
            sourceAuthority = event.sourcePurchaseAuthority,
            sourceAggregateId = event.sourceAggregateId,
            sourceAggregateVersion = event.sourceAggregateVersion,
            localVersion = localVersion,
            proof = proof,
        )

    /** 임상 완료·환불 사실의 source 순서를 공통 watermark 계약으로 검증합니다. */
    fun verify(
        producer: String,
        event: TreatmentFulfillmentEvent,
        localVersion: Long?,
        proof: SourceAuthorityVersionProof?,
    ): SourceVersionDecision =
        verify(
            producer = producer,
            tenantGroupId = event.tenantGroupId,
            clinicId = event.clinicId,
            sourceAuthority = event.sourcePurchaseAuthority,
            sourceAggregateId = event.sourceAggregateId,
            sourceAggregateVersion = event.sourceAggregateVersion,
            localVersion = localVersion,
            proof = proof,
        )

    /**
     * 외부 aggregate 종류와 무관한 연속 version·authority proof 검증의 단일 구현입니다.
     *
     * 로컬 watermark보다 과거이면 replay/conflict 분류를 caller에게 위임하고, 바로 다음
     * version만 기본 허용합니다. gap은 동일 tenant/clinic/producer/authority/aggregate를
     * 가리키는 유효한 짧은 수명 증거가 목표 version까지 포괄할 때만 허용합니다.
     */
    private fun verify(
        producer: String,
        tenantGroupId: Long,
        clinicId: Long,
        sourceAuthority: String,
        sourceAggregateId: String,
        sourceAggregateVersion: Long,
        localVersion: Long?,
        proof: SourceAuthorityVersionProof?,
    ): SourceVersionDecision {
        val watermark = localVersion ?: 0L
        if (sourceAggregateVersion <= watermark) return SourceVersionDecision.STALE_OR_DUPLICATE
        if (sourceAggregateVersion == watermark + 1L) return SourceVersionDecision.ACCEPT

        val now = clock.instant()
        val proofCoversGap =
            proof != null &&
                proof.tenantGroupId == tenantGroupId &&
                proof.clinicId == clinicId &&
                proof.producer == producer &&
                proof.sourceAuthority == sourceAuthority &&
                proof.sourceAggregateId == sourceAggregateId &&
                proof.verifiedVersion >= sourceAggregateVersion &&
                !proof.verifiedAt.isAfter(now) &&
                !proof.expiresAt.isBefore(now)
        return if (proofCoversGap) SourceVersionDecision.ACCEPT else SourceVersionDecision.WAITING_GAP
    }
}
