package io.bluetape4k.clinic.appointment.event.integration

fun interface SourceAuthorityVersionProofProvider {
    fun obtain(producer: String, event: PurchaseCompletedEvent): SourceAuthorityVersionProof?
}

/**
 * 원시 구매 envelope의 신뢰성 검증·준비 경계입니다.
 *
 * handler가 atomic inbox/plan/outbox 트랜잭션을 열기 전에 signature 검사,
 * authority proof 조회, patient protection을 모두 완료합니다.
 */
class PurchaseCompletedIngress(
    private val trustVerifier: SchedulingEventTrustVerifier,
    private val eventAdapter: PurchaseCompletedEventAdapter,
    private val versionProofProvider: SourceAuthorityVersionProofProvider,
    private val patientReferenceProtector: PatientReferenceProtector,
    private val quarantineEnvelopeProtector: QuarantineEnvelopeProtector,
    private val handler: PurchaseCompletedHandler,
) {
    fun accept(
        rawEnvelope: UntrustedSchedulingEventEnvelope<PurchaseCompletedEvent>,
    ): PurchaseHandleResult {
        PurchaseEventBounds.validateEnvelopeMetadata(rawEnvelope)
        val protectedQuarantineEnvelope = quarantineEnvelopeProtector.protect(rawEnvelope)
        val trusted = try {
            trustVerifier.verify(rawEnvelope)
        } catch (failure: SchedulingTrustException) {
            return handler.quarantineRejectedEnvelope(
                rawEnvelope,
                protectedQuarantineEnvelope,
                failure.reasonCode,
            )
        }
        val normalized = eventAdapter.adapt(trusted)
        val proof = try {
            versionProofProvider.obtain(normalized.producer, normalized.payload)
        } catch (failure: SourceAuthorityUnavailableException) {
            return handler.stageAuthorityUnavailable(
                normalized,
                failure.failureReason,
                protectedQuarantineEnvelope,
            )
        }
        val protectedReference = patientReferenceProtector.protect(
            normalized.payload.tenantGroupId,
            normalized.payload.patientReferenceToken,
        )
        return handler.handle(normalized, proof, protectedReference, protectedQuarantineEnvelope)
    }
}
