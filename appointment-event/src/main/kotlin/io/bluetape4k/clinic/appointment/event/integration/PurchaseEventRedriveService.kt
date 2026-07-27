package io.bluetape4k.clinic.appointment.event.integration

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.Serializable
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class PurchaseRedriveResult(
    val dryRun: Boolean,
    val status: PurchaseHandleStatus,
    val reasonCode: String?,
    val wouldCreatePlan: Boolean,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * Full operator confirmation required to preview or execute one quarantined
 * purchase event without broad selectors.
 */
data class PurchaseRedriveConfirmation(
    val quarantineId: Long,
    val actor: String,
    val reason: String,
    val approvalReferences: List<String>,
    val eventId: String,
    val sourceAggregateVersion: Long,
    val tenantGroupId: Long,
    val clinicId: Long,
    val sourcePurchaseAuthority: String,
    val sourcePurchaseId: String,
    val catalogSourceAuthority: String,
    val productId: String,
    val catalogVersion: Long,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * Re-drives one operator-supplied original envelope after exact identity
 * confirmation. Trust failures are never bypassed.
 */
class PurchaseEventRedriveService(
    private val trustVerifier: SchedulingEventTrustVerifier,
    private val eventAdapter: PurchaseCompletedEventAdapter,
    private val versionProofProvider: SourceAuthorityVersionProofProvider,
    private val patientReferenceProtector: PatientReferenceProtector,
    private val quarantineEnvelopeProtector: QuarantineEnvelopeProtector,
    private val quarantineRepository: SchedulingQuarantineRepository,
    private val writeHandler: PurchaseCompletedHandler,
) {
    fun redrive(
        originalEnvelope: UntrustedSchedulingEventEnvelope<PurchaseCompletedEvent>,
        confirmation: PurchaseRedriveConfirmation,
        dryRun: Boolean,
    ): PurchaseRedriveResult {
        require(confirmation.quarantineId > 0) { "quarantineId must be positive" }
        val payload = originalEnvelope.payload
        require(originalEnvelope.eventId == confirmation.eventId) { "eventId confirmation does not match" }
        require(payload.sourceAggregateVersion == confirmation.sourceAggregateVersion) {
            "sourceAggregateVersion confirmation does not match"
        }
        require(payload.tenantGroupId == confirmation.tenantGroupId) {
            "tenantGroupId confirmation does not match"
        }
        require(payload.clinicId == confirmation.clinicId) { "clinicId confirmation does not match" }
        require(payload.sourcePurchaseAuthority == confirmation.sourcePurchaseAuthority) {
            "sourcePurchaseAuthority confirmation does not match"
        }
        require(payload.sourcePurchaseId == confirmation.sourcePurchaseId) {
            "sourcePurchaseId confirmation does not match"
        }
        require(payload.catalogSourceAuthority == confirmation.catalogSourceAuthority) {
            "catalogSourceAuthority confirmation does not match"
        }
        require(payload.productId == confirmation.productId) { "productId confirmation does not match" }
        require(payload.catalogVersion == confirmation.catalogVersion) {
            "catalogVersion confirmation does not match"
        }
        val protectedQuarantineEnvelope = quarantineEnvelopeProtector.protect(originalEnvelope)
        val normalized = eventAdapter.adapt(trustVerifier.verify(originalEnvelope))
        val proof = versionProofProvider.obtain(normalized.producer, normalized.payload)
        val protectedReference = patientReferenceProtector.protect(
            normalized.payload.tenantGroupId,
            normalized.payload.patientReferenceToken,
        )
        val result = if (dryRun) {
            writeHandler.preview(normalized, proof, protectedReference)
        } else {
            transaction {
                quarantineRepository.recordRedrive(
                    quarantineId = confirmation.quarantineId,
                    expectedEventId = confirmation.eventId,
                    actor = confirmation.actor,
                    reason = confirmation.reason,
                    approvalReferences = confirmation.approvalReferences,
                )
            }
            writeHandler.handle(normalized, proof, protectedReference, protectedQuarantineEnvelope)
        }
        if (dryRun) {
            transaction {
                quarantineRepository.recordDryRun(
                    quarantineId = confirmation.quarantineId,
                    expectedEventId = confirmation.eventId,
                    actor = confirmation.actor,
                    reason = confirmation.reason,
                    dryRunDiffHash = result.diffHash(dryRun = true),
                )
            }
        }
        return PurchaseRedriveResult(
            dryRun = dryRun,
            status = result.status,
            reasonCode = result.reasonCode,
            wouldCreatePlan =
                dryRun &&
                    result.status == PurchaseHandleStatus.SHADOW &&
                    result.reasonCode == "WOULD_CREATE_PLAN",
        )
    }

    private fun PurchaseHandleResult.diffHash(dryRun: Boolean): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$dryRun|${status.name}|${reasonCode.orEmpty()}|${planId ?: 0L}".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
}
