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
 * 하나의 격리 구매 event를 광범위 selector 없이 미리보기·재처리하기 위한 운영자 확인입니다.
 *
 * @property operatorRole 예약 운영 redrive 권한입니다. 상품·구매팀은 원 event replay
 * authority를 제공하지만 예약 DB 격리 row의 실행 승인은
 * [RedriveOperatorRole.RESERVATION_OPERATIONS_ADMIN]만 할 수 있습니다.
 * @property eventId quarantine 원 event ID이자 처리 성공 후 inbox key입니다.
 */
data class PurchaseRedriveConfirmation(
    val quarantineId: Long,
    val operatorRole: RedriveOperatorRole,
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
 * 운영자가 제공한 원 envelope의 identity와 승인 권한을 모두 확인한 뒤 한 건만 재처리합니다.
 *
 * trust 검증 실패를 우회하지 않으며 dry-run과 실제 실행 모두 예약 운영 관리자 권한을
 * 요구합니다. append-only audit은 quarantine ID로 원 [PurchaseRedriveConfirmation.eventId]를
 * 복원하고, 성공한 consumer inbox도 같은 event ID를 key로 사용합니다.
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
        require(confirmation.operatorRole == RedriveOperatorRole.RESERVATION_OPERATIONS_ADMIN) {
            "redrive requires reservation operations administrator authority"
        }
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

/**
 * 격리 event를 실행할 수 있는 예약서비스 내부 운영 권한입니다.
 */
enum class RedriveOperatorRole {
    /** dry-run과 승인된 단건 redrive를 실행할 수 있는 유일한 역할입니다. */
    RESERVATION_OPERATIONS_ADMIN,

    /** 원 event replay authority를 제공하지만 예약 격리 row를 실행할 수 없습니다. */
    SOURCE_REPLAY_AUTHORITY,
}
