package io.bluetape4k.clinic.appointment.event.integration

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.Serializable
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 단건 redrive 또는 dry-run의 개인정보 없는 운영 결과입니다.
 *
 * @property dryRun 실제 handler transaction을 실행하지 않은 미리보기이면 `true`입니다.
 * @property status inbox/Plan 처리의 닫힌 결과 상태입니다.
 * @property reasonCode gap·quarantine·중복 같은 안정적인 운영 사유입니다.
 * @property wouldCreatePlan dry-run이 실제 실행되면 새 Plan을 만들 수 있음을 뜻합니다.
 */
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
 * @property envelopeHash 운영자가 확인한 암호화 원 envelope의 SHA-256입니다. event ID와
 * 상품 식별자가 같아도 payload가 달라지면 redrive를 거부합니다.
 * @property quarantineId 단건 selector로 사용할 양수 격리 row ID입니다.
 * @property actor 실행을 승인한 운영자 식별자입니다. 환자·credential을 포함하지 않습니다.
 * @property reason 티켓에서 승인한 짧은 실행 사유입니다.
 * @property approvalReferences release 승인 audit과 다시 대조할 불투명 참조 목록입니다.
 * @property sourceAggregateVersion 원 event의 구매 aggregate version입니다.
 * @property tenantGroupId 원 event와 격리 row가 소유된 SaaS tenant ID입니다.
 * @property clinicId 원 event와 격리 row가 소유된 병원 ID입니다.
 * @property sourcePurchaseAuthority 구매 사실을 발행한 등록 authority입니다.
 * @property sourcePurchaseId 구매서비스 내부의 불투명 구매 ID입니다.
 * @property catalogSourceAuthority 상품 BOM version을 발행한 등록 authority입니다.
 * @property productId 구매 당시 상품 식별자입니다.
 * @property catalogVersion 구매 당시 고정된 상품 catalog version입니다.
 */
data class PurchaseRedriveConfirmation(
    val quarantineId: Long,
    val operatorRole: RedriveOperatorRole,
    val actor: String,
    val reason: String,
    val approvalReferences: List<String>,
    val eventId: String,
    val envelopeHash: String,
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
    private val successAuditRecorder: RedriveSuccessAuditRecorder =
        RedriveSuccessAuditRecorder { confirmation ->
            transaction {
                quarantineRepository.recordRedriveSucceeded(
                    quarantineId = confirmation.quarantineId,
                    expectedEventId = confirmation.eventId,
                    expectedEnvelopeHash = confirmation.envelopeHash,
                    actor = confirmation.actor,
                    reason = confirmation.reason,
                    approvalReferences = confirmation.approvalReferences,
                )
            }
        },
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
        require(protectedQuarantineEnvelope.envelopeHash == confirmation.envelopeHash) {
            "envelopeHash confirmation does not match"
        }
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
                quarantineRepository.recordRedriveAttempt(
                    quarantineId = confirmation.quarantineId,
                    expectedEventId = confirmation.eventId,
                    expectedEnvelopeHash = confirmation.envelopeHash,
                    actor = confirmation.actor,
                    reason = confirmation.reason,
                    approvalReferences = confirmation.approvalReferences,
                )
            }
            val handled = try {
                writeHandler.handle(normalized, proof, protectedReference, protectedQuarantineEnvelope)
            } catch (failure: Exception) {
                transaction {
                    quarantineRepository.recordRedriveFailed(
                        quarantineId = confirmation.quarantineId,
                        expectedEventId = confirmation.eventId,
                        expectedEnvelopeHash = confirmation.envelopeHash,
                        actor = confirmation.actor,
                        approvalReferences = confirmation.approvalReferences,
                    )
                }
                throw failure
            }
            try {
                successAuditRecorder.record(confirmation)
            } catch (failure: Exception) {
                throw RedriveAuditReconciliationRequiredException(
                    quarantineId = confirmation.quarantineId,
                    eventId = confirmation.eventId,
                    cause = failure,
                )
            }
            handled
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
 * handler 성공 뒤 append-only 성공 audit을 기록하는 별도 장애 주입 경계입니다.
 *
 * 업무 mutation은 이미 commit되었으므로 이 경계의 실패를 handler 실패로 바꾸면 안 됩니다.
 * 기본 구현은 [SchedulingQuarantineRepository.recordRedriveSucceeded]를 별도 transaction에서
 * 호출하며, 실패하면 직전 `REDRIVE_ATTEMPT`가 reconciliation 대상임을 나타냅니다.
 */
fun interface RedriveSuccessAuditRecorder {
    fun record(confirmation: PurchaseRedriveConfirmation)
}

/**
 * redrive 업무 mutation은 commit됐지만 성공 audit 기록만 실패했음을 나타냅니다.
 *
 * caller는 같은 [quarantineId]와 [eventId]로 재시도할 수 있습니다. handler의 inbox
 * 멱등성이 업무 mutation 재적용을 막고, 남아 있는 `REDRIVE_ATTEMPT` 뒤에 성공 audit을
 * 다시 append할 수 있습니다. 원 예외 message는 외부 응답에 사용하지 않습니다.
 */
class RedriveAuditReconciliationRequiredException(
    val quarantineId: Long,
    val eventId: String,
    cause: Throwable,
) : IllegalStateException(
    "redrive succeeded but its audit outcome requires reconciliation",
    cause,
)

/**
 * 격리 event를 실행할 수 있는 예약서비스 내부 운영 권한입니다.
 */
enum class RedriveOperatorRole {
    /** dry-run과 승인된 단건 redrive를 실행할 수 있는 유일한 역할입니다. */
    RESERVATION_OPERATIONS_ADMIN,

    /** 원 event replay authority를 제공하지만 예약 격리 row를 실행할 수 없습니다. */
    SOURCE_REPLAY_AUTHORITY,
}
