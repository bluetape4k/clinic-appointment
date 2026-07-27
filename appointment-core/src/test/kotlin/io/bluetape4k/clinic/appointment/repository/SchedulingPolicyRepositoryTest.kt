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
import io.bluetape4k.clinic.appointment.model.tables.EffectiveSchedulingPolicySnapshots
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyApprovals
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyDefinitions
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyScopeHeads
import io.bluetape4k.clinic.appointment.test.AbstractExposedTest
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withTables
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Instant

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

            val advanced = repository.compareAndIncrementGeneration(tenantScope, expectedRevision = 0L)
            advanced.revision shouldBeEqualTo 1L
            advanced.generation shouldBeEqualTo 1L

            assertFailsWith<PolicyScopeHeadConflictException> {
                repository.compareAndIncrementGeneration(tenantScope, expectedRevision = 0L)
            }
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
        version: Long = 1L,
        lifecycle: PolicyLifecycle = PolicyLifecycle.DRAFT,
        effectiveFrom: Instant = Instant.parse("2026-07-27T00:00:00Z"),
        effectiveUntil: Instant? = null,
    ) = SchedulingPolicyDefinitionRecord(
        tenantGroupId = 1L,
        scope = scope,
        clinicId = clinicId,
        kind = SchedulingPolicyKind.BOOKING_COMMITMENT,
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
}
