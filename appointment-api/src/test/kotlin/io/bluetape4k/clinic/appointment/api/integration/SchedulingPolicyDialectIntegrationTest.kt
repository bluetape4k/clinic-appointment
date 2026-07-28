package io.bluetape4k.clinic.appointment.api.integration

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.api.policy.ActivateSchedulingPolicyCommand
import io.bluetape4k.clinic.appointment.api.policy.ApproveSchedulingPolicyCommand
import io.bluetape4k.clinic.appointment.api.policy.CreateSchedulingPolicyDraftCommand
import io.bluetape4k.clinic.appointment.api.policy.PolicyActivationPublisher
import io.bluetape4k.clinic.appointment.api.policy.PolicyPreviewEvidence
import io.bluetape4k.clinic.appointment.api.policy.PolicyPreviewEvidenceVerifier
import io.bluetape4k.clinic.appointment.api.policy.PolicyTenantBoundaryVerifier
import io.bluetape4k.clinic.appointment.api.policy.SchedulingPolicyCommandService
import io.bluetape4k.clinic.appointment.api.security.ActorContext
import io.bluetape4k.clinic.appointment.api.security.ActorType
import io.bluetape4k.clinic.appointment.api.security.AuthenticationAssurance
import io.bluetape4k.clinic.appointment.api.test.AbstractApiIntegrationTest
import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxEvents
import io.bluetape4k.clinic.appointment.event.policy.SchedulingPolicyEventRepository
import io.bluetape4k.clinic.appointment.model.dto.PolicyPreviewJobStatus
import io.bluetape4k.clinic.appointment.model.dto.PolicyScopeRef
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyDefinitionRecord
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyPreviewJobRecord
import io.bluetape4k.clinic.appointment.model.policy.ActorRole
import io.bluetape4k.clinic.appointment.model.policy.PolicyLifecycle
import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import io.bluetape4k.clinic.appointment.model.policy.SchedulingPolicyKind
import io.bluetape4k.clinic.appointment.model.tables.EffectiveSchedulingPolicySnapshots
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyActivationCommands
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyApprovals
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyDefinitions
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyPreviewJobs
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyScopeHeads
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.repository.SchedulingPolicyJobRepository
import io.bluetape4k.clinic.appointment.repository.SchedulingPolicyRepository
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 동일한 V9 scheduling-policy 계약이 H2, PostgreSQL, MySQL에서 같은 결과를 내는지 검증한다.
 *
 * 이 테스트는 각 dialect에서 실제 Flyway schema와 Exposed repository를 함께 사용한다.
 * 병렬 활성화는 서로 다른 JVM lock이 아니라 scope-head CAS와 database transaction으로
 * 수렴해야 하며, preview lease·반개구간 overlap·불변 snapshot·generic outbox도 같은
 * 의미를 유지해야 한다.
 */
class SchedulingPolicyDialectIntegrationTest : AbstractApiIntegrationTest() {

    private val now = Instant.parse("2026-07-28T03:00:00Z")
    private val prefix = UUID.randomUUID().toString().replace("-", "")
    private val policyRepository = SchedulingPolicyRepository()
    private val jobRepository = SchedulingPolicyJobRepository(
        "dialect-policy-secret-value-32-bytes".toByteArray(),
    )
    private var tenantGroupId = 0L
    private lateinit var scope: PolicyScopeRef

    @BeforeEach
    fun setUpTenant() {
        tenantGroupId = transaction {
            TenantGroups.insert {
                it[tenantCode] = "policy-dialect-$prefix"
                it[displayName] = "Policy Dialect Tenant"
                it[active] = true
            }[TenantGroups.id].value
        }
        scope = PolicyScopeRef(tenantGroupId, PolicyScope.TENANT_DEFAULT)
    }

    @AfterEach
    fun cleanUpPolicyRows() {
        if (tenantGroupId == 0L) return
        transaction {
            SchedulingOutboxEvents.deleteWhere {
                SchedulingOutboxEvents.tenantGroupId eq tenantGroupId
            }
            SchedulingPolicyPreviewJobs.deleteWhere {
                SchedulingPolicyPreviewJobs.tenantGroupId eq tenantGroupId
            }
            SchedulingPolicyActivationCommands.deleteWhere {
                SchedulingPolicyActivationCommands.tenantGroupId eq tenantGroupId
            }
            val definitionIds = SchedulingPolicyDefinitions
                .selectAll()
                .where { SchedulingPolicyDefinitions.tenantGroupId eq tenantGroupId }
                .map { it[SchedulingPolicyDefinitions.id].value }
            if (definitionIds.isNotEmpty()) {
                SchedulingPolicyApprovals.deleteWhere {
                    SchedulingPolicyApprovals.definitionId inList definitionIds
                }
            }
            SchedulingPolicyDefinitions.deleteWhere {
                SchedulingPolicyDefinitions.tenantGroupId eq tenantGroupId
            }
            SchedulingPolicyScopeHeads.deleteWhere {
                SchedulingPolicyScopeHeads.tenantGroupId eq tenantGroupId
            }
            EffectiveSchedulingPolicySnapshots.deleteWhere {
                EffectiveSchedulingPolicySnapshots.tenantGroupId eq tenantGroupId
            }
            TenantGroups.deleteWhere { TenantGroups.id eq tenantGroupId }
        }
    }

    @RepeatedTest(3)
    fun `parallel activation and identical replay converge to one durable winner`() {
        val service = commandService()
        val admin = actor()
        val draft = createApprovedDraft(service, admin)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val futures = (1..2).map {
            executor.submit(Callable {
                ready.countDown()
                start.await(5, TimeUnit.SECONDS).shouldBeTrue()
                service.activate(activation(draft, admin))
            })
        }

        ready.await(5, TimeUnit.SECONDS).shouldBeTrue()
        start.countDown()
        val results = try {
            futures.map { it.get(20, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        results.count { it.idempotentReplay } shouldBeEqualTo 1
        results.map { it.command.id }.distinct().size shouldBeEqualTo 1
        transaction {
            policyRepository.lockScopeHead(scope).generation shouldBeEqualTo 1L
            SchedulingPolicyActivationCommands.selectAll()
                .where {
                    (SchedulingPolicyActivationCommands.tenantGroupId eq tenantGroupId) and
                        (SchedulingPolicyActivationCommands.scope eq PolicyScope.TENANT_DEFAULT) and
                        (SchedulingPolicyActivationCommands.clinicScopeKey eq 0L)
                }
                .count() shouldBeEqualTo 1L
            SchedulingOutboxEvents.selectAll()
                .where {
                    SchedulingOutboxEvents.tenantGroupId eq tenantGroupId
                }
                .single()
                .also { row ->
                    row[SchedulingOutboxEvents.aggregateType] shouldBeEqualTo "SCHEDULING_POLICY"
                    row[SchedulingOutboxEvents.aggregateId] shouldBeEqualTo draft.toString()
                    (row[SchedulingOutboxEvents.planId] == null).shouldBeTrue()
                    (row[SchedulingOutboxEvents.clinicId] == null).shouldBeTrue()
                    (row[SchedulingOutboxEvents.causationEventId] == null).shouldBeTrue()
                }
        }
    }

    @Test
    fun `overlap effective snapshot and preview lease semantics remain dialect neutral`() {
        val service = commandService()
        val admin = actor()
        val activeId = createApprovedDraft(service, admin)
        service.activate(activation(activeId, admin))

        transaction {
            policyRepository.createDefinition(
                definition(
                    version = 2L,
                    lifecycle = PolicyLifecycle.SCHEDULED,
                    effectiveFrom = now.plusSeconds(300),
                    effectiveUntil = now.plusSeconds(900),
                    payloadHash = "c".repeat(64),
                )
            )

            policyRepository.findActiveDefinitionAt(
                scope,
                SchedulingPolicyKind.BOOKING_COMMITMENT,
                now.plusSeconds(60),
            )!!.id shouldBeEqualTo activeId
            policyRepository.findOverlappingPublishedDefinitions(
                scope,
                SchedulingPolicyKind.BOOKING_COMMITMENT,
                now.plusSeconds(600),
                now.plusSeconds(1_200),
            ).map { it.version } shouldBeEqualTo listOf(1L, 2L)

            val firstSnapshot = policyRepository.saveSnapshot(
                tenantGroupId = tenantGroupId,
                clinicId = 41L,
                decisionAt = now,
                serviceAt = now.plusSeconds(60),
                tenantGeneration = 1L,
                clinicGeneration = 0L,
                sourceVersionsJson = """{"BOOKING_COMMITMENT":1}""",
                sourceByPathJson = """{"/booking":"TENANT"}""",
                disabledFeaturesJson = "[]",
                warningsJson = "[]",
                payloadJson = """{"mode":"REQUEST"}""",
                snapshotHash = "d".repeat(64),
            )
            val replayedSnapshot = policyRepository.saveSnapshot(
                tenantGroupId = tenantGroupId,
                clinicId = 41L,
                decisionAt = now.plusSeconds(10),
                serviceAt = now.plusSeconds(70),
                tenantGeneration = 2L,
                clinicGeneration = 0L,
                sourceVersionsJson = """{"BOOKING_COMMITMENT":2}""",
                sourceByPathJson = """{"/booking":"CHANGED"}""",
                disabledFeaturesJson = "[]",
                warningsJson = """["changed"]""",
                payloadJson = """{"mode":"CHANGED"}""",
                snapshotHash = "d".repeat(64),
            )
            replayedSnapshot.id shouldBeEqualTo firstSnapshot.id
            replayedSnapshot.payloadJson shouldBeEqualTo """{"mode":"REQUEST"}"""
            replayedSnapshot.tenantGeneration shouldBeEqualTo 1L

            val preview = jobRepository.createPreviewJob(
                SchedulingPolicyPreviewJobRecord(
                    tenantGroupId = tenantGroupId,
                    scope = PolicyScope.TENANT_DEFAULT,
                    clinicId = null,
                    definitionId = activeId,
                    draftRevision = 1L,
                    tenantGeneration = 1L,
                    clinicGeneration = 0L,
                    clinicGenerationDigest = "e".repeat(64),
                    partitionCount = 1,
                    status = PolicyPreviewJobStatus.PENDING,
                    deadlineAt = now.plusSeconds(300),
                    nextAttemptAt = now,
                    horizonUntil = now.plusSeconds(300),
                )
            )
            jobRepository.claimDuePreview(
                requireNotNull(preview.id),
                "dialect-worker-a",
                now,
                now.plusSeconds(30),
            ).shouldBeTrue()
            jobRepository.claimDuePreview(
                requireNotNull(preview.id),
                "dialect-worker-b",
                now.plusSeconds(1),
                now.plusSeconds(31),
            ).shouldBeFalse()
        }
    }

    private fun commandService() = SchedulingPolicyCommandService(
        policyRepository = policyRepository,
        jobRepository = jobRepository,
        tenantBoundaryVerifier = PolicyTenantBoundaryVerifier { requestedScope, actor ->
            requestedScope == scope && actor.allowedTenantCodes.contains("policy-dialect-$prefix")
        },
        previewVerifier = PolicyPreviewEvidenceVerifier { _, _, _ -> true },
        publisher = PolicyActivationPublisher(
            SchedulingPolicyEventRepository()::insertPolicyActivated,
        ),
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    private fun createApprovedDraft(
        service: SchedulingPolicyCommandService,
        admin: ActorContext,
    ): Long {
        val draft = service.createDraft(
            CreateSchedulingPolicyDraftCommand(
                scope = scope,
                kind = SchedulingPolicyKind.BOOKING_COMMITMENT,
                schemaVersion = 1,
                effectiveFrom = now,
                effectiveUntil = null,
                payloadHash = "b".repeat(64),
                payloadJson = "{}",
                changeReason = "Dialect integration",
                expectedScopeRevision = 0L,
                actor = admin,
            )
        ).definition
        service.approve(ApproveSchedulingPolicyCommand(scope, requireNotNull(draft.id), 1L, admin))
        return requireNotNull(draft.id)
    }

    private fun activation(
        definitionId: Long,
        admin: ActorContext,
    ) = ActivateSchedulingPolicyCommand(
        scope = scope,
        definitionId = definitionId,
        expectedDraftRevision = 1L,
        expectedActiveRevision = 1L,
        idempotencyKey = "dialect-idempotency-key",
        preview = PolicyPreviewEvidence(
            definitionId = definitionId,
            draftRevision = 1L,
            tenantGeneration = 0L,
            clinicGeneration = 0L,
            evidenceId = "dialect-preview-evidence",
        ),
        actor = admin,
    )

    private fun definition(
        version: Long,
        lifecycle: PolicyLifecycle,
        effectiveFrom: Instant,
        effectiveUntil: Instant?,
        payloadHash: String,
    ) = SchedulingPolicyDefinitionRecord(
        tenantGroupId = tenantGroupId,
        scope = PolicyScope.TENANT_DEFAULT,
        kind = SchedulingPolicyKind.BOOKING_COMMITMENT,
        version = version,
        schemaVersion = 1,
        lifecycle = lifecycle,
        effectiveFrom = effectiveFrom,
        effectiveUntil = effectiveUntil,
        revision = 1L,
        payloadHash = payloadHash,
        payloadJson = "{}",
        createdByActorId = "dialect-admin",
        createdByActorRole = ActorRole.ADMIN,
        changeReason = "Dialect overlap fixture",
    )

    private fun actor() = ActorContext(
        actorId = "dialect-admin",
        actorType = ActorType.ADMIN,
        roles = setOf(ActorRole.ADMIN),
        scopes = setOf("policy:write"),
        allowedTenantCodes = setOf("policy-dialect-$prefix"),
        allowedClinicIds = emptySet(),
        patientSubjectId = null,
        assurance = AuthenticationAssurance.MFA,
        issuer = "test-gateway",
        tokenId = "dialect-token",
        authenticatedAt = now.minusSeconds(30),
        correlationId = "dialect-correlation",
    )
}
