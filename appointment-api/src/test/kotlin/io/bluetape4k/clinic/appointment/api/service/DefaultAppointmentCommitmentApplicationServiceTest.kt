package io.bluetape4k.clinic.appointment.api.service

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.clinic.appointment.api.commitment.AvailableProposalResource
import io.bluetape4k.clinic.appointment.api.commitment.AppointmentCommitmentCommandMetrics
import io.bluetape4k.clinic.appointment.api.commitment.CommitmentConflictReason
import io.bluetape4k.clinic.appointment.api.commitment.CommitmentMetricResult
import io.bluetape4k.clinic.appointment.api.commitment.ConfirmedAppointmentProjectionTarget
import io.bluetape4k.clinic.appointment.api.commitment.CurrentPolicySnapshot
import io.bluetape4k.clinic.appointment.api.commitment.ProposalCandidateSlot
import io.bluetape4k.clinic.appointment.api.commitment.VisitProposalInput
import io.bluetape4k.clinic.appointment.api.commitment.VisitCommitmentCommandTestSupport
import io.bluetape4k.clinic.appointment.api.config.AppointmentCommitmentApiError
import io.bluetape4k.clinic.appointment.api.config.AppointmentCommitmentApiException
import io.bluetape4k.clinic.appointment.api.config.AppointmentCommitmentMode
import io.bluetape4k.clinic.appointment.api.config.AppointmentCommitmentProperties
import io.bluetape4k.clinic.appointment.api.dto.commitment.ConsentEvidenceRequest
import io.bluetape4k.clinic.appointment.api.dto.commitment.CreateAppointmentRequestV2
import io.bluetape4k.clinic.appointment.api.dto.commitment.CreateChangeProposalRequest
import io.bluetape4k.clinic.appointment.api.dto.commitment.DirectConfirmRequest
import io.bluetape4k.clinic.appointment.api.dto.commitment.DirectCreateAppointmentRequest
import io.bluetape4k.clinic.appointment.api.notification.AppointmentMemberResolver
import io.bluetape4k.clinic.appointment.api.notification.MemberResolution
import io.bluetape4k.clinic.appointment.api.notification.NotificationMemberApiError
import io.bluetape4k.clinic.appointment.api.notification.NotificationMemberApiException
import io.bluetape4k.clinic.appointment.api.dto.commitment.ProposalDecisionRequest
import io.bluetape4k.clinic.appointment.api.security.ActorContext
import io.bluetape4k.clinic.appointment.api.security.ActorType
import io.bluetape4k.clinic.appointment.api.security.AuthenticationAssurance
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentItemDraft
import io.bluetape4k.clinic.appointment.model.commitment.ResourceAllocationMode
import io.bluetape4k.clinic.appointment.model.commitment.ResourceAllocationDraft
import io.bluetape4k.clinic.appointment.model.commitment.ResourceType
import io.bluetape4k.clinic.appointment.model.dto.AppointmentProposalRecord
import io.bluetape4k.clinic.appointment.model.dto.AppointmentVisitIdentityDraft
import io.bluetape4k.clinic.appointment.model.dto.ResourceAllocationRequest
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.clinic.appointment.model.policy.ActorRole
import io.bluetape4k.clinic.appointment.model.policy.AdminBookingMode
import io.bluetape4k.clinic.appointment.model.policy.BookingCommitmentPolicy
import io.bluetape4k.clinic.appointment.model.policy.CompiledSchedulingPolicy
import io.bluetape4k.clinic.appointment.model.policy.ConfirmedChangeMode
import io.bluetape4k.clinic.appointment.model.policy.ConsentEvidenceRequirement
import io.bluetape4k.clinic.appointment.model.policy.EffectiveSchedulingPolicy
import io.bluetape4k.clinic.appointment.model.policy.PatientBookingMode
import io.bluetape4k.clinic.appointment.model.policy.PolicyGenerationVector
import io.bluetape4k.clinic.appointment.model.policy.ProvisionalCapacityMode
import io.bluetape4k.clinic.appointment.model.tables.AppointmentItems
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlanRevisions
import io.bluetape4k.clinic.appointment.model.tables.ConsentDecisions
import io.bluetape4k.clinic.appointment.model.tables.PlanRevisionDependencies
import io.bluetape4k.clinic.appointment.model.tables.PlanRevisionGroupingConstraints
import io.bluetape4k.clinic.appointment.model.tables.PlanRevisionTreatments
import io.bluetape4k.clinic.appointment.model.tables.ResourceAllocations
import io.bluetape4k.clinic.appointment.model.plan.PlanTreatmentStatus
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Test
import io.mockk.clearMocks
import io.mockk.spyk
import io.mockk.verify
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration

/**
 * 실제 application service가 Gateway actor·DTO를 command 입력으로 변환하고 rollout metric을
 * 기록하는지 검증한다.
 */
internal class DefaultAppointmentCommitmentApplicationServiceTest : VisitCommitmentCommandTestSupport() {

    @Test
    fun `idempotency key uses a secret scoped HMAC digest`() {
        val first =
            HmacAppointmentCommitmentIdempotencyKeyHasher("a".repeat(32).toByteArray())
                .hash("caller-visible-key")
        val second =
            HmacAppointmentCommitmentIdempotencyKeyHasher("b".repeat(32).toByteArray())
                .hash("caller-visible-key")

        first.length shouldBeEqualTo 64
        first shouldNotBeEqualTo "caller-visible-key"
        first shouldNotBeEqualTo second
        assertFailsWith<IllegalArgumentException> {
            HmacAppointmentCommitmentIdempotencyKeyHasher("short".toByteArray())
        }
    }

    @Test
    fun `new writes require WRITE mode and clinic allowlist`() {
        val service = applicationService(AppointmentCommitmentProperties(mode = AppointmentCommitmentMode.OFF))

        val exception = assertFailsWith<AppointmentCommitmentApiException> {
            service.directCreate(adminActor(), "direct-disabled-01", true, directCreateRequest())
        }

        exception.error shouldBeEqualTo AppointmentCommitmentApiError.INGRESS_DISABLED
    }

    @Test
    fun `queries remain available when new ingress write is disabled`() {
        val created = confirmDirect(commandService(CLOCK), "existing-query")
        val service = applicationService(AppointmentCommitmentProperties(mode = AppointmentCommitmentMode.OFF))

        val response = service.query(adminActor(), created.commitment.appointmentId)

        response.appointmentId shouldBeEqualTo created.commitment.appointmentId
        response.commitmentId shouldBeEqualTo created.commitment.id
    }

    @Test
    fun `query keeps the single appointment lookup budget`() {
        val created = confirmDirect(commandService(CLOCK), "single-query-budget")
        val resolver = spyk(accessResolver())
        val service = applicationService(
            AppointmentCommitmentProperties(mode = AppointmentCommitmentMode.OFF),
            accessResolver = resolver,
        )

        service.query(adminActor(), created.commitment.appointmentId)

        verify(exactly = 1) { resolver.requireAppointmentAccess(any(), created.commitment.appointmentId) }
    }

    @Test
    fun `query exposes immutable plan and clinic display metadata`() {
        val created = confirmDirect(commandService(CLOCK), "display-metadata")
        val response = applicationService(AppointmentCommitmentProperties(mode = AppointmentCommitmentMode.OFF))
            .query(adminActor(), created.commitment.appointmentId)

        response.currentProposal.productName shouldBeEqualTo "미백 패키지"
        response.currentProposal.sessionNumber shouldBeEqualTo 1
        response.currentProposal.totalSessions shouldBeEqualTo 2
        response.currentProposal.clinicDisplayName shouldBeEqualTo "Task 6 Clinic"
    }

    @Test
    fun `records proposal latency and allocation conflict metrics on command paths`() {
        val registry = SimpleMeterRegistry()
        val service = applicationService(
            properties =
                AppointmentCommitmentProperties(
                    mode = AppointmentCommitmentMode.WRITE,
                    clinicAllowlist = setOf(clinic.clinicId),
                ),
            registry = registry,
        )

        service.directCreate(adminActor(), "direct-metric-01", true, directCreateRequest("metric-01"))
        assertFailsWith<AppointmentCommitmentApiException> {
            service.directCreate(adminActor(), "direct-metric-02", true, directCreateRequest("metric-02"))
        }

        registry
            .timer(
                "appointment.commitment.proposal.latency",
                "tenant",
                "tenant-task6",
                "clinic",
                "clinic-${clinic.clinicId}",
                "result",
                "SUCCESS",
            ).count() shouldBeEqualTo 1L
        registry
            .counter(
                "appointment.commitment.allocation.conflict",
                "tenant",
                "tenant-task6",
                "clinic",
                "clinic-${clinic.clinicId}",
                "reason",
                "OVERLAP",
            ).count() shouldBeEqualTo 1.0
    }

    @Test
    fun `metric failure does not replace a committed command result or domain error`() {
        val failingMetrics =
            object : AppointmentCommitmentCommandMetrics {
                override fun recordProposalLatency(
                    tenant: String,
                    clinic: String,
                    result: CommitmentMetricResult,
                    latency: Duration,
                ) = error("registry unavailable")

                override fun recordAllocationConflict(
                    tenant: String,
                    clinic: String,
                    reason: CommitmentConflictReason,
                ) = error("registry unavailable")
            }
        val service =
            applicationService(
                properties =
                    AppointmentCommitmentProperties(
                        mode = AppointmentCommitmentMode.WRITE,
                        clinicAllowlist = setOf(clinic.clinicId),
                    ),
                metrics = failingMetrics,
            )

        val committed =
            service.directCreate(
                adminActor(),
                "direct-metric-failure-01",
                true,
                directCreateRequest("metric-failure"),
            )
        val conflict = assertFailsWith<AppointmentCommitmentApiException> {
            service.directCreate(
                adminActor(),
                "direct-metric-failure-02",
                true,
                directCreateRequest("metric-failure-conflict"),
            )
        }

        committed.confirmedProposalId shouldBeEqualTo committed.currentProposal.proposalId
        conflict.error shouldBeEqualTo AppointmentCommitmentApiError.RESOURCE_CONFLICT
    }

    @Test
    fun `decline evidence identity is scoped by appointment and proposal`() {
        appointmentDeclineEvidenceId(1L, 11L, "SCHEDULE_REJECTED") shouldNotBeEqualTo
            appointmentDeclineEvidenceId(2L, 11L, "SCHEDULE_REJECTED")
        appointmentDeclineEvidenceId(1L, 11L, "SCHEDULE_REJECTED") shouldNotBeEqualTo
            appointmentDeclineEvidenceId(1L, 12L, "SCHEDULE_REJECTED")
    }

    @Test
    fun `direct create schedules only the next valid separated package visit`() {
        val service = writableApplicationService()

        val response = service.directCreate(adminActor(), "direct-package-01", true, directCreateRequest())

        val treatmentKeys =
            transaction(database) {
                AppointmentItems
                    .selectAll()
                    .where { AppointmentItems.proposalId eq response.currentProposal.proposalId }
                    .map { it[AppointmentItems.treatmentKey] }
            }
        treatmentKeys shouldBeEqualTo listOf("whitening")
    }

    @Test
    fun `customer and admin entry points preserve every member directory error`() {
        NotificationMemberApiError.entries
            .filterNot { it == NotificationMemberApiError.MEMBER_ID_REQUIRED }
            .forEach { error ->
                val service = writableApplicationService(
                    appointmentMemberResolver = FailingMemberResolver(error),
                )

                val customerFailure = assertFailsWith<NotificationMemberApiException> {
                    service.requestAppointment(
                        patientActor(),
                        "member-customer-${error.name}",
                        true,
                        createAppointmentRequest("member-customer"),
                    )
                }
                val adminFailure = assertFailsWith<NotificationMemberApiException> {
                    service.directCreate(
                        adminActor(),
                        "member-admin-${error.name}",
                        true,
                        directCreateRequest("member-admin"),
                    )
                }

                customerFailure.error shouldBeEqualTo error
                adminFailure.error shouldBeEqualTo error
            }
    }

    @Test
    fun `생성된 방문 capacity bucket은 snapshot 상한까지 허용하고 다음 확정을 거부한다`() {
        fun candidateSlot(practitionerIndex: Int) =
            ProposalCandidateSlot(
                tenantGroupId = TENANT_ID,
                clinicId = clinic.clinicId,
                startsAt = PROPOSAL_START,
                availableResources =
                    listOf(
                        AvailableProposalResource(
                            resourceType = ResourceType.PRACTITIONER,
                            resourceId = "doctor-capacity-$practitionerIndex",
                            capabilities = setOf("DOCTOR"),
                            allocationMode = ResourceAllocationMode.EXCLUSIVE,
                            capacityUnits = 1,
                        ),
                    ),
                visitCapacityBuckets =
                    listOf(
                        AvailableProposalResource(
                            resourceType = ResourceType.CAPACITY_BUCKET,
                            resourceId = "clinic-throughput-30m",
                            capabilities = emptySet(),
                            allocationMode = ResourceAllocationMode.CAPACITY_BUCKET,
                            capacityUnits = 1,
                            maximumCapacity = 3,
                        ),
                    ),
            )

        val confirmed =
            (1..3).map { index ->
                writableApplicationService(
                    planningResolver = FakePlanningResolver(listOf(candidateSlot(index))),
                ).directCreate(
                    adminActor(),
                    "direct-generated-capacity-$index",
                    true,
                    directCreateRequest("generated-capacity-$index"),
                )
            }

        val persistedMaximums =
            transaction(database) {
                ResourceAllocations
                    .selectAll()
                    .where {
                        ResourceAllocations.proposalId inList
                            confirmed.map { it.currentProposal.proposalId }
                    }
                    .filter { it[ResourceAllocations.resourceType] == ResourceType.CAPACITY_BUCKET }
                    .map { it[ResourceAllocations.maximumCapacity] }
            }
        persistedMaximums shouldBeEqualTo listOf(3, 3, 3)

        val exception = assertFailsWith<AppointmentCommitmentApiException> {
            writableApplicationService(
                planningResolver = FakePlanningResolver(listOf(candidateSlot(4))),
            ).directCreate(
                adminActor(),
                "direct-generated-capacity-4",
                true,
                directCreateRequest("generated-capacity-4"),
            )
        }
        exception.error shouldBeEqualTo AppointmentCommitmentApiError.RESOURCE_CONFLICT
    }

    @Test
    fun `policy snapshot scope mismatch fails closed before command execution`() {
        val service = writableApplicationService(
            policySnapshot =
                CurrentPolicySnapshot(
                    id = 99L,
                    policy = effectivePolicy(tenantGroupId = TENANT_ID + 1L),
                ),
        )

        val exception = assertFailsWith<AppointmentCommitmentApiException> {
            service.directCreate(adminActor(), "direct-policy-mismatch-01", true, directCreateRequest())
        }

        exception.error shouldBeEqualTo AppointmentCommitmentApiError.SCOPE_FORBIDDEN
    }

    @Test
    fun `no compatible inventory fails closed without fabricating a resource`() {
        val service = writableApplicationService(planningResolver = FakePlanningResolver(candidateSlots = emptyList()))

        val exception = assertFailsWith<AppointmentCommitmentApiException> {
            service.directCreate(adminActor(), "direct-no-inventory-01", true, directCreateRequest())
        }

        exception.error shouldBeEqualTo AppointmentCommitmentApiError.RESOURCE_CONFLICT
    }

    @Test
    fun `fake consent evidence fails closed before command execution`() {
        val service = writableApplicationService(consentMutation = ConsentEvidenceMutation.FAKE_HASH)

        val exception = assertFailsWith<AppointmentCommitmentApiException> {
            service.directCreate(adminActor(), "direct-fake-consent-01", true, directCreateRequest("fake-consent"))
        }

        exception.error shouldBeEqualTo AppointmentCommitmentApiError.CONSENT_REQUIRED
    }

    @Test
    fun `cross tenant and prefix lookalike consent authorities fail before verifier lookup`() {
        val service = writableApplicationService()

        listOf("other-tenant:consent-service", "tenant-task60:consent-service").forEachIndexed { index, authority ->
            val exception = assertFailsWith<AppointmentCommitmentApiException> {
                service.directCreate(
                    adminActor(),
                    "direct-cross-tenant-$index",
                    true,
                    directCreateRequest("cross-tenant-$index", evidenceAuthority = authority),
                )
            }

            exception.error shouldBeEqualTo AppointmentCommitmentApiError.SCOPE_FORBIDDEN
        }
    }

    @Test
    fun `consent evidence bound to another proposal fails exact proposal validation`() {
        val service = writableApplicationService(consentMutation = ConsentEvidenceMutation.OTHER_PROPOSAL)

        val exception = assertFailsWith<AppointmentCommitmentApiException> {
            service.directCreate(adminActor(), "direct-other-proposal-01", true, directCreateRequest("other-proposal"))
        }

        exception.error shouldBeEqualTo AppointmentCommitmentApiError.CONSENT_REQUIRED
    }

    @Test
    fun `expired consent evidence fails direct confirmation policy freshness`() {
        val service = writableApplicationService(consentMutation = ConsentEvidenceMutation.EXPIRED)

        val exception = assertFailsWith<AppointmentCommitmentApiException> {
            service.directCreate(adminActor(), "direct-expired-consent-01", true, directCreateRequest("expired-consent"))
        }

        exception.error shouldBeEqualTo AppointmentCommitmentApiError.CONSENT_REQUIRED
    }

    @Test
    fun `terms hash required policy rejects evidence without authoritative terms`() {
        val service = writableApplicationService(
            policySnapshot =
                CurrentPolicySnapshot(
                    42L,
                    effectivePolicy(termsHashRequired = true),
                ),
            consentMutation = ConsentEvidenceMutation.MISSING_TERMS,
        )

        val exception = assertFailsWith<AppointmentCommitmentApiException> {
            service.directCreate(adminActor(), "direct-missing-terms-01", true, directCreateRequest("missing-terms"))
        }

        exception.error shouldBeEqualTo AppointmentCommitmentApiError.CONSENT_REQUIRED
    }

    @Test
    fun `terms hash required policy rejects a different non-null authoritative terms hash`() {
        val service =
            writableApplicationService(
                policySnapshot =
                    CurrentPolicySnapshot(
                        43L,
                        effectivePolicy(termsHashRequired = true),
                    ),
                consentMutation = ConsentEvidenceMutation.WRONG_TERMS,
            )

        val exception = assertFailsWith<AppointmentCommitmentApiException> {
            service.directCreate(adminActor(), "direct-wrong-terms-01", true, directCreateRequest("wrong-terms"))
        }

        exception.error shouldBeEqualTo AppointmentCommitmentApiError.CONSENT_REQUIRED
    }

    @Test
    fun `accepted consent evidence cannot be reused for another commitment decision`() {
        val service = writableApplicationService()
        val request = createAppointmentRequest("reused-consent")

        service.requestAppointment(patientActor(), "request-reused-consent-01", true, request)
        val exception = assertFailsWith<AppointmentCommitmentApiException> {
            service.requestAppointment(patientActor(), "request-reused-consent-02", true, request)
        }

        exception.error shouldBeEqualTo AppointmentCommitmentApiError.CONSENT_EVIDENCE_REUSED
    }

    @Test
    fun `direct confirm validates current consent evidence before approving customer proposal`() {
        val service = writableApplicationService()
        val requested =
            service.requestAppointment(patientActor(), "request-direct-confirm-01", true, createAppointmentRequest("direct-confirm"))

        val confirmed =
            service.directConfirm(
                adminActor(),
                requested.appointmentId,
                requested.version,
                "direct-confirm-01",
                DirectConfirmRequest(
                    proposalId = requested.proposalId,
                    evidence = consentEvidence("direct-confirm-admin"),
                ),
            )

        confirmed.confirmedProposalId shouldBeEqualTo requested.proposalId
        transaction(database) {
            ConsentDecisions
                .selectAll()
                .where { ConsentDecisions.commitmentId eq confirmed.commitmentId }
                .count() shouldBeEqualTo 2L
            ConsentDecisions
                .selectAll()
                .where {
                    (ConsentDecisions.commitmentId eq confirmed.commitmentId) and
                        (ConsentDecisions.evidenceId eq consentEvidence("direct-confirm-admin").evidenceId)
                }
                .count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `direct create keeps the two lookup budget`() {
        val directCreateResolver = spyk(accessResolver())
        val directCreateService = writableApplicationService(accessResolver = directCreateResolver)

        directCreateService.directCreate(adminActor(), "budget-direct-create-01", true, directCreateRequest("budget-direct-create"))

        verify(exactly = 1) { directCreateResolver.resolvePlan(any(), clinic.planId) }
        verify(exactly = 1) { directCreateResolver.requireConsentAuthority(any(), any()) }
    }

    @Test
    fun `direct confirm keeps the two lookup budget`() {
        val requestResolver = spyk(accessResolver())
        val requestService = writableApplicationService(accessResolver = requestResolver)
        val requested =
            requestService.requestAppointment(
                patientActor(),
                "budget-direct-confirm-request-01",
                true,
                createAppointmentRequest("budget-direct-confirm-request"),
            )
        clearMocks(requestResolver, answers = false)

        requestService.directConfirm(
            adminActor(),
            requested.appointmentId,
            requested.version,
            "budget-direct-confirm-01",
            DirectConfirmRequest(
                proposalId = requested.proposalId,
                evidence = consentEvidence("budget-direct-confirm-admin"),
            ),
        )

        verify(exactly = 1) { requestResolver.requireAppointmentAccess(any(), requested.appointmentId) }
        verify(exactly = 1) { requestResolver.requireConsentAuthority(any(), any()) }
    }

    @Test
    fun `direct confirm keeps the policy snapshot pinned when the proposal was created`() {
        val original = CurrentPolicySnapshot(42L, effectivePolicy())
        val newer = CurrentPolicySnapshot(
            43L,
            effectivePolicy().copy(id = "b".repeat(64), snapshotHash = "b".repeat(64)),
        )
        var current = original
        val snapshots = mapOf(original.id to original, newer.id to newer)
        val resolver =
            object : AppointmentCommitmentPolicySnapshotResolver {
                override fun resolve(
                    tenantGroupId: Long,
                    clinicId: Long,
                    decisionAt: java.time.Instant,
                    serviceAt: java.time.Instant,
                ): CurrentPolicySnapshot = current

                override fun resolvePersisted(
                    tenantGroupId: Long,
                    clinicId: Long,
                    snapshotId: Long,
                ): PersistedPolicySnapshotReference {
                    val persisted = requireNotNull(snapshots[snapshotId])
                    return PersistedPolicySnapshotReference(
                        id = persisted.id,
                        snapshotHash = persisted.policy.snapshotHash,
                        tenantGeneration = persisted.policy.generation.tenantGeneration,
                        clinicGeneration = persisted.policy.generation.clinicGeneration,
                        sourceVersions = persisted.policy.sourceVersions,
                        payload = persisted.policy.payload,
                    )
                }
            }
        val service = writableApplicationService(policySnapshotResolver = resolver)
        val requested =
            service.requestAppointment(
                patientActor(),
                "request-direct-policy-pin-01",
                true,
                createAppointmentRequest("direct-policy-pin"),
            )
        current = newer

        val confirmed =
            service.directConfirm(
                adminActor(),
                requested.appointmentId,
                requested.version,
                "direct-policy-pin-01",
                DirectConfirmRequest(
                    proposalId = requested.proposalId,
                    evidence = consentEvidence("direct-policy-pin-admin"),
                ),
            )

        confirmed.confirmedProposalId shouldBeEqualTo requested.proposalId
        confirmed.effectivePolicySnapshotId shouldBeEqualTo original.id
        confirmed.currentProposal.policySnapshot.snapshotHash shouldBeEqualTo original.policy.snapshotHash
    }

    @Test
    fun `customer accepts a change proposal with the policy snapshot pinned when it was created`() {
        val original = CurrentPolicySnapshot(42L, effectivePolicy())
        val change = CurrentPolicySnapshot(
            43L,
            effectivePolicy().copy(id = "b".repeat(64), snapshotHash = "b".repeat(64)),
        )
        val newer = CurrentPolicySnapshot(
            44L,
            effectivePolicy().copy(id = "c".repeat(64), snapshotHash = "c".repeat(64)),
        )
        var current = original
        val pinned = mutableMapOf(
            original.id to original.policy.snapshotHash,
            change.id to change.policy.snapshotHash,
        )
        val resolver =
            object : AppointmentCommitmentPolicySnapshotResolver {
                override fun resolve(
                    tenantGroupId: Long,
                    clinicId: Long,
                    decisionAt: java.time.Instant,
                    serviceAt: java.time.Instant,
                ): CurrentPolicySnapshot = current

                override fun resolvePersisted(
                    tenantGroupId: Long,
                    clinicId: Long,
                    snapshotId: Long,
                ): PersistedPolicySnapshotReference =
                    PersistedPolicySnapshotReference(
                        id = snapshotId,
                        snapshotHash = requireNotNull(pinned[snapshotId]),
                        tenantGeneration = 1L,
                        clinicGeneration = 0L,
                        sourceVersions = emptyMap(),
                        payload = if (snapshotId == original.id) original.policy.payload else change.policy.payload,
                    )
            }
        val service = writableApplicationService(policySnapshotResolver = resolver)
        val confirmed =
            service.directCreate(adminActor(), "direct-policy-pin-01", true, directCreateRequest("policy-pin"))
        current = change
        val proposal =
            service.createChangeProposal(
                adminActor(),
                confirmed.appointmentId,
                confirmed.version,
                "change-policy-pin-01",
                CreateChangeProposalRequest(
                    preferredStartAt = PROPOSAL_START,
                    preferredEndAt = PROPOSAL_START.plusSeconds(3_600),
                ),
            )
        current = newer

        val accepted =
            service.decideProposal(
                patientActor(),
                proposal.appointmentId,
                proposal.proposalId,
                proposal.version,
                "accept-policy-pin-01",
                ProposalDecisionRequest(consentEvidence("accept-policy-pin")),
            )

        accepted.confirmedProposalId shouldBeEqualTo proposal.proposalId
        accepted.effectivePolicySnapshotId shouldBeEqualTo change.id
    }

    @Test
    fun `change proposal uses the current active plan revision after external facts`() {
        val service = writableApplicationService()
        val confirmed =
            service.directCreate(
                adminActor(),
                "direct-active-revision-01",
                true,
                directCreateRequest("active-revision"),
            )
        val activeRevisionId =
            transaction(database) {
                AppointmentPlanRevisions.update(
                    where = { AppointmentPlanRevisions.id eq clinic.planRevisionId },
                ) {
                    it[active] = false
                }
                val newRevisionId =
                    AppointmentPlanRevisions
                        .insertAndGetId {
                            it[planId] = clinic.planId
                            it[revision] = 2L
                            it[productVersionId] = "whitening-v2"
                            it[snapshotHash] = "e".repeat(64)
                            it[active] = true
                        }.value
                PlanRevisionTreatments.insert {
                    it[planRevisionId] = newRevisionId
                    it[treatmentKey] = "migrated-whitening"
                    it[componentProductId] = "whitening-component"
                    it[componentProductVersionId] = "whitening-component-v2"
                    it[productVersionId] = "whitening-v2"
                    it[status] = PlanTreatmentStatus.PENDING
                    it[sourceBomItemId] = "bom-whitening-v2"
                    it[sequence] = 1
                    it[representativeTreatmentName] = "전환된 미백 치료"
                    it[detailedTreatmentCodesPayload] = "[\"WHITENING_V2\"]"
                    it[preparationMinutes] = 10
                    it[treatmentMinutes] = 40
                    it[recoveryMinutes] = 10
                    it[practitionerQualificationsPayload] = "[\"DOCTOR\"]"
                    it[equipmentTypesPayload] = "[]"
                    it[spaceCapabilitiesPayload] = "[]"
                }
                newRevisionId
            }

        val changed =
            service.createChangeProposal(
                adminActor(),
                confirmed.appointmentId,
                confirmed.version,
                "change-active-revision-01",
                CreateChangeProposalRequest(
                    preferredStartAt = PROPOSAL_START,
                    preferredEndAt = PROPOSAL_START.plusSeconds(3_600),
                ),
            )

        transaction(database) {
            val item =
                AppointmentItems
                    .selectAll()
                    .where { AppointmentItems.proposalId eq changed.proposalId }
                    .single()
            item[AppointmentItems.planRevisionId].value shouldBeEqualTo activeRevisionId
            item[AppointmentItems.treatmentKey] shouldBeEqualTo "migrated-whitening"
        }
    }

    private fun writableApplicationService(
        policySnapshot: CurrentPolicySnapshot = CurrentPolicySnapshot(42L, effectivePolicy()),
        policySnapshotResolver: AppointmentCommitmentPolicySnapshotResolver? = null,
        planningResolver: AppointmentCommitmentPlanningResolver = FakePlanningResolver(),
        appointmentMemberResolver: AppointmentMemberResolver = VerifiedMemberResolver,
        consentMutation: ConsentEvidenceMutation = ConsentEvidenceMutation.NONE,
        accessResolver: AppointmentCommitmentAccessResolver = accessResolver(),
    ) = applicationService(
        properties =
            AppointmentCommitmentProperties(
                mode = AppointmentCommitmentMode.WRITE,
                clinicAllowlist = setOf(clinic.clinicId),
        ),
        policySnapshot = policySnapshot,
        policySnapshotResolver = policySnapshotResolver,
        planningResolver = planningResolver,
        appointmentMemberResolver = appointmentMemberResolver,
        consentMutation = consentMutation,
        accessResolver = accessResolver,
    )

    private fun applicationService(
        properties: AppointmentCommitmentProperties,
        registry: SimpleMeterRegistry = SimpleMeterRegistry(),
        metrics: AppointmentCommitmentCommandMetrics =
            io.bluetape4k.clinic.appointment.api.commitment.AppointmentCommitmentMetrics(registry),
        policySnapshot: CurrentPolicySnapshot = CurrentPolicySnapshot(42L, effectivePolicy()),
        policySnapshotResolver: AppointmentCommitmentPolicySnapshotResolver? = null,
        planningResolver: AppointmentCommitmentPlanningResolver = FakePlanningResolver(),
        appointmentMemberResolver: AppointmentMemberResolver = VerifiedMemberResolver,
        consentMutation: ConsentEvidenceMutation = ConsentEvidenceMutation.NONE,
        accessResolver: AppointmentCommitmentAccessResolver = accessResolver(),
    ) = DefaultAppointmentCommitmentApplicationService(
        database = database,
        properties = properties,
        accessResolver = accessResolver,
        commandService = commandService(CLOCK),
        policySnapshotResolver = policySnapshotResolver ?: object : AppointmentCommitmentPolicySnapshotResolver {
                override fun resolve(
                    tenantGroupId: Long,
                    clinicId: Long,
                    decisionAt: java.time.Instant,
                    serviceAt: java.time.Instant,
                ): CurrentPolicySnapshot = policySnapshot

                override fun resolvePersisted(
                    tenantGroupId: Long,
                    clinicId: Long,
                    snapshotId: Long,
                ): PersistedPolicySnapshotReference =
                    PersistedPolicySnapshotReference(
                        id = snapshotId,
                        snapshotHash = policySnapshot.policy.snapshotHash,
                        tenantGeneration = policySnapshot.policy.generation.tenantGeneration,
                        clinicGeneration = policySnapshot.policy.generation.clinicGeneration,
                        sourceVersions = policySnapshot.policy.sourceVersions,
                        payload = policySnapshot.policy.payload,
                    )
            },
        planningResolver = planningResolver,
        appointmentMemberResolver = appointmentMemberResolver,
        consentEvidenceVerifier = FakeConsentEvidenceVerifier(consentMutation),
        metrics = metrics,
        idempotencyKeyHasher =
            HmacAppointmentCommitmentIdempotencyKeyHasher(
                "test-appointment-commitment-key".padEnd(32, '!').toByteArray(),
            ),
        clock = CLOCK,
    ).also {
        ensurePlanRevisionAggregateTables()
    }

    private fun accessResolver() =
        AppointmentCommitmentAccessResolver(
            database = database,
            patientSubjectFingerprintResolver =
                PatientSubjectFingerprintResolver { _, patientSubjectId ->
                    if (patientSubjectId == "patient-subject-ok") {
                        clinic.patientReferenceFingerprint
                    } else {
                        "0".repeat(64)
                    }
                },
        )

    private fun ensurePlanRevisionAggregateTables() {
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(
                PlanRevisionDependencies,
                PlanRevisionGroupingConstraints,
            )
        }
    }

    private fun directCreateRequest(
        suffix: String = "default",
        evidenceAuthority: String = "tenant-task6:consent-service",
    ) =
        DirectCreateAppointmentRequest(
            appointmentPlanId = clinic.planId,
            preferredStartAt = PROPOSAL_START,
            preferredEndAt = PROPOSAL_START.plusSeconds(3_600),
            evidence = consentEvidence(suffix, evidenceAuthority),
        )

    private fun createAppointmentRequest(suffix: String = "default") =
        CreateAppointmentRequestV2(
            appointmentPlanId = clinic.planId,
            preferredStartAt = PROPOSAL_START,
            preferredEndAt = PROPOSAL_START.plusSeconds(3_600),
            evidence = consentEvidence(suffix),
        )

    private fun consentEvidence(
        suffix: String,
        evidenceAuthority: String = "tenant-task6:consent-service",
    ) = ConsentEvidenceRequest(
        evidenceAuthority = evidenceAuthority,
        evidenceId = "ev_${suffix.replace("-", "_").padEnd(20, '0')}",
    )

    private fun adminActor() =
        ActorContext(
            actorId = "actor-admin",
            actorType = ActorType.ADMIN,
            roles = setOf(ActorRole.ADMIN),
            scopes = emptySet(),
            allowedTenantCodes = setOf("tenant-task6"),
            allowedClinicIds = setOf(clinic.clinicId),
            patientSubjectId = null,
            assurance = AuthenticationAssurance.MFA,
            issuer = "appointment-auth-service",
            tokenId = "token-admin",
            authenticatedAt = NOW,
            correlationId = "correlation-admin",
            selectedClinicId = clinic.clinicId,
            selectedTenantCode = "tenant-task6",
        )

    private fun patientActor() =
        ActorContext(
            actorId = "actor-patient",
            actorType = ActorType.PATIENT,
            roles = setOf(ActorRole.PATIENT),
            scopes = emptySet(),
            allowedTenantCodes = setOf("tenant-task6"),
            allowedClinicIds = setOf(clinic.clinicId),
            patientSubjectId = "patient-subject-ok",
            assurance = AuthenticationAssurance.MFA,
            issuer = "appointment-auth-service",
            tokenId = "token-patient",
            authenticatedAt = NOW,
            correlationId = "correlation-patient",
            selectedClinicId = clinic.clinicId,
            selectedTenantCode = "tenant-task6",
        )

    private fun effectivePolicy(
        tenantGroupId: Long = TENANT_ID,
        clinicId: Long = clinic.clinicId,
        termsHashRequired: Boolean = false,
    ): EffectiveSchedulingPolicy =
        EffectiveSchedulingPolicy(
            id = "a".repeat(64),
            tenantGroupId = tenantGroupId,
            clinicId = clinicId,
            decisionAt = NOW,
            serviceAt = PROPOSAL_START,
            generation = PolicyGenerationVector(1L, 0L),
            sourceVersions = emptyMap(),
            sourceByPath = emptyMap(),
            disabledFeatures = emptySet(),
            warnings = emptyList(),
            payload =
                CompiledSchedulingPolicy(
                    bookingCommitment =
                        BookingCommitmentPolicy(
                            adminBookingMode = AdminBookingMode.DIRECT_CONFIRM_WITH_CONSENT_EVIDENCE,
                            patientBookingMode = PatientBookingMode.PROVISIONAL_APPROVAL_REQUIRED,
                            provisionalCapacityMode = ProvisionalCapacityMode.NO_HOLD,
                            provisionalRequestTtl = Duration.ofMinutes(30),
                            resourceHoldTtl = null,
                            approvalRoles = setOf(ActorRole.ADMIN),
                            adminConsentEvidence =
                                ConsentEvidenceRequirement(
                                    allowedEvidenceTypes = setOf("OPAQUE_REFERENCE"),
                                    maximumAge = Duration.ofDays(365),
                                    termsHashRequired = termsHashRequired,
                                ),
                            confirmedChangeMode = ConfirmedChangeMode.NEW_PROPOSAL_AND_CUSTOMER_CONSENT,
                        ),
                ),
            snapshotHash = "a".repeat(64),
        )

    private enum class ConsentEvidenceMutation {
        NONE,
        FAKE_HASH,
        OTHER_PROPOSAL,
        EXPIRED,
        MISSING_TERMS,
        WRONG_TERMS,
    }

    private inner class FakeConsentEvidenceVerifier(
        private val mutation: ConsentEvidenceMutation,
    ) : AppointmentCommitmentConsentEvidenceVerifier {
        override fun verify(
            request: AppointmentCommitmentConsentEvidenceVerificationRequest,
        ): VerifiedAppointmentCommitmentConsentEvidence =
            VerifiedAppointmentCommitmentConsentEvidence(
                evidenceAuthority = request.evidence.evidenceAuthority,
                evidenceId = request.evidence.evidenceId,
                evidenceType = "OPAQUE_REFERENCE",
                evidenceHash =
                    if (mutation == ConsentEvidenceMutation.FAKE_HASH) {
                        "x".repeat(64)
                    } else {
                        sha256("${request.evidence.evidenceAuthority}|${request.evidence.evidenceId}|${request.proposalHash}")
                    },
                decidedAt =
                    if (mutation == ConsentEvidenceMutation.EXPIRED) {
                        NOW.minus(Duration.ofDays(366).plusSeconds(1))
                    } else {
                        NOW
                    },
                termsHash =
                    when {
                        !request.termsHashRequired || mutation == ConsentEvidenceMutation.MISSING_TERMS -> null
                        mutation == ConsentEvidenceMutation.WRONG_TERMS -> "e".repeat(64)
                        else -> request.requiredTermsHash
                    },
                tenantGroupId = request.tenantGroupId,
                clinicId = request.clinicId,
                patientReferenceFingerprint = request.patientReferenceFingerprint,
                appointmentPlanId = request.appointmentPlanId,
                appointmentId = request.appointmentId,
                proposalId = request.proposalId,
                proposalHash =
                    if (mutation == ConsentEvidenceMutation.OTHER_PROPOSAL) {
                        "9".repeat(64)
                    } else {
                        request.proposalHash
                    },
                policySnapshotId = request.policySnapshotId,
                policySnapshotHash = request.policySnapshotHash,
            )
    }

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private object VerifiedMemberResolver : AppointmentMemberResolver {
        override fun resolveLegacy(
            tenantGroupId: Long,
            clinicId: Long,
            requested: MemberId?,
        ): MemberResolution = MemberResolution.Resolved(requested ?: MemberId("patient-external-01"))

        override fun resolvePlan(
            actor: ActorContext,
            access: ResolvedAppointmentPlanAccess,
        ): MemberId = MemberId("patient-external-01")
    }

    private class FailingMemberResolver(
        private val error: NotificationMemberApiError,
    ) : AppointmentMemberResolver {
        override fun resolveLegacy(
            tenantGroupId: Long,
            clinicId: Long,
            requested: MemberId?,
        ): MemberResolution = throw NotificationMemberApiException(error)

        override fun resolvePlan(
            actor: ActorContext,
            access: ResolvedAppointmentPlanAccess,
        ): MemberId = throw NotificationMemberApiException(error)
    }

    private inner class FakePlanningResolver(
        private val candidateSlots: List<ProposalCandidateSlot> = listOf(defaultCandidateSlot()),
    ) : AppointmentCommitmentPlanningResolver {
        override fun resolveIdentity(
            actor: ActorContext,
            access: ResolvedAppointmentPlanAccess,
        ): AppointmentVisitIdentityDraft =
            AppointmentVisitIdentityDraft(
                patientName = "홍길동",
                patientPhone = "010-1234-5678",
                memberId = MemberId("patient-external-01"),
                patientReferenceFingerprint = access.plan.patientReferenceFingerprint,
            )

        override fun resolveCandidateSlots(request: AppointmentCommitmentCandidateSlotRequest): List<ProposalCandidateSlot> =
            candidateSlots

        override fun resolveStoredProposalResourceRequests(
            clinicId: Long,
            proposal: AppointmentProposalRecord,
            items: List<AppointmentItemDraft>,
        ): List<ResourceAllocationRequest> =
            listOf(
                ResourceAllocationRequest(
                    ResourceAllocationDraft(
                        resourceType = ResourceType.PRACTITIONER,
                        resourceId = "doctor-${this@DefaultAppointmentCommitmentApplicationServiceTest.clinic.doctorId}",
                        startsAt = proposal.proposedStartAt,
                        endsAt = proposal.proposedEndAt,
                        capacityUnits = 1,
                        allocationMode = ResourceAllocationMode.EXCLUSIVE,
                        appointmentItemKey = items.first().treatmentKey,
                    ),
                    1,
                ),
            )

        override fun resolveProjectionTarget(
            clinicId: Long,
            proposal: VisitProposalInput,
        ): ConfirmedAppointmentProjectionTarget {
            val practitioner =
                proposal.resourceRequests
                    .map(ResourceAllocationRequest::allocation)
                    .single { it.resourceType == ResourceType.PRACTITIONER }
            return ConfirmedAppointmentProjectionTarget(
                doctorId = clinic.doctorId,
                treatmentTypeId = clinic.treatmentTypeId,
                practitionerResourceId = practitioner.resourceId,
            )
        }
    }

    private fun defaultCandidateSlot(): ProposalCandidateSlot =
        ProposalCandidateSlot(
            tenantGroupId = TENANT_ID,
            clinicId = clinic.clinicId,
            startsAt = PROPOSAL_START,
            availableResources =
                listOf(
                    AvailableProposalResource(
                        resourceType = ResourceType.PRACTITIONER,
                        resourceId = "doctor-${clinic.doctorId}",
                        capabilities = setOf("DOCTOR"),
                        allocationMode = ResourceAllocationMode.EXCLUSIVE,
                        capacityUnits = 1,
                    ),
                    AvailableProposalResource(
                        resourceType = ResourceType.EQUIPMENT,
                        resourceId = "equipment-consultation",
                        capabilities = setOf("CONSULTATION_DEVICE"),
                        allocationMode = ResourceAllocationMode.EXCLUSIVE,
                        capacityUnits = 1,
                    ),
                ),
        )
}
