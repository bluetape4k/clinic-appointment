package io.bluetape4k.clinic.appointment.api.commitment

import io.bluetape4k.clinic.appointment.api.notification.AppointmentNotificationWriter
import io.bluetape4k.clinic.appointment.api.notification.CommitmentAppointmentNotification
import io.bluetape4k.clinic.appointment.api.notification.MemberResolution
import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxEvents
import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxStatus
import io.bluetape4k.clinic.appointment.event.notification.CancellationReasonCode
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitment
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitmentStatus
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentOrigin
import io.bluetape4k.clinic.appointment.model.commitment.ConsentDecision
import io.bluetape4k.clinic.appointment.model.commitment.ConsentDecisionType
import io.bluetape4k.clinic.appointment.model.commitment.ProposalConsentSubject
import io.bluetape4k.clinic.appointment.model.commitment.ResourceType
import io.bluetape4k.clinic.appointment.model.dto.AppointmentCommandResultRecord
import io.bluetape4k.clinic.appointment.model.dto.AppointmentCommitmentRecord
import io.bluetape4k.clinic.appointment.model.dto.AppointmentItemAppendScope
import io.bluetape4k.clinic.appointment.model.dto.AppointmentProposalRecord
import io.bluetape4k.clinic.appointment.model.dto.AppointmentRecord
import io.bluetape4k.clinic.appointment.model.dto.CommandClaimResult
import io.bluetape4k.clinic.appointment.model.dto.ConfirmedAppointmentProjection
import io.bluetape4k.clinic.appointment.model.dto.ResourceAllocationRequest
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.clinic.appointment.model.policy.AdminBookingMode
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityDecisionStamp
import io.bluetape4k.clinic.appointment.model.tables.AppointmentAuditEvents
import io.bluetape4k.clinic.appointment.repository.AppointmentCommandIdempotencyConflictException
import io.bluetape4k.clinic.appointment.repository.AppointmentCommandIdempotencyRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentCommitmentRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentItemRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentRepository
import io.bluetape4k.clinic.appointment.repository.LockedResourceAvailability
import io.bluetape4k.clinic.appointment.repository.ResourceAllocationConflictException
import io.bluetape4k.clinic.appointment.repository.ResourceAllocationRepository
import io.bluetape4k.clinic.appointment.service.ProposalHasher
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

/**
 * 방문 예약의 proposal·동의·자원 점유·확정 projection을 한 transaction으로 조정합니다.
 *
 * 이 서비스가 여는 Exposed transaction은 다음 순서를 보장합니다.
 *
 * 1. actor scope별 멱등 command를 선점합니다.
 * 2. proposal과 정확히 결합된 최신 고객 동의를 검증합니다.
 * 3. 변경 확정이면 새 allocation을 먼저 검증·생성합니다.
 * 4. commitment version CAS로 현재 확정 proposal을 교체합니다.
 * 5. CAS 성공 뒤에만 이전 proposal allocation을 해제하고 legacy projection을 갱신합니다.
 * 6. 감사 event, outbox event, 멱등 결과를 같은 transaction에 기록합니다.
 *
 * 이 순서 때문에 동의 거부, proposal 만료, 자원 충돌, version 충돌 중 어느 하나라도
 * 발생하면 새 allocation과 부수 효과가 rollback되고 기존 확정 예약은 그대로 유지됩니다.
 *
 * repository는 transaction을 열지 않습니다. 이 서비스 밖에서 아래 primitive를 부분적으로
 * 조합하면 확정 포인터와 allocation의 원자성이 깨질 수 있습니다.
 *
 * 재시도는 PostgreSQL serialization failure(`40001`)와 deadlock(`40P01`)에만 적용합니다.
 * 업무 version 충돌과 자원 충돌은 최신 caller 의사결정이 필요하므로 재시도하지 않습니다.
 *
 * @param database command transaction을 실행할 Exposed database입니다.
 * @param clock proposal 만료, 감사, allocation 해제 시각의 권위 있는 UTC clock입니다.
 * @param maxTransactionAttempts 최초 실행을 포함한 일시적 DB 오류 최대 시도 횟수입니다.
 * @param initialRetryDelayMillis 첫 재시도 전 대기 시간입니다. 이후 시도는 지수 backoff를 적용합니다.
 * @param retryDelay 재시도 backoff를 수행합니다. 테스트에서는 실제 대기 없이 주입할 수 있습니다.
 * @param retryJitterMillis 재시도 횟수별 jitter를 반환합니다. 기본 구현은 0~24ms입니다.
 * @param appointmentRepository commitment v2 방문 identity와 확정 projection 저장소입니다.
 * @param commitmentRepository commitment, proposal, 고객 동의 저장소입니다.
 * @param itemRepository proposal에 고정된 Plan-linked 세부 진료 스냅샷 저장소입니다.
 * @param allocationRepository 실제 자원 점유 검증과 교체 저장소입니다.
 * @param idempotencyRepository actor scope별 command 선점과 결과 저장소입니다.
 * @param notificationWriter commitment v2 알림 outbox를 같은 command transaction에 기록하는 writer입니다.
 */
internal class AppointmentCommitmentCommandService(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
    private val maxTransactionAttempts: Int = DEFAULT_MAX_TRANSACTION_ATTEMPTS,
    private val initialRetryDelayMillis: Long = DEFAULT_INITIAL_RETRY_DELAY_MILLIS,
    private val retryDelay: (Long) -> Unit = Thread::sleep,
    private val retryJitterMillis: (Int) -> Long = {
        ThreadLocalRandom.current().nextLong(MAX_JITTER_MILLIS + 1)
    },
    private val appointmentRepository: AppointmentRepository = AppointmentRepository(),
    private val commitmentRepository: AppointmentCommitmentRepository = AppointmentCommitmentRepository(),
    private val itemRepository: AppointmentItemRepository = AppointmentItemRepository(),
    private val allocationRepository: ResourceAllocationRepository = ResourceAllocationRepository(),
    private val idempotencyRepository: AppointmentCommandIdempotencyRepository =
        AppointmentCommandIdempotencyRepository(),
    private val notificationWriter: AppointmentNotificationWriter = NoopAppointmentNotificationWriter,
    private val bookingEligibilityGate: BookingEligibilityGate = BookingEligibilityGate.disabled(),
) {
    init {
        require(maxTransactionAttempts in 1..3) {
            "maxTransactionAttempts must be between 1 and 3"
        }
        require(initialRetryDelayMillis in 1..1_000) {
            "initialRetryDelayMillis must be between 1 and 1000"
        }
    }

    /**
     * 고객이 선택·동의한 첫 proposal을 관리자 승인 전 `PROPOSED` 가예약으로 생성합니다.
     *
     * 고객 동의는 proposal ID가 생긴 뒤 서비스가 ID/revision/hash에 결합해 append합니다.
     * 자원 점유와 legacy 확정 projection은 관리자 승인 command까지 만들지 않습니다.
     */
    fun requestCustomerAppointment(command: CustomerAppointmentRequestCommand): AppointmentCommitmentCommandResult =
        executeCommand(command.context, OPERATION_CUSTOMER_REQUEST) {
            val now = Instant.now(clock)
            requireClinicScope(command.context)
            val reliabilityStamp = bookingEligibilityGate.requireAllowed(command.context, command.identity.memberId)
            requireProposalActive(command.expiresAt, now)
            bookingEligibilityGate.requireFresh(command.context, command.identity.memberId, reliabilityStamp)
            val created =
                createInitialProposal(
                    context = command.context,
                    identity = command.identity,
                    proposalInput = command.proposal,
                    expiresAt = command.expiresAt,
                    representativeTreatmentName = command.representativeTreatmentName,
                    origin = AppointmentOrigin.PATIENT,
                    reliabilityStamp = reliabilityStamp,
                    status =
                        if (command.holdResources) {
                            AppointmentCommitmentStatus.HELD
                        } else {
                            AppointmentCommitmentStatus.PROPOSED
                        },
                )
            if (command.holdResources) {
                requireResourceItemReferences(created.proposal.id, command.proposal.resourceRequests)
                allocationRepository.createConfirmedAllocations(
                    tenantGroupId = command.context.tenantGroupId,
                    clinicId = command.context.clinicId,
                    proposalId = created.proposal.id,
                    replacingProposalId = null,
                    requests = command.proposal.resourceRequests,
                )
            }
            appendConsent(created.commitment, created.proposal, command.consent)
            writeDecision(
                context = command.context,
                eventType = EVENT_APPOINTMENT_REQUESTED,
                commitment = created.commitment,
                proposal = created.proposal,
                occurredAt = now,
            )
            notificationWriter.commitmentRequested(
                notificationInput(
                    context = command.context,
                    commitment = created.commitment,
                    proposal = created.proposal,
                    memberId = requireIdentityMemberId(command.identity),
                ),
            )
            persistCommandResult(command.context, created.commitment, created.proposal)
            AppointmentCommitmentCommandResult(
                commitment = created.commitment,
                proposal = created.proposal,
                idempotentReplay = false,
            )
        }

    /**
     * 고객 요청으로 생성된 첫 proposal을 병원 승인으로 확정합니다.
     *
     * command body의 proposal 입력, 영속 proposal, canonical hash, 고객 동의를 모두
     * 비교한 뒤 자원을 점유합니다. 고객 요청 당시 동의가 없거나 다른 proposal에 결합된
     * 동의만 있으면 fail-closed로 거부합니다.
     */
    fun approveCustomerProposal(command: ConfirmAppointmentProposalCommand): AppointmentCommitmentCommandResult =
        executeCommand(command.context, OPERATION_ADMIN_APPROVAL) {
            val now = Instant.now(clock)
            val commitment = requireCommitment(command.context, command.appointmentId)
            requireExpectedVersion(commitment, command.expectedVersion)
            requireInitialCustomerApproval(commitment)
            val reliabilityStamp = bookingEligibilityGate.requireAllowed(
                command.context,
                requireCommitmentMemberId(command.context, commitment.appointmentId),
            )
            val proposal =
                requireExactProposal(
                    commitment = commitment,
                    proposalId = command.proposalId,
                    proposalInput = command.proposal,
                    expectedProposalHash = command.expectedProposalHash,
                    now = now,
                )
            if (proposal.supersedesProposalId != null) {
                reject(
                    AppointmentCommitmentCommandError.INVALID_TRANSITION,
                    "admin approval only confirms the initial customer proposal",
                )
            }
            command.consent?.let { appendConsent(commitment, proposal, it) }
            command.policyDecision?.let { policy ->
                requireDirectConfirmationPolicy(
                    proposal = proposal,
                    consent = requireNotNull(command.consent),
                    policy = policy,
                    now = now,
                )
            }
            requireAcceptedConsent(commitment, proposal)
            bookingEligibilityGate.requireFresh(
                command.context,
                requireCommitmentMemberId(command.context, commitment.appointmentId),
                reliabilityStamp,
            )
            val confirmed =
                confirmProposal(
                    context = command.context,
                    commitment = commitment,
                    proposal = proposal,
                    resourceRequests = command.proposal.resourceRequests,
                    projectionTarget = command.projectionTarget,
                    now = now,
                    reliabilityStamp = reliabilityStamp,
                )
            writeDecision(
                context = command.context,
                eventType = EVENT_APPOINTMENT_CONFIRMED,
                commitment = confirmed,
                proposal = proposal,
                occurredAt = now,
            )
            notificationWriter.commitmentConfirmed(
                notificationInput(
                    context = command.context,
                    commitment = confirmed,
                    proposal = proposal,
                    memberId = requireCommitmentMemberId(command.context, confirmed.appointmentId),
                ),
            )
            persistCommandResult(command.context, confirmed, proposal)
            AppointmentCommitmentCommandResult(confirmed, proposal, idempotentReplay = false)
        }

    /**
     * 고객 동의가 준비된 병원 최초 proposal을 생성과 동시에 확정합니다.
     *
     * 방문 identity, proposal, 동의, allocation, commitment CAS, projection, outbox가
     * 하나의 transaction에서 완료되므로 중간 가예약 row가 외부에 보이지 않습니다.
     */
    fun confirmDirectAppointment(command: DirectAppointmentConfirmationCommand): AppointmentCommitmentCommandResult =
        executeCommand(command.context, OPERATION_DIRECT_CONFIRMATION) {
            val now = Instant.now(clock)
            requireClinicScope(command.context)
            val reliabilityStamp = bookingEligibilityGate.requireAllowed(command.context, command.identity.memberId)
            requireProposalActive(command.expiresAt, now)
            requireDirectConfirmationPolicy(command, now)
            val availabilityLock = allocationRepository.lockAndValidateAvailability(
                tenantGroupId = command.context.tenantGroupId,
                clinicId = command.context.clinicId,
                replacingProposalId = null,
                requests = command.proposal.resourceRequests,
            )
            bookingEligibilityGate.requireFresh(command.context, command.identity.memberId, reliabilityStamp)
            val created =
                createInitialProposal(
                    context = command.context,
                    identity = command.identity,
                    proposalInput = command.proposal,
                    expiresAt = command.expiresAt,
                    representativeTreatmentName = command.representativeTreatmentName,
                    origin = AppointmentOrigin.CLINIC,
                    reliabilityStamp = reliabilityStamp,
                    status = AppointmentCommitmentStatus.PROPOSED,
                )
            appendConsent(created.commitment, created.proposal, command.consent)
            val confirmed =
                confirmProposal(
                    context = command.context,
                    commitment = created.commitment,
                    proposal = created.proposal,
                    resourceRequests = command.proposal.resourceRequests,
                    projectionTarget = command.projectionTarget,
                    now = now,
                    reliabilityStamp = reliabilityStamp,
                    availabilityLock = availabilityLock,
                )
            writeDecision(
                context = command.context,
                eventType = EVENT_APPOINTMENT_CONFIRMED,
                commitment = confirmed,
                proposal = created.proposal,
                occurredAt = now,
            )
            notificationWriter.commitmentConfirmed(
                notificationInput(
                    context = command.context,
                    commitment = confirmed,
                    proposal = created.proposal,
                    memberId = requireIdentityMemberId(command.identity),
                ),
            )
            persistCommandResult(command.context, confirmed, created.proposal)
            AppointmentCommitmentCommandResult(confirmed, created.proposal, idempotentReplay = false)
        }

    /**
     * 현재 확정 예약을 유지한 채 대체 proposal revision만 append합니다.
     *
     * 새 proposal 발행만으로 확정 포인터나 기존 allocation을 변경하지 않습니다.
     * [VisitProposalInput.supersedesProposalId]는 현재 확정 proposal과 정확히 같아야 합니다.
     */
    fun proposeChange(command: ChangeAppointmentProposalCommand): AppointmentCommitmentCommandResult =
        executeCommand(command.context, OPERATION_PROPOSE_CHANGE) {
            val now = Instant.now(clock)
            val commitment = requireCommitment(command.context, command.appointmentId)
            requireExpectedVersion(commitment, command.expectedVersion)
            if (commitment.status != AppointmentCommitmentStatus.CONFIRMED) {
                reject(
                    AppointmentCommitmentCommandError.INVALID_TRANSITION,
                    "only confirmed commitment can receive a change proposal",
                )
            }
            requireProposalActive(command.expiresAt, now)
            if (command.proposal.supersedesProposalId != commitment.confirmedProposalId) {
                reject(
                    AppointmentCommitmentCommandError.VERSION_CONFLICT,
                    "change proposal must supersede the current confirmed proposal",
                )
            }
            requireNextProposalRevision(commitment, command.proposal.revision)
            val draft = command.proposal.toDraft(command.appointmentId)
            val proposal =
                commitmentRepository.appendProposal(
                    commitmentId = commitment.id,
                    draft = draft,
                    proposalHash = ProposalHasher.hash(draft),
                    expiresAt = command.expiresAt,
                    representativeTreatmentName = command.representativeTreatmentName,
                    createdByActor = command.context.actorAuditRef,
                )
            appendProposalItems(
                context = command.context,
                appointmentId = command.appointmentId,
                proposalId = proposal.id,
                patientReferenceFingerprint =
                    appointmentRepository.findPatientReferenceFingerprint(
                        appointmentId = command.appointmentId,
                        tenantGroupId = command.context.tenantGroupId,
                        clinicId = command.context.clinicId,
                    ) ?: reject(
                        AppointmentCommitmentCommandError.APPOINTMENT_ITEM_INVALID,
                        "commitment appointment has no patient reference fingerprint",
                    ),
                memberStableRef = null,
                proposalInput = command.proposal,
            )
            writeDecision(
                context = command.context,
                eventType = EVENT_APPOINTMENT_CHANGE_PROPOSED,
                commitment = commitment,
                proposal = proposal,
                occurredAt = now,
            )
            persistCommandResult(command.context, commitment, proposal)
            AppointmentCommitmentCommandResult(commitment, proposal, idempotentReplay = false)
        }

    /**
     * 고객 수락을 append하고 변경 proposal을 새 확정으로 원자 교체합니다.
     *
     * 수락 증빙은 서비스가 영속 proposal subject에 결합합니다. 새 allocation을 만든 뒤
     * commitment CAS가 실패하면 transaction rollback으로 새 allocation도 제거됩니다.
     */
    fun acceptProposal(command: AcceptAppointmentProposalCommand): AppointmentCommitmentCommandResult =
        executeCommand(command.context, OPERATION_ACCEPT_CHANGE) {
            val now = Instant.now(clock)
            val initialCommitment = requireCommitment(command.context, command.appointmentId)
            val lockedProposal = requireLockedProposal(initialCommitment, command.proposalId)
            val commitment = requireCommitment(command.context, command.appointmentId)
            requireExpectedVersion(commitment, command.expectedVersion)
            if (
                commitment.status != AppointmentCommitmentStatus.CONFIRMED ||
                commitment.confirmedProposalId == null
            ) {
                reject(
                    AppointmentCommitmentCommandError.INVALID_TRANSITION,
                    "proposal acceptance only changes an already confirmed appointment",
                )
            }
            val proposal =
                requireExactProposal(
                    commitment = commitment,
                    proposal = lockedProposal,
                    proposalInput = command.proposal,
                    expectedProposalHash = command.expectedProposalHash,
                    now = now,
                )
            requirePendingChangeProposal(commitment, proposal)
            val previousProposal =
                commitmentRepository.findProposal(
                    commitmentId = commitment.id,
                    proposalId = checkNotNull(commitment.confirmedProposalId),
                ) ?: error("confirmed proposal must remain readable")
            appendConsent(commitment, proposal, command.consent)
            requireAcceptedConsent(commitment, proposal)
            val confirmed =
                confirmProposal(
                    context = command.context,
                    commitment = commitment,
                    proposal = proposal,
                    resourceRequests = command.proposal.resourceRequests,
                    projectionTarget = command.projectionTarget,
                    now = now,
                )
            writeDecision(
                context = command.context,
                eventType = EVENT_APPOINTMENT_CONFIRMATION_CHANGED,
                commitment = confirmed,
                proposal = proposal,
                occurredAt = now,
            )
            val memberId = requireCommitmentMemberId(command.context, confirmed.appointmentId)
            notificationWriter.commitmentRescheduled(
                previous =
                    notificationInput(
                        context = command.context,
                        commitment = commitment,
                        proposal = previousProposal,
                        memberId = memberId,
                    ),
                replacement =
                    notificationInput(
                        context = command.context,
                        commitment = confirmed,
                        proposal = proposal,
                        memberId = memberId,
                    ),
            )
            persistCommandResult(command.context, confirmed, proposal)
            AppointmentCommitmentCommandResult(confirmed, proposal, idempotentReplay = false)
        }

    /**
     * proposal 거부 증빙을 append하되 기존 확정 예약은 변경하지 않습니다.
     */
    fun declineProposal(command: DeclineAppointmentProposalCommand): AppointmentCommitmentCommandResult =
        executeCommand(command.context, OPERATION_DECLINE_PROPOSAL) {
            val now = Instant.now(clock)
            val initialCommitment = requireCommitment(command.context, command.appointmentId)
            val lockedProposal = requireLockedProposal(initialCommitment, command.proposalId)
            val commitment = requireCommitment(command.context, command.appointmentId)
            requireExpectedVersion(commitment, command.expectedVersion)
            val proposal =
                requireProposalHash(
                    proposal = lockedProposal,
                    expectedProposalHash = command.expectedProposalHash,
                )
            requirePendingChangeProposal(commitment, proposal)
            appendConsent(commitment, proposal, command.consent)
            if (
                !commitmentRepository.advanceConfirmedVersion(
                    commitmentId = commitment.id,
                    expectedVersion = command.expectedVersion,
                    confirmedProposalId = checkNotNull(commitment.confirmedProposalId),
                    updatedAt = now,
                )
            ) {
                reject(
                    AppointmentCommitmentCommandError.VERSION_CONFLICT,
                    "commitment version changed before proposal decline",
                )
            }
            val afterDecline = requireCommitment(command.context, command.appointmentId)
            writeDecision(
                context = command.context,
                eventType = EVENT_APPOINTMENT_PROPOSAL_DECLINED,
                commitment = afterDecline,
                proposal = proposal,
                occurredAt = now,
            )
            persistCommandResult(command.context, afterDecline, proposal)
            AppointmentCommitmentCommandResult(afterDecline, proposal, idempotentReplay = false)
        }

    /**
     * proposal 유효시간 경과를 기록합니다.
     *
     * 최초 가예약 proposal이면 commitment를 `EXPIRED`로 CAS 전환합니다. 이미 확정된
     * 예약의 변경 proposal이면 기존 확정 포인터와 allocation을 그대로 보존합니다.
     */
    fun expireProposal(command: ExpireAppointmentProposalCommand): AppointmentCommitmentCommandResult =
        executeCommand(command.context, OPERATION_EXPIRE_PROPOSAL) {
            val now = Instant.now(clock)
            val initialCommitment = requireCommitment(command.context, command.appointmentId)
            val lockedProposal = requireLockedProposal(initialCommitment, command.proposalId)
            val commitment = requireCommitment(command.context, command.appointmentId)
            requireExpectedVersion(commitment, command.expectedVersion)
            val proposal =
                requireProposalHash(
                    proposal = lockedProposal,
                    expectedProposalHash = command.expectedProposalHash,
                )
            if (commitment.status == AppointmentCommitmentStatus.CONFIRMED) {
                requirePendingChangeProposal(commitment, proposal)
            } else if (proposal.supersedesProposalId != null) {
                reject(
                    AppointmentCommitmentCommandError.PROPOSAL_NOT_CURRENT,
                    "unconfirmed commitment can only expire its initial proposal",
                )
            }
            if (now.isBefore(proposal.expiresAt)) {
                reject(
                    AppointmentCommitmentCommandError.PROPOSAL_NOT_EXPIRED,
                    "proposal has not reached its expiry instant",
                )
            }
            if (!commitmentRepository.markProposalExpired(proposal.id, now)) {
                reject(
                    AppointmentCommitmentCommandError.PROPOSAL_ALREADY_EXPIRED,
                    "proposal expiry was already recorded",
                )
            }
            val afterExpiry =
                if (commitment.status == AppointmentCommitmentStatus.CONFIRMED) {
                    if (
                        !commitmentRepository.advanceConfirmedVersion(
                            commitmentId = commitment.id,
                            expectedVersion = command.expectedVersion,
                            confirmedProposalId = checkNotNull(commitment.confirmedProposalId),
                            updatedAt = now,
                        )
                    ) {
                        reject(
                            AppointmentCommitmentCommandError.VERSION_CONFLICT,
                            "commitment version changed before proposal expiry",
                        )
                    }
                    requireCommitment(command.context, command.appointmentId)
                } else {
                    if (
                        !commitmentRepository.expireUnconfirmedByVersion(
                            commitmentId = commitment.id,
                            expectedVersion = command.expectedVersion,
                            updatedAt = now,
                        )
                    ) {
                        reject(
                            AppointmentCommitmentCommandError.VERSION_CONFLICT,
                            "commitment version changed before proposal expiry",
                        )
                    }
                    if (commitment.status == AppointmentCommitmentStatus.HELD) {
                        allocationRepository.releaseActiveAllocations(proposal.id, now)
                    }
                    requireCommitment(command.context, command.appointmentId)
                }
            val expiredProposal =
                commitmentRepository.findProposal(commitment.id, proposal.id)
                    ?: error("expired proposal must remain readable")
            writeDecision(
                context = command.context,
                eventType = EVENT_APPOINTMENT_PROPOSAL_EXPIRED,
                commitment = afterExpiry,
                proposal = expiredProposal,
                occurredAt = now,
            )
            persistCommandResult(command.context, afterExpiry, expiredProposal)
            AppointmentCommitmentCommandResult(afterExpiry, expiredProposal, idempotentReplay = false)
        }

    /**
     * 현재 가예약 또는 확정 예약을 취소하고 활성 allocation을 원자적으로 해제합니다.
     *
     * 확정 예약에 변경 proposal이 대기 중이어도 확정 포인터를 취소 대상으로 사용합니다.
     * [CancelAppointmentCommand.reasonCode]는 등록 code만 허용하며 outbox에 자유 텍스트나
     * 고객 민감정보를 포함하지 않습니다.
     */
    fun cancelAppointment(command: CancelAppointmentCommand): AppointmentCommitmentCommandResult =
        executeCommand(command.context, OPERATION_CANCEL_APPOINTMENT) {
            val now = Instant.now(clock)
            val initialCommitment = requireCommitment(command.context, command.appointmentId)
            val lockedProposal = requireLockedProposal(initialCommitment, command.proposalId)
            val commitment = requireCommitment(command.context, command.appointmentId)
            requireExpectedVersion(commitment, command.expectedVersion)
            val proposal =
                requireProposalHash(
                    proposal = lockedProposal,
                    expectedProposalHash = command.expectedProposalHash,
                )
            when (commitment.status) {
                AppointmentCommitmentStatus.EXPIRED,
                AppointmentCommitmentStatus.CANCELLED,
                -> reject(
                    AppointmentCommitmentCommandError.INVALID_TRANSITION,
                    "expired or cancelled appointment cannot be cancelled",
                )

                AppointmentCommitmentStatus.CONFIRMED -> {
                    if (proposal.id != commitment.confirmedProposalId) {
                        reject(
                            AppointmentCommitmentCommandError.PROPOSAL_NOT_CURRENT,
                            "cancellation must target the confirmed proposal",
                        )
                    }
                }

                AppointmentCommitmentStatus.PROPOSED,
                AppointmentCommitmentStatus.HELD,
                -> {
                    if (commitment.confirmedProposalId != null || proposal.supersedesProposalId != null) {
                        reject(
                            AppointmentCommitmentCommandError.PROPOSAL_NOT_CURRENT,
                            "unconfirmed cancellation must target the initial proposal",
                        )
                    }
                }
            }
            if (
                !commitmentRepository.cancelByVersion(
                    commitmentId = commitment.id,
                    expectedVersion = commitment.version,
                    updatedAt = now,
                )
            ) {
                reject(
                    AppointmentCommitmentCommandError.VERSION_CONFLICT,
                    "commitment version changed before cancellation",
                )
            }
            allocationRepository.releaseActiveAllocations(
                commitment.confirmedProposalId ?: proposal.id,
                now,
            )
            check(
                appointmentRepository.cancelCommitmentProjection(
                    appointmentId = commitment.appointmentId,
                    tenantGroupId = command.context.tenantGroupId,
                    clinicId = command.context.clinicId,
                    updatedAt = now,
                ),
            ) {
                "cancelled appointment projection target must exist"
            }
            val cancelled = requireCommitment(command.context, command.appointmentId)
            writeDecision(
                context = command.context,
                eventType = EVENT_APPOINTMENT_CANCELLED,
                commitment = cancelled,
                proposal = proposal,
                occurredAt = now,
                reasonCode = command.reasonCode,
            )
            notificationWriter.commitmentCancelled(
                notification =
                    notificationInput(
                        context = command.context,
                        commitment = cancelled,
                        proposal = proposal,
                        memberId = requireCommitmentMemberId(command.context, cancelled.appointmentId),
                    ),
                reasonCode = CancellationReasonCode(command.reasonCode),
            )
            persistCommandResult(command.context, cancelled, proposal)
            AppointmentCommitmentCommandResult(cancelled, proposal, idempotentReplay = false)
        }

    /**
     * 멱등 선점과 제한된 transient DB 재시도를 감싼 transaction을 실행합니다.
     *
     * replay는 현재 row로 응답을 다시 계산하지 않습니다. 같은 transaction에서 결과의
     * tenant·clinic 소유권과 snapshot hash를 검증한 뒤 최초 command 완료 시 저장한
     * commitment/proposal 응답을 그대로 반환하며 원래 side effect를 재실행하지 않습니다.
     */
    private fun executeCommand(
        context: CommitmentCommandContext,
        operation: String,
        commandBlock: () -> AppointmentCommitmentCommandResult,
    ): AppointmentCommitmentCommandResult {
        var attempt = 1
        while (true) {
            try {
                val result =
                    transaction(database) {
                        this.maxAttempts = 1
                        when (
                            idempotencyRepository.claim(
                                tenantGroupId = context.tenantGroupId,
                                clinicId = context.clinicId,
                                actorScopeHash = context.actorScopeHash,
                                idempotencyKeyHash = context.idempotencyKeyHash,
                                commandHash = context.commandHash,
                            )
                        ) {
                            CommandClaimResult.ACQUIRED -> commandBlock()
                            CommandClaimResult.REPLAY -> replayCommandResult(context)
                        }
                    }
                log.info {
                    "Appointment commitment command completed: operation=$operation, " +
                        "commitmentId=${result.commitment.id}, proposalId=${result.proposal.id}, " +
                        "replay=${result.idempotentReplay}"
                }
                return result
            } catch (exception: AppointmentCommandIdempotencyConflictException) {
                reject(
                    AppointmentCommitmentCommandError.IDEMPOTENCY_KEY_REUSED,
                    "idempotency key is bound to another command",
                    exception,
                )
            } catch (exception: ResourceAllocationConflictException) {
                reject(
                    AppointmentCommitmentCommandError.RESOURCE_CONFLICT,
                    "resource allocation conflicts with an active confirmation",
                    exception,
                )
            } catch (exception: AppointmentCommitmentCommandException) {
                log.warn {
                    "Appointment commitment command rejected: operation=$operation, " +
                        "code=${exception.code}, tenantGroupId=${context.tenantGroupId}, " +
                        "clinicId=${context.clinicId}, correlationId=${context.correlationId}"
                }
                throw exception
            } catch (exception: Throwable) {
                if (exception.isConsentEvidenceReuse()) {
                    reject(
                        AppointmentCommitmentCommandError.CONSENT_EVIDENCE_REUSED,
                        "consent evidence is already bound to another decision",
                        exception,
                    )
                }
                if (!exception.isRetryableTransactionFailure()) {
                    throw exception
                }
                if (attempt >= maxTransactionAttempts) {
                    log.warn(exception) {
                        "Appointment commitment command failed after bounded retry: " +
                            "operation=$operation, attempts=$attempt"
                    }
                    reject(
                        AppointmentCommitmentCommandError.RETRY_EXHAUSTED,
                        "database transaction retry limit was exhausted",
                        exception,
                    )
                }
                val delayMillis =
                    initialRetryDelayMillis * (1L shl (attempt - 1)) +
                        retryJitterMillis(attempt).coerceAtLeast(0)
                log.warn(exception) {
                    "Retrying appointment commitment transaction: " +
                        "operation=$operation, attempt=$attempt, delayMs=$delayMillis"
                }
                try {
                    retryDelay(delayMillis)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    reject(
                        AppointmentCommitmentCommandError.RETRY_INTERRUPTED,
                        "database transaction retry was interrupted",
                        interrupted,
                    )
                }
                attempt++
            }
        }
    }

    /** 방문 identity, commitment version 1, 첫 proposal을 같은 transaction에 생성합니다. */
    private fun createInitialProposal(
        context: CommitmentCommandContext,
        identity: AppointmentVisitIdentity,
        proposalInput: VisitProposalInput,
        expiresAt: Instant,
        representativeTreatmentName: String,
        origin: AppointmentOrigin,
        reliabilityStamp: BookingReliabilityDecisionStamp? = null,
        status: AppointmentCommitmentStatus,
    ): InitialProposal {
        requireInitialProposalRevision(proposalInput.revision)
        val appointmentId =
            appointmentRepository.createCommitmentVisitIdentity(
                clinicId = context.clinicId,
                identity = identity,
            )
        val draft = proposalInput.toDraft(appointmentId, reliabilityStamp)
        val commitment =
            commitmentRepository.create(
                AppointmentCommitment(
                    appointmentId = appointmentId,
                    status = status,
                    origin = origin,
                    confirmedProposalId = null,
                    effectivePolicySnapshotId = proposalInput.policySnapshotId,
                    version = INITIAL_COMMITMENT_VERSION,
                    bookingReliabilityStamp = reliabilityStamp,
                ),
            )
        val proposal =
            commitmentRepository.appendProposal(
                commitmentId = commitment.id,
                draft = draft,
                proposalHash = ProposalHasher.hash(draft),
                expiresAt = expiresAt,
                representativeTreatmentName = representativeTreatmentName,
                createdByActor = context.actorAuditRef,
            )
        appendProposalItems(
            context = context,
            appointmentId = appointmentId,
            proposalId = proposal.id,
            patientReferenceFingerprint = identity.patientReferenceFingerprint,
            memberStableRef = identity.memberId,
            proposalInput = proposalInput,
        )
        return InitialProposal(commitment, proposal)
    }

    /**
     * proposal hash에 포함된 Plan-linked item을 같은 transaction에서 불변 row로 고정합니다.
     *
     * 저장 직전 tenant·clinic·patient·Plan revision 경계를 재검증합니다. caller 입력이
     * 구매 당시 Plan treatment snapshot과 다르면 원시 repository 예외를 노출하지 않고
     * 안정적인 application 오류로 변환하며 전체 command transaction을 rollback합니다.
     */
    private fun appendProposalItems(
        context: CommitmentCommandContext,
        appointmentId: Long,
        proposalId: Long,
        patientReferenceFingerprint: String,
        memberStableRef: MemberId?,
        proposalInput: VisitProposalInput,
    ) {
        try {
            itemRepository.appendValidated(
                scope =
                    AppointmentItemAppendScope(
                        appointmentId = appointmentId,
                        proposalId = proposalId,
                        tenantGroupId = context.tenantGroupId,
                        clinicId = context.clinicId,
                        patientReferenceFingerprint = patientReferenceFingerprint,
                        memberStableRef = memberStableRef,
                    ),
                items = proposalInput.items,
            )
            itemRepository.requireResourceReferences(
                proposalId = proposalId,
                requests = proposalInput.resourceRequests,
            )
        } catch (invalidItem: IllegalArgumentException) {
            reject(
                AppointmentCommitmentCommandError.APPOINTMENT_ITEM_INVALID,
                "proposal item does not match the scoped immutable plan treatment",
                invalidItem,
            )
        }
    }

    /**
     * 새 allocation 생성, commitment CAS, 이전 allocation 해제, projection 갱신 순서를
     * 유지하면서 proposal을 확정합니다.
     */
    private fun confirmProposal(
        context: CommitmentCommandContext,
        commitment: AppointmentCommitmentRecord,
        proposal: AppointmentProposalRecord,
        resourceRequests: List<ResourceAllocationRequest>,
        projectionTarget: ConfirmedAppointmentProjectionTarget,
        now: Instant,
        reliabilityStamp: BookingReliabilityDecisionStamp? = null,
        availabilityLock: LockedResourceAvailability? = null,
    ): AppointmentCommitmentRecord {
        requireResourceItemReferences(proposal.id, resourceRequests)
        val projection =
            deriveConfirmedProjection(
                context = context,
                proposal = proposal,
                resourceRequests = resourceRequests,
                target = projectionTarget,
            )
        val previousProposalId = commitment.confirmedProposalId
        if (commitment.status == AppointmentCommitmentStatus.HELD) {
            requireHeldAllocations(proposal.id, resourceRequests)
        } else {
            allocationRepository.createConfirmedAllocations(
                tenantGroupId = context.tenantGroupId,
                clinicId = context.clinicId,
                proposalId = proposal.id,
                replacingProposalId = previousProposalId,
                requests = resourceRequests,
                availabilityLock = availabilityLock,
            )
        }
        if (
            !commitmentRepository.confirmByVersion(
                commitmentId = commitment.id,
                expectedVersion = commitment.version,
                proposalId = proposal.id,
                updatedAt = now,
                bookingReliabilityStamp = reliabilityStamp,
                expectedBookingReliabilityStamp = commitment.bookingReliabilityStamp
                    ?: proposal.bookingReliabilityStamp,
            )
        ) {
            reject(
                AppointmentCommitmentCommandError.VERSION_CONFLICT,
                "commitment version changed before confirmation",
            )
        }
        previousProposalId?.let { allocationRepository.releaseActiveAllocations(it, now) }
        check(
            appointmentRepository.updateConfirmedProjection(
                appointmentId = commitment.appointmentId,
                tenantGroupId = context.tenantGroupId,
                clinicId = context.clinicId,
                projection = projection,
                updatedAt = now,
            ),
        ) {
            "confirmed appointment projection target must exist"
        }
        return checkNotNull(commitmentRepository.findById(commitment.id)) {
            "confirmed commitment must remain readable"
        }
    }

    /** HELD 상태가 proposal의 정확한 자원 요청을 이미 active allocation으로 보유하는지 확인합니다. */
    private fun requireHeldAllocations(
        proposalId: Long,
        resourceRequests: List<ResourceAllocationRequest>,
    ) {
        val actual =
            allocationRepository.findByProposal(proposalId)
                .filter { it.status == io.bluetape4k.clinic.appointment.model.dto.ResourceAllocationStatus.ACTIVE }
                .map {
                    val allocation = it.allocation
                    listOf(
                        allocation.resourceType.name,
                        allocation.resourceId,
                        allocation.startsAt.toString(),
                        allocation.endsAt.toString(),
                        allocation.capacityUnits.toString(),
                        it.maximumCapacity.toString(),
                        allocation.allocationMode.name,
                        allocation.appointmentItemKey,
                    )
                }
                .sortedBy { it.joinToString(separator = "|") }
        val expected =
            resourceRequests
                .map {
                    val allocation = it.allocation
                    listOf(
                        allocation.resourceType.name,
                        allocation.resourceId,
                        allocation.startsAt.toString(),
                        allocation.endsAt.toString(),
                        allocation.capacityUnits.toString(),
                        it.maximumCapacity.toString(),
                        allocation.allocationMode.name,
                        allocation.appointmentItemKey,
                    )
                }
                .sortedBy { it.joinToString(separator = "|") }
        if (actual != expected) {
            reject(
                AppointmentCommitmentCommandError.RESOURCE_CONFLICT,
                "held allocations do not match the proposal resource snapshot",
            )
        }
    }

    /** 확정 직전에도 resource item 참조를 저장된 proposal item 기준으로 다시 검증합니다. */
    private fun requireResourceItemReferences(
        proposalId: Long,
        resourceRequests: List<ResourceAllocationRequest>,
    ) {
        try {
            itemRepository.requireResourceReferences(proposalId, resourceRequests)
        } catch (invalidReference: IllegalArgumentException) {
            reject(
                AppointmentCommitmentCommandError.APPOINTMENT_ITEM_INVALID,
                "resource allocation does not reference an item in the same proposal",
                invalidReference,
            )
        }
    }

    /** command의 proposal 입력과 저장된 불변 proposal이 완전히 같은지 검증합니다. */
    private fun requireExactProposal(
        commitment: AppointmentCommitmentRecord,
        proposalId: Long,
        proposalInput: VisitProposalInput,
        expectedProposalHash: String,
        now: Instant,
    ): AppointmentProposalRecord {
        val proposal = requireProposalHash(commitment, proposalId, expectedProposalHash)
        return requireExactProposal(
            commitment = commitment,
            proposal = proposal,
            proposalInput = proposalInput,
            expectedProposalHash = expectedProposalHash,
            now = now,
        )
    }

    /** 잠근 proposal과 command의 불변 입력을 canonical hash까지 포함해 비교합니다. */
    private fun requireExactProposal(
        commitment: AppointmentCommitmentRecord,
        proposal: AppointmentProposalRecord,
        proposalInput: VisitProposalInput,
        expectedProposalHash: String,
        now: Instant,
    ): AppointmentProposalRecord {
        requireProposalHash(proposal, expectedProposalHash)
        val draft = proposalInput.toDraft(commitment.appointmentId)
        val calculatedHash = ProposalHasher.hash(draft)
        if (
            calculatedHash != expectedProposalHash ||
            proposal.revision != draft.revision ||
            proposal.proposedStartAt != draft.startsAt ||
            proposal.proposedEndAt != draft.endsAt ||
            proposal.policySnapshotId != draft.policySnapshotId ||
            proposal.supersedesProposalId != draft.supersedesProposalId
        ) {
            reject(
                AppointmentCommitmentCommandError.PROPOSAL_NOT_FOUND,
                "proposal input does not match the persisted immutable proposal",
            )
        }
        requireProposalActive(proposal, now)
        return proposal
    }

    /** commitment 소유권과 caller가 제시한 canonical hash를 함께 검증합니다. */
    private fun requireProposalHash(
        commitment: AppointmentCommitmentRecord,
        proposalId: Long,
        expectedProposalHash: String,
    ): AppointmentProposalRecord {
        val proposal =
            commitmentRepository.findProposal(commitment.id, proposalId)
                ?: reject(
                    AppointmentCommitmentCommandError.PROPOSAL_NOT_FOUND,
                    "proposal does not belong to the appointment commitment",
                )
        return requireProposalHash(proposal, expectedProposalHash)
    }

    /** 이미 소유권을 검증해 잠근 proposal의 canonical hash만 비교합니다. */
    private fun requireProposalHash(
        proposal: AppointmentProposalRecord,
        expectedProposalHash: String,
    ): AppointmentProposalRecord {
        if (proposal.proposalHash != expectedProposalHash) {
            reject(
                AppointmentCommitmentCommandError.PROPOSAL_NOT_FOUND,
                "proposal hash does not match the persisted proposal",
            )
        }
        return proposal
    }

    /**
     * proposal 종결 경쟁을 직렬화합니다.
     *
     * caller는 잠금 획득 뒤 commitment를 다시 읽어 expected version을 검사해야 합니다.
     */
    private fun requireLockedProposal(
        commitment: AppointmentCommitmentRecord,
        proposalId: Long,
    ): AppointmentProposalRecord =
        commitmentRepository.findProposalForUpdate(commitment.id, proposalId)
            ?: reject(
                AppointmentCommitmentCommandError.PROPOSAL_NOT_FOUND,
                "proposal does not belong to the appointment commitment",
            )

    /** proposal ID/revision/hash에 결합된 최신 수락 동의가 있는지 검증합니다. */
    private fun requireAcceptedConsent(
        commitment: AppointmentCommitmentRecord,
        proposal: AppointmentProposalRecord,
    ) {
        val decision =
            commitmentRepository.findLatestProposalDecision(
                commitmentId = commitment.id,
                proposalId = proposal.id,
                proposalRevision = proposal.revision,
                proposalHash = proposal.proposalHash,
            ) ?: reject(
                AppointmentCommitmentCommandError.CONSENT_REQUIRED,
                "accepted consent for the exact proposal is required",
            )
        if (decision.decision == ConsentDecisionType.DECLINED) {
            reject(
                AppointmentCommitmentCommandError.CUSTOMER_DECLINED,
                "the latest exact proposal decision is declined",
            )
        }
    }

    /** 외부 동의 증빙 metadata를 정확한 영속 proposal subject에 결합해 append합니다. */
    private fun appendConsent(
        commitment: AppointmentCommitmentRecord,
        proposal: AppointmentProposalRecord,
        evidence: ProposalConsentEvidence,
    ) {
        commitmentRepository.appendConsent(
            commitmentId = commitment.id,
            decision =
                ConsentDecision(
                    subject =
                        ProposalConsentSubject(
                            proposalId = proposal.id,
                            proposalRevision = proposal.revision,
                            proposalHash = proposal.proposalHash,
                        ),
                    decision = evidence.decision,
                    evidenceAuthority = evidence.evidenceAuthority,
                    evidenceId = evidence.evidenceId,
                    evidenceHash = evidence.evidenceHash,
                    decidedAt = evidence.decidedAt,
                    actorRef = evidence.actorRef,
                    evidenceType = evidence.evidenceType,
                    termsHash = evidence.termsHash,
                ),
        )
    }

    /** 신뢰된 tenant와 clinic의 실제 결합을 새 aggregate 생성 전에 검증합니다. */
    private fun requireClinicScope(context: CommitmentCommandContext) {
        if (
            !appointmentRepository.isClinicInTenant(
                tenantGroupId = context.tenantGroupId,
                clinicId = context.clinicId,
            )
        ) {
            reject(
                AppointmentCommitmentCommandError.SCOPE_MISMATCH,
                "clinic does not belong to the command tenant scope",
            )
        }
    }

    /** appointment ID의 tenant·clinic 소유권을 검증한 뒤 commitment를 반환합니다. */
    private fun requireCommitment(
        context: CommitmentCommandContext,
        appointmentId: Long,
    ): AppointmentCommitmentRecord {
        if (
            !appointmentRepository.isAppointmentInScope(
                appointmentId = appointmentId,
                tenantGroupId = context.tenantGroupId,
                clinicId = context.clinicId,
            )
        ) {
            reject(
                AppointmentCommitmentCommandError.SCOPE_MISMATCH,
                "appointment does not belong to the command scope",
            )
        }
        return commitmentRepository.findByAppointmentId(appointmentId)
            ?: reject(
                AppointmentCommitmentCommandError.COMMITMENT_NOT_FOUND,
                "appointment commitment does not exist",
            )
    }

    /** commitment v2 알림은 회원 DB 조회 기준인 durable member ID만 전달합니다. */
    private fun notificationInput(
        context: CommitmentCommandContext,
        commitment: AppointmentCommitmentRecord,
        proposal: AppointmentProposalRecord,
        memberId: MemberId,
    ): CommitmentAppointmentNotification =
        CommitmentAppointmentNotification(
            tenantGroupId = context.tenantGroupId,
            clinicId = context.clinicId,
            appointmentId = commitment.appointmentId,
            memberId = memberId,
            commitmentVersion = commitment.version,
            proposalRevision = proposal.revision,
            startsAt = proposal.proposedStartAt,
            endsAt = proposal.proposedEndAt,
        )

    private fun requireIdentityMemberId(identity: AppointmentVisitIdentity): MemberId =
        identity.memberId
            ?: reject(
                AppointmentCommitmentCommandError.APPOINTMENT_ITEM_INVALID,
                "commitment appointment has no verified memberId",
            )

    private fun requireCommitmentMemberId(
        context: CommitmentCommandContext,
        appointmentId: Long,
    ): MemberId =
        appointmentRepository.findCommitmentMemberId(
            appointmentId = appointmentId,
            tenantGroupId = context.tenantGroupId,
            clinicId = context.clinicId,
        ) ?: reject(
            AppointmentCommitmentCommandError.APPOINTMENT_ITEM_INVALID,
            "commitment appointment has no verified memberId",
        )

    /** stale caller가 allocation write에 진입하기 전에 version을 검증합니다. */
    private fun requireExpectedVersion(
        commitment: AppointmentCommitmentRecord,
        expectedVersion: Long,
    ) {
        if (commitment.version != expectedVersion) {
            reject(
                AppointmentCommitmentCommandError.VERSION_CONFLICT,
                "appointment commitment version is stale",
            )
        }
    }

    /** 최초 proposal revision은 service가 1로 고정해 DB unique 오류가 API로 새지 않게 합니다. */
    private fun requireInitialProposalRevision(revision: Long) {
        if (revision != INITIAL_PROPOSAL_REVISION) {
            reject(
                AppointmentCommitmentCommandError.PROPOSAL_REVISION_CONFLICT,
                "initial proposal revision must be 1",
            )
        }
    }

    /** 최신 영속 revision 바로 다음 값만 append해 revision의 단조 증가 계약을 보존합니다. */
    private fun requireNextProposalRevision(
        commitment: AppointmentCommitmentRecord,
        revision: Long,
    ) {
        val latestRevision =
            commitmentRepository.findLatestProposalRevision(commitment.id)
                ?: reject(
                    AppointmentCommitmentCommandError.PROPOSAL_REVISION_CONFLICT,
                    "confirmed commitment must have an existing proposal revision",
                )
        if (revision != latestRevision + 1L) {
            reject(
                AppointmentCommitmentCommandError.PROPOSAL_REVISION_CONFLICT,
                "change proposal revision must immediately follow the latest revision",
            )
        }
    }

    /** 고객 가예약의 최초 proposal만 관리자 승인 경로로 확정할 수 있게 제한합니다. */
    private fun requireInitialCustomerApproval(commitment: AppointmentCommitmentRecord) {
        if (
            commitment.status !in
            setOf(AppointmentCommitmentStatus.PROPOSED, AppointmentCommitmentStatus.HELD) ||
            commitment.origin != AppointmentOrigin.PATIENT ||
            commitment.confirmedProposalId != null
        ) {
            reject(
                AppointmentCommitmentCommandError.INVALID_TRANSITION,
                "admin approval requires an unconfirmed customer-origin proposal",
            )
        }
    }

    /** 현재 확정 proposal을 정확히 대체하는 아직 미확정된 변경 proposal인지 검증합니다. */
    private fun requirePendingChangeProposal(
        commitment: AppointmentCommitmentRecord,
        proposal: AppointmentProposalRecord,
    ) {
        if (
            commitment.status != AppointmentCommitmentStatus.CONFIRMED ||
            commitment.confirmedProposalId == null ||
            proposal.id == commitment.confirmedProposalId ||
            proposal.supersedesProposalId != commitment.confirmedProposalId
        ) {
            reject(
                AppointmentCommitmentCommandError.PROPOSAL_NOT_CURRENT,
                "proposal does not supersede the current confirmation",
            )
        }
    }

    /** 현재 clock 기준 새로 발행할 proposal의 만료 시각이 미래인지 검증합니다. */
    private fun requireProposalActive(
        expiresAt: Instant,
        now: Instant,
    ) {
        if (!now.isBefore(expiresAt)) {
            reject(
                AppointmentCommitmentCommandError.PROPOSAL_EXPIRED,
                "proposal has expired",
            )
        }
    }

    /** 영속 만료 표식과 유효시간을 함께 검사해 만료 event 뒤 수락을 차단합니다. */
    private fun requireProposalActive(
        proposal: AppointmentProposalRecord,
        now: Instant,
    ) {
        if (proposal.expiredAt != null || !now.isBefore(proposal.expiresAt)) {
            reject(
                AppointmentCommitmentCommandError.PROPOSAL_EXPIRED,
                "proposal has expired",
            )
        }
    }

    /** 관리자 직접 확정에 사용한 유효 정책과 동의 증빙의 유형·신선도·약관 hash를 검증합니다. */
    private fun requireDirectConfirmationPolicy(
        command: DirectAppointmentConfirmationCommand,
        now: Instant,
    ) =
        requireDirectConfirmationPolicy(
            proposalPolicySnapshotId = command.proposal.policySnapshotId,
            consent = command.consent,
            policy = command.policyDecision,
            now = now,
        )

    /**
     * 영속 proposal에 대한 직접 확정 정책을 같은 transaction 안에서 검증한다.
     *
     * application service가 검증한 현재 정책을 command에 포함해도 proposal은 그 사이
     * 변경될 수 있으므로, 확정 CAS 직전에 영속 row의 정책 snapshot과 다시 비교한다.
     */
    private fun requireDirectConfirmationPolicy(
        proposal: AppointmentProposalRecord,
        consent: ProposalConsentEvidence,
        policy: DirectConfirmationPolicyDecision,
        now: Instant,
    ) =
        requireDirectConfirmationPolicy(
            proposalPolicySnapshotId = proposal.policySnapshotId,
            consent = consent,
            policy = policy,
            now = now,
        )

    private fun requireDirectConfirmationPolicy(
        proposalPolicySnapshotId: Long?,
        consent: ProposalConsentEvidence,
        policy: DirectConfirmationPolicyDecision,
        now: Instant,
    ) {
        if (
            policy.policySnapshotId != proposalPolicySnapshotId ||
            policy.adminBookingMode != AdminBookingMode.DIRECT_CONFIRM_WITH_CONSENT_EVIDENCE
        ) {
            reject(
                AppointmentCommitmentCommandError.DIRECT_CONFIRM_NOT_ALLOWED,
                "effective booking policy does not allow direct confirmation",
            )
        }
        val evidenceAge = Duration.between(consent.decidedAt, now)
        if (
            consent.evidenceType !in policy.allowedEvidenceTypes ||
            evidenceAge.isNegative ||
            evidenceAge > policy.maximumEvidenceAge ||
            (policy.termsHashRequired && consent.termsHash != policy.requiredTermsHash)
        ) {
            reject(
                AppointmentCommitmentCommandError.CONSENT_EVIDENCE_INVALID,
                "consent evidence does not satisfy the effective booking policy",
            )
        }
    }

    /**
     * 확정 시간을 proposal에서 계산하고 legacy FK와 담당자 자원 선택을 tenant·clinic에 묶습니다.
     *
     * legacy row는 하루 안의 `LocalDate + LocalTime`만 표현하므로 병원 timezone에서 날짜가
     * 넘어가는 proposal은 조용히 잘라 쓰지 않고 안정적인 projection 오류로 거부합니다.
     */
    private fun deriveConfirmedProjection(
        context: CommitmentCommandContext,
        proposal: AppointmentProposalRecord,
        resourceRequests: List<ResourceAllocationRequest>,
        target: ConfirmedAppointmentProjectionTarget,
    ): ConfirmedAppointmentProjection {
        val practitionerSelected =
            resourceRequests.any {
                it.allocation.resourceType == ResourceType.PRACTITIONER &&
                    it.allocation.resourceId == target.practitionerResourceId
            }
        if (
            !practitionerSelected ||
            !appointmentRepository.areProjectionReferencesInClinic(
                clinicId = context.clinicId,
                doctorId = target.doctorId,
                treatmentTypeId = target.treatmentTypeId,
            )
        ) {
            reject(
                AppointmentCommitmentCommandError.CONFIRMED_PROJECTION_INVALID,
                "projection references must match the selected practitioner and clinic",
            )
        }
        val zoneId =
            appointmentRepository.findClinicTimezone(
                tenantGroupId = context.tenantGroupId,
                clinicId = context.clinicId,
            ) ?: reject(
                AppointmentCommitmentCommandError.SCOPE_MISMATCH,
                "clinic timezone does not belong to the command scope",
            )
        val localStart = LocalDateTime.ofInstant(proposal.proposedStartAt, zoneId)
        val localEnd = LocalDateTime.ofInstant(proposal.proposedEndAt, zoneId)
        if (localStart.toLocalDate() != localEnd.toLocalDate()) {
            reject(
                AppointmentCommitmentCommandError.CONFIRMED_PROJECTION_INVALID,
                "legacy projection cannot represent a visit crossing a local date boundary",
            )
        }
        return ConfirmedAppointmentProjection(
            doctorId = target.doctorId,
            treatmentTypeId = target.treatmentTypeId,
            appointmentDate = localStart.toLocalDate(),
            startTime = localStart.toLocalTime(),
            endTime = localEnd.toLocalTime(),
        )
    }

    /** 감사 projection과 redacted outbox event를 같은 transaction에 기록합니다. */
    private fun writeDecision(
        context: CommitmentCommandContext,
        eventType: String,
        commitment: AppointmentCommitmentRecord,
        proposal: AppointmentProposalRecord,
        occurredAt: Instant,
        reasonCode: String? = null,
    ) {
        AppointmentAuditEvents.insert {
            it[tenantGroupId] = context.tenantGroupId
            it[clinicId] = context.clinicId
            it[aggregateType] = COMMITMENT_AGGREGATE_TYPE
            it[aggregateId] = commitment.id.toString()
            it[AppointmentAuditEvents.eventType] = eventType
            it[actorScopeHash] = context.actorScopeHash
            it[payloadHash] = context.commandHash
            it[AppointmentAuditEvents.occurredAt] = occurredAt
        }
        val eventId =
            UUID
                .nameUUIDFromBytes(
                    "$eventType:${commitment.id}:${proposal.id}:${context.commandHash}"
                        .toByteArray(StandardCharsets.UTF_8),
                ).toString()
        SchedulingOutboxEvents.insert {
            it[SchedulingOutboxEvents.eventId] = eventId
            it[causationEventId] = null
            it[correlationId] = context.correlationId
            it[SchedulingOutboxEvents.eventType] = eventType
            it[tenantGroupId] = context.tenantGroupId
            it[clinicId] = context.clinicId
            it[planId] = null
            it[aggregateType] = COMMITMENT_AGGREGATE_TYPE
            it[aggregateId] = commitment.id.toString()
            it[schemaVersion] = OUTBOX_SCHEMA_VERSION
            it[payloadJson] =
                buildString {
                    append("{\"appointmentId\":${commitment.appointmentId}")
                    append(",\"commitmentId\":${commitment.id}")
                    append(",\"proposalId\":${proposal.id}")
                    append(",\"commitmentVersion\":${commitment.version}")
                    reasonCode?.let { append(",\"reasonCode\":\"$it\"") }
                    append('}')
                }
            it[status] = SchedulingOutboxStatus.PENDING
            it[attemptCount] = 0
        }
    }

    /** 같은 transaction 마지막에 caller가 본 commitment/proposal 응답 snapshot을 기록합니다. */
    private fun persistCommandResult(
        context: CommitmentCommandContext,
        commitment: AppointmentCommitmentRecord,
        proposal: AppointmentProposalRecord,
    ) {
        if (
            !idempotencyRepository.complete(
                tenantGroupId = context.tenantGroupId,
                clinicId = context.clinicId,
                actorScopeHash = context.actorScopeHash,
                idempotencyKeyHash = context.idempotencyKeyHash,
                commandHash = context.commandHash,
                result =
                    AppointmentCommandResultRecord(
                        resultType = IDEMPOTENCY_RESULT_PROPOSAL,
                        resultId = proposal.id,
                        commitment = commitment,
                        proposal = proposal,
                        responseHash = calculateResponseHash(commitment, proposal),
                    ),
            )
        ) {
            reject(
                AppointmentCommitmentCommandError.IDEMPOTENCY_RESULT_MISSING,
                "idempotency result could not be completed",
            )
        }
    }

    /**
     * 완료된 멱등 row의 최초 응답 snapshot을 현재 scope와 무결성 확인 뒤 재생합니다.
     *
     * 현재 commitment는 scope와 원본 row 존재 여부를 확인하는 데만 사용합니다. 이후
     * 일정 변경이 있어도 replay 응답을 현재 상태로 치환하지 않습니다.
     */
    private fun replayCommandResult(context: CommitmentCommandContext): AppointmentCommitmentCommandResult {
        val stored =
            idempotencyRepository.findResult(
                tenantGroupId = context.tenantGroupId,
                clinicId = context.clinicId,
                actorScopeHash = context.actorScopeHash,
                idempotencyKeyHash = context.idempotencyKeyHash,
            ) ?: reject(
                AppointmentCommitmentCommandError.IDEMPOTENCY_RESULT_MISSING,
                "idempotency command has no durable result",
            )
        if (stored.resultType != IDEMPOTENCY_RESULT_PROPOSAL) {
            reject(
                AppointmentCommitmentCommandError.IDEMPOTENCY_RESULT_MISSING,
                "idempotency result type is unsupported",
            )
        }
        val persistedProposal =
            commitmentRepository.findProposalById(stored.resultId)
                ?: reject(
                    AppointmentCommitmentCommandError.IDEMPOTENCY_RESULT_MISSING,
                    "idempotency proposal result no longer exists",
                )
        val persistedCommitment =
            commitmentRepository.findById(persistedProposal.commitmentId)
                ?: reject(
                    AppointmentCommitmentCommandError.IDEMPOTENCY_RESULT_MISSING,
                    "idempotency commitment result no longer exists",
                )
        if (
            !appointmentRepository.isAppointmentInScope(
                appointmentId = persistedCommitment.appointmentId,
                tenantGroupId = context.tenantGroupId,
                clinicId = context.clinicId,
            )
        ) {
            reject(
                AppointmentCommitmentCommandError.SCOPE_MISMATCH,
                "idempotency result does not belong to the command scope",
            )
        }
        if (
            stored.commitment.id != persistedCommitment.id ||
            stored.proposal.id != persistedProposal.id ||
            stored.responseHash != calculateResponseHash(stored.commitment, stored.proposal)
        ) {
            reject(
                AppointmentCommitmentCommandError.IDEMPOTENCY_RESULT_MISSING,
                "idempotency response snapshot failed integrity validation",
            )
        }
        return AppointmentCommitmentCommandResult(
            commitment = stored.commitment,
            proposal = stored.proposal,
            idempotentReplay = true,
        )
    }

    /** 멱등 replay가 검증할 수 있는 commitment/proposal 응답의 canonical SHA-256입니다. */
    private fun calculateResponseHash(
        commitment: AppointmentCommitmentRecord,
        proposal: AppointmentProposalRecord,
    ): String {
        val canonical =
            listOf(
                commitment.id,
                commitment.appointmentId,
                commitment.status,
                commitment.origin,
                commitment.confirmedProposalId,
                commitment.effectivePolicySnapshotId,
                commitment.version,
                commitment.bookingReliabilityStamp?.decisionId,
                commitment.bookingReliabilityStamp?.policyVersionId,
                commitment.bookingReliabilityStamp?.policyHash,
                commitment.bookingReliabilityStamp?.evaluationDigest,
                commitment.bookingReliabilityStamp?.expiresAt,
                proposal.id,
                proposal.commitmentId,
                proposal.revision,
                proposal.proposedStartAt,
                proposal.proposedEndAt,
                proposal.expiresAt,
                proposal.expiredAt,
                proposal.representativeTreatmentName,
                proposal.proposalHash,
                proposal.policySnapshotId,
                proposal.supersedesProposalId,
                proposal.createdByActor,
                proposal.bookingReliabilityStamp?.decisionId,
                proposal.bookingReliabilityStamp?.policyVersionId,
                proposal.bookingReliabilityStamp?.policyHash,
                proposal.bookingReliabilityStamp?.evaluationDigest,
                proposal.bookingReliabilityStamp?.expiresAt,
            ).joinToString(separator = "|") { it?.toString().orEmpty() }
        return MessageDigest
            .getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it) }
    }

    /** 원인 chain에 재시도 가능한 PostgreSQL transaction SQL state가 있는지 확인합니다. */
    private fun Throwable.isRetryableTransactionFailure(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (
                current is SQLException &&
                current.sqlState in RETRYABLE_SQL_STATES
            ) {
                return true
            }
            if (
                current is ExposedSQLException &&
                current.sqlState in RETRYABLE_SQL_STATES
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    /**
     * 전역 consent evidence unique 제약 위반을 공개 가능한 업무 오류로 식별한다.
     *
     * SQL message 전체는 외부에 노출하지 않고 제약 이름과 표준 unique SQL state를 함께
     * 확인한다. 같은 SQL state의 다른 제약 위반을 consent 재사용으로 오분류하지 않는다.
     */
    private fun Throwable.isConsentEvidenceReuse(): Boolean {
        var current: Throwable? = this
        var uniqueViolation = false
        var consentConstraint = false
        while (current != null) {
            if (current is SQLException && current.sqlState in UNIQUE_VIOLATION_SQL_STATES) {
                uniqueViolation = true
            }
            if (current is ExposedSQLException && current.sqlState in UNIQUE_VIOLATION_SQL_STATES) {
                uniqueViolation = true
            }
            if (current.message.orEmpty().contains(CONSENT_EVIDENCE_CONSTRAINT, ignoreCase = true)) {
                consentConstraint = true
            }
            current = current.cause
        }
        return uniqueViolation && consentConstraint
    }

    /** 안정적인 업무 오류를 던지고 표현식 위치에서 호출할 수 있게 `Nothing`을 반환합니다. */
    private fun reject(
        code: AppointmentCommitmentCommandError,
        message: String,
        cause: Throwable? = null,
    ): Nothing = throw AppointmentCommitmentCommandException(code, message, cause)

    /** 최초 proposal 생성 transaction의 내부 결합 결과입니다. */
    private class InitialProposal(
        val commitment: AppointmentCommitmentRecord,
        val proposal: AppointmentProposalRecord,
    )

    private object NoopAppointmentNotificationWriter : AppointmentNotificationWriter {
        override fun appointmentCreated(
            tenantGroupId: Long,
            record: AppointmentRecord,
            version: Long,
            resolution: MemberResolution,
        ) = Unit

        override fun statusChanged(
            tenantGroupId: Long,
            record: AppointmentRecord,
            version: Long,
            from: AppointmentState,
            to: AppointmentState,
        ) = Unit

        override fun cancelled(
            tenantGroupId: Long,
            record: AppointmentRecord,
            version: Long,
            reasonCode: CancellationReasonCode?,
        ) = Unit

        override fun rescheduled(
            tenantGroupId: Long,
            original: AppointmentRecord,
            replacement: AppointmentRecord,
            version: Long,
        ) = Unit

        override fun commitmentRequested(notification: CommitmentAppointmentNotification) = Unit

        override fun commitmentConfirmed(notification: CommitmentAppointmentNotification) = Unit

        override fun commitmentCancelled(
            notification: CommitmentAppointmentNotification,
            reasonCode: CancellationReasonCode?,
        ) = Unit

        override fun commitmentRescheduled(
            previous: CommitmentAppointmentNotification,
            replacement: CommitmentAppointmentNotification,
        ) = Unit
    }

    private companion object : KLogging() {
        const val INITIAL_COMMITMENT_VERSION = 1L
        const val INITIAL_PROPOSAL_REVISION = 1L
        const val OUTBOX_SCHEMA_VERSION = 1
        const val DEFAULT_MAX_TRANSACTION_ATTEMPTS = 3
        const val DEFAULT_INITIAL_RETRY_DELAY_MILLIS = 25L
        const val MAX_JITTER_MILLIS = 24L

        const val COMMITMENT_AGGREGATE_TYPE = "APPOINTMENT_COMMITMENT"
        const val IDEMPOTENCY_RESULT_PROPOSAL = "APPOINTMENT_PROPOSAL"

        const val OPERATION_CUSTOMER_REQUEST = "customer-request"
        const val OPERATION_ADMIN_APPROVAL = "admin-approval"
        const val OPERATION_DIRECT_CONFIRMATION = "direct-confirmation"
        const val OPERATION_PROPOSE_CHANGE = "propose-change"
        const val OPERATION_ACCEPT_CHANGE = "accept-change"
        const val OPERATION_DECLINE_PROPOSAL = "decline-proposal"
        const val OPERATION_EXPIRE_PROPOSAL = "expire-proposal"
        const val OPERATION_CANCEL_APPOINTMENT = "cancel-appointment"

        const val EVENT_APPOINTMENT_REQUESTED = "APPOINTMENT_REQUESTED"
        const val EVENT_APPOINTMENT_CONFIRMED = "APPOINTMENT_CONFIRMED"
        const val EVENT_APPOINTMENT_CHANGE_PROPOSED = "APPOINTMENT_CHANGE_PROPOSED"
        const val EVENT_APPOINTMENT_CONFIRMATION_CHANGED = "APPOINTMENT_CONFIRMATION_CHANGED"
        const val EVENT_APPOINTMENT_PROPOSAL_DECLINED = "APPOINTMENT_PROPOSAL_DECLINED"
        const val EVENT_APPOINTMENT_PROPOSAL_EXPIRED = "APPOINTMENT_PROPOSAL_EXPIRED"
        const val EVENT_APPOINTMENT_CANCELLED = "APPOINTMENT_CANCELLED"

        val RETRYABLE_SQL_STATES = setOf("40001", "40P01")
        val UNIQUE_VIOLATION_SQL_STATES = setOf("23505", "23000")
        const val CONSENT_EVIDENCE_CONSTRAINT = "uq_consent_evidence"
    }
}
