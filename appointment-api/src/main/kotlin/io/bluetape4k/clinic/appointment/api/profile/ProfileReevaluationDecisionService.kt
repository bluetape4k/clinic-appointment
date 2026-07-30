package io.bluetape4k.clinic.appointment.api.profile

import io.bluetape4k.clinic.appointment.api.commitment.GeneratedAppointmentProposal
import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxEvents
import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxStatus
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitmentStatus
import io.bluetape4k.clinic.appointment.model.commitment.ResourceAllocationDraft
import io.bluetape4k.clinic.appointment.model.dto.AppointmentCommitmentRecord
import io.bluetape4k.clinic.appointment.model.dto.AppointmentItemAppendScope
import io.bluetape4k.clinic.appointment.model.dto.AppointmentProposalRecord
import io.bluetape4k.clinic.appointment.model.dto.ResourceAllocationRecord
import io.bluetape4k.clinic.appointment.model.dto.ResourceAllocationStatus
import io.bluetape4k.clinic.appointment.model.profile.ProfileReevaluationOutcomeType
import io.bluetape4k.clinic.appointment.model.profile.isProfileReevaluationEligible
import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.repository.AppointmentCommitmentRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentItemRepository
import io.bluetape4k.clinic.appointment.repository.ProfileReevaluationRepository
import io.bluetape4k.clinic.appointment.repository.ResourceAllocationRepository
import io.bluetape4k.clinic.appointment.service.ProposalHasher
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * 프로필 변경 한 건이 예약 합의와 자원 점유에 미치는 결과를 원자적으로 적용합니다.
 *
 * 비용이 큰 CRM 조회와 후보 계산은 [ProfileReevaluationPlanner]가 transaction 밖에서
 * 수행합니다. 최종 transaction은 commitment와 기존 allocation을 다시 잠근 뒤 새
 * proposal·allocation 생성, commitment CAS, 이전 allocation 해제, 결과·outbox 기록을
 * 하나로 묶습니다. 계산 또는 저장 실패는 transaction 전체를 rollback합니다.
 */
class ProfileReevaluationDecisionService(
    private val database: Database,
    private val planner: ProfileReevaluationPlanner,
    private val clock: Clock = Clock.systemUTC(),
    private val commitmentRepository: AppointmentCommitmentRepository = AppointmentCommitmentRepository(),
    private val itemRepository: AppointmentItemRepository = AppointmentItemRepository(),
    private val allocationRepository: ResourceAllocationRepository = ResourceAllocationRepository(),
    private val reevaluationRepository: ProfileReevaluationRepository = ProfileReevaluationRepository(),
) {
    fun reevaluate(command: ProfileReevaluationDecisionCommand): ProfileReevaluationDecisionResult {
        val initial = transaction(database) { loadInitial(command) }
        initial.completedOutcome?.let { completed ->
            return completedResult(initial, completed.outcomeType)
        }
        if (!initial.commitment.status.isProfileReevaluationEligible) {
            return applyDecision(command, initial, ProfileReevaluationDecision.Unchanged)
        }

        val decision =
            planner.plan(
                ProfileReevaluationPlanningSnapshot(
                    commitment = initial.commitment,
                    proposal = initial.proposal,
                    activeAllocations = initial.activeAllocations,
                    pinnedPolicySnapshotId = initial.commitment.effectivePolicySnapshotId,
                ),
            )
        return applyDecision(command, initial, decision)
    }

    private fun loadInitial(command: ProfileReevaluationDecisionCommand): ReevaluationDecisionState {
        requireJobMatches(command)
        requireAppointmentScope(command)
        val commitment =
            requireNotNull(commitmentRepository.findById(command.commitmentId)) {
                "profile reevaluation commitment does not exist"
            }
        require(commitment.appointmentId == command.appointmentId) {
            "profile reevaluation commitment must belong to appointment"
        }
        val proposal =
            requireNotNull(commitmentRepository.findLatestProposal(commitment.id)) {
                "profile reevaluation proposal does not exist"
            }
        val allocations =
            allocationRepository
                .findByProposal(proposal.id)
                .filter { it.status == ResourceAllocationStatus.ACTIVE }
        return ReevaluationDecisionState(
            commitment = commitment,
            proposal = proposal,
            activeAllocations = allocations,
            completedOutcome = reevaluationRepository.findOutcome(command.jobId, command.appointmentId),
        )
    }

    private fun applyDecision(
        command: ProfileReevaluationDecisionCommand,
        initial: ReevaluationDecisionState,
        decision: ProfileReevaluationDecision,
    ): ProfileReevaluationDecisionResult =
        transaction(database) {
            requireJobMatches(command)
            require(
                reevaluationRepository.lockCurrentRevision(command.jobId, command.targetRevision),
            ) {
                "profile reevaluation job is not the current revision"
            }
            reevaluationRepository
                .findOutcomeForUpdate(command.jobId, command.appointmentId)
                ?.let { completed ->
                    return@transaction completedResult(loadCurrent(command), completed.outcomeType)
                }
            requireAppointmentScope(command)
            val currentCommitment =
                requireNotNull(commitmentRepository.findByIdForUpdate(command.commitmentId)) {
                    "profile reevaluation commitment does not exist"
                }
            require(currentCommitment.appointmentId == command.appointmentId) {
                "profile reevaluation commitment must belong to appointment"
            }
            val currentProposal =
                requireNotNull(commitmentRepository.findLatestProposalForUpdate(currentCommitment.id)) {
                    "profile reevaluation proposal does not exist"
                }
            val activeAllocations =
                allocationRepository.findActiveByProposalForUpdate(currentProposal.id)
            val current =
                ReevaluationDecisionState(
                    commitment = currentCommitment,
                    proposal = currentProposal,
                    activeAllocations = activeAllocations,
                    completedOutcome = null,
                )

            if (!currentCommitment.status.isProfileReevaluationEligible) {
                return@transaction finish(
                    command = command,
                    state = current,
                    outcomeType = ProfileReevaluationOutcomeType.SKIPPED_INELIGIBLE,
                )
            }
            if (!current.sameDecisionBoundary(initial)) {
                return@transaction finish(
                    command = command,
                    state = current,
                    outcomeType = ProfileReevaluationOutcomeType.SKIPPED_UNCHANGED,
                )
            }

            when (decision) {
                ProfileReevaluationDecision.KeepHeld -> {
                    if (currentCommitment.status != AppointmentCommitmentStatus.HELD) {
                        finish(command, current, ProfileReevaluationOutcomeType.SKIPPED_UNCHANGED)
                    } else {
                        finish(command, current, ProfileReevaluationOutcomeType.HOLD_KEPT)
                    }
                }

                ProfileReevaluationDecision.FallbackToProposed -> {
                    if (currentCommitment.status != AppointmentCommitmentStatus.HELD) {
                        finish(command, current, ProfileReevaluationOutcomeType.SKIPPED_UNCHANGED)
                    } else {
                        applyFallback(command, current)
                    }
                }

                is ProfileReevaluationDecision.ReplaceHeld -> {
                    if (currentCommitment.status != AppointmentCommitmentStatus.HELD) {
                        finish(command, current, ProfileReevaluationOutcomeType.SKIPPED_UNCHANGED)
                    } else {
                        applyCandidate(
                            command = command,
                            current = current,
                            candidate = decision.candidate,
                            nextStatus = AppointmentCommitmentStatus.HELD,
                            replaceAllocations = true,
                            outcomeType = ProfileReevaluationOutcomeType.HOLD_REPLACED,
                        )
                    }
                }

                is ProfileReevaluationDecision.SupersedeProposed -> {
                    if (currentCommitment.status != AppointmentCommitmentStatus.PROPOSED) {
                        finish(command, current, ProfileReevaluationOutcomeType.SKIPPED_UNCHANGED)
                    } else {
                        applyCandidate(
                            command = command,
                            current = current,
                            candidate = decision.candidate,
                            nextStatus = AppointmentCommitmentStatus.PROPOSED,
                            replaceAllocations = false,
                            outcomeType = ProfileReevaluationOutcomeType.PROPOSAL_SUPERSEDED,
                        )
                    }
                }

                ProfileReevaluationDecision.Unchanged ->
                    finish(
                        command = command,
                        state = current,
                        outcomeType = ProfileReevaluationOutcomeType.SKIPPED_UNCHANGED,
                    )
            }
        }

    private fun applyFallback(
        command: ProfileReevaluationDecisionCommand,
        current: ReevaluationDecisionState,
    ): ProfileReevaluationDecisionResult {
        val now = Instant.now(clock)
        check(
            commitmentRepository.advanceProfileReevaluationByVersion(
                commitmentId = current.commitment.id,
                expectedStatus = AppointmentCommitmentStatus.HELD,
                expectedVersion = current.commitment.version,
                nextStatus = AppointmentCommitmentStatus.PROPOSED,
                effectivePolicySnapshotId = current.commitment.effectivePolicySnapshotId,
                updatedAt = now,
            ),
        ) {
            "profile reevaluation commitment CAS failed"
        }
        check(
            allocationRepository.releaseActiveAllocations(current.proposal.id, now) ==
                current.activeAllocations.size,
        ) {
            "profile reevaluation did not release every active allocation"
        }
        val updated =
            requireNotNull(commitmentRepository.findById(current.commitment.id)) {
                "profile reevaluation commitment disappeared"
            }
        return finish(
            command = command,
            state = current.copy(commitment = updated),
            outcomeType = ProfileReevaluationOutcomeType.FALLBACK_TO_PROPOSED,
            completedAt = now,
        )
    }

    private fun applyCandidate(
        command: ProfileReevaluationDecisionCommand,
        current: ReevaluationDecisionState,
        candidate: ProfileReevaluationCandidate,
        nextStatus: AppointmentCommitmentStatus,
        replaceAllocations: Boolean,
        outcomeType: ProfileReevaluationOutcomeType,
    ): ProfileReevaluationDecisionResult {
        val now = Instant.now(clock)
        candidate.requireMatches(current, now)
        val generated = candidate.generated
        val proposal =
            commitmentRepository.appendProposal(
                commitmentId = current.commitment.id,
                draft = generated.proposal,
                proposalHash = generated.proposalHash,
                expiresAt = candidate.expiresAt,
                representativeTreatmentName = candidate.representativeTreatmentName,
                createdByActor = PROFILE_REEVALUATION_EMITTER,
            )
        itemRepository.appendValidated(
            scope =
                AppointmentItemAppendScope(
                    appointmentId = command.appointmentId,
                    proposalId = proposal.id,
                    tenantGroupId = command.tenantGroupId,
                    clinicId = command.clinicId,
                    patientReferenceFingerprint = command.patientReferenceFingerprint,
                ),
            items = generated.proposal.items,
        )
        itemRepository.requireResourceReferences(proposal.id, generated.resourceRequests)
        if (replaceAllocations) {
            allocationRepository.createConfirmedAllocations(
                tenantGroupId = command.tenantGroupId,
                clinicId = command.clinicId,
                proposalId = proposal.id,
                replacingProposalId = current.proposal.id,
                requests = generated.resourceRequests,
            )
        }
        check(
            commitmentRepository.advanceProfileReevaluationByVersion(
                commitmentId = current.commitment.id,
                expectedStatus = current.commitment.status,
                expectedVersion = current.commitment.version,
                nextStatus = nextStatus,
                effectivePolicySnapshotId = generated.proposal.policySnapshotId,
                updatedAt = now,
            ),
        ) {
            "profile reevaluation commitment CAS failed"
        }
        if (replaceAllocations) {
            check(
                allocationRepository.releaseActiveAllocations(current.proposal.id, now) ==
                    current.activeAllocations.size,
            ) {
                "profile reevaluation did not release every active allocation"
            }
        }
        val updated =
            requireNotNull(commitmentRepository.findById(current.commitment.id)) {
                "profile reevaluation commitment disappeared"
            }
        return finish(
            command = command,
            state =
                ReevaluationDecisionState(
                    commitment = updated,
                    proposal = proposal,
                    activeAllocations =
                        if (replaceAllocations) {
                            allocationRepository.findByProposal(proposal.id)
                        } else {
                            emptyList()
                        },
                    completedOutcome = null,
                ),
            outcomeType = outcomeType,
            completedAt = now,
        )
    }

    private fun finish(
        command: ProfileReevaluationDecisionCommand,
        state: ReevaluationDecisionState,
        outcomeType: ProfileReevaluationOutcomeType,
        completedAt: Instant = Instant.now(clock),
    ): ProfileReevaluationDecisionResult {
        reevaluationRepository.recordOutcome(
            jobId = command.jobId,
            revision = command.targetRevision,
            appointmentId = command.appointmentId,
            outcomeType = outcomeType,
        )
        writeOutbox(command, state, outcomeType, completedAt)
        return ProfileReevaluationDecisionResult(
            outcomeType = outcomeType,
            commitment = state.commitment,
            proposal = state.proposal,
            idempotentReplay = false,
        )
    }

    private fun writeOutbox(
        command: ProfileReevaluationDecisionCommand,
        state: ReevaluationDecisionState,
        outcomeType: ProfileReevaluationOutcomeType,
        completedAt: Instant,
    ) {
        val eventType = "PROFILE_REEVALUATION_${outcomeType.name}"
        val eventId =
            UUID.nameUUIDFromBytes(
                "${command.jobId}:${command.targetRevision}:${command.appointmentId}:${outcomeType.name}"
                    .toByteArray(StandardCharsets.UTF_8),
            ).toString()
        SchedulingOutboxEvents.insertIgnore {
            it[SchedulingOutboxEvents.eventId] = eventId
            it[causationEventId] = command.upstreamEventId
            it[correlationId] = "profile-reevaluation:${command.jobId}:${command.targetRevision}"
            it[SchedulingOutboxEvents.eventType] = eventType
            it[tenantGroupId] = command.tenantGroupId
            it[clinicId] = command.clinicId
            it[planId] = null
            it[aggregateType] = APPOINTMENT_COMMITMENT_AGGREGATE
            it[aggregateId] = state.commitment.id.toString()
            it[schemaVersion] = OUTBOX_SCHEMA_VERSION
            it[payloadJson] =
                buildString {
                    append("{\"jobId\":${command.jobId}")
                    append(",\"appointmentId\":${command.appointmentId}")
                    append(",\"revision\":${command.targetRevision}")
                    append(",\"outcomeType\":\"${outcomeType.name}\"")
                    append(",\"policySnapshotId\":${state.commitment.effectivePolicySnapshotId}")
                    append(",\"assessmentReference\":\"${command.assessmentReference.jsonEscaped()}\"")
                    append(",\"assessmentHash\":\"${command.assessmentHash}\"")
                    append(",\"emitter\":\"$PROFILE_REEVALUATION_EMITTER\"")
                    append(",\"eventId\":\"$eventId\"")
                    append(",\"completedAt\":\"$completedAt\"}")
                }
            it[status] = SchedulingOutboxStatus.PENDING
            it[attemptCount] = 0
        }
    }

    private fun requireJobMatches(command: ProfileReevaluationDecisionCommand) {
        val job =
            requireNotNull(reevaluationRepository.findJob(command.jobId)) {
                "profile reevaluation job does not exist"
            }
        require(
            job.targetRevision == command.targetRevision &&
                job.eventId == command.upstreamEventId &&
                job.scope.tenantGroupId == command.tenantGroupId &&
                job.scope.clinicId == command.clinicId &&
                job.scope.patientReferenceFingerprint == command.patientReferenceFingerprint &&
                job.assessmentRef == command.assessmentReference &&
                job.assessmentHash == command.assessmentHash,
        ) {
            "profile reevaluation command must match durable job"
        }
    }

    private fun requireAppointmentScope(command: ProfileReevaluationDecisionCommand) {
        val matches =
            (Appointments innerJoin Clinics)
                .selectAll()
                .where {
                    (Appointments.id eq command.appointmentId) and
                        (Appointments.clinicId eq command.clinicId) and
                        (Clinics.tenantGroupId eq command.tenantGroupId) and
                        (Appointments.patientReferenceFingerprint eq command.patientReferenceFingerprint)
                }.count() == 1L
        require(matches) { "profile reevaluation appointment scope does not match" }
    }

    private fun loadCurrent(command: ProfileReevaluationDecisionCommand): ReevaluationDecisionState {
        val commitment =
            requireNotNull(commitmentRepository.findById(command.commitmentId)) {
                "profile reevaluation commitment does not exist"
            }
        val proposal =
            requireNotNull(commitmentRepository.findLatestProposal(commitment.id)) {
                "profile reevaluation proposal does not exist"
            }
        return ReevaluationDecisionState(
            commitment = commitment,
            proposal = proposal,
            activeAllocations =
                allocationRepository
                    .findByProposal(proposal.id)
                    .filter { it.status == ResourceAllocationStatus.ACTIVE },
            completedOutcome = reevaluationRepository.findOutcome(command.jobId, command.appointmentId),
        )
    }

    private fun completedResult(
        state: ReevaluationDecisionState,
        outcomeType: ProfileReevaluationOutcomeType,
    ) = ProfileReevaluationDecisionResult(
        outcomeType = outcomeType,
        commitment = state.commitment,
        proposal = state.proposal,
        idempotentReplay = true,
    )

    private companion object {
        const val PROFILE_REEVALUATION_EMITTER = "profile-reevaluation-worker"
        const val APPOINTMENT_COMMITMENT_AGGREGATE = "APPOINTMENT_COMMITMENT"
        const val OUTBOX_SCHEMA_VERSION = 1
    }
}

/** transaction 밖의 CRM 조회·정책 평가·후보 계산 경계입니다. */
fun interface ProfileReevaluationPlanner {
    fun plan(snapshot: ProfileReevaluationPlanningSnapshot): ProfileReevaluationDecision
}

/**
 * 계산기가 기존 선점 검증에 사용할 불변 snapshot입니다.
 *
 * [pinnedPolicySnapshotId]는 현재 정책이 아니라 commitment가 이미 고정한 정책입니다.
 */
data class ProfileReevaluationPlanningSnapshot(
    val commitment: AppointmentCommitmentRecord,
    val proposal: AppointmentProposalRecord,
    val activeAllocations: List<ResourceAllocationRecord>,
    val pinnedPolicySnapshotId: Long,
)

sealed interface ProfileReevaluationDecision {
    data object KeepHeld : ProfileReevaluationDecision

    data class ReplaceHeld(
        val candidate: ProfileReevaluationCandidate,
    ) : ProfileReevaluationDecision

    data object FallbackToProposed : ProfileReevaluationDecision

    data class SupersedeProposed(
        val candidate: ProfileReevaluationCandidate,
    ) : ProfileReevaluationDecision

    data object Unchanged : ProfileReevaluationDecision
}

/** 현재 정책과 최신 assessment로 계산한 새 proposal 후보입니다. */
data class ProfileReevaluationCandidate(
    val generated: GeneratedAppointmentProposal,
    val expiresAt: Instant,
    val representativeTreatmentName: String,
) {
    init {
        representativeTreatmentName.requireNotBlank("representativeTreatmentName")
    }

    internal fun requireMatches(
        current: ReevaluationDecisionState,
        now: Instant,
    ) {
        val proposal = generated.proposal
        require(proposal.appointmentId == current.commitment.appointmentId) {
            "candidate appointment must match commitment"
        }
        require(proposal.revision == current.proposal.revision + 1L) {
            "candidate revision must immediately follow current proposal"
        }
        require(proposal.supersedesProposalId == current.proposal.id) {
            "candidate must supersede current proposal"
        }
        require(generated.proposalHash == ProposalHasher.hash(proposal)) {
            "candidate proposal hash must be canonical"
        }
        require(
            generated.resourceRequests.map { it.allocation.toComparisonKey() } ==
                proposal.allocations.map { it.toComparisonKey() },
        ) {
            "candidate resource requests must match proposal allocations"
        }
        require(expiresAt > now && expiresAt <= proposal.startsAt) {
            "candidate expiry must be active and not after proposal start"
        }
    }
}

data class ProfileReevaluationDecisionCommand(
    val jobId: Long,
    val targetRevision: Long,
    val upstreamEventId: String,
    val tenantGroupId: Long,
    val clinicId: Long,
    val patientReferenceFingerprint: String,
    val appointmentId: Long,
    val commitmentId: Long,
    val assessmentReference: String,
    val assessmentHash: String,
) {
    init {
        jobId.requirePositiveNumber("jobId")
        targetRevision.requirePositiveNumber("targetRevision")
        tenantGroupId.requirePositiveNumber("tenantGroupId")
        clinicId.requirePositiveNumber("clinicId")
        appointmentId.requirePositiveNumber("appointmentId")
        commitmentId.requirePositiveNumber("commitmentId")
        require(upstreamEventId.isNotBlank() && upstreamEventId.length <= 160) {
            "upstreamEventId must contain 1..160 characters"
        }
        require(SHA256.matches(patientReferenceFingerprint)) {
            "patientReferenceFingerprint must be lowercase SHA-256"
        }
        require(assessmentReference.isNotBlank() && assessmentReference.length <= 512) {
            "assessmentReference must contain 1..512 characters"
        }
        require(assessmentReference.none(Char::isISOControl)) {
            "assessmentReference must not contain control characters"
        }
        require(SHA256.matches(assessmentHash)) {
            "assessmentHash must be lowercase SHA-256"
        }
    }

    private companion object {
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}

data class ProfileReevaluationDecisionResult(
    val outcomeType: ProfileReevaluationOutcomeType,
    val commitment: AppointmentCommitmentRecord,
    val proposal: AppointmentProposalRecord?,
    val idempotentReplay: Boolean,
)

internal data class ReevaluationDecisionState(
    val commitment: AppointmentCommitmentRecord,
    val proposal: AppointmentProposalRecord,
    val activeAllocations: List<ResourceAllocationRecord>,
    val completedOutcome:
        io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationOutcomeRecord?,
) {
    fun sameDecisionBoundary(other: ReevaluationDecisionState): Boolean =
        commitment.id == other.commitment.id &&
            commitment.status == other.commitment.status &&
            commitment.version == other.commitment.version &&
            proposal.id == other.proposal.id
}

private fun String.jsonEscaped(): String =
    buildString(length) {
        this@jsonEscaped.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                else -> append(character)
            }
        }
    }

private fun ResourceAllocationDraft.toComparisonKey(): List<Any?> =
    listOf(
        resourceType,
        resourceId,
        startsAt,
        endsAt,
        capacityUnits,
        maximumCapacity,
        allocationMode,
        appointmentItemKey,
    )
