package io.bluetape4k.clinic.appointment.event.integration

data class PurchaseRedriveResult(
    val dryRun: Boolean,
    val status: PurchaseHandleStatus,
    val reasonCode: String?,
    val wouldCreatePlan: Boolean,
)

/**
 * Re-drives one operator-supplied original envelope after exact identity
 * confirmation. Trust failures are never bypassed.
 */
class PurchaseEventRedriveService(
    private val trustVerifier: SchedulingEventTrustVerifier,
    private val eventAdapter: PurchaseCompletedEventAdapter,
    private val versionProofProvider: SourceAuthorityVersionProofProvider,
    private val patientReferenceProtector: PatientReferenceProtector,
    private val writeHandler: PurchaseCompletedHandler,
    private val shadowHandler: PurchaseCompletedHandler,
) {
    fun redrive(
        originalEnvelope: UntrustedSchedulingEventEnvelope<PurchaseCompletedEvent>,
        expectedEventId: String,
        expectedSourceAggregateVersion: Long,
        dryRun: Boolean,
    ): PurchaseRedriveResult {
        require(originalEnvelope.eventId == expectedEventId) { "eventId confirmation does not match" }
        require(originalEnvelope.payload.sourceAggregateVersion == expectedSourceAggregateVersion) {
            "sourceAggregateVersion confirmation does not match"
        }
        val normalized = eventAdapter.adapt(trustVerifier.verify(originalEnvelope))
        val proof = versionProofProvider.obtain(normalized.payload)
        val protectedReference = patientReferenceProtector.protect(normalized.payload.patientReferenceToken)
        val result = if (dryRun) {
            shadowHandler.handle(normalized, proof, protectedReference)
        } else {
            writeHandler.handle(normalized, proof, protectedReference)
        }
        return PurchaseRedriveResult(
            dryRun = dryRun,
            status = result.status,
            reasonCode = result.reasonCode,
            wouldCreatePlan = dryRun && result.status == PurchaseHandleStatus.SHADOW,
        )
    }
}
