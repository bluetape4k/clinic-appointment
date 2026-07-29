package io.bluetape4k.clinic.appointment.api.commitment

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.clinic.appointment.api.test.API_INTEGRATION_RESOURCE
import io.bluetape4k.clinic.appointment.api.test.Containers
import io.bluetape4k.clinic.appointment.model.commitment.ResourceAllocationMode
import io.bluetape4k.clinic.appointment.model.dto.ResourceAllocationStatus
import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.tables.ConsentDecisions
import io.bluetape4k.clinic.appointment.model.tables.ResourceAllocations
import io.bluetape4k.clinic.appointment.repository.AppointmentCommitmentRepository
import io.bluetape4k.clinic.appointment.repository.ResourceAllocationRepository
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock
import java.time.Clock
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier

/**
 * PostgreSQL row lock과 commitment version CAS가 함께 수렴하는지 검증합니다.
 *
 * `MultithreadingTester`는 두 database command의 예외 전파와 worker 수명주기를 관리하고,
 * [CyclicBarrier]는 서로 다른 proposal이 같은 expected version을 읽은 뒤 경쟁하도록
 * 시작 지점만 맞춥니다. 일반 stress 반복보다 정확한 두 transaction 결과가 필요하므로
 * worker별 결과는 thread-safe queue에 수집합니다.
 */
@ResourceLock(value = API_INTEGRATION_RESOURCE, mode = ResourceAccessMode.READ_WRITE)
internal class VisitCommitmentConcurrencyTest : VisitCommitmentCommandTestSupport() {
    override fun createDatabase(): Database {
        val postgres = Containers.Postgres
        return Database.connect(
            url = postgres.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = postgres.username ?: "test",
            password = postgres.password ?: "",
        )
    }

    @Test
    fun `PostgreSQL도 중복 동의 증빙을 안정 오류로 변환하고 transaction을 rollback한다`() {
        val service = commandService()
        val proposal = proposalInput(revision = 1L, resourceId = "doctor-${clinic.doctorId}")
        service.requestCustomerAppointment(
            CustomerAppointmentRequestCommand(
                context = commandContext("postgres-consent-owner"),
                identity = appointmentIdentity("postgres-consent-owner"),
                proposal = proposal,
                expiresAt = ACTIVE_EXPIRY,
                representativeTreatmentName = "첫 예약",
                consent = acceptedConsent("postgres-shared-evidence"),
            ),
        )

        val failure = assertFailsWith<AppointmentCommitmentCommandException> {
            service.requestCustomerAppointment(
                CustomerAppointmentRequestCommand(
                    context = commandContext("postgres-consent-reuse"),
                    identity = appointmentIdentity("postgres-consent-reuse"),
                    proposal = proposal,
                    expiresAt = ACTIVE_EXPIRY,
                    representativeTreatmentName = "두 번째 예약",
                    consent = acceptedConsent("postgres-shared-evidence"),
                ),
            )
        }

        failure.code shouldBeEqualTo AppointmentCommitmentCommandError.CONSENT_EVIDENCE_REUSED
        transaction(database) {
            ConsentDecisions.selectAll().count() shouldBeEqualTo 1L
            Appointments.selectAll().count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `서로 다른 proposal 동시 수락은 하나만 확정하고 예약 손실을 만들지 않는다`() {
        // Given: 같은 기존 확정 예약을 대체하려는 서로 다른 자원의 proposal 두 개
        val service = commandService()
        val original = confirmDirect(service, "concurrent-original")
        val firstInput =
            proposalInput(
                revision = 2L,
                resourceId = "doctor-concurrent-a",
                supersedesProposalId = original.proposal.id,
            )
        val secondInput =
            proposalInput(
                revision = 3L,
                resourceId = "doctor-concurrent-b",
                supersedesProposalId = original.proposal.id,
            )
        val first =
            service.proposeChange(
                ChangeAppointmentProposalCommand(
                    context = commandContext("concurrent-propose-a"),
                    appointmentId = original.commitment.appointmentId,
                    expectedVersion = original.commitment.version,
                    proposal = firstInput,
                    expiresAt = ACTIVE_EXPIRY,
                    representativeTreatmentName = "동시 변경 A",
                ),
            )
        val second =
            service.proposeChange(
                ChangeAppointmentProposalCommand(
                    context = commandContext("concurrent-propose-b"),
                    appointmentId = original.commitment.appointmentId,
                    expectedVersion = original.commitment.version,
                    proposal = secondInput,
                    expiresAt = ACTIVE_EXPIRY,
                    representativeTreatmentName = "동시 변경 B",
                ),
            )
        val barrier = CyclicBarrier(2)
        val results = ConcurrentLinkedQueue<Result<AppointmentCommitmentCommandResult>>()

        // When: 두 고객 수락 command가 서로 다른 멱등 키로 동시에 실행
        MultithreadingTester()
            .workers(2)
            .rounds(1)
            .addAll(
                {
                    barrier.await()
                    results +=
                        runCatching {
                            service.acceptProposal(
                                AcceptAppointmentProposalCommand(
                                    context = commandContext("concurrent-accept-a"),
                                    appointmentId = original.commitment.appointmentId,
                                    proposalId = first.proposal.id,
                                    expectedVersion = original.commitment.version,
                                    proposal = firstInput,
                                    expectedProposalHash = first.proposal.proposalHash,
                                    projectionTarget = confirmedProjectionTarget("doctor-concurrent-a"),
                                    consent = acceptedConsent("concurrent-accept-a"),
                                ),
                            )
                        }
                },
                {
                    barrier.await()
                    results +=
                        runCatching {
                            service.acceptProposal(
                                AcceptAppointmentProposalCommand(
                                    context = commandContext("concurrent-accept-b"),
                                    appointmentId = original.commitment.appointmentId,
                                    proposalId = second.proposal.id,
                                    expectedVersion = original.commitment.version,
                                    proposal = secondInput,
                                    expectedProposalHash = second.proposal.proposalHash,
                                    projectionTarget = confirmedProjectionTarget("doctor-concurrent-b"),
                                    consent = acceptedConsent("concurrent-accept-b"),
                                ),
                            )
                        }
                },
            ).run()

        // Then: 정확히 한 proposal만 이기고 loser의 새 allocation은 rollback됨
        val successful = results.mapNotNull(Result<AppointmentCommitmentCommandResult>::getOrNull)
        val failed =
            results
                .mapNotNull(Result<AppointmentCommitmentCommandResult>::exceptionOrNull)
                .filterIsInstance<AppointmentCommitmentCommandException>()
        successful shouldHaveSize 1
        failed shouldHaveSize 1
        failed.single().code shouldBeEqualTo AppointmentCommitmentCommandError.VERSION_CONFLICT

        val winningProposalId = successful.single().proposal.id
        val losingProposalId =
            setOf(first.proposal.id, second.proposal.id)
                .single { it != winningProposalId }
        val current = currentConfirmation(original.commitment.appointmentId)
        current.proposal.id shouldBeEqualTo winningProposalId
        current.allocations.single().status shouldBeEqualTo ResourceAllocationStatus.ACTIVE
        transaction(database) {
            ResourceAllocationRepository()
                .findByProposal(original.proposal.id)
                .single()
                .status shouldBeEqualTo ResourceAllocationStatus.RELEASED
            ResourceAllocationRepository().findByProposal(losingProposalId) shouldHaveSize 0
        }
        results.all(Result<AppointmentCommitmentCommandResult>::isSuccess).shouldBeFalse()
        (current.allocations.count { it.status == ResourceAllocationStatus.ACTIVE } == 1).shouldBeTrue()
    }

    @Test
    fun `같은 변경 proposal 수락과 거부는 하나의 종결 결정만 반영한다`() {
        // Given: 기존 확정을 대체할 하나의 변경 proposal
        val service = commandService()
        val original = confirmDirect(service, "accept-decline-original")
        val changeInput =
            proposalInput(
                revision = 2L,
                resourceId = "doctor-accept-decline",
                supersedesProposalId = original.proposal.id,
            )
        val change =
            service.proposeChange(
                ChangeAppointmentProposalCommand(
                    context = commandContext("accept-decline-propose"),
                    appointmentId = original.commitment.appointmentId,
                    expectedVersion = original.commitment.version,
                    proposal = changeInput,
                    expiresAt = ACTIVE_EXPIRY,
                    representativeTreatmentName = "수락 거부 경합",
                ),
            )

        // When: 같은 expected version에서 고객 수락과 거부가 동시에 도착
        val results =
            executeConcurrentTerminalCommands(
                firstOperation = "accept",
                firstCommand = {
                    service.acceptProposal(
                        AcceptAppointmentProposalCommand(
                            context = commandContext("accept-decline-accept"),
                            appointmentId = original.commitment.appointmentId,
                            proposalId = change.proposal.id,
                            expectedVersion = original.commitment.version,
                            proposal = changeInput,
                            expectedProposalHash = change.proposal.proposalHash,
                            projectionTarget = confirmedProjectionTarget("doctor-accept-decline"),
                            consent = acceptedConsent("accept-decline-accept"),
                        ),
                    )
                },
                secondOperation = "decline",
                secondCommand = {
                    service.declineProposal(
                        DeclineAppointmentProposalCommand(
                            context = commandContext("accept-decline-decline"),
                            appointmentId = original.commitment.appointmentId,
                            proposalId = change.proposal.id,
                            expectedVersion = original.commitment.version,
                            expectedProposalHash = change.proposal.proposalHash,
                            consent = declinedConsent("accept-decline-decline"),
                        ),
                    )
                },
            )

        // Then: proposal row lock과 commitment CAS가 정확히 한 종결 command만 허용
        assertSingleTerminalWinner(results)
        val winner = results.single { it.result.isSuccess }.operation
        val current = currentConfirmation(original.commitment.appointmentId)
        current.commitment.version shouldBeEqualTo original.commitment.version + 1L
        if (winner == "accept") {
            current.proposal.id shouldBeEqualTo change.proposal.id
            current.allocations.single().status shouldBeEqualTo ResourceAllocationStatus.ACTIVE
        } else {
            current.proposal.id shouldBeEqualTo original.proposal.id
            transaction(database) {
                ResourceAllocationRepository().findByProposal(change.proposal.id) shouldHaveSize 0
            }
        }
    }

    @Test
    fun `같은 변경 proposal 수락과 만료는 하나의 종결 결정만 반영한다`() {
        // Given: 수락 가능한 clock과 만료 clock이 같은 변경 proposal을 바라봄
        val acceptanceService = commandService()
        val original = confirmDirect(acceptanceService, "accept-expire-original")
        val changeInput =
            proposalInput(
                revision = 2L,
                resourceId = "doctor-accept-expire",
                supersedesProposalId = original.proposal.id,
            )
        val change =
            acceptanceService.proposeChange(
                ChangeAppointmentProposalCommand(
                    context = commandContext("accept-expire-propose"),
                    appointmentId = original.commitment.appointmentId,
                    expectedVersion = original.commitment.version,
                    proposal = changeInput,
                    expiresAt = ACTIVE_EXPIRY,
                    representativeTreatmentName = "수락 만료 경합",
                ),
            )
        val expiryService =
            commandService(
                clock = Clock.fixed(ACTIVE_EXPIRY, ZoneOffset.UTC),
            )

        // When: 유효시간 직전 수락과 권위 있는 만료 기록이 동시에 실행
        val results =
            executeConcurrentTerminalCommands(
                firstOperation = "accept",
                firstCommand = {
                    acceptanceService.acceptProposal(
                        AcceptAppointmentProposalCommand(
                            context = commandContext("accept-expire-accept"),
                            appointmentId = original.commitment.appointmentId,
                            proposalId = change.proposal.id,
                            expectedVersion = original.commitment.version,
                            proposal = changeInput,
                            expectedProposalHash = change.proposal.proposalHash,
                            projectionTarget = confirmedProjectionTarget("doctor-accept-expire"),
                            consent = acceptedConsent("accept-expire-accept"),
                        ),
                    )
                },
                secondOperation = "expire",
                secondCommand = {
                    expiryService.expireProposal(
                        ExpireAppointmentProposalCommand(
                            context = commandContext("accept-expire-expire"),
                            appointmentId = original.commitment.appointmentId,
                            proposalId = change.proposal.id,
                            expectedVersion = original.commitment.version,
                            expectedProposalHash = change.proposal.proposalHash,
                        ),
                    )
                },
            )

        // Then: loser는 version conflict로 rollback되고 포인터·allocation·만료 표식이 일관됨
        assertSingleTerminalWinner(results)
        val winner = results.single { it.result.isSuccess }.operation
        val current = currentConfirmation(original.commitment.appointmentId)
        val persistedChange =
            transaction(database) {
                checkNotNull(
                    AppointmentCommitmentRepository()
                        .findProposal(current.commitment.id, change.proposal.id),
                )
            }
        current.commitment.version shouldBeEqualTo original.commitment.version + 1L
        if (winner == "accept") {
            current.proposal.id shouldBeEqualTo change.proposal.id
            persistedChange.expiredAt shouldBeEqualTo null
            current.allocations.single().status shouldBeEqualTo ResourceAllocationStatus.ACTIVE
        } else {
            current.proposal.id shouldBeEqualTo original.proposal.id
            persistedChange.expiredAt shouldBeEqualTo ACTIVE_EXPIRY
            transaction(database) {
                ResourceAllocationRepository().findByProposal(change.proposal.id) shouldHaveSize 0
            }
        }
    }

    @Test
    fun `시작 시각이 다른 동일 전담 자원 동시 확정은 하나만 성공한다`() {
        // Given: 시작점은 다르지만 30분 겹치는 동일 의료진 전담 proposal 두 개
        val service = commandService()
        val resourceId = "doctor-resource-mutex"
        val firstInput =
            proposalInput(
                revision = 1L,
                resourceId = resourceId,
                startsAt = PROPOSAL_START,
            )
        val secondInput =
            proposalInput(
                revision = 1L,
                resourceId = resourceId,
                startsAt = PROPOSAL_START.plusSeconds(1_800),
            )

        // When: 서로 다른 시작 시각의 신규 예약을 PostgreSQL에서 동시에 확정
        val results =
            executeConcurrentDirectConfirmations(
                service = service,
                firstKey = "resource-mutex-a",
                firstInput = firstInput,
                secondKey = "resource-mutex-b",
                secondInput = secondInput,
            )

        // Then: 자원 단위 mutex 뒤 재검증으로 한 command만 확정
        assertSingleResourceConflict(results)
        transaction(database) {
            ResourceAllocations
                .selectAll()
                .where {
                    (ResourceAllocations.resourceId eq resourceId) and
                        (ResourceAllocations.status eq ResourceAllocationStatus.ACTIVE)
                }.count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `시작 시각이 다른 capacity 동시 확정은 상한을 초과하지 않는다`() {
        // Given: 최대 1명인 같은 capacity 자원에서 30분 겹치는 proposal 두 개
        val service = commandService()
        val resourceId = "capacity-resource-mutex"
        val firstInput =
            proposalInput(
                revision = 1L,
                resourceId = resourceId,
                startsAt = PROPOSAL_START,
                allocationMode = ResourceAllocationMode.CAPACITY_BUCKET,
                maximumCapacity = 1,
                practitionerResourceId = "doctor-capacity-a",
            )
        val secondInput =
            proposalInput(
                revision = 1L,
                resourceId = resourceId,
                startsAt = PROPOSAL_START.plusSeconds(1_800),
                allocationMode = ResourceAllocationMode.CAPACITY_BUCKET,
                maximumCapacity = 1,
                practitionerResourceId = "doctor-capacity-b",
            )

        // When: 서로 다른 시작 시각의 capacity 예약을 동시에 확정
        val results =
            executeConcurrentDirectConfirmations(
                service = service,
                firstKey = "capacity-mutex-a",
                firstInput = firstInput,
                secondKey = "capacity-mutex-b",
                secondInput = secondInput,
            )

        // Then: commit 뒤 활성 사용량은 상한 1을 넘지 않음
        assertSingleResourceConflict(results)
        transaction(database) {
            ResourceAllocations
                .selectAll()
                .where {
                    (ResourceAllocations.resourceId eq resourceId) and
                        (ResourceAllocations.status eq ResourceAllocationStatus.ACTIVE)
                }.sumOf { it[ResourceAllocations.capacityUnits] } shouldBeEqualTo 1
        }
    }

    /**
     * 두 신규 직접 확정 command의 시작점만 barrier로 맞추고 실제 transaction 수명주기는
     * [MultithreadingTester]가 관리합니다.
     */
    private fun executeConcurrentDirectConfirmations(
        service: AppointmentCommitmentCommandService,
        firstKey: String,
        firstInput: VisitProposalInput,
        secondKey: String,
        secondInput: VisitProposalInput,
    ): List<Result<AppointmentCommitmentCommandResult>> {
        val barrier = CyclicBarrier(2)
        val results = ConcurrentLinkedQueue<Result<AppointmentCommitmentCommandResult>>()
        val firstCommand = directConfirmationCommand(firstKey, firstInput)
        val secondCommand = directConfirmationCommand(secondKey, secondInput)

        MultithreadingTester()
            .workers(2)
            .rounds(1)
            .addAll(
                {
                    barrier.await()
                    results += runCatching { service.confirmDirectAppointment(firstCommand) }
                },
                {
                    barrier.await()
                    results += runCatching { service.confirmDirectAppointment(secondCommand) }
                },
            ).run()
        return results.toList()
    }

    /** 동시 확정용 command에서 멱등·동의 증빙을 각 예약에 독립적으로 결합합니다. */
    private fun directConfirmationCommand(
        key: String,
        proposal: VisitProposalInput,
    ) = DirectAppointmentConfirmationCommand(
        context = commandContext(key),
        identity = appointmentIdentity(key),
        proposal = proposal,
        expiresAt = ACTIVE_EXPIRY,
        representativeTreatmentName = "동시 자원 확정",
        projectionTarget =
            confirmedProjectionTarget(
                proposal.resourceRequests
                    .first()
                    .allocation.resourceId,
            ),
        policyDecision = directConfirmationPolicyDecision(),
        consent = acceptedConsent(key),
    )

    /** 두 경합 결과가 정확히 한 성공과 한 안정적인 자원 충돌로 수렴했는지 검증합니다. */
    private fun assertSingleResourceConflict(results: List<Result<AppointmentCommitmentCommandResult>>) {
        results.mapNotNull(Result<AppointmentCommitmentCommandResult>::getOrNull) shouldHaveSize 1
        val failures =
            results
                .mapNotNull(Result<AppointmentCommitmentCommandResult>::exceptionOrNull)
                .filterIsInstance<AppointmentCommitmentCommandException>()
        failures shouldHaveSize 1
        failures.single().code shouldBeEqualTo AppointmentCommitmentCommandError.RESOURCE_CONFLICT
    }

    /**
     * 같은 proposal을 종결하는 두 command의 시작점만 맞추고 operation label과 결과를 보존합니다.
     */
    private fun executeConcurrentTerminalCommands(
        firstOperation: String,
        firstCommand: () -> AppointmentCommitmentCommandResult,
        secondOperation: String,
        secondCommand: () -> AppointmentCommitmentCommandResult,
    ): List<TerminalCommandResult> {
        val barrier = CyclicBarrier(2)
        val results = ConcurrentLinkedQueue<TerminalCommandResult>()
        MultithreadingTester()
            .workers(2)
            .rounds(1)
            .addAll(
                {
                    barrier.await()
                    results += TerminalCommandResult(firstOperation, runCatching(firstCommand))
                },
                {
                    barrier.await()
                    results += TerminalCommandResult(secondOperation, runCatching(secondCommand))
                },
            ).run()
        return results.toList()
    }

    /** 종결 경쟁이 정확히 한 성공과 한 안정적인 version 충돌로 수렴했는지 검증합니다. */
    private fun assertSingleTerminalWinner(results: List<TerminalCommandResult>) {
        results.count { it.result.isSuccess } shouldBeEqualTo 1
        val failures =
            results
                .mapNotNull { it.result.exceptionOrNull() }
                .filterIsInstance<AppointmentCommitmentCommandException>()
        failures shouldHaveSize 1
        failures.single().code shouldBeEqualTo AppointmentCommitmentCommandError.VERSION_CONFLICT
    }

    /** 동시 종결 command의 종류와 transaction 결과를 thread-safe queue 밖으로 전달합니다. */
    private class TerminalCommandResult(
        val operation: String,
        val result: Result<AppointmentCommitmentCommandResult>,
    )
}
