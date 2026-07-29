package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyApprovalRecord
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyDefinitionRecord
import io.bluetape4k.clinic.appointment.model.dto.PolicyScopeRef
import io.bluetape4k.clinic.appointment.model.policy.ActorRole
import io.bluetape4k.clinic.appointment.model.policy.PolicyLifecycle
import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import io.bluetape4k.clinic.appointment.model.policy.SchedulingPolicyKind
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.EffectiveSchedulingPolicySnapshots
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyApprovals
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyDefinitions
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyScopeHeads
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.test.AbstractExposedTest
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withTables
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.core.statements.api.PreparedStatementApi
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Instant

/**
 * 버전 정책 영속성의 잠금 순서·반개구간·불변 snapshot 계약을 데이터베이스 방언별로 검증한다.
 *
 * 호출자 소유 트랜잭션 안에서 tenant 스코프를 clinic보다 먼저 잠그고, revision CAS와 generation
 * 증가를 분리하며, `effectiveFrom <= at < effectiveUntil`인 `ACTIVE` 정의만 선택하는지
 * 확인한다. 같은 scoped hash는 최초 canonical bytes를 보존하고 과거 예약 증거를 갱신하지
 * 않아야 한다.
 */
class SchedulingPolicyRepositoryTest : AbstractExposedTest() {

    private val repository = SchedulingPolicyRepository()

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `definition identity uses a non-null tenant sentinel and approvals are revision scoped`(testDB: TestDB) {
        withPolicyTables(testDB) {
            val tenantDefinition = repository.createDefinition(definition())
            tenantDefinition.id.shouldNotBeNull().shouldBeGreaterThan(0L)
            tenantDefinition.clinicScopeKey shouldBeEqualTo 0L

            val clinicDefinition = repository.createDefinition(
                definition(
                    scope = PolicyScope.CLINIC_OVERRIDE,
                    clinicId = 41L,
                    version = 1L,
                )
            )
            clinicDefinition.clinicScopeKey shouldBeEqualTo 41L

            repository.addApproval(approval(tenantDefinition.id.shouldNotBeNull(), revision = 1L))
            repository.addApproval(approval(tenantDefinition.id.shouldNotBeNull(), revision = 2L))

            repository.findApprovals(tenantDefinition.id.shouldNotBeNull(), 1L).size shouldBeEqualTo 1
            repository.findApprovals(tenantDefinition.id.shouldNotBeNull(), 2L).size shouldBeEqualTo 1

            assertFailsWith<ExposedSQLException> {
                repository.addApproval(approval(tenantDefinition.id.shouldNotBeNull(), revision = 2L))
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `definition version is unique inside its exact scope and kind`(testDB: TestDB) {
        withPolicyTables(testDB) {
            repository.createDefinition(definition())

            assertFailsWith<ExposedSQLException> {
                repository.createDefinition(definition())
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `scope head generation advances only from the expected revision`(testDB: TestDB) {
        withPolicyTables(testDB) {
            val tenantScope = PolicyScopeRef(tenantGroupId = 1L, scope = PolicyScope.TENANT_DEFAULT)
            val clinicScope = PolicyScopeRef(
                tenantGroupId = 1L,
                scope = PolicyScope.CLINIC_OVERRIDE,
                clinicId = 41L,
            )

            repository.lockScopeHeads(tenantScope, clinicScope).map { it.clinicScopeKey } shouldBeEqualTo listOf(0L, 41L)
            val initial = repository.lockScopeHead(tenantScope)
            initial.revision shouldBeEqualTo 0L
            initial.generation shouldBeEqualTo 0L
            initial.clinicGenerationEpoch shouldBeEqualTo 0L

            val advanced = repository.compareAndIncrementGeneration(tenantScope, expectedRevision = 0L)
            advanced.revision shouldBeEqualTo 1L
            advanced.generation shouldBeEqualTo 1L
            advanced.clinicGenerationEpoch shouldBeEqualTo 0L

            val digestBeforeClinicChange = repository.clinicGenerationDigest(tenantScope.tenantGroupId)
            val clinicAdvanced = repository.compareAndIncrementGeneration(clinicScope, expectedRevision = 0L)
            clinicAdvanced.revision shouldBeEqualTo 1L
            clinicAdvanced.generation shouldBeEqualTo 1L
            repository.lockScopeHead(tenantScope).clinicGenerationEpoch shouldBeEqualTo 1L
            (repository.clinicGenerationDigest(tenantScope.tenantGroupId) == digestBeforeClinicChange) shouldBeEqualTo false

            assertFailsWith<PolicyScopeHeadConflictException> {
                repository.compareAndIncrementGeneration(tenantScope, expectedRevision = 0L)
            }
            assertFailsWith<PolicyScopeHeadConflictException> {
                repository.compareAndIncrementGeneration(clinicScope, expectedRevision = 0L)
            }
            repository.lockScopeHead(tenantScope).clinicGenerationEpoch shouldBeEqualTo 1L
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `tenant clinic digest is derived from one exact scope head lookup`(testDB: TestDB) {
        withTables(
            testDB,
            TenantGroups,
            Clinics,
            SchedulingPolicyScopeHeads,
        ) {
            repeat(200) { offset ->
                val clinicId = offset + 1L
                Clinics.insert {
                    it[id] = EntityID(clinicId, Clinics)
                    it[tenantGroupId] = EntityID(1L, TenantGroups)
                    it[name] = "Clinic $clinicId"
                }
            }
            repository.lockScopeHead(PolicyScopeRef(1L, PolicyScope.TENANT_DEFAULT))
            val capture = SqlStatementCapture()
            registerInterceptor(capture)

            repository.clinicGenerationDigest(1L).length shouldBeEqualTo 64

            val digestReads = capture.statements.filter { it.startsWith("select") }
            digestReads.size shouldBeEqualTo 1
            digestReads.single().contains(SchedulingPolicyScopeHeads.tableName) shouldBeEqualTo true
            digestReads.single().contains(Clinics.tableName) shouldBeEqualTo false
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `authoritative head reads do not bootstrap and active selection uses half open boundaries`(testDB: TestDB) {
        withPolicyTables(testDB) {
            val tenantScope = PolicyScopeRef(tenantGroupId = 1L, scope = PolicyScope.TENANT_DEFAULT)
            val from = Instant.parse("2026-07-27T00:00:00Z")
            val until = from.plusSeconds(3_600)

            repository.findScopeHead(tenantScope).shouldBeNull()
            repository.lockScopeHead(tenantScope)
            repository.findScopeHead(tenantScope).shouldNotBeNull().generation shouldBeEqualTo 0L

            repository.createDefinition(
                definition(
                    lifecycle = PolicyLifecycle.ACTIVE,
                    effectiveFrom = from,
                    effectiveUntil = until,
                )
            )
            repository.createDefinition(
                definition(
                    version = 2L,
                    lifecycle = PolicyLifecycle.SCHEDULED,
                    effectiveFrom = until,
                    effectiveUntil = null,
                )
            )

            repository.findActiveDefinitionAt(
                tenantScope,
                SchedulingPolicyKind.BOOKING_COMMITMENT,
                from,
            ).shouldNotBeNull().version shouldBeEqualTo 1L
            repository.findActiveDefinitionAt(
                tenantScope,
                SchedulingPolicyKind.BOOKING_COMMITMENT,
                until.minusMillis(1),
            ).shouldNotBeNull().version shouldBeEqualTo 1L
            repository.findActiveDefinitionAt(
                tenantScope,
                SchedulingPolicyKind.BOOKING_COMMITMENT,
                until,
            ).shouldBeNull()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `bulk active lookup binds each policy kind to its exact evaluation instant`(testDB: TestDB) {
        withPolicyTables(testDB) {
            val scope = PolicyScopeRef(tenantGroupId = 1L, scope = PolicyScope.TENANT_DEFAULT)
            val decisionAt = Instant.parse("2026-01-01T00:00:00Z")
            val serviceAt = Instant.parse("2027-01-01T00:00:00Z")
            repository.createDefinition(
                definition(
                    kind = SchedulingPolicyKind.BOOKING_COMMITMENT,
                    lifecycle = PolicyLifecycle.ACTIVE,
                    effectiveFrom = decisionAt.minusSeconds(86_400),
                    effectiveUntil = decisionAt.plusSeconds(86_400),
                )
            )
            repository.createDefinition(
                definition(
                    kind = SchedulingPolicyKind.BOOKING_COMMITMENT,
                    version = 2L,
                    lifecycle = PolicyLifecycle.ACTIVE,
                    effectiveFrom = Instant.parse("2026-06-01T00:00:00Z"),
                    effectiveUntil = Instant.parse("2026-07-01T00:00:00Z"),
                )
            )
            repository.createDefinition(
                definition(
                    kind = SchedulingPolicyKind.CAPACITY_AND_OVERBOOKING,
                    lifecycle = PolicyLifecycle.ACTIVE,
                    effectiveFrom = serviceAt.minusSeconds(86_400),
                    effectiveUntil = serviceAt.plusSeconds(86_400),
                )
            )
            val capture = SqlStatementCapture()
            registerInterceptor(capture)

            val active = repository.findActiveDefinitionsAt(
                scope,
                mapOf(
                    SchedulingPolicyKind.BOOKING_COMMITMENT to decisionAt,
                    SchedulingPolicyKind.CAPACITY_AND_OVERBOOKING to serviceAt,
                )
            )

            active.keys shouldBeEqualTo setOf(
                SchedulingPolicyKind.BOOKING_COMMITMENT,
                SchedulingPolicyKind.CAPACITY_AND_OVERBOOKING,
            )
            val sql = capture.statements.last {
                it.startsWith("select") && SchedulingPolicyDefinitions.tableName in it
            }
            Regex("effective_from[^<]{0,16}<=").findAll(sql).count() shouldBeEqualTo 2
            Regex("policy_kind[^=]{0,16}=").findAll(sql).count() shouldBeEqualTo 2
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `draft version allocation and revision-only scope mutation are serialized`(testDB: TestDB) {
        withPolicyTables(testDB) {
            val scope = PolicyScopeRef(tenantGroupId = 1L, scope = PolicyScope.TENANT_DEFAULT)
            repository.nextDefinitionVersion(
                scope,
                SchedulingPolicyKind.BOOKING_COMMITMENT,
            ) shouldBeEqualTo 1L

            repository.createDefinition(definition(version = 1L))
            repository.nextDefinitionVersion(
                scope,
                SchedulingPolicyKind.BOOKING_COMMITMENT,
            ) shouldBeEqualTo 2L

            repository.lockScopeHead(scope)
            val revised = repository.compareAndIncrementRevision(scope, expectedRevision = 0L)
            revised.revision shouldBeEqualTo 1L
            revised.generation shouldBeEqualTo 0L

            assertFailsWith<PolicyScopeHeadConflictException> {
                repository.compareAndIncrementRevision(scope, expectedRevision = 0L)
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `draft revision and lifecycle transitions use compare and set contracts`(testDB: TestDB) {
        withPolicyTables(testDB) {
            val draft = repository.createDefinition(definition())
            val definitionId = draft.id.shouldNotBeNull()

            val revised = repository.compareAndReviseDraft(
                definitionId = definitionId,
                expectedRevision = 1L,
                schemaVersion = 1,
                effectiveFrom = Instant.parse("2026-07-28T00:00:00Z"),
                effectiveUntil = Instant.parse("2026-08-28T00:00:00Z"),
                payloadHash = "c".repeat(64),
                payloadJson = """{"schemaVersion":1,"revision":2}""",
                changeReason = "Revise booking safeguards",
            ).shouldNotBeNull()
            revised.revision shouldBeEqualTo 2L
            revised.lifecycle shouldBeEqualTo PolicyLifecycle.DRAFT
            revised.payloadHash shouldBeEqualTo "c".repeat(64)

            repository.compareAndReviseDraft(
                definitionId = definitionId,
                expectedRevision = 1L,
                schemaVersion = 1,
                effectiveFrom = revised.effectiveFrom,
                effectiveUntil = revised.effectiveUntil,
                payloadHash = revised.payloadHash,
                payloadJson = revised.payloadJson,
                changeReason = revised.changeReason,
            ).shouldBeNull()

            repository.compareAndTransitionLifecycle(
                definitionId = definitionId,
                expectedRevision = 2L,
                expectedLifecycle = PolicyLifecycle.DRAFT,
                targetLifecycle = PolicyLifecycle.SCHEDULED,
            ).shouldNotBeNull().lifecycle shouldBeEqualTo PolicyLifecycle.SCHEDULED

            repository.compareAndTransitionLifecycle(
                definitionId = definitionId,
                expectedRevision = 2L,
                expectedLifecycle = PolicyLifecycle.DRAFT,
                targetLifecycle = PolicyLifecycle.ACTIVE,
            ).shouldBeNull()

            repository.compareAndTransitionLifecycle(
                definitionId = definitionId,
                expectedRevision = 2L,
                expectedLifecycle = PolicyLifecycle.SCHEDULED,
                targetLifecycle = PolicyLifecycle.ACTIVE,
            ).shouldNotBeNull().lifecycle shouldBeEqualTo PolicyLifecycle.ACTIVE

            assertFailsWith<IllegalArgumentException> {
                repository.compareAndTransitionLifecycle(
                    definitionId = definitionId,
                    expectedRevision = 2L,
                    expectedLifecycle = PolicyLifecycle.ACTIVE,
                    targetLifecycle = PolicyLifecycle.DRAFT,
                )
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `published interval lookup and immutable snapshot identity are reproducible`(testDB: TestDB) {
        withPolicyTables(testDB) {
            val now = Instant.parse("2026-07-27T00:00:00Z")
            repository.createDefinition(
                definition(
                    lifecycle = PolicyLifecycle.ACTIVE,
                    effectiveFrom = now,
                    effectiveUntil = now.plusSeconds(3_600),
                )
            )
            repository.createDefinition(
                definition(
                    version = 2L,
                    lifecycle = PolicyLifecycle.SCHEDULED,
                    effectiveFrom = now.plusSeconds(7_200),
                    effectiveUntil = null,
                )
            )

            repository.findOverlappingPublishedDefinitions(
                PolicyScopeRef(1L, PolicyScope.TENANT_DEFAULT),
                SchedulingPolicyKind.BOOKING_COMMITMENT,
                now.plusSeconds(1_800),
                now.plusSeconds(5_400),
            ).map { it.version } shouldBeEqualTo listOf(1L)

            val first = repository.saveSnapshot(
                tenantGroupId = 1L,
                clinicId = 41L,
                decisionAt = now,
                serviceAt = now.plusSeconds(86_400),
                tenantGeneration = 3L,
                clinicGeneration = 2L,
                sourceVersionsJson = """{"BOOKING_COMMITMENT":{"tenantVersion":1,"clinicVersion":null}}""",
                sourceByPathJson = """{"booking.adminBookingMode":"TENANT"}""",
                disabledFeaturesJson = "[]",
                warningsJson = "[]",
                payloadJson = """{"bookingCommitment":{}}""",
                snapshotHash = "a".repeat(64),
            )
            val reused = repository.saveSnapshot(
                tenantGroupId = 1L,
                clinicId = 41L,
                decisionAt = now.plusSeconds(60),
                serviceAt = now.plusSeconds(86_460),
                tenantGeneration = 4L,
                clinicGeneration = 2L,
                sourceVersionsJson = "{}",
                sourceByPathJson = "{}",
                disabledFeaturesJson = "[]",
                warningsJson = "[]",
                payloadJson = "{}",
                snapshotHash = "a".repeat(64),
            )

            reused.id shouldBeEqualTo first.id
            reused.payloadJson shouldBeEqualTo first.payloadJson
            repository.findSnapshot(1L, 41L, "a".repeat(64)).shouldNotBeNull()
            repository.findSnapshot(1L, 41L, first.id).shouldNotBeNull()
            repository.findSnapshot(2L, 41L, first.id).shouldBeNull()
            repository.findSnapshot(1L, 42L, first.id).shouldBeNull()

            assertFailsWith<ExposedSQLException> {
                EffectiveSchedulingPolicySnapshots.insert {
                    it[tenantGroupId] = 1L
                    it[clinicId] = 41L
                    it[decisionAt] = now
                    it[serviceAt] = now
                    it[tenantGeneration] = 1L
                    it[clinicGeneration] = 0L
                    it[sourceVersionsJson] = "{}"
                    it[sourceByPathJson] = "{}"
                    it[disabledFeaturesJson] = "[]"
                    it[warningsJson] = "[]"
                    it[payloadJson] = "{}"
                    it[snapshotHash] = "a".repeat(64)
                }
            }
        }
    }

    private fun withPolicyTables(
        testDB: TestDB,
        statement: org.jetbrains.exposed.v1.jdbc.JdbcTransaction.() -> Unit,
    ) = withTables(
        testDB,
        SchedulingPolicyDefinitions,
        SchedulingPolicyApprovals,
        SchedulingPolicyScopeHeads,
        EffectiveSchedulingPolicySnapshots,
    ) { statement() }

    private fun definition(
        scope: PolicyScope = PolicyScope.TENANT_DEFAULT,
        clinicId: Long? = null,
        kind: SchedulingPolicyKind = SchedulingPolicyKind.BOOKING_COMMITMENT,
        version: Long = 1L,
        lifecycle: PolicyLifecycle = PolicyLifecycle.DRAFT,
        effectiveFrom: Instant = Instant.parse("2026-07-27T00:00:00Z"),
        effectiveUntil: Instant? = null,
    ) = SchedulingPolicyDefinitionRecord(
        tenantGroupId = 1L,
        scope = scope,
        clinicId = clinicId,
        kind = kind,
        version = version,
        schemaVersion = 1,
        lifecycle = lifecycle,
        effectiveFrom = effectiveFrom,
        effectiveUntil = effectiveUntil,
        revision = 1L,
        payloadHash = "b".repeat(64),
        payloadJson = """{"schemaVersion":1}""",
        createdByActorId = "admin-1",
        createdByActorRole = ActorRole.ADMIN,
        changeReason = "Initial booking policy",
    )

    private fun approval(
        definitionId: Long,
        revision: Long,
    ) = SchedulingPolicyApprovalRecord(
        definitionId = definitionId,
        draftRevision = revision,
        actorId = "approver-1",
        actorRole = ActorRole.ADMIN,
        assuranceLevel = "MFA",
        approvedAt = Instant.parse("2026-07-27T01:00:00Z"),
    )

    /**
     * repository가 생성한 SQL만 수집하여 정책 종류별 정확한 시각 predicate가 DB 경계에
     * 유지되는지 검증한다. bind 값이나 SQL은 테스트 assertion에만 사용하고 로그로 남기지 않는다.
     */
    private class SqlStatementCapture : StatementInterceptor {
        val statements = mutableListOf<String>()

        override fun afterExecution(
            transaction: Transaction,
            contexts: List<StatementContext>,
            executedStatement: PreparedStatementApi,
        ) {
            contexts.firstOrNull()?.let { context ->
                statements += context.sql(transaction).lowercase()
            }
        }
    }
}
