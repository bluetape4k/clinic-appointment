package io.bluetape4k.clinic.appointment.api.service

import io.bluetape4k.clinic.appointment.commitment.CancellationReasonRegistry
import io.bluetape4k.clinic.appointment.api.commitment.AcceptAppointmentProposalCommand
import io.bluetape4k.clinic.appointment.api.commitment.AppointmentCommitmentCommandError
import io.bluetape4k.clinic.appointment.api.commitment.AppointmentCommitmentCommandException
import io.bluetape4k.clinic.appointment.api.commitment.AppointmentCommitmentCommandMetrics
import io.bluetape4k.clinic.appointment.api.commitment.AppointmentCommitmentCommandService
import io.bluetape4k.clinic.appointment.api.commitment.AppointmentProposalRequest
import io.bluetape4k.clinic.appointment.api.commitment.AppointmentProposalService
import io.bluetape4k.clinic.appointment.api.commitment.CancelAppointmentCommand
import io.bluetape4k.clinic.appointment.api.commitment.ChangeAppointmentProposalCommand
import io.bluetape4k.clinic.appointment.api.commitment.CommitmentConflictReason
import io.bluetape4k.clinic.appointment.api.commitment.CommitmentMetricResult
import io.bluetape4k.clinic.appointment.api.commitment.CommitmentCommandContext
import io.bluetape4k.clinic.appointment.api.commitment.ConfirmAppointmentProposalCommand
import io.bluetape4k.clinic.appointment.api.commitment.CurrentPolicySnapshot
import io.bluetape4k.clinic.appointment.api.commitment.CustomerAppointmentRequestCommand
import io.bluetape4k.clinic.appointment.api.commitment.DeclineAppointmentProposalCommand
import io.bluetape4k.clinic.appointment.api.commitment.DirectAppointmentConfirmationCommand
import io.bluetape4k.clinic.appointment.api.commitment.DirectConfirmationPolicyDecision
import io.bluetape4k.clinic.appointment.api.commitment.ExpireAppointmentProposalCommand
import io.bluetape4k.clinic.appointment.api.commitment.ProposalConsentEvidence
import io.bluetape4k.clinic.appointment.api.commitment.VisitProposalInput
import io.bluetape4k.clinic.appointment.api.config.AppointmentCommitmentApiError
import io.bluetape4k.clinic.appointment.api.config.AppointmentCommitmentApiException
import io.bluetape4k.clinic.appointment.api.config.AppointmentCommitmentProperties
import io.bluetape4k.clinic.appointment.api.config.toApiException
import io.bluetape4k.clinic.appointment.api.dto.commitment.AppointmentCommitmentResponse
import io.bluetape4k.clinic.appointment.api.dto.commitment.AppointmentCommitmentDisplay
import io.bluetape4k.clinic.appointment.api.dto.commitment.AppointmentProposalResponse
import io.bluetape4k.clinic.appointment.api.dto.commitment.ApproveProposalRequest
import io.bluetape4k.clinic.appointment.api.dto.commitment.CancelAppointmentRequest
import io.bluetape4k.clinic.appointment.api.dto.commitment.ConsentEvidenceRequest
import io.bluetape4k.clinic.appointment.api.dto.commitment.CreateAppointmentRequestV2
import io.bluetape4k.clinic.appointment.api.notification.AppointmentMemberResolver
import io.bluetape4k.clinic.appointment.api.dto.commitment.CreateChangeProposalRequest
import io.bluetape4k.clinic.appointment.api.dto.commitment.DeclineProposalRequest
import io.bluetape4k.clinic.appointment.api.dto.commitment.DirectConfirmRequest
import io.bluetape4k.clinic.appointment.api.dto.commitment.DirectCreateAppointmentRequest
import io.bluetape4k.clinic.appointment.api.dto.commitment.ProposalDecisionRequest
import io.bluetape4k.clinic.appointment.api.dto.commitment.toProposalResponse
import io.bluetape4k.clinic.appointment.api.dto.commitment.toResponse
import io.bluetape4k.clinic.appointment.api.security.ActorContext
import io.bluetape4k.clinic.appointment.api.security.ActorType
import io.bluetape4k.clinic.appointment.api.tenant.TenantCodeRules
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentItemDraft
import io.bluetape4k.clinic.appointment.model.commitment.ConsentDecisionType
import io.bluetape4k.clinic.appointment.model.dto.AppointmentCommitmentRecord
import io.bluetape4k.clinic.appointment.model.dto.AppointmentProposalRecord
import io.bluetape4k.clinic.appointment.model.dto.AppointmentVisitIdentityDraft
import io.bluetape4k.clinic.appointment.model.dto.PersistedAppointmentPlanRevisionAggregateRecord
import io.bluetape4k.clinic.appointment.model.dto.ResourceAllocationRequest
import io.bluetape4k.clinic.appointment.model.plan.BookingPreferenceSnapshot
import io.bluetape4k.clinic.appointment.model.tables.AppointmentItems
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlans
import io.bluetape4k.clinic.appointment.model.tables.AppointmentProposals
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.repository.AppointmentCommitmentRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentPlanRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentPlanRevisionRepository
import io.bluetape4k.clinic.appointment.service.ProposalHasher
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.error
import io.bluetape4k.logging.info
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import tools.jackson.databind.json.JsonMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * raw `Idempotency-Key`를 저장 가능한 keyed digest로 변환하는 보안 경계이다.
 *
 * 구현체는 예약 command 전용 비밀값과 domain을 사용해야 하며 JWT, 정책 command,
 * 환자 fingerprint의 비밀값을 재사용하면 안 된다. 반환값은 소문자 64자
 * HMAC-SHA-256이어야 하고 raw key나 비밀값을 로그·예외에 포함하지 않는다.
 */
internal fun interface AppointmentCommitmentIdempotencyKeyHasher {
    fun hash(rawKey: String): String
}

/**
 * 고객 거절 증빙을 전역 unique 제약에 안전한 예약·proposal 범위 식별자로 만듭니다.
 *
 * 같은 고객이 같은 사유로 여러 proposal을 거절해도 서로 다른 증빙이어야 하며, 원문
 * 자유 텍스트나 환자 식별자를 포함하지 않습니다.
 */
internal fun appointmentDeclineEvidenceId(
    appointmentId: Long,
    proposalId: Long,
    reasonCode: String,
): String = "$appointmentId:$proposalId:$reasonCode"

/**
 * 예약 command 전용 비밀값으로 HMAC-SHA-256을 계산한다.
 *
 * [Mac]은 thread-safe하지 않으므로 호출마다 새 instance를 만들며, 생성자 입력은
 * 방어적으로 복사해 외부 배열 변경이 이후 digest에 영향을 주지 않게 한다.
 */
internal class HmacAppointmentCommitmentIdempotencyKeyHasher(secret: ByteArray) :
    AppointmentCommitmentIdempotencyKeyHasher {
    private val key = secret.copyOf()

    init {
        require(key.size >= MINIMUM_SECRET_BYTES) {
            "appointment commitment idempotency secret must be at least $MINIMUM_SECRET_BYTES bytes"
        }
    }

    override fun hash(rawKey: String): String =
        Mac
            .getInstance(HMAC_SHA_256)
            .apply { init(SecretKeySpec(key, HMAC_SHA_256)) }
            .doFinal("$DOMAIN|$rawKey".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val HMAC_SHA_256 = "HmacSHA256"
        const val DOMAIN = "appointment-commitment-idempotency-v1"
        const val MINIMUM_SECRET_BYTES = 32
    }
}

/**
 * Gateway actor와 commitment DTO를 내부 commitment command/query 서비스로 변환하는 기본 구현이다.
 *
 * request body는 일정 의도와 opaque 동의 증빙만 제공한다. 이 구현은 먼저
 * [AppointmentCommitmentAccessResolver]로 Gateway tenant·clinic·patient scope를
 * 검증하고, Plan revision·projection FK·proposal hash는 서버 저장소에서 다시 읽어
 * command 입력을 만든다. 신규 commitment 생성만 [AppointmentCommitmentProperties]의
 * `WRITE + clinicAllowlist`를 통과해야 하며, 이미 생성된 commitment row의 조회·승인·수락·거절·
 * 변경 제안은 rollback 중에도 유지된다.
 */
internal class DefaultAppointmentCommitmentApplicationService(
    private val database: Database,
    private val properties: AppointmentCommitmentProperties,
    private val accessResolver: AppointmentCommitmentAccessResolver,
    private val commandService: AppointmentCommitmentCommandService,
    private val policySnapshotResolver: AppointmentCommitmentPolicySnapshotResolver,
    private val planningResolver: AppointmentCommitmentPlanningResolver,
    private val appointmentMemberResolver: AppointmentMemberResolver,
    private val consentEvidenceVerifier: AppointmentCommitmentConsentEvidenceVerifier,
    private val metrics: AppointmentCommitmentCommandMetrics,
    private val idempotencyKeyHasher: AppointmentCommitmentIdempotencyKeyHasher,
    private val clock: Clock = Clock.systemUTC(),
    private val proposalService: AppointmentProposalService = AppointmentProposalService(),
    private val commitmentRepository: AppointmentCommitmentRepository = AppointmentCommitmentRepository(),
    private val planRepository: AppointmentPlanRepository = AppointmentPlanRepository(),
    private val planRevisionRepository: AppointmentPlanRevisionRepository = AppointmentPlanRevisionRepository(),
) : AppointmentCommitmentApplicationService {

    override fun requestAppointment(
        actor: ActorContext,
        idempotencyKey: String,
        createOnly: Boolean,
        request: CreateAppointmentRequestV2,
    ): AppointmentProposalResponse {
        require(createOnly) { "createOnly must be true" }
        val planAccess = accessResolver.resolvePlan(actor, request.appointmentPlanId)
        requireNewCommitmentWrite(planAccess.clinicId)
        accessResolver.requireConsentAuthority(actor, request.evidence.evidenceAuthority)
        val planRevision = activeRevision(request.appointmentPlanId)
        val policySnapshot = currentPolicySnapshot(planAccess, request.preferredStartAt)
        val proposal = initialProposalInput(
            access = planAccess,
            revision = INITIAL_PROPOSAL_REVISION,
            startsAt = request.preferredStartAt,
            endsAt = request.preferredEndAt,
            planRevision = planRevision,
            supersedesProposalId = null,
            policySnapshot = policySnapshot,
        )
        val consent = acceptedConsent(
            actor = actor,
            evidence =
                verifyConsentEvidence(
                    request = request.evidence,
                    tenantGroupId = planAccess.tenantGroupId,
                    clinicId = planAccess.clinicId,
                    patientReferenceFingerprint = planAccess.plan.patientReferenceFingerprint,
                    appointmentPlanId = request.appointmentPlanId,
                    appointmentId = null,
                    proposalId = null,
                    proposalHash = initialProposalConsentHash(proposal),
                    policySnapshot = policySnapshot,
                    decision = ConsentDecisionType.ACCEPTED,
                ),
        )
        return runCommand(actor, planAccess.tenantGroupId, planAccess.clinicId) {
            commandService.requestCustomerAppointment(
                CustomerAppointmentRequestCommand(
                    context = commandContext(actor, planAccess.tenantGroupId, planAccess.clinicId, idempotencyKey, "request", request),
                    identity = resolveIdentity(actor, planAccess),
                    proposal = proposal,
                    expiresAt = proposalExpiry(proposal.startsAt),
                    representativeTreatmentName = representativeTreatmentName(planRevision),
                    consent = consent,
                    holdResources =
                        bookingCommitment(policySnapshot).provisionalCapacityMode ==
                            io.bluetape4k.clinic.appointment.model.policy.ProvisionalCapacityMode.HARD_HOLD,
                ),
            ).let { proposalResponse(planAccess.tenantGroupId, planAccess.clinicId, it.commitment, it.proposal) }
        }
    }

    override fun directCreate(
        actor: ActorContext,
        idempotencyKey: String,
        createOnly: Boolean,
        request: DirectCreateAppointmentRequest,
    ): AppointmentCommitmentResponse {
        require(createOnly) { "createOnly must be true" }
        val planAccess = accessResolver.resolvePlan(actor, request.appointmentPlanId)
        requireNewCommitmentWrite(planAccess.clinicId)
        accessResolver.requireConsentAuthority(actor, request.evidence.evidenceAuthority)
        val planRevision = activeRevision(request.appointmentPlanId)
        val policySnapshot = currentPolicySnapshot(planAccess, request.preferredStartAt)
        val proposal = initialProposalInput(
            access = planAccess,
            revision = INITIAL_PROPOSAL_REVISION,
            startsAt = request.preferredStartAt,
            endsAt = request.preferredEndAt,
            planRevision = planRevision,
            supersedesProposalId = null,
            policySnapshot = policySnapshot,
        )
        val booking = bookingCommitment(policySnapshot)
        val consentRequirement = booking.adminConsentEvidence
        val consent = acceptedConsent(
            actor = actor,
            evidence =
                verifyConsentEvidence(
                    request = request.evidence,
                    tenantGroupId = planAccess.tenantGroupId,
                    clinicId = planAccess.clinicId,
                    patientReferenceFingerprint = planAccess.plan.patientReferenceFingerprint,
                    appointmentPlanId = request.appointmentPlanId,
                    appointmentId = null,
                    proposalId = null,
                    proposalHash = initialProposalConsentHash(proposal),
                    policySnapshot = policySnapshot,
                    decision = ConsentDecisionType.ACCEPTED,
                    allowedEvidenceTypes = consentRequirement.allowedEvidenceTypes,
                    maximumEvidenceAge = consentRequirement.maximumAge,
                    termsHashRequired = consentRequirement.termsHashRequired,
                    requiredTermsHash =
                        requiredConsentTermsHash(
                            consentRequirement.termsHashRequired,
                            planAccess.plan.catalogPayloadHash,
                        ),
                ),
        )
        return runCommand(actor, planAccess.tenantGroupId, planAccess.clinicId) {
            commandService.confirmDirectAppointment(
                DirectAppointmentConfirmationCommand(
                    context = commandContext(actor, planAccess.tenantGroupId, planAccess.clinicId, idempotencyKey, "direct-create", request),
                    identity = resolveIdentity(actor, planAccess),
                    proposal = proposal,
                    expiresAt = proposalExpiry(proposal.startsAt),
                    representativeTreatmentName = representativeTreatmentName(planRevision),
                    projectionTarget = planningResolver.resolveProjectionTarget(planAccess.clinicId, proposal),
                    policyDecision =
                        directPolicyDecision(
                            policySnapshot,
                            planAccess.plan.catalogPayloadHash,
                        ),
                    consent = consent,
                ),
            ).let { commitmentResponse(planAccess.tenantGroupId, planAccess.clinicId, it.commitment, it.proposal) }
        }
    }

    override fun approveProposal(
        actor: ActorContext,
        appointmentId: Long,
        expectedVersion: Long,
        idempotencyKey: String,
        request: ApproveProposalRequest,
    ): AppointmentCommitmentResponse {
        val access = accessResolver.requireAppointmentAccess(actor, appointmentId)
        val persisted = persistedProposalInput(access.clinicId, appointmentId, request.proposalId)
        return runCommand(actor, access.tenantGroupId, access.clinicId) {
            commandService.approveCustomerProposal(
                ConfirmAppointmentProposalCommand(
                    context = commandContext(actor, access.tenantGroupId, access.clinicId, idempotencyKey, "approve", appointmentId, expectedVersion, request),
                    appointmentId = appointmentId,
                    proposalId = request.proposalId,
                    expectedVersion = expectedVersion,
                    proposal = persisted.input,
                    expectedProposalHash = persisted.proposal.proposalHash,
                    projectionTarget = planningResolver.resolveProjectionTarget(access.clinicId, persisted.input),
                ),
            ).let { commitmentResponse(access.tenantGroupId, access.clinicId, it.commitment, it.proposal) }
        }
    }

    override fun decideProposal(
        actor: ActorContext,
        appointmentId: Long,
        proposalId: Long,
        expectedVersion: Long,
        idempotencyKey: String,
        request: ProposalDecisionRequest,
    ): AppointmentCommitmentResponse {
        val access = accessResolver.requireAppointmentAccess(actor, appointmentId)
        accessResolver.requireConsentAuthority(actor, request.evidence.evidenceAuthority)
        val persisted = persistedProposalInput(access.clinicId, appointmentId, proposalId)
        val policySnapshot =
            policySnapshotResolver.resolvePersisted(
                tenantGroupId = access.tenantGroupId,
                clinicId = access.clinicId,
                snapshotId = persisted.input.policySnapshotId,
            )
        val consent = acceptedConsent(
            actor = actor,
            evidence =
                verifyConsentEvidence(
                    request = request.evidence,
                    tenantGroupId = access.tenantGroupId,
                    clinicId = access.clinicId,
                    patientReferenceFingerprint = access.patientReferenceFingerprint,
                    appointmentPlanId = null,
                    appointmentId = appointmentId,
                    proposalId = proposalId,
                    proposalHash = persisted.proposal.proposalHash,
                    policySnapshot = policySnapshot,
                    decision = ConsentDecisionType.ACCEPTED,
                ),
        )
        return runCommand(actor, access.tenantGroupId, access.clinicId) {
            commandService.acceptProposal(
                AcceptAppointmentProposalCommand(
                    context = commandContext(actor, access.tenantGroupId, access.clinicId, idempotencyKey, "accept", appointmentId, proposalId, expectedVersion, request),
                    appointmentId = appointmentId,
                    proposalId = proposalId,
                    expectedVersion = expectedVersion,
                    proposal = persisted.input,
                    expectedProposalHash = persisted.proposal.proposalHash,
                    projectionTarget = planningResolver.resolveProjectionTarget(access.clinicId, persisted.input),
                    consent = consent,
                ),
            ).let { commitmentResponse(access.tenantGroupId, access.clinicId, it.commitment, it.proposal) }
        }
    }

    override fun declineProposal(
        actor: ActorContext,
        appointmentId: Long,
        proposalId: Long,
        expectedVersion: Long,
        idempotencyKey: String,
        request: DeclineProposalRequest,
    ): AppointmentCommitmentResponse {
        val access = accessResolver.requireAppointmentAccess(actor, appointmentId)
        val persisted = persistedProposalInput(access.clinicId, appointmentId, proposalId)
        return runCommand(actor, access.tenantGroupId, access.clinicId) {
            commandService.declineProposal(
                DeclineAppointmentProposalCommand(
                    context = commandContext(actor, access.tenantGroupId, access.clinicId, idempotencyKey, "decline", appointmentId, proposalId, expectedVersion, request),
                    appointmentId = appointmentId,
                    proposalId = proposalId,
                    expectedVersion = expectedVersion,
                    expectedProposalHash = persisted.proposal.proposalHash,
                    consent = declinedConsent(actor, appointmentId, proposalId, request.reasonCode),
                ),
            ).let { commitmentResponse(access.tenantGroupId, access.clinicId, it.commitment, it.proposal) }
        }
    }

    override fun directConfirm(
        actor: ActorContext,
        appointmentId: Long,
        expectedVersion: Long,
        idempotencyKey: String,
        request: DirectConfirmRequest,
    ): AppointmentCommitmentResponse {
        val access = accessResolver.requireAppointmentAccess(actor, appointmentId)
        accessResolver.requireConsentAuthority(actor, request.evidence.evidenceAuthority)
        val persisted = persistedProposalInput(access.clinicId, appointmentId, request.proposalId)
        val policySnapshot =
            policySnapshotResolver.resolvePersisted(
                tenantGroupId = access.tenantGroupId,
                clinicId = access.clinicId,
                snapshotId = persisted.input.policySnapshotId,
            )
        val booking = bookingCommitment(policySnapshot)
        val consentRequirement = booking.adminConsentEvidence
        val catalogPayloadHash =
            catalogPayloadHashByProposal(
                tenantGroupId = access.tenantGroupId,
                clinicId = access.clinicId,
                proposalId = request.proposalId,
            )
        val verifiedConsent =
            verifyConsentEvidence(
                request = request.evidence,
                tenantGroupId = access.tenantGroupId,
                clinicId = access.clinicId,
                patientReferenceFingerprint = access.patientReferenceFingerprint,
                appointmentPlanId = null,
                appointmentId = appointmentId,
                proposalId = request.proposalId,
                proposalHash = persisted.proposal.proposalHash,
                policySnapshot = policySnapshot,
                decision = ConsentDecisionType.ACCEPTED,
                allowedEvidenceTypes = consentRequirement.allowedEvidenceTypes,
                maximumEvidenceAge = consentRequirement.maximumAge,
                termsHashRequired = consentRequirement.termsHashRequired,
                requiredTermsHash =
                    requiredConsentTermsHash(
                        consentRequirement.termsHashRequired,
                        catalogPayloadHash,
                    ),
            )
        val policyDecision = directPolicyDecision(policySnapshot, catalogPayloadHash)
        val consent = acceptedConsent(actor, verifiedConsent)
        return runCommand(actor, access.tenantGroupId, access.clinicId) {
            commandService.approveCustomerProposal(
                ConfirmAppointmentProposalCommand(
                    context =
                        commandContext(
                            actor,
                            access.tenantGroupId,
                            access.clinicId,
                            idempotencyKey,
                            "direct-confirm",
                            appointmentId,
                            expectedVersion,
                            request,
                            policyDecision.policySnapshotHash,
                            verifiedConsent.evidenceHash,
                        ),
                    appointmentId = appointmentId,
                    proposalId = request.proposalId,
                    expectedVersion = expectedVersion,
                    proposal = persisted.input,
                    expectedProposalHash = persisted.proposal.proposalHash,
                    projectionTarget = planningResolver.resolveProjectionTarget(access.clinicId, persisted.input),
                    consent = consent,
                    policyDecision = policyDecision,
                ),
            ).let { commitmentResponse(access.tenantGroupId, access.clinicId, it.commitment, it.proposal) }
        }
    }

    override fun createChangeProposal(
        actor: ActorContext,
        appointmentId: Long,
        expectedVersion: Long,
        idempotencyKey: String,
        request: CreateChangeProposalRequest,
    ): AppointmentProposalResponse {
        val access = accessResolver.requireAppointmentAccess(actor, appointmentId)
        val current = readCommitmentWithCurrentProposal(appointmentId)
        val planRevision = activePlanRevisionByAppointmentProposal(current.proposal.id)
        val proposal = proposalInput(
            tenantGroupId = access.tenantGroupId,
            clinicId = access.clinicId,
            revision = current.proposal.revision + 1L,
            startsAt = request.preferredStartAt,
            endsAt = request.preferredEndAt,
            planRevision = planRevision,
            supersedesProposalId = current.commitment.confirmedProposalId,
            policySnapshot = null,
        )
        return runCommand(actor, access.tenantGroupId, access.clinicId) {
            commandService.proposeChange(
                ChangeAppointmentProposalCommand(
                    context = commandContext(actor, access.tenantGroupId, access.clinicId, idempotencyKey, "change", appointmentId, expectedVersion, request),
                    appointmentId = appointmentId,
                    expectedVersion = expectedVersion,
                    proposal = proposal,
                    expiresAt = proposalExpiry(proposal.startsAt),
                    representativeTreatmentName = representativeTreatmentName(planRevision),
                ),
            ).let { proposalResponse(access.tenantGroupId, access.clinicId, it.commitment, it.proposal) }
        }
    }

    override fun query(
        actor: ActorContext,
        appointmentId: Long,
    ): AppointmentCommitmentResponse {
        val access = accessResolver.requireAppointmentAccess(actor, appointmentId)
        val current = readCommitmentWithCurrentProposal(appointmentId)
        return commitmentResponse(
            access.tenantGroupId,
            access.clinicId,
            current.commitment,
            current.proposal,
        )
    }

    override fun expireProposal(
        actor: ActorContext,
        appointmentId: Long,
        proposalId: Long,
        expectedVersion: Long,
        idempotencyKey: String,
    ): AppointmentCommitmentResponse {
        val access = accessResolver.requireAppointmentAccess(actor, appointmentId)
        val persisted = persistedProposalInput(access.clinicId, appointmentId, proposalId)
        return runCommand(actor, access.tenantGroupId, access.clinicId) {
            commandService.expireProposal(
                ExpireAppointmentProposalCommand(
                    context =
                        commandContext(
                            actor,
                            access.tenantGroupId,
                            access.clinicId,
                            idempotencyKey,
                            "expire",
                            appointmentId,
                            proposalId,
                            expectedVersion,
                        ),
                    appointmentId = appointmentId,
                    proposalId = proposalId,
                    expectedVersion = expectedVersion,
                    expectedProposalHash = persisted.proposal.proposalHash,
                ),
            ).let { commitmentResponse(access.tenantGroupId, access.clinicId, it.commitment, it.proposal) }
        }
    }

    override fun cancelAppointment(
        actor: ActorContext,
        appointmentId: Long,
        expectedVersion: Long,
        idempotencyKey: String,
        request: CancelAppointmentRequest,
    ): AppointmentCommitmentResponse {
        requireCancellationActor(actor, request.reasonDetail)
        val access = accessResolver.requireAppointmentCancellationAccess(actor, appointmentId)
        val current = readCommitmentWithCurrentProposal(appointmentId)
        return runCancellationCommand(actor, access.tenantGroupId, access.clinicId) {
            commandService.cancelAppointment(
                CancelAppointmentCommand(
                    context =
                        commandContext(
                            actor,
                            access.tenantGroupId,
                            access.clinicId,
                            idempotencyKey,
                            "cancel",
                            appointmentId,
                            current.proposal.id,
                            expectedVersion,
                            CancellationReasonRegistry.canonicalHashHex(
                                request.reasonCode,
                                request.reasonDetail,
                            ),
                        ),
                    appointmentId = appointmentId,
                    proposalId = current.proposal.id,
                    expectedVersion = expectedVersion,
                    expectedProposalHash = current.proposal.proposalHash,
                    reasonCode = request.reasonCode,
                    reasonDetail = request.reasonDetail,
                ),
            ).let { commitmentResponse(access.tenantGroupId, access.clinicId, it.commitment, it.proposal) }
        }
    }

    private fun requireCancellationActor(
        actor: ActorContext,
        reasonDetail: String?,
    ) {
        when (actor.actorType) {
            ActorType.ADMIN,
            ActorType.STAFF,
            -> Unit

            ActorType.PATIENT -> {
                if (reasonDetail != null) {
                    throw AppointmentCommitmentApiException(AppointmentCommitmentApiError.PAYLOAD_INVALID)
                }
            }

            ActorType.DOCTOR,
            ActorType.SYSTEM,
            -> throw AppointmentCommitmentApiException(AppointmentCommitmentApiError.SCOPE_FORBIDDEN)
        }
    }

    private fun proposalResponse(
        tenantGroupId: Long,
        clinicId: Long,
        commitment: AppointmentCommitmentRecord,
        proposal: AppointmentProposalRecord,
    ): AppointmentProposalResponse =
        commitment.toProposalResponse(
            proposal,
            policySnapshotResolver.resolvePersisted(tenantGroupId, clinicId, proposal.policySnapshotId),
            appointmentDisplay(tenantGroupId, clinicId, commitment.appointmentId, proposal.id),
        )

    private fun commitmentResponse(
        tenantGroupId: Long,
        clinicId: Long,
        commitment: AppointmentCommitmentRecord,
        proposal: AppointmentProposalRecord,
    ): AppointmentCommitmentResponse =
        commitment.toResponse(
            proposal,
            policySnapshotResolver.resolvePersisted(tenantGroupId, clinicId, proposal.policySnapshotId),
            appointmentDisplay(tenantGroupId, clinicId, commitment.appointmentId, proposal.id),
        )

    /**
     * 상품·회차 metadata는 기존 immutable Plan/Plan revision snapshot에서 조립하고,
     * clinic 이름은 tenant·clinic ownership을 확인한 현재 canonical row에서 읽습니다.
     * raw patient scope나 구매 원문은 읽지 않으며, 항목이 여러 회차를 함께 묶은 경우에는
     * 잘못된 회차를 표시하지 않도록 단일 sequence일 때만 sessionNumber를 채웁니다.
     */
    private fun appointmentDisplay(
        tenantGroupId: Long,
        clinicId: Long,
        appointmentId: Long,
        proposalId: Long,
    ): AppointmentCommitmentDisplay = transaction(database) {
        val clinicDisplayName = Clinics
            .select(Clinics.name)
            .where {
                (Clinics.id eq clinicId) and
                    (Clinics.tenantGroupId eq tenantGroupId)
            }
            .singleOrNull()
            ?.get(Clinics.name)

        val itemRows = AppointmentItems
            .selectAll()
            .where {
                (AppointmentItems.appointmentId eq appointmentId) and
                    (AppointmentItems.proposalId eq proposalId)
            }
            .toList()
        val revisionId = itemRows
            .map { it[AppointmentItems.planRevisionId].value }
            .distinct()
            .singleOrNull()
        val revision = revisionId?.let(planRevisionRepository::findById)
        val productName = revision?.let {
            AppointmentPlans
                .select(AppointmentPlans.productName)
                .where {
                    (AppointmentPlans.id eq it.revision.planId) and
                        (AppointmentPlans.tenantGroupId eq tenantGroupId) and
                        (AppointmentPlans.clinicId eq clinicId)
                }
                .singleOrNull()
                ?.get(AppointmentPlans.productName)
        }
        val sequenceByTreatment = revision
            ?.treatments
            ?.associate { it.treatmentKey to it.sequence }
            .orEmpty()
        val sequences = itemRows
            .mapNotNull { sequenceByTreatment[it[AppointmentItems.treatmentKey]] }
            .distinct()
        AppointmentCommitmentDisplay(
                productName = productName,
            sessionNumber = sequences.singleOrNull()?.takeIf { it > 0 },
            totalSessions = revision?.treatments?.maxOfOrNull { it.sequence }?.takeIf { it > 0 },
            clinicDisplayName = clinicDisplayName,
        )
    }

    private fun requireNewCommitmentWrite(clinicId: Long) {
        if (!properties.isWriteEnabled(clinicId)) {
            throw AppointmentCommitmentApiException(AppointmentCommitmentApiError.INGRESS_DISABLED)
        }
    }

    private fun activeRevision(planId: Long): PersistedAppointmentPlanRevisionAggregateRecord =
        transaction(database) {
            planRevisionRepository.findActive(planId)
        } ?: throw AppointmentCommitmentApiException(AppointmentCommitmentApiError.COMMITMENT_NOT_FOUND)

    /**
     * 기존 proposal의 Plan을 찾되 새 변경안은 그 Plan의 현재 활성 revision으로 계산합니다.
     *
     * proposal item에 고정된 과거 revision을 그대로 반환하면 상품 version 전환, 완료,
     * 부분 이행, 환불 event가 만든 최신 미래 작업을 되돌릴 수 있습니다.
     */
    private fun activePlanRevisionByAppointmentProposal(
        proposalId: Long,
    ): PersistedAppointmentPlanRevisionAggregateRecord =
        transaction(database) {
            val historicalRevisionId = AppointmentItems
                .select(AppointmentItems.planRevisionId)
                .where { AppointmentItems.proposalId eq proposalId }
                .limit(1)
                .singleOrNull()
                ?.get(AppointmentItems.planRevisionId)
                ?.value
            historicalRevisionId
                ?.let(planRevisionRepository::findById)
                ?.revision
                ?.planId
                ?.let(planRevisionRepository::findActive)
        } ?: throw AppointmentCommitmentApiException(AppointmentCommitmentApiError.COMMITMENT_NOT_FOUND)

    /**
     * proposal item provenance로 구매 당시 상품 payload hash를 정확한 scope 안에서 찾습니다.
     *
     * 약관 hash 검증은 현재 카탈로그나 외부 검증 결과가 아니라 proposal이 참조한 Plan의
     * 불변 구매 snapshot을 사용해야 합니다.
     */
    private fun catalogPayloadHashByProposal(
        tenantGroupId: Long,
        clinicId: Long,
        proposalId: Long,
    ): String =
        transaction(database) {
            val planId =
                AppointmentItems
                    .select(AppointmentItems.planRevisionId)
                    .where { AppointmentItems.proposalId eq proposalId }
                    .limit(1)
                    .singleOrNull()
                    ?.get(AppointmentItems.planRevisionId)
                    ?.value
                    ?.let(planRevisionRepository::findById)
                    ?.revision
                    ?.planId
            planId
                ?.let {
                    planRepository.findPlanByIdAndTenantClinic(
                        id = it,
                        tenantGroupId = tenantGroupId,
                        clinicId = clinicId,
                    )
                }
                ?.catalogPayloadHash
        } ?: throw AppointmentCommitmentApiException(AppointmentCommitmentApiError.COMMITMENT_NOT_FOUND)

    private fun persistedProposalInput(
        clinicId: Long,
        appointmentId: Long,
        proposalId: Long,
    ): PersistedProposalInput {
        val current = readCommitmentWithProposal(appointmentId, proposalId)
        val items = appointmentItems(proposalId)
        val input = VisitProposalInput(
            revision = current.proposal.revision,
            startsAt = current.proposal.proposedStartAt,
            endsAt = current.proposal.proposedEndAt,
            items = items,
            resourceRequests = storedProposalResourceRequests(clinicId, current.proposal, items),
            policySnapshotId = current.proposal.policySnapshotId,
            supersedesProposalId = current.proposal.supersedesProposalId,
        )
        return PersistedProposalInput(current.proposal, input)
    }

    private fun initialProposalInput(
        access: ResolvedAppointmentPlanAccess,
        revision: Long,
        startsAt: Instant,
        endsAt: Instant,
        planRevision: PersistedAppointmentPlanRevisionAggregateRecord,
        supersedesProposalId: Long?,
        policySnapshot: CurrentPolicySnapshot?,
    ): VisitProposalInput =
        proposalInput(
            tenantGroupId = access.tenantGroupId,
            clinicId = access.clinicId,
            revision = revision,
            startsAt = startsAt,
            endsAt = endsAt,
            planRevision = planRevision,
            supersedesProposalId = supersedesProposalId,
            policySnapshot = policySnapshot,
        )

    private fun proposalInput(
        tenantGroupId: Long,
        clinicId: Long,
        revision: Long,
        startsAt: Instant,
        endsAt: Instant,
        planRevision: PersistedAppointmentPlanRevisionAggregateRecord,
        supersedesProposalId: Long?,
        policySnapshot: CurrentPolicySnapshot?,
    ): VisitProposalInput {
        val resolvedPolicySnapshot = policySnapshot ?: currentPolicySnapshot(tenantGroupId, clinicId, startsAt)
        val candidateSlots =
            planningResolver.resolveCandidateSlots(
                AppointmentCommitmentCandidateSlotRequest(
                    tenantGroupId = tenantGroupId,
                    clinicId = clinicId,
                    planRevisionId = planRevision.revision.id,
                    preferredStartAt = startsAt,
                    preferredEndAt = endsAt,
                ),
            )
        val result =
            proposalService.generate(
                AppointmentProposalRequest(
                    tenantGroupId = tenantGroupId,
                    clinicId = clinicId,
                    appointmentIdSeed = planRevision.revision.planId,
                    proposalRevision = revision,
                    planRevisionId = planRevision.revision.id,
                    treatments = planRevision.treatments,
                    dependencies = planRevision.dependencies,
                    groupingConstraints = planRevision.groupingConstraints,
                    bookingPreference = exactPreference(startsAt),
                    purchasedAt = Instant.now(clock),
                    initialBookingRule = null,
                    completedAtByTreatmentKey = emptyMap(),
                    attemptNumberByTreatmentKey = emptyMap(),
                    changedTreatmentKeys = emptySet(),
                    confirmedTreatmentKeys = emptySet(),
                    candidateSlots = candidateSlots,
                    searchDays = properties.ceiling.searchDays,
                    policySnapshot = resolvedPolicySnapshot,
                ),
            )
        val generated = result.proposals.firstOrNull {
            !it.proposal.endsAt.isAfter(endsAt)
        } ?: throw AppointmentCommitmentApiException(
            AppointmentCommitmentApiError.RESOURCE_CONFLICT,
            "no compatible commitment proposal slot",
        )
        val proposal = generated.proposal
        return VisitProposalInput(
            revision = proposal.revision,
            startsAt = proposal.startsAt,
            endsAt = proposal.endsAt,
            items = proposal.items,
            resourceRequests = generated.resourceRequests,
            policySnapshotId = proposal.policySnapshotId,
            supersedesProposalId = supersedesProposalId,
        )
    }

    private fun appointmentItems(proposalId: Long): List<AppointmentItemDraft> =
        transaction(database) {
            AppointmentItems
                .selectAll()
                .where { AppointmentItems.proposalId eq proposalId }
                .orderBy(AppointmentItems.id, SortOrder.ASC)
                .map { row ->
                    AppointmentItemDraft(
                        planRevisionId = row[AppointmentItems.planRevisionId].value,
                        treatmentKey = row[AppointmentItems.treatmentKey],
                        representativeTreatmentName = row[AppointmentItems.representativeTreatmentName],
                        detailedTreatmentCodes = decodeStringList(row[AppointmentItems.detailedTreatmentCodesPayload]),
                        preparationMinutes = row[AppointmentItems.preparationMinutes],
                        treatmentMinutes = row[AppointmentItems.treatmentMinutes],
                        recoveryMinutes = row[AppointmentItems.recoveryMinutes],
                        attemptNumber = row[AppointmentItems.attemptNumber],
                    )
                }
        }

    private fun storedProposalResourceRequests(
        clinicId: Long,
        proposal: AppointmentProposalRecord,
        items: List<AppointmentItemDraft>,
    ): List<ResourceAllocationRequest> =
        planningResolver.resolveStoredProposalResourceRequests(clinicId, proposal, items)

    /**
     * planning adapter가 제공한 표시 정보에 회원 디렉터리에서 검증한 불투명 ID를 결합한다.
     *
     * adapter가 임의의 회원 ID를 반환하더라도 사용하지 않으며, 이름과 전화번호로 회원을
     * 추정하지 않는다.
     */
    private fun resolveIdentity(
        actor: ActorContext,
        access: ResolvedAppointmentPlanAccess,
    ): AppointmentVisitIdentityDraft {
        val verifiedMemberId = appointmentMemberResolver.resolvePlan(actor, access)
        val planned = planningResolver.resolveIdentity(actor, access)
        return AppointmentVisitIdentityDraft(
            patientName = planned.patientName,
            patientPhone = planned.patientPhone,
            memberId = verifiedMemberId,
            patientReferenceFingerprint = planned.patientReferenceFingerprint,
        )
    }

    private fun currentPolicySnapshot(
        access: ResolvedAppointmentPlanAccess,
        serviceAt: Instant,
    ): CurrentPolicySnapshot = currentPolicySnapshot(access.tenantGroupId, access.clinicId, serviceAt)

    private fun currentPolicySnapshot(
        tenantGroupId: Long,
        clinicId: Long,
        serviceAt: Instant,
    ): CurrentPolicySnapshot {
        val policySnapshot = policySnapshotResolver.resolve(
            tenantGroupId = tenantGroupId,
            clinicId = clinicId,
            decisionAt = Instant.now(clock),
            serviceAt = serviceAt,
        )
        if (
            policySnapshot.policy.tenantGroupId != tenantGroupId ||
            policySnapshot.policy.clinicId != clinicId
        ) {
            throw AppointmentCommitmentApiException(
                AppointmentCommitmentApiError.SCOPE_FORBIDDEN,
                "effective policy scope does not match appointment scope",
            )
        }
        return policySnapshot
    }

    private fun bookingCommitment(policySnapshot: CurrentPolicySnapshot) =
        policySnapshot.policy.payload.bookingCommitment
            ?: throw AppointmentCommitmentApiException(
                AppointmentCommitmentApiError.DIRECT_CONFIRM_NOT_ALLOWED,
                "effective booking commitment policy is unavailable",
            )

    private fun bookingCommitment(policySnapshot: PersistedPolicySnapshotReference) =
        policySnapshot.payload.bookingCommitment
            ?: throw AppointmentCommitmentApiException(
                AppointmentCommitmentApiError.DIRECT_CONFIRM_NOT_ALLOWED,
                "persisted booking commitment policy is unavailable",
            )

    private fun directPolicyDecision(
        policySnapshot: CurrentPolicySnapshot,
        catalogPayloadHash: String,
    ): DirectConfirmationPolicyDecision {
        val booking = bookingCommitment(policySnapshot)
        val consentRequirement = booking.adminConsentEvidence
        if (
            booking.adminBookingMode !=
            io.bluetape4k.clinic.appointment.model.policy.AdminBookingMode.DIRECT_CONFIRM_WITH_CONSENT_EVIDENCE
        ) {
            throw AppointmentCommitmentApiException(
                AppointmentCommitmentApiError.DIRECT_CONFIRM_NOT_ALLOWED,
                "effective booking policy does not allow direct confirmation",
            )
        }
        return DirectConfirmationPolicyDecision(
            policySnapshotId = policySnapshot.id,
            policySnapshotHash = policySnapshot.policy.snapshotHash,
            adminBookingMode = booking.adminBookingMode,
            allowedEvidenceTypes = consentRequirement.allowedEvidenceTypes,
            maximumEvidenceAge = consentRequirement.maximumAge,
            termsHashRequired = consentRequirement.termsHashRequired,
            requiredTermsHash =
                requiredConsentTermsHash(
                    consentRequirement.termsHashRequired,
                    catalogPayloadHash,
                ),
        )
    }

    private fun directPolicyDecision(
        policySnapshot: PersistedPolicySnapshotReference,
        catalogPayloadHash: String,
    ): DirectConfirmationPolicyDecision {
        val booking = bookingCommitment(policySnapshot)
        val consentRequirement = booking.adminConsentEvidence
        if (
            booking.adminBookingMode !=
            io.bluetape4k.clinic.appointment.model.policy.AdminBookingMode.DIRECT_CONFIRM_WITH_CONSENT_EVIDENCE
        ) {
            throw AppointmentCommitmentApiException(
                AppointmentCommitmentApiError.DIRECT_CONFIRM_NOT_ALLOWED,
                "persisted booking policy does not allow direct confirmation",
            )
        }
        return DirectConfirmationPolicyDecision(
            policySnapshotId = policySnapshot.id,
            policySnapshotHash = policySnapshot.snapshotHash,
            adminBookingMode = booking.adminBookingMode,
            allowedEvidenceTypes = consentRequirement.allowedEvidenceTypes,
            maximumEvidenceAge = consentRequirement.maximumAge,
            termsHashRequired = consentRequirement.termsHashRequired,
            requiredTermsHash =
                requiredConsentTermsHash(
                    consentRequirement.termsHashRequired,
                    catalogPayloadHash,
                ),
        )
    }

    /**
     * 약관 hash가 필요한 직접 확정은 구매 당시 고정한 상품 payload hash와 대조합니다.
     *
     * 외부 검증기가 반환한 값을 다시 기대값으로 사용하지 않습니다. 상품서비스가 발행한
     * canonical payload hash를 Plan에 보존하고 있으므로 새 카탈로그 버전이나 임의의
     * non-null hash가 과거 구매·proposal의 동의 범위를 대신할 수 없습니다.
     */
    private fun requiredConsentTermsHash(
        termsHashRequired: Boolean,
        catalogPayloadHash: String,
    ): String? =
        if (termsHashRequired) {
            require(catalogPayloadHash.matches(SHA256)) {
                "catalogPayloadHash must be a lowercase SHA-256 value"
            }
            catalogPayloadHash
        } else {
            null
        }

    private fun exactPreference(startsAt: Instant): BookingPreferenceSnapshot =
        BookingPreferenceSnapshot.ExactDateTime(
            originalLocalDateTime = startsAt.atOffset(ZoneOffset.UTC).toLocalDateTime(),
            originalOffset = ZoneOffset.UTC,
            zoneId = ZoneOffset.UTC,
            normalizedInstant = startsAt,
        )

    private fun acceptedConsent(
        actor: ActorContext,
        evidence: VerifiedAppointmentCommitmentConsentEvidence,
    ): ProposalConsentEvidence =
        consent(actor, evidence, ConsentDecisionType.ACCEPTED)

    private fun declinedConsent(
        actor: ActorContext,
        appointmentId: Long,
        proposalId: Long,
        reasonCode: String,
    ): ProposalConsentEvidence =
        ProposalConsentEvidence(
            decision = ConsentDecisionType.DECLINED,
            evidenceType = EVIDENCE_TYPE,
            evidenceAuthority = "decline:${actor.actorType.name.lowercase()}",
            evidenceId = appointmentDeclineEvidenceId(appointmentId, proposalId, reasonCode),
            evidenceHash = hash("decline|${actor.actorScopeText()}|$appointmentId|$proposalId|$reasonCode"),
            decidedAt = Instant.now(clock),
            termsHash = null,
            actorRef = actor.actorAuditRef(),
        )

    private fun consent(
        actor: ActorContext,
        evidence: VerifiedAppointmentCommitmentConsentEvidence,
        decision: ConsentDecisionType,
    ): ProposalConsentEvidence =
        ProposalConsentEvidence(
            decision = decision,
            evidenceType = evidence.evidenceType,
            evidenceAuthority = evidence.evidenceAuthority,
            evidenceId = evidence.evidenceId,
            evidenceHash = evidence.evidenceHash,
            decidedAt = evidence.decidedAt,
            termsHash = evidence.termsHash,
            actorRef = actor.actorAuditRef(),
        )

    private fun verifyConsentEvidence(
        request: ConsentEvidenceRequest,
        tenantGroupId: Long,
        clinicId: Long,
        patientReferenceFingerprint: String,
        appointmentPlanId: Long?,
        appointmentId: Long?,
        proposalId: Long?,
        proposalHash: String,
        policySnapshot: CurrentPolicySnapshot,
        decision: ConsentDecisionType,
        allowedEvidenceTypes: Set<String>? = null,
        maximumEvidenceAge: Duration? = null,
        termsHashRequired: Boolean = false,
        requiredTermsHash: String? = null,
    ): VerifiedAppointmentCommitmentConsentEvidence =
        verifyConsentEvidenceReference(
            request = request,
            tenantGroupId = tenantGroupId,
            clinicId = clinicId,
            patientReferenceFingerprint = patientReferenceFingerprint,
            appointmentPlanId = appointmentPlanId,
            appointmentId = appointmentId,
            proposalId = proposalId,
            proposalHash = proposalHash,
            policySnapshotId = policySnapshot.id,
            policySnapshotHash = policySnapshot.policy.snapshotHash,
            decision = decision,
            allowedEvidenceTypes = allowedEvidenceTypes,
            maximumEvidenceAge = maximumEvidenceAge,
            termsHashRequired = termsHashRequired,
            requiredTermsHash = requiredTermsHash,
        )

    private fun verifyConsentEvidence(
        request: ConsentEvidenceRequest,
        tenantGroupId: Long,
        clinicId: Long,
        patientReferenceFingerprint: String,
        appointmentPlanId: Long?,
        appointmentId: Long?,
        proposalId: Long?,
        proposalHash: String,
        policySnapshot: PersistedPolicySnapshotReference,
        decision: ConsentDecisionType,
        allowedEvidenceTypes: Set<String>? = null,
        maximumEvidenceAge: Duration? = null,
        termsHashRequired: Boolean = false,
        requiredTermsHash: String? = null,
    ): VerifiedAppointmentCommitmentConsentEvidence =
        verifyConsentEvidenceReference(
            request = request,
            tenantGroupId = tenantGroupId,
            clinicId = clinicId,
            patientReferenceFingerprint = patientReferenceFingerprint,
            appointmentPlanId = appointmentPlanId,
            appointmentId = appointmentId,
            proposalId = proposalId,
            proposalHash = proposalHash,
            policySnapshotId = policySnapshot.id,
            policySnapshotHash = policySnapshot.snapshotHash,
            decision = decision,
            allowedEvidenceTypes = allowedEvidenceTypes,
            maximumEvidenceAge = maximumEvidenceAge,
            termsHashRequired = termsHashRequired,
            requiredTermsHash = requiredTermsHash,
        )

    private fun verifyConsentEvidenceReference(
        request: ConsentEvidenceRequest,
        tenantGroupId: Long,
        clinicId: Long,
        patientReferenceFingerprint: String,
        appointmentPlanId: Long?,
        appointmentId: Long?,
        proposalId: Long?,
        proposalHash: String,
        policySnapshotId: Long,
        policySnapshotHash: String,
        decision: ConsentDecisionType,
        allowedEvidenceTypes: Set<String>? = null,
        maximumEvidenceAge: Duration? = null,
        termsHashRequired: Boolean = false,
        requiredTermsHash: String? = null,
    ): VerifiedAppointmentCommitmentConsentEvidence {
        val verificationRequest =
            AppointmentCommitmentConsentEvidenceVerificationRequest(
                evidence = request,
                tenantGroupId = tenantGroupId,
                clinicId = clinicId,
                patientReferenceFingerprint = patientReferenceFingerprint,
                appointmentPlanId = appointmentPlanId,
                appointmentId = appointmentId,
                proposalId = proposalId,
                proposalHash = proposalHash,
                policySnapshotId = policySnapshotId,
                policySnapshotHash = policySnapshotHash,
                decision = decision,
                allowedEvidenceTypes = allowedEvidenceTypes,
                maximumEvidenceAge = maximumEvidenceAge,
                termsHashRequired = termsHashRequired,
                requiredTermsHash = requiredTermsHash,
                verifiedAt = Instant.now(clock),
            )
        val verified = consentEvidenceVerifier.verify(verificationRequest)
        requireConsentMatch(verificationRequest, verified)
        return verified
    }

    private fun requireConsentMatch(
        expected: AppointmentCommitmentConsentEvidenceVerificationRequest,
        actual: VerifiedAppointmentCommitmentConsentEvidence,
    ) {
        val age = Duration.between(actual.decidedAt, expected.verifiedAt)
        val matches =
            constantTimeEquals(expected.evidence.evidenceAuthority, actual.evidenceAuthority) &&
                constantTimeEquals(expected.evidence.evidenceId, actual.evidenceId) &&
                expected.tenantGroupId == actual.tenantGroupId &&
                expected.clinicId == actual.clinicId &&
                constantTimeEquals(expected.patientReferenceFingerprint, actual.patientReferenceFingerprint) &&
                expected.appointmentPlanId == actual.appointmentPlanId &&
                expected.appointmentId == actual.appointmentId &&
                expected.proposalId == actual.proposalId &&
                constantTimeEquals(expected.proposalHash, actual.proposalHash) &&
                expected.policySnapshotId == actual.policySnapshotId &&
                constantTimeEquals(expected.policySnapshotHash, actual.policySnapshotHash) &&
                actual.evidenceHash.matches(SHA256) &&
                (actual.termsHash == null || actual.termsHash.matches(SHA256)) &&
                (expected.allowedEvidenceTypes == null || actual.evidenceType in expected.allowedEvidenceTypes) &&
                !age.isNegative &&
                (expected.maximumEvidenceAge == null || age <= expected.maximumEvidenceAge) &&
                (!expected.termsHashRequired || actual.termsHash != null) &&
                (
                    expected.requiredTermsHash == null ||
                        actual.termsHash?.let {
                            constantTimeEquals(expected.requiredTermsHash, it)
                        } == true
                    )
        if (!matches) {
            throw AppointmentCommitmentApiException(
                AppointmentCommitmentApiError.CONSENT_REQUIRED,
                "consent evidence does not match the appointment commitment decision",
            )
        }
    }

    /**
     * DB 식별자 생성 전에도 영속 proposal과 동일한 동의 대상 hash를 계산합니다.
     *
     * [ProposalHasher]는 생성 ID를 의도적으로 제외하므로 placeholder 값은 hash 결과에
     * 영향을 주지 않습니다.
     */
    private fun initialProposalConsentHash(proposal: VisitProposalInput): String =
        ProposalHasher.hash(proposal.toDraft(CONSENT_HASH_PLACEHOLDER_APPOINTMENT_ID))

    private fun commandContext(
        actor: ActorContext,
        tenantGroupId: Long,
        clinicId: Long,
        idempotencyKey: String,
        operation: String,
        vararg parts: Any?,
    ): CommitmentCommandContext =
        CommitmentCommandContext(
            tenantGroupId = tenantGroupId,
            clinicId = clinicId,
            actorScopeHash = hash(actor.actorScopeText()),
            actorAuditRef = actor.actorAuditRef(),
            actorRole = actor.actorType.name,
            idempotencyKeyHash = idempotencyKeyHasher.hash(idempotencyKey),
            commandHash = hash(listOf(operation, actor.actorScopeText(), *parts).joinToString("|")),
            correlationId = actor.correlationId,
        )

    private fun readCommitmentWithCurrentProposal(appointmentId: Long): CommitmentWithProposal =
        transaction(database) {
            val commitment = commitmentRepository.findByAppointmentId(appointmentId)
            commitment?.let {
                val proposalId = it.confirmedProposalId ?: latestProposalId(it.id)
                proposalId?.let { currentProposalId ->
                    commitmentRepository.findProposal(it.id, currentProposalId)
                }?.let { proposal -> CommitmentWithProposal(it, proposal) }
            }
        } ?: throw AppointmentCommitmentApiException(AppointmentCommitmentApiError.COMMITMENT_NOT_FOUND)

    private fun readCommitmentWithProposal(
        appointmentId: Long,
        proposalId: Long,
    ): CommitmentWithProposal =
        transaction(database) {
            val commitment = commitmentRepository.findByAppointmentId(appointmentId)
            commitment?.let {
                commitmentRepository.findProposal(it.id, proposalId)
                    ?.let { proposal -> CommitmentWithProposal(commitment, proposal) }
            }
        } ?: throw AppointmentCommitmentApiException(AppointmentCommitmentApiError.COMMITMENT_NOT_FOUND)

    private fun latestProposalId(commitmentId: Long): Long? =
        AppointmentProposals
            .select(AppointmentProposals.id)
            .where { AppointmentProposals.commitmentId eq commitmentId }
            .orderBy(AppointmentProposals.revision, SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.get(AppointmentProposals.id)
            ?.value

    private fun representativeTreatmentName(planRevision: PersistedAppointmentPlanRevisionAggregateRecord): String =
        planRevision.treatments.firstOrNull()?.representativeTreatmentName
            ?: throw AppointmentCommitmentApiException(AppointmentCommitmentApiError.COMMITMENT_NOT_FOUND)

    private fun proposalExpiry(startsAt: Instant): Instant =
        Instant.now(clock).plus(properties.proposalTtl).coerceAtMost(startsAt)

    private fun <T> runCommand(
        actor: ActorContext,
        tenantGroupId: Long,
        clinicId: Long,
        latencyRecorder: (String, String, CommitmentMetricResult, Duration) -> Unit =
            { tenant, clinic, result, latency ->
                metrics.recordProposalLatency(tenant, clinic, result, latency)
            },
        block: () -> T,
    ): T {
        val startedAt = Instant.now(clock)
        try {
            return block().also {
                recordMetricSafely {
                    latencyRecorder(
                        tenantTag(actor, tenantGroupId),
                        clinicTag(clinicId),
                        CommitmentMetricResult.SUCCESS,
                        Duration.between(startedAt, Instant.now(clock)),
                    )
                }
                log.info { "Appointment commitment application command completed" }
            }
        } catch (exception: AppointmentCommitmentCommandException) {
            val result =
                if (exception.code == AppointmentCommitmentCommandError.RESOURCE_CONFLICT) {
                    recordMetricSafely {
                        metrics.recordAllocationConflict(
                            tenant = tenantTag(actor, tenantGroupId),
                            clinic = clinicTag(clinicId),
                            reason = CommitmentConflictReason.OVERLAP,
                        )
                    }
                    CommitmentMetricResult.REJECTED
                } else {
                    CommitmentMetricResult.REJECTED
                }
            recordMetricSafely {
                latencyRecorder(
                    tenantTag(actor, tenantGroupId),
                    clinicTag(clinicId),
                    result,
                    Duration.between(startedAt, Instant.now(clock)),
                )
            }
            throw exception.toApiException()
        }
    }

    private fun <T> runCancellationCommand(
        actor: ActorContext,
        tenantGroupId: Long,
        clinicId: Long,
        block: () -> T,
    ): T =
        runCommand(
            actor = actor,
            tenantGroupId = tenantGroupId,
            clinicId = clinicId,
            latencyRecorder = { tenant, clinic, result, latency ->
                metrics.recordCancellationLatency(tenant, clinic, result, latency)
            },
            block = block,
        )

    /**
     * 관측 실패가 이미 결정된 예약 command 결과를 덮어쓰지 않도록 격리합니다.
     *
     * metric 이름·scope 값·원 예외 메시지는 기록하지 않아 식별자와 registry 내부정보가
     * 운영 로그로 유출되지 않게 합니다.
     */
    private inline fun recordMetricSafely(record: () -> Unit) {
        try {
            record()
        } catch (failure: Exception) {
            log.error(failure) { "Appointment commitment metric recording failed" }
        }
    }

    private fun decodeStringList(payload: String): List<String> =
        LIST_MAPPER.readValue(payload, Array<String>::class.java).toList()

    private fun ActorContext.actorScopeText(): String =
        listOf(actorType, actorId, allowedTenantCodes.sorted(), selectedClinicId, patientSubjectId.orEmpty())
            .joinToString("|")

    private fun ActorContext.actorAuditRef(): String =
        "${actorType.name.lowercase()}:${hash(actorId).take(24)}"

    private fun hash(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun constantTimeEquals(
        expected: String,
        actual: String,
    ): Boolean =
        MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.UTF_8),
            actual.toByteArray(StandardCharsets.UTF_8),
        )

    private fun tenantTag(
        actor: ActorContext,
        tenantGroupId: Long,
    ): String =
        actor.selectedTenantCode
            ?.takeIf(TenantCodeRules::isCanonical)
            ?: "tenant-$tenantGroupId"

    private fun clinicTag(clinicId: Long): String = "clinic-$clinicId"

    private data class CommitmentWithProposal(
        val commitment: AppointmentCommitmentRecord,
        val proposal: AppointmentProposalRecord,
    )

    private data class PersistedProposalInput(
        val proposal: AppointmentProposalRecord,
        val input: VisitProposalInput,
    )

    private companion object : KLogging() {
        const val INITIAL_PROPOSAL_REVISION = 1L
        const val CONSENT_HASH_PLACEHOLDER_APPOINTMENT_ID = 1L
        const val EVIDENCE_TYPE = "OPAQUE_REFERENCE"
        val SHA256 = Regex("[0-9a-f]{64}")
        val LIST_MAPPER: JsonMapper = JsonMapper.builder().build()
    }
}
