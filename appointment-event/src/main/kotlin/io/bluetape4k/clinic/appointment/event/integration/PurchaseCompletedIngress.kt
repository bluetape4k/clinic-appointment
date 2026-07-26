package io.bluetape4k.clinic.appointment.event.integration

fun interface SourceAuthorityVersionProofProvider {
    fun obtain(event: PurchaseCompletedEvent): SourceAuthorityVersionProof?
}

/**
 * Trust and preparation boundary for raw purchase envelopes.
 *
 * Signature checks, authority proof lookup, and patient protection complete
 * before the handler opens its atomic inbox/plan/outbox transaction.
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
                rawEnvelope.trusted(),
                protectedQuarantineEnvelope,
                failure.reasonCode,
            )
        }
        val normalized = eventAdapter.adapt(trusted)
        val proof = try {
            versionProofProvider.obtain(normalized.payload)
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
