package io.bluetape4k.clinic.appointment.api.profile

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.api.commitment.CustomerAppointmentRequestCommand
import io.bluetape4k.clinic.appointment.api.commitment.GeneratedAppointmentProposal
import io.bluetape4k.clinic.appointment.api.commitment.VisitCommitmentCommandTestSupport
import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxEvents
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitmentStatus
import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationScope
import io.bluetape4k.clinic.appointment.model.dto.ResourceAllocationStatus
import io.bluetape4k.clinic.appointment.model.dto.UpsertProfileChange
import io.bluetape4k.clinic.appointment.model.profile.ProfileReevaluationOutcomeType
import io.bluetape4k.clinic.appointment.model.tables.ProfileReevaluationHeads
import io.bluetape4k.clinic.appointment.model.tables.ProfileReevaluationJobs
import io.bluetape4k.clinic.appointment.model.tables.ProfileReevaluationOutcomes
import io.bluetape4k.clinic.appointment.repository.AppointmentCommitmentRepository
import io.bluetape4k.clinic.appointment.repository.ProfileReevaluationRepository
import io.bluetape4k.clinic.appointment.repository.ResourceAllocationRepository
import io.bluetape4k.clinic.appointment.service.ProposalHasher
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

internal class ProfileReevaluationDecisionServiceTest : VisitCommitmentCommandTestSupport() {
    override fun createDatabase(): Database =
        Database.connect(
            url =
                "jdbc:h2:mem:profile_reevaluation_decision;" +
                    "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )

    @BeforeEach
    fun createProfileReevaluationTables() {
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(
                ProfileReevaluationHeads,
                ProfileReevaluationJobs,
                ProfileReevaluationOutcomes,
            )
            /*
             * Exposed가 H2 enum inList check를 schema 생성 transaction의 connection에
             * 결합하므로 다음 transaction의 insert에서 닫힌 connection을 참조한다.
             * 운영 PostgreSQL/MySQL check는 migration·통합 테스트가 검증하며, 이 H2
             * 서비스 테스트는 상태 전이 원자성만 검증하므로 해당 check만 제거한다.
             */
            exec(
                "ALTER TABLE scheduling_profile_reevaluation_jobs " +
                    "DROP CONSTRAINT IF EXISTS ck_profile_reevaluation_job_status",
            )
            exec(
                "ALTER TABLE scheduling_profile_reevaluation_jobs " +
                    "DROP CONSTRAINT IF EXISTS ck_profile_reevaluation_priority_class",
            )
            exec(
                "ALTER TABLE scheduling_profile_reevaluation_outcomes " +
                    "DROP CONSTRAINT IF EXISTS ck_profile_reevaluation_outcome_type",
            )
            ProfileReevaluationOutcomes.deleteAll()
            ProfileReevaluationJobs.deleteAll()
            ProfileReevaluationHeads.deleteAll()
        }
    }

    @Test
    fun `확정 예약은 계산기를 호출하지 않고 결과만 비식별 기록한다`() {
        val confirmed = confirmDirect(commandService(), "confirmed")
        val before = currentState(confirmed.commitment.id)
        val plannerCalled = AtomicBoolean(false)
        val service = decisionService {
            plannerCalled.set(true)
            error("confirmed appointment must not be planned")
        }
        val command = commandFor(confirmed.commitment.id, confirmed.commitment.appointmentId)

        val result = service.reevaluate(command)
        val replay = service.reevaluate(command)

        result.outcomeType shouldBeEqualTo ProfileReevaluationOutcomeType.SKIPPED_INELIGIBLE
        replay.idempotentReplay.shouldBeTrue()
        plannerCalled.get().shouldBeFalse()
        currentState(confirmed.commitment.id).let { current ->
            current.commitment shouldBeEqualTo confirmed.commitment
            current.proposal shouldBeEqualTo confirmed.proposal
            current.allocationSignatures() shouldBeEqualTo before.allocationSignatures()
        }
        transaction(database) {
            outcomeType() shouldBeEqualTo ProfileReevaluationOutcomeType.SKIPPED_INELIGIBLE
            val payload =
                SchedulingOutboxEvents
                    .selectAll()
                    .where {
                        SchedulingOutboxEvents.eventType eq "PROFILE_REEVALUATION_SKIPPED_INELIGIBLE"
                    }.single()[SchedulingOutboxEvents.payloadJson]
            payload.contains(PATIENT_REFERENCE_FINGERPRINT).shouldBeFalse()
            payload.contains("patientName").shouldBeFalse()
            SchedulingOutboxEvents
                .selectAll()
                .where {
                    SchedulingOutboxEvents.eventType eq "PROFILE_REEVALUATION_SKIPPED_INELIGIBLE"
                }.count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `유효한 선점은 고정 정책으로 검증하고 proposal과 allocation을 그대로 유지한다`() {
        val held = requestAppointment("keep", holdResources = true, resourceId = "doctor-old")
        val before = currentState(held.commitment.id)
        var observedPinnedPolicySnapshotId: Long? = null
        val service = decisionService { snapshot ->
            observedPinnedPolicySnapshotId = snapshot.pinnedPolicySnapshotId
            ProfileReevaluationDecision.KeepHeld
        }

        val result = service.reevaluate(commandFor(held.commitment.id, held.commitment.appointmentId))

        result.outcomeType shouldBeEqualTo ProfileReevaluationOutcomeType.HOLD_KEPT
        observedPinnedPolicySnapshotId shouldBeEqualTo before.commitment.effectivePolicySnapshotId
        currentState(held.commitment.id).let { after ->
            after.commitment shouldBeEqualTo before.commitment
            after.proposal.expiresAt shouldBeEqualTo before.proposal.expiresAt
            after.allocations.map { it.id } shouldBeEqualTo before.allocations.map { it.id }
            after.allocations.all { it.status == ResourceAllocationStatus.ACTIVE }.shouldBeTrue()
        }
    }

    @Test
    fun `유효하지 않은 선점은 현재 정책 후보를 만든 뒤 새 점유로 원자 교체한다`() {
        val held = requestAppointment("replace", holdResources = true, resourceId = "doctor-old")
        val before = currentState(held.commitment.id)
        val candidate = candidate(
            appointmentId = held.commitment.appointmentId,
            currentProposalId = held.proposal.id,
            revision = held.proposal.revision + 1L,
            policySnapshotId = 19L,
            resourceId = "doctor-new",
            startsAt = PROPOSAL_START.plusSeconds(7_200),
        )
        val service = decisionService { ProfileReevaluationDecision.ReplaceHeld(candidate) }

        val result = service.reevaluate(commandFor(held.commitment.id, held.commitment.appointmentId))

        result.outcomeType shouldBeEqualTo ProfileReevaluationOutcomeType.HOLD_REPLACED
        currentState(held.commitment.id).let { after ->
            after.commitment.status shouldBeEqualTo AppointmentCommitmentStatus.HELD
            after.commitment.version shouldBeEqualTo before.commitment.version + 1L
            after.commitment.effectivePolicySnapshotId shouldBeEqualTo 19L
            after.proposal.id shouldBeEqualTo result.proposal?.id
            after.proposal.supersedesProposalId shouldBeEqualTo before.proposal.id
            after.allocations.all { it.status == ResourceAllocationStatus.ACTIVE }.shouldBeTrue()
        }
        transaction(database) {
            ResourceAllocationRepository()
                .findByProposal(before.proposal.id)
                .all { it.status == ResourceAllocationStatus.RELEASED }
                .shouldBeTrue()
        }
    }

    @Test
    fun `대체 후보가 없으면 선점을 해제하고 제안 상태로 되돌린다`() {
        val held = requestAppointment("fallback", holdResources = true, resourceId = "doctor-old")
        val before = currentState(held.commitment.id)
        val service = decisionService { ProfileReevaluationDecision.FallbackToProposed }

        val result = service.reevaluate(commandFor(held.commitment.id, held.commitment.appointmentId))

        result.outcomeType shouldBeEqualTo ProfileReevaluationOutcomeType.FALLBACK_TO_PROPOSED
        currentState(held.commitment.id).let { after ->
            after.commitment.status shouldBeEqualTo AppointmentCommitmentStatus.PROPOSED
            after.commitment.version shouldBeEqualTo before.commitment.version + 1L
            after.proposal shouldBeEqualTo before.proposal
            after.allocations.all { it.status == ResourceAllocationStatus.RELEASED }.shouldBeTrue()
        }
    }

    @Test
    fun `제안 예약은 새 제안을 append하고 commitment version을 CAS 갱신한다`() {
        val proposed = requestAppointment("proposed", holdResources = false, resourceId = "doctor-old")
        val before = currentState(proposed.commitment.id)
        val candidate = candidate(
            appointmentId = proposed.commitment.appointmentId,
            currentProposalId = proposed.proposal.id,
            revision = proposed.proposal.revision + 1L,
            policySnapshotId = 23L,
            resourceId = "doctor-new",
            startsAt = PROPOSAL_START.plusSeconds(10_800),
        )
        val service = decisionService { ProfileReevaluationDecision.SupersedeProposed(candidate) }

        val result = service.reevaluate(commandFor(proposed.commitment.id, proposed.commitment.appointmentId))

        result.outcomeType shouldBeEqualTo ProfileReevaluationOutcomeType.PROPOSAL_SUPERSEDED
        currentState(proposed.commitment.id).let { after ->
            after.commitment.status shouldBeEqualTo AppointmentCommitmentStatus.PROPOSED
            after.commitment.version shouldBeEqualTo before.commitment.version + 1L
            after.commitment.effectivePolicySnapshotId shouldBeEqualTo 23L
            after.proposal.supersedesProposalId shouldBeEqualTo before.proposal.id
            after.allocations.isEmpty().shouldBeTrue()
        }
    }

    @Test
    fun `계산 실패나 새 점유 충돌은 기존 선점과 결과 기록을 모두 보존한다`() {
        requestAppointment("blocker", holdResources = true, resourceId = "doctor-blocked")
        val held = requestAppointment("technical", holdResources = true, resourceId = "doctor-old")
        val before = currentState(held.commitment.id)
        val command = commandFor(held.commitment.id, held.commitment.appointmentId)

        assertFailsWith<IllegalStateException> {
            decisionService { error("candidate calculation failed") }.reevaluate(command)
        }
        currentState(held.commitment.id).shouldMatch(before)
        outcomeCount().shouldBeEqualTo(0L)

        val conflictingCandidate = candidate(
            appointmentId = held.commitment.appointmentId,
            currentProposalId = held.proposal.id,
            revision = held.proposal.revision + 1L,
            policySnapshotId = 29L,
            resourceId = "doctor-blocked",
            startsAt = PROPOSAL_START,
        )
        assertFailsWith<IllegalStateException> {
            decisionService {
                ProfileReevaluationDecision.ReplaceHeld(conflictingCandidate)
            }.reevaluate(command)
        }

        currentState(held.commitment.id).shouldMatch(before)
        outcomeCount().shouldBeEqualTo(0L)
    }

    @Test
    fun `더 최신 프로필 revision이 도착하면 오래된 계산 결과를 commit하지 않는다`() {
        val held = requestAppointment("stale", holdResources = true, resourceId = "doctor-old")
        val before = currentState(held.commitment.id)
        val command = commandFor(held.commitment.id, held.commitment.appointmentId)
        transaction(database) {
            ProfileReevaluationRepository().upsertEvent(
                UpsertProfileChange(
                    scope =
                        ProfileReevaluationScope(
                            tenantGroupId = TENANT_ID,
                            clinicId = clinic.clinicId,
                            patientReferenceFingerprint = PATIENT_REFERENCE_FINGERPRINT,
                        ),
                    revision = 3L,
                    eventId = "profile-event-3",
                    assessmentRef = "assessment-3",
                    assessmentHash = "b".repeat(64),
                    occurredAt = NOW.plusSeconds(1),
                    heldTarget = Duration.ofSeconds(5),
                    proposedTarget = Duration.ofMinutes(5),
                    targetPolicyRef = "platform-default",
                    targetPolicyGeneration = 1L,
                ),
            )
        }

        assertFailsWith<IllegalArgumentException> {
            decisionService { ProfileReevaluationDecision.KeepHeld }.reevaluate(command)
        }

        currentState(held.commitment.id).shouldMatch(before)
        outcomeCount().shouldBeEqualTo(0L)
    }

    private fun decisionService(
        plan: (ProfileReevaluationPlanningSnapshot) ->
            ProfileReevaluationDecision,
    ) = ProfileReevaluationDecisionService(
        database = database,
        planner = ProfileReevaluationPlanner(plan),
        clock = CLOCK,
    )

    private fun requestAppointment(
        key: String,
        holdResources: Boolean,
        resourceId: String,
    ) = commandService().requestCustomerAppointment(
        CustomerAppointmentRequestCommand(
            context = commandContext(key),
            identity = appointmentIdentity(key),
            proposal = proposalInput(revision = 1L, resourceId = resourceId),
            expiresAt = ACTIVE_EXPIRY,
            representativeTreatmentName = "미백 치료",
            consent = acceptedConsent(key),
            holdResources = holdResources,
        ),
    )

    private fun commandFor(
        commitmentId: Long,
        appointmentId: Long,
    ): ProfileReevaluationDecisionCommand {
        val jobId = seedJob()
        return ProfileReevaluationDecisionCommand(
            jobId = jobId,
            targetRevision = 2L,
            upstreamEventId = "profile-event-2",
            tenantGroupId = TENANT_ID,
            clinicId = clinic.clinicId,
            patientReferenceFingerprint = PATIENT_REFERENCE_FINGERPRINT,
            appointmentId = appointmentId,
            commitmentId = commitmentId,
            assessmentReference = "assessment-2",
            assessmentHash = "a".repeat(64),
        )
    }

    private fun seedJob(): Long =
        transaction(database) {
            val scope =
                ProfileReevaluationScope(
                    tenantGroupId = TENANT_ID,
                    clinicId = clinic.clinicId,
                    patientReferenceFingerprint = PATIENT_REFERENCE_FINGERPRINT,
                )
            ProfileReevaluationRepository().upsertEvent(
                UpsertProfileChange(
                    scope = scope,
                    revision = 2L,
                    eventId = "profile-event-2",
                    assessmentRef = "assessment-2",
                    assessmentHash = "a".repeat(64),
                    occurredAt = NOW,
                    heldTarget = Duration.ofSeconds(5),
                    proposedTarget = Duration.ofMinutes(5),
                    targetPolicyRef = "platform-default",
                    targetPolicyGeneration = 1L,
                ),
            )
            ProfileReevaluationRepository().findJobs(scope).single().id
        }

    private fun candidate(
        appointmentId: Long,
        currentProposalId: Long,
        revision: Long,
        policySnapshotId: Long,
        resourceId: String,
        startsAt: Instant,
    ): ProfileReevaluationCandidate {
        val input =
            proposalInput(
                revision = revision,
                resourceId = resourceId,
                startsAt = startsAt,
                supersedesProposalId = currentProposalId,
            )
        val draft =
            io.bluetape4k.clinic.appointment.model.commitment.AppointmentProposalDraft(
                appointmentId = appointmentId,
                revision = revision,
                startsAt = input.startsAt,
                endsAt = input.endsAt,
                items = input.items,
                allocations = input.resourceRequests.map { it.allocation },
                policySnapshotId = policySnapshotId,
                supersedesProposalId = currentProposalId,
            )
        return ProfileReevaluationCandidate(
            generated =
                GeneratedAppointmentProposal(
                    proposal = draft,
                    proposalHash = ProposalHasher.hash(draft),
                    resourceRequests = input.resourceRequests,
                ),
            expiresAt = startsAt.minusSeconds(3_600),
            representativeTreatmentName = "미백 치료",
        )
    }

    private fun currentState(commitmentId: Long): DecisionState =
        transaction(database) {
            val commitment = checkNotNull(AppointmentCommitmentRepository().findById(commitmentId))
            val proposal = checkNotNull(AppointmentCommitmentRepository().findLatestProposal(commitmentId))
            DecisionState(
                commitment = commitment,
                proposal = proposal,
                allocations = ResourceAllocationRepository().findByProposal(proposal.id),
            )
        }

    private fun outcomeCount(): Long =
        transaction(database) {
            ProfileReevaluationOutcomes.selectAll().count()
        }

    private fun outcomeType(): ProfileReevaluationOutcomeType =
        ProfileReevaluationOutcomes
            .selectAll()
            .single()[ProfileReevaluationOutcomes.outcomeType]
}

private data class DecisionState(
    val commitment: io.bluetape4k.clinic.appointment.model.dto.AppointmentCommitmentRecord,
    val proposal: io.bluetape4k.clinic.appointment.model.dto.AppointmentProposalRecord,
    val allocations: List<io.bluetape4k.clinic.appointment.model.dto.ResourceAllocationRecord>,
)

private fun DecisionState.shouldMatch(expected: DecisionState) {
    commitment shouldBeEqualTo expected.commitment
    proposal shouldBeEqualTo expected.proposal
    allocationSignatures() shouldBeEqualTo expected.allocationSignatures()
}

private fun DecisionState.allocationSignatures(): List<List<Any?>> =
    allocations.map { record ->
        val allocation = record.allocation
        listOf(
            record.id,
            record.tenantGroupId,
            record.clinicId,
            record.proposalId,
            allocation.resourceType,
            allocation.resourceId,
            allocation.startsAt,
            allocation.endsAt,
            allocation.capacityUnits,
            allocation.maximumCapacity,
            allocation.allocationMode,
            allocation.appointmentItemKey,
            record.maximumCapacity,
            record.status,
        )
    }
