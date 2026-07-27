package io.bluetape4k.clinic.appointment.api.policy

import io.bluetape4k.clinic.appointment.api.config.SchedulingPolicyApiException
import io.bluetape4k.clinic.appointment.api.security.ActorContext
import io.bluetape4k.clinic.appointment.api.security.ActorType
import io.bluetape4k.clinic.appointment.api.security.AuthenticationAssurance
import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxEvents
import io.bluetape4k.clinic.appointment.event.policy.SchedulingPolicyEventRepository
import io.bluetape4k.clinic.appointment.model.dto.PolicyActivationCommandStatus
import io.bluetape4k.clinic.appointment.model.dto.PolicyScopeRef
import io.bluetape4k.clinic.appointment.model.policy.ActorRole
import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import io.bluetape4k.clinic.appointment.model.policy.SchedulingPolicyKind
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlans
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogProjections
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyActivationCommands
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyApprovals
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyDefinitions
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyScopeHeads
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.repository.SchedulingPolicyJobRepository
import io.bluetape4k.clinic.appointment.repository.SchedulingPolicyRepository
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 스코프 헤드 행이 병렬 활성화의 유일한 직렬화 지점임을 검증한다.
 *
 * 두 요청은 의도적으로 서로 다른 멱등 키를 사용한다. 따라서 멱등 키가 동시성 mutex인 것처럼
 * 우연히 통과할 수 없다. 정확히 한 트랜잭션만 초안을 교체하고 세대를 증가시키며 명령 완료와
 * outbox 이벤트 기록까지 원자적으로 끝내야 한다.
 */
class SchedulingPolicyActivationConcurrencyTest {
    private val now = Instant.parse("2026-07-27T00:00:00Z")
    private val scope = PolicyScopeRef(1L, PolicyScope.TENANT_DEFAULT)
    private val policyRepository = SchedulingPolicyRepository()
    private val jobRepository = SchedulingPolicyJobRepository("concurrency-policy-secret-value".toByteArray())
    private val service = SchedulingPolicyCommandService(
        policyRepository,
        jobRepository,
        PolicyTenantBoundaryVerifier { requestedScope, actor ->
            requestedScope.tenantGroupId == 1L && "tenant-one" in actor.allowedTenantCodes
        },
        PolicyPreviewEvidenceVerifier { _, _, _ -> true },
        PolicyActivationPublisher(SchedulingPolicyEventRepository()::insertPolicyActivated),
        Clock.fixed(now, ZoneOffset.UTC),
    )

    @BeforeEach
    fun setup() {
        Database.connect(
            "jdbc:h2:mem:policy_concurrency_${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver",
        )
        transaction {
            SchemaUtils.createMissingTablesAndColumns(*ALL_TABLES)
            ALL_TABLES.reversed().forEach { it.deleteAll() }
            TenantGroups.insert {
                it[id] = EntityID(1L, TenantGroups)
                it[tenantCode] = "tenant-one"
                it[displayName] = "Tenant One"
                it[active] = true
            }
        }
    }

    @Test
    fun `concurrent activations produce one generation one completed command and one event`() {
        val admin = actor()
        val draft = service.createDraft(
            CreateSchedulingPolicyDraftCommand(
                scope = scope,
                kind = SchedulingPolicyKind.BOOKING_COMMITMENT,
                schemaVersion = 1,
                effectiveFrom = now,
                effectiveUntil = null,
                payloadHash = "b".repeat(64),
                payloadJson = "{}",
                changeReason = "Concurrent activation test",
                expectedScopeRevision = 0L,
                actor = admin,
            )
        ).definition
        service.approve(ApproveSchedulingPolicyCommand(scope, draft.id!!, 1L, admin))

        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val tasks = listOf("concurrent-a", "concurrent-b").map { key ->
            Callable {
                ready.countDown()
                start.await(5, TimeUnit.SECONDS)
                runCatching {
                    service.activate(
                        ActivateSchedulingPolicyCommand(
                            scope = scope,
                            definitionId = draft.id!!,
                            expectedDraftRevision = 1L,
                            expectedActiveRevision = 1L,
                            idempotencyKey = key,
                            preview = PolicyPreviewEvidence(
                                definitionId = draft.id!!,
                                draftRevision = 1L,
                                tenantGeneration = 0L,
                                clinicGeneration = 0L,
                                evidenceId = "preview-concurrent",
                            ),
                            actor = admin,
                        )
                    )
                }
            }
        }
        val futures = tasks.map(executor::submit)
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        val outcomes = futures.map { it.get(15, TimeUnit.SECONDS) }
        executor.shutdownNow()

        assertEquals(1, outcomes.count { it.isSuccess })
        assertEquals(1, outcomes.count { it.exceptionOrNull() is SchedulingPolicyApiException })
        transaction {
            assertEquals(1L, policyRepository.lockScopeHead(scope).generation)
            assertEquals(
                1L,
                SchedulingPolicyActivationCommands.selectAll()
                    .where {
                        SchedulingPolicyActivationCommands.status eq
                            PolicyActivationCommandStatus.COMPLETED
                    }
                    .count(),
            )
            assertEquals(1L, SchedulingOutboxEvents.selectAll().count())
        }
    }

    @Test
    fun `concurrent identical idempotency intent converges on one completed result`() {
        val admin = actor()
        val draftId = createApprovedDraft(admin)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val futures = (1..2).map {
            executor.submit(Callable {
                ready.countDown()
                start.await(5, TimeUnit.SECONDS)
                service.activate(activation(draftId, admin, "same-concurrent-key"))
            })
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        val results = futures.map { it.get(15, TimeUnit.SECONDS) }
        executor.shutdownNow()

        assertEquals(1, results.count { it.idempotentReplay })
        assertEquals(1, results.map { it.command.id }.distinct().size)
        transaction {
            assertEquals(1L, policyRepository.lockScopeHead(scope).generation)
            assertEquals(1L, SchedulingPolicyActivationCommands.selectAll().count())
            assertEquals(1L, SchedulingOutboxEvents.selectAll().count())
        }
    }

    private fun createApprovedDraft(admin: ActorContext): Long {
        val draft = service.createDraft(
            CreateSchedulingPolicyDraftCommand(
                scope = scope,
                kind = SchedulingPolicyKind.BOOKING_COMMITMENT,
                schemaVersion = 1,
                effectiveFrom = now,
                effectiveUntil = null,
                payloadHash = "d".repeat(64),
                payloadJson = "{}",
                changeReason = "Idempotent concurrency test",
                expectedScopeRevision = 0L,
                actor = admin,
            )
        ).definition
        service.approve(ApproveSchedulingPolicyCommand(scope, draft.id!!, 1L, admin))
        return draft.id!!
    }

    private fun activation(
        definitionId: Long,
        admin: ActorContext,
        idempotencyKey: String,
    ) = ActivateSchedulingPolicyCommand(
        scope = scope,
        definitionId = definitionId,
        expectedDraftRevision = 1L,
        expectedActiveRevision = 1L,
        idempotencyKey = idempotencyKey,
        preview = PolicyPreviewEvidence(
            definitionId = definitionId,
            draftRevision = 1L,
            tenantGeneration = 0L,
            clinicGeneration = 0L,
            evidenceId = "preview-idempotent-concurrent",
        ),
        actor = admin,
    )

    private fun actor() = ActorContext(
        actorId = "concurrency-admin",
        actorType = ActorType.ADMIN,
        roles = setOf(ActorRole.ADMIN),
        scopes = setOf("policy:write"),
        allowedTenantCodes = setOf("tenant-one"),
        allowedClinicIds = emptySet(),
        patientSubjectId = null,
        assurance = AuthenticationAssurance.MFA,
        issuer = "test-gateway",
        tokenId = "concurrency-token",
        authenticatedAt = now.minusSeconds(60),
        correlationId = "concurrency-correlation",
    )

    private companion object {
        val ALL_TABLES = arrayOf(
            TenantGroups,
            Clinics,
            ProductCatalogProjections,
            AppointmentPlans,
            SchedulingPolicyDefinitions,
            SchedulingPolicyApprovals,
            SchedulingPolicyScopeHeads,
            SchedulingPolicyActivationCommands,
            SchedulingOutboxEvents,
        )
    }
}
