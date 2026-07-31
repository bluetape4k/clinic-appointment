package io.bluetape4k.clinic.appointment.api.integration

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.api.commitment.CustomerAppointmentRequestCommand
import io.bluetape4k.clinic.appointment.api.commitment.GeneratedAppointmentProposal
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationCandidate
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationDecision
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationDecisionService
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationPlanner
import io.bluetape4k.clinic.appointment.api.test.API_INTEGRATION_RESOURCE
import io.bluetape4k.clinic.appointment.api.test.Containers
import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxEvents
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitmentStatus
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentProposalDraft
import io.bluetape4k.clinic.appointment.model.dto.ResourceAllocationStatus
import io.bluetape4k.clinic.appointment.model.profile.ProfileReevaluationJobStatus
import io.bluetape4k.clinic.appointment.model.tables.ProfileReevaluationOutcomes
import io.bluetape4k.clinic.appointment.repository.AppointmentCommitmentRepository
import io.bluetape4k.clinic.appointment.repository.ProfileReevaluationRepository
import io.bluetape4k.clinic.appointment.repository.ResourceAllocationRepository
import io.bluetape4k.clinic.appointment.service.ProposalHasher
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock

/**
 * 기술 실패가 기존 예약과 자원 점유를 부분 변경하지 않는지 실제 운영 DB에서 검증합니다.
 */
internal abstract class AbstractProfileReevaluationFailureIntegrationTest :
    ProfileReevaluationDatabaseIntegrationTestSupport() {

    @Test
    fun `후보 계산 예외는 예약과 점유를 변경하지 않고 작업을 재시도 가능한 상태로 남긴다`() {
        val held = requestHeld("planner-failure", "doctor-planner-failure")
        val command = commandFor(held.commitment.id, held.commitment.appointmentId)

        assertFailsWith<IllegalStateException> {
            ProfileReevaluationDecisionService(
                database = database,
                planner = ProfileReevaluationPlanner { error("injected planning failure") },
                clock = CLOCK,
            ).reevaluate(command)
        }

        transaction(database) {
            val current =
                checkNotNull(
                    AppointmentCommitmentRepository().findById(held.commitment.id),
                )
            current shouldBeEqualTo held.commitment
            ResourceAllocationRepository()
                .findByProposal(held.proposal.id)
                .all { it.status == ResourceAllocationStatus.ACTIVE }
                .shouldBeTrue()
            ProfileReevaluationOutcomes.selectAll().count() shouldBeEqualTo 0L
            profileOutboxCount() shouldBeEqualTo 0
            ProfileReevaluationRepository()
                .findJob(command.jobId)
                ?.status shouldBeEqualTo ProfileReevaluationJobStatus.PENDING
        }
    }

    @Test
    fun `새 allocation 충돌은 proposal과 commitment와 outcome을 모두 rollback한다`() {
        requestHeld("allocation-blocker", "doctor-shared-conflict")
        val held = requestHeld("allocation-target", "doctor-original")
        val command = commandFor(held.commitment.id, held.commitment.appointmentId)
        val candidate =
            candidate(
                appointmentId = held.commitment.appointmentId,
                currentProposalId = held.proposal.id,
                revision = held.proposal.revision + 1L,
                resourceId = "doctor-shared-conflict",
            )

        assertFailsWith<IllegalStateException> {
            ProfileReevaluationDecisionService(
                database = database,
                planner =
                    ProfileReevaluationPlanner {
                        ProfileReevaluationDecision.ReplaceHeld(candidate)
                    },
                clock = CLOCK,
            ).reevaluate(command)
        }

        transaction(database) {
            val current =
                checkNotNull(
                    AppointmentCommitmentRepository().findById(held.commitment.id),
                )
            current shouldBeEqualTo held.commitment
            AppointmentCommitmentRepository()
                .findLatestProposal(held.commitment.id)
                ?.id shouldBeEqualTo held.proposal.id
            ResourceAllocationRepository()
                .findByProposal(held.proposal.id)
                .all { it.status == ResourceAllocationStatus.ACTIVE }
                .shouldBeTrue()
            ProfileReevaluationOutcomes.selectAll().count() shouldBeEqualTo 0L
            profileOutboxCount() shouldBeEqualTo 0
        }
    }

    @Test
    fun `outbox insert 실패는 commitment와 allocation과 outcome을 함께 rollback한다`() {
        val held = requestHeld("outbox-failure", "doctor-outbox-failure")
        val command = commandFor(held.commitment.id, held.commitment.appointmentId)
        transaction(database) {
            SchemaUtils.drop(SchedulingOutboxEvents)
        }
        try {
            assertFailsWith<Exception> {
                ProfileReevaluationDecisionService(
                    database = database,
                    planner =
                        ProfileReevaluationPlanner {
                            ProfileReevaluationDecision.FallbackToProposed
                        },
                    clock = CLOCK,
                ).reevaluate(command)
            }
        } finally {
            transaction(database) {
                SchemaUtils.createMissingTablesAndColumns(SchedulingOutboxEvents)
            }
        }

        transaction(database) {
            val current =
                checkNotNull(
                    AppointmentCommitmentRepository().findById(held.commitment.id),
                )
            current shouldBeEqualTo held.commitment
            ResourceAllocationRepository()
                .findByProposal(held.proposal.id)
                .all { it.status == ResourceAllocationStatus.ACTIVE }
                .shouldBeTrue()
            ProfileReevaluationOutcomes.selectAll().count() shouldBeEqualTo 0L
        }
    }

    private fun requestHeld(
        key: String,
        resourceId: String,
    ) = commandService().requestCustomerAppointment(
        CustomerAppointmentRequestCommand(
            context = commandContext(key),
            identity = appointmentIdentity(key),
            proposal = proposalInput(revision = 1L, resourceId = resourceId),
            expiresAt = ACTIVE_EXPIRY,
            representativeTreatmentName = "장애 주입 선점",
            consent = acceptedConsent(key),
            holdResources = true,
        ),
    )

    private fun candidate(
        appointmentId: Long,
        currentProposalId: Long,
        revision: Long,
        resourceId: String,
    ): ProfileReevaluationCandidate {
        val input =
            proposalInput(
                revision = revision,
                resourceId = resourceId,
                supersedesProposalId = currentProposalId,
            )
        val draft =
            AppointmentProposalDraft(
                appointmentId = appointmentId,
                revision = revision,
                startsAt = input.startsAt,
                endsAt = input.endsAt,
                items = input.items,
                allocations = input.resourceRequests.map { it.allocation },
                policySnapshotId = 31L,
                supersedesProposalId = currentProposalId,
            )
        return ProfileReevaluationCandidate(
            generated =
                GeneratedAppointmentProposal(
                    proposal = draft,
                    proposalHash = ProposalHasher.hash(draft),
                    resourceRequests = input.resourceRequests,
                ),
            expiresAt = PROPOSAL_START.minusSeconds(3_600),
            representativeTreatmentName = "장애 주입 후보",
        )
    }

    private fun profileOutboxCount(): Int =
        SchedulingOutboxEvents
            .selectAll()
            .count { it[SchedulingOutboxEvents.eventType].startsWith("PROFILE_REEVALUATION_") }
}

/**
 * PostgreSQL에서 재평가 transaction의 장애 rollback 계약을 검증합니다.
 */
@ResourceLock(value = API_INTEGRATION_RESOURCE, mode = ResourceAccessMode.READ_WRITE)
internal class ProfileReevaluationFailureIntegrationTest :
    AbstractProfileReevaluationFailureIntegrationTest() {
    override fun createDatabase(): Database {
        val postgres = Containers.Postgres
        return Database.connect(
            url = postgres.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = postgres.username ?: "test",
            password = postgres.password ?: "",
        )
    }

    private companion object {
        @JvmStatic
        @AfterAll
        fun cleanSharedSchema() = Containers.cleanPostgresSchema()
    }
}

/**
 * MySQL에서 PostgreSQL과 같은 재평가 장애 rollback 계약을 검증합니다.
 */
@ResourceLock(value = API_INTEGRATION_RESOURCE, mode = ResourceAccessMode.READ_WRITE)
internal class ProfileReevaluationFailureMySqlIntegrationTest :
    AbstractProfileReevaluationFailureIntegrationTest() {

    @Test
    fun `MySQL에서 bootstrap head의 기준 시각은 zero date를 만들지 않는다`() {
        val head =
            transaction(database) {
                ProfileReevaluationRepository()
                    .upsertEvent(profileChange(revision = 7L, occurredAt = NOW))
            }

        head.occurredAt shouldBeEqualTo NOW
    }

    override fun createDatabase(): Database {
        val mysql = Containers.MySql8
        return Database.connect(
            url = mysql.jdbcUrl,
            driver = "com.mysql.cj.jdbc.Driver",
            user = mysql.username ?: "test",
            password = mysql.password ?: "",
        )
    }

    private companion object {
        @JvmStatic
        @AfterAll
        fun cleanSharedSchema() = Containers.cleanMySqlSchema()
    }
}
