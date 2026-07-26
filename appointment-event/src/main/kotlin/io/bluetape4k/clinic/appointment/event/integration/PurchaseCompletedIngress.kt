package io.bluetape4k.clinic.appointment.event.integration

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Clock

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
    private val handler: PurchaseCompletedHandler,
    private val eventRepository: SchedulingEventRepository,
    private val clock: Clock,
) {
    fun accept(
        rawEnvelope: UntrustedSchedulingEventEnvelope<PurchaseCompletedEvent>,
    ): PurchaseHandleResult {
        val trusted = try {
            trustVerifier.verify(rawEnvelope)
        } catch (failure: SchedulingTrustException) {
            return quarantineTrustFailure(rawEnvelope, failure.reasonCode)
        }
        val normalized = eventAdapter.adapt(trusted)
        val proof = versionProofProvider.obtain(normalized.payload)
        val protectedReference = patientReferenceProtector.protect(normalized.payload.patientReferenceToken)
        return handler.handle(normalized, proof, protectedReference)
    }

    private fun quarantineTrustFailure(
        rawEnvelope: UntrustedSchedulingEventEnvelope<PurchaseCompletedEvent>,
        reasonCode: String,
    ): PurchaseHandleResult =
        transaction {
            eventRepository.findInbox(rawEnvelope.eventId)?.let {
                return@transaction PurchaseHandleResult(PurchaseHandleStatus.DUPLICATE, "EVENT_ALREADY_TERMINAL")
            }
            val inboxId = eventRepository.insertReceived(rawEnvelope.trusted())
            eventRepository.markQuarantined(inboxId, reasonCode, clock.instant())
            PurchaseHandleResult(PurchaseHandleStatus.QUARANTINED, reasonCode)
        }
}
