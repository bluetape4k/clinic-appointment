package io.bluetape4k.clinic.appointment.api.commitment

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxEvents
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitmentStatus
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentItemDraft
import io.bluetape4k.clinic.appointment.model.commitment.ResourceAllocationDraft
import io.bluetape4k.clinic.appointment.model.dto.AppointmentCommandResultRecord
import io.bluetape4k.clinic.appointment.model.dto.CommandClaimResult
import io.bluetape4k.clinic.appointment.model.dto.ResourceAllocationRequest
import io.bluetape4k.clinic.appointment.model.dto.ResourceAllocationStatus
import io.bluetape4k.clinic.appointment.model.policy.AdminBookingMode
import io.bluetape4k.clinic.appointment.model.tables.AppointmentAuditEvents
import io.bluetape4k.clinic.appointment.model.tables.AppointmentCommandIdempotencies
import io.bluetape4k.clinic.appointment.model.tables.AppointmentItems
import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.tables.ConsentDecisions
import io.bluetape4k.clinic.appointment.repository.AppointmentCommandIdempotencyRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentCommitmentRepository
import io.bluetape4k.clinic.appointment.repository.ResourceAllocationRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

internal class AppointmentCommitmentCommandServiceTest : VisitCommitmentCommandTestSupport() {
    @Test
    fun `고객 요청은 가예약으로 생성되고 관리자 승인은 정확한 동의 proposal만 확정한다`() {
        // Given: 고객이 직접 선택하고 동의한 정확한 방문 제안
        val service = commandService()
        val proposal = proposalInput(revision = 1L, resourceId = "doctor-${clinic.doctorId}")

        // When: 고객 요청 후 병원 관리자가 같은 proposal을 승인
        val requested =
            service.requestCustomerAppointment(
                CustomerAppointmentRequestCommand(
                    context = commandContext("customer-request"),
                    identity = appointmentIdentity("customer-request"),
                    proposal = proposal,
                    expiresAt = ACTIVE_EXPIRY,
                    representativeTreatmentName = "미백 치료",
                    consent = acceptedConsent("customer-request"),
                ),
            )
        val confirmed =
            service.approveCustomerProposal(
                ConfirmAppointmentProposalCommand(
                    context = commandContext("admin-approve"),
                    appointmentId = requested.commitment.appointmentId,
                    proposalId = requested.proposal.id,
                    expectedVersion = requested.commitment.version,
                    proposal = proposal,
                    expectedProposalHash = requested.proposal.proposalHash,
                    projectionTarget = confirmedProjectionTarget(),
                ),
            )

        // Then: 확정 proposal, 자원, legacy projection, 감사/outbox가 함께 반영됨
        requested.commitment.status shouldBeEqualTo AppointmentCommitmentStatus.PROPOSED
        confirmed.commitment.status shouldBeEqualTo AppointmentCommitmentStatus.CONFIRMED
        confirmed.commitment.confirmedProposalId shouldBeEqualTo requested.proposal.id
        transaction(database) {
            ResourceAllocationRepository()
                .findByProposal(requested.proposal.id)
                .single()
                .status shouldBeEqualTo ResourceAllocationStatus.ACTIVE
            AppointmentAuditEvents
                .selectAll()
                .where { AppointmentAuditEvents.eventType eq "APPOINTMENT_CONFIRMED" }
                .count() shouldBeEqualTo 1L
            SchedulingOutboxEvents
                .selectAll()
                .where { SchedulingOutboxEvents.eventType eq "APPOINTMENT_CONFIRMED" }
                .count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `관리자 직접 확정은 고객 동의 자원 점유 projection을 한 transaction으로 만든다`() {
        // Given: 고객 동의를 이미 받은 병원 제안
        val service = commandService()

        // When: 관리자가 직접 확정
        val result = confirmDirect(service, "direct-confirm")

        // Then: 별도 승인 대기 없이 같은 command 결과가 확정 상태를 이룸
        result.commitment.status shouldBeEqualTo AppointmentCommitmentStatus.CONFIRMED
        result.commitment.confirmedProposalId shouldBeEqualTo result.proposal.id
        currentConfirmation(result.commitment.appointmentId)
            .allocations
            .single()
            .status shouldBeEqualTo ResourceAllocationStatus.ACTIVE
        transaction(database) {
            val row =
                Appointments
                    .selectAll()
                    .where { Appointments.id eq result.commitment.appointmentId }
                    .single()
            row[Appointments.appointmentDate] shouldBeEqualTo LocalDate.of(2026, 8, 10)
            row[Appointments.startTime] shouldBeEqualTo LocalTime.of(10, 0)
            row[Appointments.endTime] shouldBeEqualTo LocalTime.of(11, 0)
            val consent =
                ConsentDecisions
                    .selectAll()
                    .where { ConsentDecisions.commitmentId eq result.commitment.id }
                    .single()
            consent[ConsentDecisions.evidenceType] shouldBeEqualTo "SIGNED_FORM"
            consent[ConsentDecisions.termsHash] shouldBeEqualTo "d".repeat(64)
        }
    }

    @Test
    fun `자원 item key가 proposal item을 가리키지 않으면 직접확정을 rollback한다`() {
        // Given: Plan item은 정상이나 자원 점유만 존재하지 않는 item key를 참조함
        val service = commandService()
        val valid = proposalInput(revision = 1L, resourceId = "doctor-missing-item")
        val sourceRequest = valid.resourceRequests.single()
        val sourceAllocation = sourceRequest.allocation
        val invalid =
            VisitProposalInput(
                revision = valid.revision,
                startsAt = valid.startsAt,
                endsAt = valid.endsAt,
                items = valid.items,
                resourceRequests =
                    listOf(
                        ResourceAllocationRequest(
                            allocation =
                                ResourceAllocationDraft(
                                    resourceType = sourceAllocation.resourceType,
                                    resourceId = sourceAllocation.resourceId,
                                    startsAt = sourceAllocation.startsAt,
                                    endsAt = sourceAllocation.endsAt,
                                    capacityUnits = sourceAllocation.capacityUnits,
                                    allocationMode = sourceAllocation.allocationMode,
                                    appointmentItemKey = "missing-treatment",
                                ),
                            maximumCapacity = sourceRequest.maximumCapacity,
                        ),
                    ),
                policySnapshotId = valid.policySnapshotId,
                supersedesProposalId = valid.supersedesProposalId,
            )

        // When
        val failure =
            assertFailsWith<AppointmentCommitmentCommandException> {
                service.confirmDirectAppointment(
                    DirectAppointmentConfirmationCommand(
                        context = commandContext("missing-item-reference"),
                        identity = appointmentIdentity("missing-item-reference"),
                        proposal = invalid,
                        expiresAt = ACTIVE_EXPIRY,
                        representativeTreatmentName = "잘못된 자원 item 참조",
                        projectionTarget = confirmedProjectionTarget("doctor-missing-item"),
                        policyDecision = directConfirmationPolicyDecision(),
                        consent = acceptedConsent("missing-item-reference"),
                    ),
                )
            }

        // Then: 안정적인 오류이며 appointment부터 전체 transaction이 rollback됨
        failure.code shouldBeEqualTo AppointmentCommitmentCommandError.APPOINTMENT_ITEM_INVALID
        transaction(database) {
            Appointments.selectAll().count() shouldBeEqualTo 0L
            AppointmentItems.selectAll().count() shouldBeEqualTo 0L
        }
    }

    @Test
    fun `한 방문의 여러 세부진료와 자원을 proposal item snapshot으로 함께 확정한다`() {
        // Given: 미백과 사후 상담을 한 방문에서 수행하는 패키지 proposal
        val service = commandService()
        val proposal =
            proposalInput(
                revision = 1L,
                resourceId = "doctor-multi-item",
                includeConsultationItem = true,
                consultationResourceId = "equipment-consultation-1",
            )

        // When: 정확한 직접 확정 정책과 고객 동의로 방문을 확정
        val result =
            service.confirmDirectAppointment(
                DirectAppointmentConfirmationCommand(
                    context = commandContext("multi-item-direct"),
                    identity = appointmentIdentity("multi-item-direct"),
                    proposal = proposal,
                    expiresAt = ACTIVE_EXPIRY,
                    representativeTreatmentName = "미백 패키지",
                    projectionTarget = confirmedProjectionTarget("doctor-multi-item"),
                    policyDecision = directConfirmationPolicyDecision(),
                    consent = acceptedConsent("multi-item-direct"),
                ),
            )

        // Then: proposal에 두 item과 두 실제 자원 점유가 함께 고정됨
        transaction(database) {
            AppointmentItems
                .selectAll()
                .where { AppointmentItems.proposalId eq result.proposal.id }
                .count() shouldBeEqualTo 2L
            ResourceAllocationRepository().findByProposal(result.proposal.id) shouldHaveSize 2
        }
    }

    @Test
    fun `구매 Plan snapshot과 다른 item 입력은 전체 직접확정을 rollback한다`() {
        // Given: Plan에는 40분으로 고정된 미백 항목을 41분으로 위조한 proposal
        val service = commandService()
        val valid = proposalInput(revision = 1L, resourceId = "doctor-tampered-item")
        val tampered =
            VisitProposalInput(
                revision = valid.revision,
                startsAt = valid.startsAt,
                endsAt = valid.endsAt,
                items =
                    listOf(
                        AppointmentItemDraft(
                            planRevisionId = clinic.planRevisionId,
                            treatmentKey = "whitening",
                            representativeTreatmentName = "미백 치료",
                            detailedTreatmentCodes = listOf("WHITENING"),
                            preparationMinutes = 10,
                            treatmentMinutes = 41,
                            recoveryMinutes = 10,
                        ),
                    ),
                resourceRequests = valid.resourceRequests,
                policySnapshotId = valid.policySnapshotId,
                supersedesProposalId = null,
            )

        // When: 위조된 실행 item으로 직접 확정을 시도
        val failure =
            assertFailsWith<AppointmentCommitmentCommandException> {
                service.confirmDirectAppointment(
                    DirectAppointmentConfirmationCommand(
                        context = commandContext("tampered-item"),
                        identity = appointmentIdentity("tampered-item"),
                        proposal = tampered,
                        expiresAt = ACTIVE_EXPIRY,
                        representativeTreatmentName = "위조 미백",
                        projectionTarget = confirmedProjectionTarget("doctor-tampered-item"),
                        policyDecision = directConfirmationPolicyDecision(),
                        consent = acceptedConsent("tampered-item"),
                    ),
                )
            }

        // Then: 안정적인 업무 오류이며 방문 identity부터 side effect가 모두 rollback됨
        failure.code shouldBeEqualTo AppointmentCommitmentCommandError.APPOINTMENT_ITEM_INVALID
        transaction(database) {
            Appointments.selectAll().count() shouldBeEqualTo 0L
            AppointmentItems.selectAll().count() shouldBeEqualTo 0L
            AppointmentCommandIdempotencies.selectAll().count() shouldBeEqualTo 0L
        }
    }

    @Test
    fun `직접확정 비허용 정책과 다른 약관 동의는 각각 안정적인 오류로 거부한다`() {
        // Given: 직접 확정을 허용하지 않는 정책
        val service = commandService()
        val proposal = proposalInput(revision = 1L, resourceId = "doctor-policy-denied")
        val deniedCommand =
            DirectAppointmentConfirmationCommand(
                context = commandContext("policy-denied"),
                identity = appointmentIdentity("policy-denied"),
                proposal = proposal,
                expiresAt = ACTIVE_EXPIRY,
                representativeTreatmentName = "정책 거부",
                projectionTarget = confirmedProjectionTarget("doctor-policy-denied"),
                policyDecision =
                    directConfirmationPolicyDecision(
                        adminBookingMode =
                            AdminBookingMode.PROPOSAL_REQUIRES_CUSTOMER_ACCEPTANCE,
                    ),
                consent = acceptedConsent("policy-denied"),
            )

        // When: 비허용 정책과 약관 hash가 다른 증빙으로 각각 직접 확정을 시도
        val policyFailure =
            assertFailsWith<AppointmentCommitmentCommandException> {
                service.confirmDirectAppointment(deniedCommand)
            }
        val accepted = acceptedConsent("terms-mismatch")
        val termsFailure =
            assertFailsWith<AppointmentCommitmentCommandException> {
                service.confirmDirectAppointment(
                    DirectAppointmentConfirmationCommand(
                        context = commandContext("terms-mismatch"),
                        identity = appointmentIdentity("terms-mismatch"),
                        proposal = proposal,
                        expiresAt = ACTIVE_EXPIRY,
                        representativeTreatmentName = "약관 불일치",
                        projectionTarget = confirmedProjectionTarget("doctor-policy-denied"),
                        policyDecision = directConfirmationPolicyDecision(),
                        consent =
                            ProposalConsentEvidence(
                                decision = accepted.decision,
                                evidenceType = accepted.evidenceType,
                                evidenceAuthority = accepted.evidenceAuthority,
                                evidenceId = accepted.evidenceId,
                                evidenceHash = accepted.evidenceHash,
                                decidedAt = accepted.decidedAt,
                                termsHash = "9".repeat(64),
                                actorRef = accepted.actorRef,
                            ),
                    ),
                )
            }

        // Then: 정책과 증빙 오류를 구분하고 어떤 방문도 생성하지 않음
        policyFailure.code shouldBeEqualTo AppointmentCommitmentCommandError.DIRECT_CONFIRM_NOT_ALLOWED
        termsFailure.code shouldBeEqualTo AppointmentCommitmentCommandError.CONSENT_EVIDENCE_INVALID
        transaction(database) {
            Appointments.selectAll().count() shouldBeEqualTo 0L
        }
    }

    @Test
    fun `고객 가예약은 변경 수락 command로 관리자 승인을 우회할 수 없다`() {
        // Given: 고객 요청으로 생성됐지만 아직 관리자가 승인하지 않은 최초 proposal
        val service = commandService()
        val proposal = proposalInput(revision = 1L, resourceId = "doctor-bypass")
        val requested =
            service.requestCustomerAppointment(
                CustomerAppointmentRequestCommand(
                    context = commandContext("bypass-request"),
                    identity = appointmentIdentity("bypass-request"),
                    proposal = proposal,
                    expiresAt = ACTIVE_EXPIRY,
                    representativeTreatmentName = "승인 대기",
                    consent = acceptedConsent("bypass-request"),
                ),
            )

        // When: 최초 proposal을 변경 수락 command 형태로 확정하려고 시도
        val failure =
            assertFailsWith<AppointmentCommitmentCommandException> {
                service.acceptProposal(
                    AcceptAppointmentProposalCommand(
                        context = commandContext("bypass-accept"),
                        appointmentId = requested.commitment.appointmentId,
                        proposalId = requested.proposal.id,
                        expectedVersion = requested.commitment.version,
                        proposal = proposal,
                        expectedProposalHash = requested.proposal.proposalHash,
                        projectionTarget = confirmedProjectionTarget("doctor-bypass"),
                        consent = acceptedConsent("bypass-accept"),
                    ),
                )
            }

        // Then: 명시적인 상태 전이 오류이며 가예약과 무점유 상태가 보존됨
        failure.code shouldBeEqualTo AppointmentCommitmentCommandError.INVALID_TRANSITION
        transaction(database) {
            AppointmentCommitmentRepository()
                .findByAppointmentId(requested.commitment.appointmentId)
                ?.status shouldBeEqualTo AppointmentCommitmentStatus.PROPOSED
            ResourceAllocationRepository().findByProposal(requested.proposal.id) shouldHaveSize 0
        }
    }

    @Test
    fun `최초와 변경 proposal revision은 누락 없이 단조 증가해야 한다`() {
        // Given: revision 2로 시작하는 최초 proposal
        val service = commandService()
        val invalidInitial =
            DirectAppointmentConfirmationCommand(
                context = commandContext("revision-initial-gap"),
                identity = appointmentIdentity("revision-initial-gap"),
                proposal = proposalInput(revision = 2L, resourceId = "doctor-revision-gap"),
                expiresAt = ACTIVE_EXPIRY,
                representativeTreatmentName = "잘못된 최초 revision",
                projectionTarget = confirmedProjectionTarget("doctor-revision-gap"),
                policyDecision = directConfirmationPolicyDecision(),
                consent = acceptedConsent("revision-initial-gap"),
            )

        // When: 최초 revision을 건너뛴 뒤 정상 확정에서 변경 revision도 하나 건너뜀
        val initialFailure =
            assertFailsWith<AppointmentCommitmentCommandException> {
                service.confirmDirectAppointment(invalidInitial)
            }
        val original = confirmDirect(service, "revision-original")
        val skippedChange =
            proposalInput(
                revision = 3L,
                resourceId = "doctor-revision-change-gap",
                supersedesProposalId = original.proposal.id,
            )
        val changeFailure =
            assertFailsWith<AppointmentCommitmentCommandException> {
                service.proposeChange(
                    ChangeAppointmentProposalCommand(
                        context = commandContext("revision-change-gap"),
                        appointmentId = original.commitment.appointmentId,
                        expectedVersion = original.commitment.version,
                        proposal = skippedChange,
                        expiresAt = ACTIVE_EXPIRY,
                        representativeTreatmentName = "누락된 변경 revision",
                    ),
                )
            }

        // Then: 두 경우 모두 같은 안정적인 revision 충돌이며 실패 proposal은 남지 않음
        initialFailure.code shouldBeEqualTo AppointmentCommitmentCommandError.PROPOSAL_REVISION_CONFLICT
        changeFailure.code shouldBeEqualTo AppointmentCommitmentCommandError.PROPOSAL_REVISION_CONFLICT
        transaction(database) {
            AppointmentItems
                .selectAll()
                .where { AppointmentItems.appointmentId eq original.commitment.appointmentId }
                .count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `다른 병원 인증 scope는 appointment id를 알아도 예약을 변경하지 못한다`() {
        // Given: 첫 병원에 확정된 예약과 같은 tenant의 별도 병원 인증정보
        val service = commandService()
        val original = confirmDirect(service, "scope-original")
        val otherClinic = seedAdditionalClinic("Other")
        val changeInput =
            proposalInput(
                revision = 2L,
                resourceId = "doctor-scope-change",
                supersedesProposalId = original.proposal.id,
            )

        // When: 별도 병원 scope로 첫 병원의 예약 변경을 요청
        val failure =
            assertFailsWith<AppointmentCommitmentCommandException> {
                service.proposeChange(
                    ChangeAppointmentProposalCommand(
                        context =
                            commandContext(
                                key = "scope-foreign",
                                clinicId = otherClinic.clinicId,
                            ),
                        appointmentId = original.commitment.appointmentId,
                        expectedVersion = original.commitment.version,
                        proposal = changeInput,
                        expiresAt = ACTIVE_EXPIRY,
                        representativeTreatmentName = "다른 병원 변경",
                    ),
                )
            }

        // Then: scope 경계에서 transaction 전체가 거부되고 원래 확정만 유지됨
        failure.code shouldBeEqualTo AppointmentCommitmentCommandError.SCOPE_MISMATCH
        currentConfirmation(original.commitment.appointmentId).proposal.id shouldBeEqualTo
            original.proposal.id
        transaction(database) {
            AppointmentCommandIdempotencies.selectAll().count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `멱등 결과 참조가 다른 병원 proposal이면 replay도 거부한다`() {
        // Given: 데이터 복구 오류를 모사한 다른 병원 scope의 잘못된 durable 결과 참조
        val service = commandService()
        val original = confirmDirect(service, "replay-scope-original")
        val otherClinic = seedAdditionalClinic("Replay")
        val replayContext =
            commandContext(
                key = "replay-scope-foreign",
                clinicId = otherClinic.clinicId,
            )
        transaction(database) {
            val repository = AppointmentCommandIdempotencyRepository()
            repository.claim(
                tenantGroupId = replayContext.tenantGroupId,
                clinicId = replayContext.clinicId,
                actorScopeHash = replayContext.actorScopeHash,
                idempotencyKeyHash = replayContext.idempotencyKeyHash,
                commandHash = replayContext.commandHash,
            ) shouldBeEqualTo CommandClaimResult.ACQUIRED
            repository
                .complete(
                    tenantGroupId = replayContext.tenantGroupId,
                    clinicId = replayContext.clinicId,
                    actorScopeHash = replayContext.actorScopeHash,
                    idempotencyKeyHash = replayContext.idempotencyKeyHash,
                    commandHash = replayContext.commandHash,
                    result = commandResultRecord(original, responseHash = "0".repeat(64)),
                ).shouldBeTrue()
        }
        val appointmentCountBefore =
            transaction(database) {
                Appointments.selectAll().count()
            }

        // When: 같은 key/hash command가 replay 경로로 foreign proposal을 읽으려 함
        val failure =
            assertFailsWith<AppointmentCommitmentCommandException> {
                service.confirmDirectAppointment(
                    DirectAppointmentConfirmationCommand(
                        context = replayContext,
                        identity = appointmentIdentity("replay-scope-foreign"),
                        proposal =
                            proposalInput(
                                revision = 1L,
                                resourceId = "doctor-replay-scope",
                            ),
                        expiresAt = ACTIVE_EXPIRY,
                        representativeTreatmentName = "잘못된 replay",
                        projectionTarget = confirmedProjectionTarget("doctor-replay-scope"),
                        policyDecision = directConfirmationPolicyDecision(),
                        consent = acceptedConsent("replay-scope-foreign"),
                    ),
                )
            }

        // Then: foreign aggregate를 반환하지 않고 command body도 실행하지 않음
        failure.code shouldBeEqualTo AppointmentCommitmentCommandError.SCOPE_MISMATCH
        transaction(database) {
            Appointments.selectAll().count() shouldBeEqualTo appointmentCountBefore
        }
    }

    @Test
    fun `관리자 승인 전 가예약 만료는 commitment만 만료하고 allocation을 만들지 않는다`() {
        // Given: 고객 동의는 있지만 관리자 승인을 받지 않은 가예약 proposal
        val requestService = commandService()
        val proposal = proposalInput(revision = 1L, resourceId = "doctor-expire-request")
        val requested =
            requestService.requestCustomerAppointment(
                CustomerAppointmentRequestCommand(
                    context = commandContext("expire-request"),
                    identity = appointmentIdentity("expire-request"),
                    proposal = proposal,
                    expiresAt = ACTIVE_EXPIRY,
                    representativeTreatmentName = "승인 대기 만료",
                    consent = acceptedConsent("expire-request"),
                ),
            )
        val expiryService =
            commandService(
                clock = Clock.fixed(ACTIVE_EXPIRY, ZoneOffset.UTC),
            )

        // When: proposal 유효시간에 도달해 만료 command를 실행
        val command =
            ExpireAppointmentProposalCommand(
                context = commandContext("expire-request-record"),
                appointmentId = requested.commitment.appointmentId,
                proposalId = requested.proposal.id,
                expectedVersion = requested.commitment.version,
                expectedProposalHash = requested.proposal.proposalHash,
            )
        val expired = expiryService.expireProposal(command)
        val replay = expiryService.expireProposal(command)

        // Then: 권위 있는 만료 시각을 응답 snapshot에도 남기고 replay가 그 결과를 재사용
        expired.commitment.status shouldBeEqualTo AppointmentCommitmentStatus.EXPIRED
        expired.commitment.version shouldBeEqualTo requested.commitment.version + 1
        expired.proposal.expiredAt shouldBeEqualTo ACTIVE_EXPIRY
        replay.idempotentReplay.shouldBeTrue()
        replay.proposal.expiredAt shouldBeEqualTo ACTIVE_EXPIRY
        transaction(database) {
            ResourceAllocationRepository().findByProposal(requested.proposal.id) shouldHaveSize 0
        }
    }

    @Test
    fun `이른 만료와 중복 만료는 구분하고 만료 side effect는 한 번만 기록한다`() {
        // Given: 아직 유효한 고객 가예약
        val requestService = commandService()
        val proposal = proposalInput(revision = 1L, resourceId = "doctor-expiry-once")
        val requested =
            requestService.requestCustomerAppointment(
                CustomerAppointmentRequestCommand(
                    context = commandContext("expiry-once-request"),
                    identity = appointmentIdentity("expiry-once-request"),
                    proposal = proposal,
                    expiresAt = ACTIVE_EXPIRY,
                    representativeTreatmentName = "만료 단일 기록",
                    consent = acceptedConsent("expiry-once-request"),
                ),
            )
        val earlyCommand =
            ExpireAppointmentProposalCommand(
                context = commandContext("expiry-too-early"),
                appointmentId = requested.commitment.appointmentId,
                proposalId = requested.proposal.id,
                expectedVersion = requested.commitment.version,
                expectedProposalHash = requested.proposal.proposalHash,
            )

        // When: 만료 전 실행한 뒤, 만료 시각에 서로 다른 key로 두 번 기록
        val earlyFailure =
            assertFailsWith<AppointmentCommitmentCommandException> {
                requestService.expireProposal(earlyCommand)
            }
        val expiryService = commandService(clock = Clock.fixed(ACTIVE_EXPIRY, ZoneOffset.UTC))
        val first =
            expiryService.expireProposal(
                ExpireAppointmentProposalCommand(
                    context = commandContext("expiry-first"),
                    appointmentId = requested.commitment.appointmentId,
                    proposalId = requested.proposal.id,
                    expectedVersion = requested.commitment.version,
                    expectedProposalHash = requested.proposal.proposalHash,
                ),
            )
        val repeatedFailure =
            assertFailsWith<AppointmentCommitmentCommandException> {
                expiryService.expireProposal(
                    ExpireAppointmentProposalCommand(
                        context = commandContext("expiry-second"),
                        appointmentId = requested.commitment.appointmentId,
                        proposalId = requested.proposal.id,
                        expectedVersion = first.commitment.version,
                        expectedProposalHash = requested.proposal.proposalHash,
                    ),
                )
            }

        // Then: 조기·중복 원인이 구분되고 outbox 만료 event는 하나만 존재
        earlyFailure.code shouldBeEqualTo AppointmentCommitmentCommandError.PROPOSAL_NOT_EXPIRED
        repeatedFailure.code shouldBeEqualTo AppointmentCommitmentCommandError.PROPOSAL_ALREADY_EXPIRED
        transaction(database) {
            SchedulingOutboxEvents
                .selectAll()
                .where {
                    SchedulingOutboxEvents.eventType eq
                        "APPOINTMENT_PROPOSAL_EXPIRED"
                }.count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `변경 proposal 거부는 기존 확정 proposal과 allocation을 유지한다`() {
        // Given: 이미 확정된 예약과 이를 대체하려는 새 proposal
        val service = commandService()
        val original = confirmDirect(service, "decline-original")
        val changeInput =
            proposalInput(
                revision = 2L,
                resourceId = "doctor-change",
                startsAt = PROPOSAL_START.plusSeconds(86_400),
                supersedesProposalId = original.proposal.id,
            )
        val change =
            service.proposeChange(
                ChangeAppointmentProposalCommand(
                    context = commandContext("decline-propose"),
                    appointmentId = original.commitment.appointmentId,
                    expectedVersion = original.commitment.version,
                    proposal = changeInput,
                    expiresAt = ACTIVE_EXPIRY,
                    representativeTreatmentName = "미백 치료 변경",
                ),
            )

        // When: 고객이 변경 proposal을 거부
        val declined =
            service.declineProposal(
                DeclineAppointmentProposalCommand(
                    context = commandContext("decline-change"),
                    appointmentId = original.commitment.appointmentId,
                    proposalId = change.proposal.id,
                    expectedVersion = original.commitment.version,
                    expectedProposalHash = change.proposal.proposalHash,
                    consent = declinedConsent("decline-change"),
                ),
            )

        // Then: 확정 포인터와 기존 active allocation은 바뀌지 않음
        declined.commitment.confirmedProposalId shouldBeEqualTo original.proposal.id
        declined.commitment.version shouldBeEqualTo original.commitment.version + 1L
        val current = currentConfirmation(original.commitment.appointmentId)
        current.proposal.id shouldBeEqualTo original.proposal.id
        current.allocations.single().status shouldBeEqualTo ResourceAllocationStatus.ACTIVE
        transaction(database) {
            ResourceAllocationRepository().findByProposal(change.proposal.id) shouldHaveSize 0
        }
    }

    @Test
    fun `만료된 변경 proposal 수락은 기존 확정 상태와 allocation을 유지한다`() {
        // Given: 기존 확정 예약과 발행 시점에는 유효했던 변경 proposal
        val service = commandService()
        val original = confirmDirect(service, "expiry-original")
        val changeInput =
            proposalInput(
                revision = 2L,
                resourceId = "doctor-expired",
                supersedesProposalId = original.proposal.id,
            )
        val change =
            service.proposeChange(
                ChangeAppointmentProposalCommand(
                    context = commandContext("expiry-propose"),
                    appointmentId = original.commitment.appointmentId,
                    expectedVersion = original.commitment.version,
                    proposal = changeInput,
                    expiresAt = ACTIVE_EXPIRY,
                    representativeTreatmentName = "만료 변경",
                ),
            )
        val expiredService =
            commandService(clock = Clock.fixed(ACTIVE_EXPIRY, ZoneOffset.UTC))

        // When: 권위 있는 clock이 만료 시각에 도달한 뒤 고객이 수락
        val failure =
            assertFailsWith<AppointmentCommitmentCommandException> {
                expiredService.acceptProposal(
                    AcceptAppointmentProposalCommand(
                        context = commandContext("expiry-accept"),
                        appointmentId = original.commitment.appointmentId,
                        proposalId = change.proposal.id,
                        expectedVersion = original.commitment.version,
                        proposal = changeInput,
                        expectedProposalHash = change.proposal.proposalHash,
                        projectionTarget = confirmedProjectionTarget("doctor-expired"),
                        consent = acceptedConsent("expiry-accept"),
                    ),
                )
            }

        // Then: 만료 오류만 반환하고 기존 예약은 손실되지 않음
        failure.code shouldBeEqualTo AppointmentCommitmentCommandError.PROPOSAL_EXPIRED
        val expired =
            expiredService.expireProposal(
                ExpireAppointmentProposalCommand(
                    context = commandContext("expiry-record"),
                    appointmentId = original.commitment.appointmentId,
                    proposalId = change.proposal.id,
                    expectedVersion = original.commitment.version,
                    expectedProposalHash = change.proposal.proposalHash,
                ),
            )
        expired.commitment.confirmedProposalId shouldBeEqualTo original.proposal.id
        expired.commitment.version shouldBeEqualTo original.commitment.version + 1L
        expired.proposal.expiredAt shouldBeEqualTo ACTIVE_EXPIRY
        val current = currentConfirmation(original.commitment.appointmentId)
        current.proposal.id shouldBeEqualTo original.proposal.id
        current.allocations.single().status shouldBeEqualTo ResourceAllocationStatus.ACTIVE
    }

    @Test
    fun `새 allocation 실패는 기존 확정 proposal과 allocation을 보존한다`() {
        // Given: 기존 확정 예약과 다른 예약이 이미 점유한 자원을 요구하는 변경 proposal
        val service = commandService()
        val original = confirmDirect(service, "allocation-original")
        val occupiedResource = "occupied-doctor"
        confirmDirect(service, "allocation-occupied", occupiedResource)
        val changeInput =
            proposalInput(
                revision = 2L,
                resourceId = occupiedResource,
                supersedesProposalId = original.proposal.id,
            )
        val change =
            service.proposeChange(
                ChangeAppointmentProposalCommand(
                    context = commandContext("allocation-propose"),
                    appointmentId = original.commitment.appointmentId,
                    expectedVersion = original.commitment.version,
                    proposal = changeInput,
                    expiresAt = ACTIVE_EXPIRY,
                    representativeTreatmentName = "충돌 변경",
                ),
            )

        // When: 고객이 충돌하는 변경 proposal을 수락
        val failure =
            assertFailsWith<AppointmentCommitmentCommandException> {
                service.acceptProposal(
                    AcceptAppointmentProposalCommand(
                        context = commandContext("allocation-accept"),
                        appointmentId = original.commitment.appointmentId,
                        proposalId = change.proposal.id,
                        expectedVersion = original.commitment.version,
                        proposal = changeInput,
                        expectedProposalHash = change.proposal.proposalHash,
                        projectionTarget = confirmedProjectionTarget(occupiedResource),
                        consent = acceptedConsent("allocation-accept"),
                    ),
                )
            }

        // Then: 새 점유는 rollback되고 기존 확정 예약은 그대로 유지됨
        failure.code shouldBeEqualTo AppointmentCommitmentCommandError.RESOURCE_CONFLICT
        val current = currentConfirmation(original.commitment.appointmentId)
        current.proposal.id shouldBeEqualTo original.proposal.id
        current.allocations.single().status shouldBeEqualTo ResourceAllocationStatus.ACTIVE
        transaction(database) {
            ResourceAllocationRepository().findByProposal(change.proposal.id) shouldHaveSize 0
        }
    }

    @Test
    fun `동일한 멱등 command replay는 durable 결과와 단일 side effect만 반환한다`() {
        // Given: 직접 확정 command 하나
        val service = commandService()
        val command =
            DirectAppointmentConfirmationCommand(
                context = commandContext("idempotent-direct"),
                identity = appointmentIdentity("idempotent-direct"),
                proposal = proposalInput(revision = 1L, resourceId = "doctor-idempotent"),
                expiresAt = ACTIVE_EXPIRY,
                representativeTreatmentName = "멱등 확정",
                projectionTarget = confirmedProjectionTarget("doctor-idempotent"),
                policyDecision = directConfirmationPolicyDecision(),
                consent = acceptedConsent("idempotent-direct"),
            )

        // When: 같은 actor scope, key, command hash로 두 번 실행
        val first = service.confirmDirectAppointment(command)
        val replay = service.confirmDirectAppointment(command)

        // Then: 두 번째 호출은 같은 결과를 재생하고 row를 중복 생성하지 않음
        replay.idempotentReplay.shouldBeTrue()
        replay.commitment shouldBeEqualTo first.commitment
        replay.proposal shouldBeEqualTo first.proposal
        transaction(database) {
            AppointmentCommandIdempotencies.selectAll().count() shouldBeEqualTo 1L
            ResourceAllocationRepository().findByProposal(first.proposal.id) shouldHaveSize 1
            SchedulingOutboxEvents
                .selectAll()
                .where { SchedulingOutboxEvents.eventType eq "APPOINTMENT_CONFIRMED" }
                .count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `멱등 replay는 이후 예약 변경이 있어도 최초 command 응답 snapshot을 반환한다`() {
        // Given: 직접 확정 command와 그 이후 고객 동의를 거친 변경 확정
        val service = commandService()
        val originalInput = proposalInput(revision = 1L, resourceId = "doctor-replay-snapshot")
        val originalCommand =
            DirectAppointmentConfirmationCommand(
                context = commandContext("replay-snapshot-original"),
                identity = appointmentIdentity("replay-snapshot-original"),
                proposal = originalInput,
                expiresAt = ACTIVE_EXPIRY,
                representativeTreatmentName = "최초 확정",
                projectionTarget = confirmedProjectionTarget("doctor-replay-snapshot"),
                policyDecision = directConfirmationPolicyDecision(),
                consent = acceptedConsent("replay-snapshot-original"),
            )
        val original = service.confirmDirectAppointment(originalCommand)
        val changeInput =
            proposalInput(
                revision = 2L,
                resourceId = "doctor-replay-snapshot-change",
                startsAt = PROPOSAL_START.plusSeconds(86_400),
                supersedesProposalId = original.proposal.id,
            )
        val change =
            service.proposeChange(
                ChangeAppointmentProposalCommand(
                    context = commandContext("replay-snapshot-propose"),
                    appointmentId = original.commitment.appointmentId,
                    expectedVersion = original.commitment.version,
                    proposal = changeInput,
                    expiresAt = ACTIVE_EXPIRY,
                    representativeTreatmentName = "변경 확정",
                ),
            )
        val changed =
            service.acceptProposal(
                AcceptAppointmentProposalCommand(
                    context = commandContext("replay-snapshot-accept"),
                    appointmentId = original.commitment.appointmentId,
                    proposalId = change.proposal.id,
                    expectedVersion = original.commitment.version,
                    proposal = changeInput,
                    expectedProposalHash = change.proposal.proposalHash,
                    projectionTarget =
                        confirmedProjectionTarget("doctor-replay-snapshot-change"),
                    consent = acceptedConsent("replay-snapshot-accept"),
                ),
            )

        // When: 최초 command의 같은 actor scope, key, hash를 다시 실행
        val replay = service.confirmDirectAppointment(originalCommand)

        // Then: 현재 변경 상태가 아니라 최초 command 완료 시점 응답을 그대로 재생
        changed.commitment.version shouldBeEqualTo original.commitment.version + 1L
        replay.idempotentReplay.shouldBeTrue()
        replay.commitment shouldBeEqualTo original.commitment
        replay.proposal shouldBeEqualTo original.proposal
        replay.commitment.confirmedProposalId shouldBeEqualTo original.proposal.id
    }

    @Test
    fun `같은 멱등 key의 다른 command hash는 기존 결과를 재생하지 않는다`() {
        // Given: 한 actor scope에서 이미 완료된 직접 확정 command
        val service = commandService()
        val original =
            DirectAppointmentConfirmationCommand(
                context = commandContext("idempotency-conflict", commandHash = "a".repeat(64)),
                identity = appointmentIdentity("idempotency-conflict"),
                proposal = proposalInput(revision = 1L, resourceId = "doctor-idempotency-conflict"),
                expiresAt = ACTIVE_EXPIRY,
                representativeTreatmentName = "멱등 충돌",
                projectionTarget = confirmedProjectionTarget("doctor-idempotency-conflict"),
                policyDecision = directConfirmationPolicyDecision(),
                consent = acceptedConsent("idempotency-conflict"),
            )
        service.confirmDirectAppointment(original)

        // When: 같은 key를 다른 canonical command hash에 재사용
        val failure =
            assertFailsWith<AppointmentCommitmentCommandException> {
                service.confirmDirectAppointment(
                    DirectAppointmentConfirmationCommand(
                        context =
                            CommitmentCommandContext(
                                tenantGroupId = original.context.tenantGroupId,
                                clinicId = original.context.clinicId,
                                actorScopeHash = original.context.actorScopeHash,
                                actorAuditRef = original.context.actorAuditRef,
                                idempotencyKeyHash = original.context.idempotencyKeyHash,
                                commandHash = "b".repeat(64),
                                correlationId = original.context.correlationId,
                            ),
                        identity = original.identity,
                        proposal = original.proposal,
                        expiresAt = original.expiresAt,
                        representativeTreatmentName = original.representativeTreatmentName,
                        projectionTarget = original.projectionTarget,
                        policyDecision = original.policyDecision,
                        consent = original.consent,
                    ),
                )
            }

        // Then: 기존 결과를 잘못 재생하거나 새 side effect를 만들지 않고 충돌을 반환
        failure.code shouldBeEqualTo AppointmentCommitmentCommandError.IDEMPOTENCY_KEY_REUSED
        transaction(database) {
            AppointmentCommandIdempotencies.selectAll().count() shouldBeEqualTo 1L
            SchedulingOutboxEvents
                .selectAll()
                .where { SchedulingOutboxEvents.eventType eq "APPOINTMENT_CONFIRMED" }
                .count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `expected version 충돌은 재시도 없이 즉시 반환한다`() {
        // Given: version 2로 확정된 예약
        var retryCount = 0
        val service = commandService { retryCount++ }
        val original = confirmDirect(service, "version-original")
        val changeInput =
            proposalInput(
                revision = 2L,
                resourceId = "doctor-version",
                supersedesProposalId = original.proposal.id,
            )
        val change =
            service.proposeChange(
                ChangeAppointmentProposalCommand(
                    context = commandContext("version-propose"),
                    appointmentId = original.commitment.appointmentId,
                    expectedVersion = original.commitment.version,
                    proposal = changeInput,
                    expiresAt = ACTIVE_EXPIRY,
                    representativeTreatmentName = "version 변경",
                ),
            )

        // When: stale expected version으로 변경 수락
        val failure =
            assertFailsWith<AppointmentCommitmentCommandException> {
                service.acceptProposal(
                    AcceptAppointmentProposalCommand(
                        context = commandContext("version-accept"),
                        appointmentId = original.commitment.appointmentId,
                        proposalId = change.proposal.id,
                        expectedVersion = original.commitment.version - 1,
                        proposal = changeInput,
                        expectedProposalHash = change.proposal.proposalHash,
                        projectionTarget = confirmedProjectionTarget("doctor-version"),
                        consent = acceptedConsent("version-accept"),
                    ),
                )
            }

        // Then: domain conflict이며 DB retry/backoff를 수행하지 않음
        failure.code shouldBeEqualTo AppointmentCommitmentCommandError.VERSION_CONFLICT
        retryCount shouldBeEqualTo 0
        transaction(database) {
            ResourceAllocationRepository().findByProposal(change.proposal.id) shouldHaveSize 0
        }
    }

    @Test
    fun `serialization failure는 bounded backoff 후 durable 결과를 재생한다`() {
        // Given: 두 번의 PostgreSQL serialization failure 뒤 replay가 가능한 command
        val original = confirmDirect(commandService(), "retry-success-original")
        val idempotencyRepository = mockk<AppointmentCommandIdempotencyRepository>()
        var claimAttempts = 0
        every {
            idempotencyRepository.claim(any(), any(), any(), any(), any())
        } answers {
            claimAttempts++
            if (claimAttempts < 3) {
                throw SQLException("serialization failure", "40001")
            }
            CommandClaimResult.REPLAY
        }
        every {
            idempotencyRepository.findResult(any(), any(), any(), any())
        } returns commandResultRecord(original)
        val delays = mutableListOf<Long>()
        val service =
            AppointmentCommitmentCommandService(
                database = database,
                clock = CLOCK,
                retryDelay = delays::add,
                retryJitterMillis = { 0L },
                idempotencyRepository = idempotencyRepository,
            )
        val command =
            DirectAppointmentConfirmationCommand(
                context = commandContext("retry-success"),
                identity = appointmentIdentity("retry-success"),
                proposal = proposalInput(revision = 1L, resourceId = "doctor-retry-success"),
                expiresAt = ACTIVE_EXPIRY,
                representativeTreatmentName = "재시도 성공",
                projectionTarget = confirmedProjectionTarget("doctor-retry-success"),
                policyDecision = directConfirmationPolicyDecision(),
                consent = acceptedConsent("retry-success"),
            )

        // When: transient SQL state 두 번 뒤 세 번째 transaction이 replay를 읽음
        val replay = service.confirmDirectAppointment(command)

        // Then: 최대 경계 안에서 25ms, 50ms backoff만 수행하고 side effect 없이 재생
        replay.idempotentReplay.shouldBeTrue()
        replay.commitment shouldBeEqualTo original.commitment
        replay.proposal shouldBeEqualTo original.proposal
        delays shouldBeEqualTo listOf(25L, 50L)
        verify(exactly = 3) {
            idempotencyRepository.claim(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `재시도 backoff 중 interrupt는 flag를 복구하고 안정적인 오류로 종료한다`() {
        // Given: 첫 transaction이 serialization failure이고 backoff가 interrupt되는 command
        val idempotencyRepository = mockk<AppointmentCommandIdempotencyRepository>()
        every {
            idempotencyRepository.claim(any(), any(), any(), any(), any())
        } throws SQLException("serialization failure", "40001")
        val service =
            AppointmentCommitmentCommandService(
                database = database,
                clock = CLOCK,
                retryDelay = { throw InterruptedException("shutdown") },
                retryJitterMillis = { 0L },
                idempotencyRepository = idempotencyRepository,
            )
        val command =
            CustomerAppointmentRequestCommand(
                context = commandContext("retry-interrupted"),
                identity = appointmentIdentity("retry-interrupted"),
                proposal = proposalInput(revision = 1L, resourceId = "doctor-retry-interrupted"),
                expiresAt = ACTIVE_EXPIRY,
                representativeTreatmentName = "재시도 중단",
                consent = acceptedConsent("retry-interrupted"),
            )
        Thread.interrupted()

        try {
            // When: transaction retry 대기 중 thread interrupt가 발생
            val failure =
                assertFailsWith<AppointmentCommitmentCommandException> {
                    service.requestCustomerAppointment(command)
                }

            // Then: stable error로 종료하고 상위 lifecycle이 관찰할 interrupt flag를 복구
            failure.code shouldBeEqualTo AppointmentCommitmentCommandError.RETRY_INTERRUPTED
            Thread.currentThread().isInterrupted.shouldBeTrue()
            verify(exactly = 1) {
                idempotencyRepository.claim(any(), any(), any(), any(), any())
            }
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `deadlock 재시도 세 번 소진은 RETRY_EXHAUSTED로 종료한다`() {
        // Given: 매 transaction에서 PostgreSQL deadlock SQL state를 반환하는 저장소
        val idempotencyRepository = mockk<AppointmentCommandIdempotencyRepository>()
        every {
            idempotencyRepository.claim(any(), any(), any(), any(), any())
        } throws SQLException("deadlock detected", "40P01")
        val delays = mutableListOf<Long>()
        val service =
            AppointmentCommitmentCommandService(
                database = database,
                clock = CLOCK,
                retryDelay = delays::add,
                retryJitterMillis = { 0L },
                idempotencyRepository = idempotencyRepository,
            )
        val command =
            CustomerAppointmentRequestCommand(
                context = commandContext("retry-exhausted"),
                identity = appointmentIdentity("retry-exhausted"),
                proposal = proposalInput(revision = 1L, resourceId = "doctor-retry-exhausted"),
                expiresAt = ACTIVE_EXPIRY,
                representativeTreatmentName = "재시도 소진",
                consent = acceptedConsent("retry-exhausted"),
            )

        // When: 최대 세 transaction 모두 deadlock으로 실패
        val failure =
            assertFailsWith<AppointmentCommitmentCommandException> {
                service.requestCustomerAppointment(command)
            }

        // Then: 세 번째 실패 뒤 추가 sleep이나 무한 재시도 없이 안정적인 오류로 종료
        failure.code shouldBeEqualTo AppointmentCommitmentCommandError.RETRY_EXHAUSTED
        delays shouldBeEqualTo listOf(25L, 50L)
        verify(exactly = 3) {
            idempotencyRepository.claim(any(), any(), any(), any(), any())
        }
    }
}
