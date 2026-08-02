package io.bluetape4k.clinic.appointment.api.policy

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.model.dto.PolicyPreviewProgress
import io.bluetape4k.clinic.appointment.model.dto.PolicyScopeRef
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyDefinitionRecord
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyPreviewJobRecord
import io.bluetape4k.clinic.appointment.model.policy.ActorRole
import io.bluetape4k.clinic.appointment.model.policy.PolicyGenerationVector
import io.bluetape4k.clinic.appointment.model.policy.PolicyLifecycle
import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import io.bluetape4k.clinic.appointment.model.policy.SchedulingPolicyKind
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyDefinitions
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyPreviewJobs
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyScopeHeads
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.repository.SchedulingPolicyImpactRepository
import io.bluetape4k.clinic.appointment.repository.SchedulingPolicyJobRepository
import io.bluetape4k.clinic.appointment.repository.SchedulingPolicyRepository
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 실제 Exposed 트랜잭션에서 preview queue admission의 직렬화 경계를 검증한다.
 *
 * 두 호출을 같은 병원 scope와 capacity `1`로 동시에 시작한다. 일반 concurrency helper는
 * 두 트랜잭션이 admission row lock 앞에서 정확히 경쟁하는 시점을 보장하지 않으므로, 이 테스트는
 * 의도적으로 시작 latch를 사용한다. 결과는 정확히 한 durable job만 수락되고 다른 호출은
 * `null`이어야 하며, check와 insert가 분리된 구현이면 두 호출이 모두 수락되어 실패한다.
 */
class ExposedSchedulingPolicyPreviewStoreTest {

    @Test
    fun `tenant baseline admission persists explicit scope without a clinic sentinel identity`() {
        Database.connect(
            "jdbc:h2:mem:tenant_policy_preview_admission_${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver",
        )
        transaction {
            SchemaUtils.create(
                TenantGroups,
                Clinics,
                SchedulingPolicyScopeHeads,
                SchedulingPolicyPreviewJobs,
            )
            insertTenantWithClinic(tenantGroupId = 1L, clinicId = 41L)
        }
        val store = ExposedSchedulingPolicyPreviewStore(
            SchedulingPolicyJobRepository("tenant-preview-store-secret-32".toByteArray()),
            SchedulingPolicyImpactRepository(),
            SchedulingPolicyRepository(),
        )
        val command = CreateSchedulingPolicyPreviewCommand(
            scope = PolicyScopeRef(1L, PolicyScope.TENANT_DEFAULT),
            definitionId = 7L,
            draftRevision = 3L,
            generation = PolicyGenerationVector(2L, 0L),
            horizonFrom = Instant.parse("2026-07-27T00:00:00Z"),
            horizonUntil = Instant.parse("2026-08-27T00:00:00Z"),
            requestedAt = Instant.parse("2026-07-27T00:00:00Z"),
        )

        val admission = store.tryCreate(command, capacity = 1, jobDeadline = Duration.ofMinutes(5))

        requireNotNull(admission).job.let { job ->
            job.scope shouldBeEqualTo PolicyScope.TENANT_DEFAULT
            job.clinicId.shouldBeNull()
            job.clinicScopeKey shouldBeEqualTo 0L
            job.clinicGeneration shouldBeEqualTo 0L
            requireNotNull(job.clinicGenerationDigest).length shouldBeEqualTo 64
        }
    }

    @Test
    fun `tenant preview becomes stale when any clinic override generation changes`() {
        val fixture = completedTenantPreviewFixture("generation_change")

        fixture.store.isPinnedStateCurrent(fixture.job).shouldBeTrue()
        fixture.verifier
            .verify(fixture.evidence, fixture.definition, fixture.command.generation)
            .shouldBeTrue()

        transaction {
            fixture.policyRepository.compareAndIncrementGeneration(
                fixture.clinicScope,
                expectedRevision = 0L,
            )
        }

        fixture.store.isPinnedStateCurrent(fixture.job).shouldBeFalse()
        fixture.verifier
            .verify(fixture.evidence, fixture.definition, fixture.command.generation)
            .shouldBeFalse()
    }

    @Test
    fun `tenant preview remains current when clinic inventory changes without a policy generation change`() {
        val fixture = completedTenantPreviewFixture("clinic_added")

        fixture.store.isPinnedStateCurrent(fixture.job).shouldBeTrue()
        fixture.verifier
            .verify(fixture.evidence, fixture.definition, fixture.command.generation)
            .shouldBeTrue()

        transaction {
            Clinics.insert {
                it[id] = EntityID(42L, Clinics)
                it[tenantGroupId] = EntityID(1L, TenantGroups)
                it[name] = "Clinic 42"
                it[timezone] = "Asia/Seoul"
            }
        }

        fixture.store.isPinnedStateCurrent(fixture.job).shouldBeTrue()
        fixture.verifier
            .verify(fixture.evidence, fixture.definition, fixture.command.generation)
            .shouldBeTrue()
    }

    @Test
    fun `same clinic capacity check and creation are one serialized admission`() {
        Database.connect(
            "jdbc:h2:mem:policy_preview_admission_${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver",
        )
        transaction {
            SchemaUtils.create(SchedulingPolicyScopeHeads, SchedulingPolicyPreviewJobs)
        }
        val store = ExposedSchedulingPolicyPreviewStore(
            SchedulingPolicyJobRepository("preview-store-test-secret-32-bytes".toByteArray()),
            SchedulingPolicyImpactRepository(),
            SchedulingPolicyRepository(),
        )
        val command = CreateSchedulingPolicyPreviewCommand(
            scope = PolicyScopeRef(1L, PolicyScope.CLINIC_OVERRIDE, 41L),
            definitionId = 7L,
            draftRevision = 3L,
            generation = PolicyGenerationVector(2L, 1L),
            horizonFrom = Instant.parse("2026-07-27T00:00:00Z"),
            horizonUntil = Instant.parse("2026-08-27T00:00:00Z"),
            requestedAt = Instant.parse("2020-01-01T00:00:00Z"),
        )
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val results = (1..2).map {
                executor.submit<SchedulingPolicyPreviewAdmission?> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS)) { "preview admission start timed out" }
                    store.tryCreate(command, capacity = 1, jobDeadline = Duration.ofMinutes(5))
                }
            }
            check(ready.await(5, TimeUnit.SECONDS)) { "preview admission workers did not become ready" }
            start.countDown()

            val admissions = results.map { it.get(5, TimeUnit.SECONDS) }

            admissions.count { it != null } shouldBeEqualTo 1
            admissions.filterNotNull().single().let { admission ->
                admission.job.nextAttemptAt shouldBeEqualTo admission.acceptedAt
                admission.job.deadlineAt shouldBeEqualTo admission.acceptedAt.plus(Duration.ofMinutes(5))
            }
            admissions.single { it == null }.shouldBeNull()
            transaction {
                SchedulingPolicyPreviewJobs.selectAll().count()
            } shouldBeEqualTo 1L
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    private fun completedTenantPreviewFixture(suffix: String): CompletedTenantPreviewFixture {
        Database.connect(
            "jdbc:h2:mem:tenant_policy_preview_${suffix}_${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver",
        )
        transaction {
            SchemaUtils.create(
                TenantGroups,
                Clinics,
                SchedulingPolicyDefinitions,
                SchedulingPolicyScopeHeads,
                SchedulingPolicyPreviewJobs,
            )
            insertTenantWithClinic(tenantGroupId = 1L, clinicId = 41L)
        }
        val policyRepository = SchedulingPolicyRepository()
        val jobRepository = SchedulingPolicyJobRepository(
            "tenant-preview-stale-secret-32".toByteArray()
        )
        val store = ExposedSchedulingPolicyPreviewStore(
            jobRepository,
            SchedulingPolicyImpactRepository(),
            policyRepository,
        )
        val tenantScope = PolicyScopeRef(1L, PolicyScope.TENANT_DEFAULT)
        val clinicScope = PolicyScopeRef(1L, PolicyScope.CLINIC_OVERRIDE, 41L)
        val now = Instant.parse("2026-07-27T00:00:00Z")
        val definition = transaction {
            policyRepository.lockScopeHead(clinicScope)
            policyRepository.createDefinition(
                SchedulingPolicyDefinitionRecord(
                    tenantGroupId = 1L,
                    scope = PolicyScope.TENANT_DEFAULT,
                    kind = SchedulingPolicyKind.BOOKING_COMMITMENT,
                    version = 1L,
                    schemaVersion = 1,
                    lifecycle = PolicyLifecycle.DRAFT,
                    effectiveFrom = now,
                    effectiveUntil = null,
                    revision = 3L,
                    payloadHash = "a".repeat(64),
                    payloadJson = "{}",
                    createdByActorId = "admin-1",
                    createdByActorRole = ActorRole.ADMIN,
                    changeReason = "Verify tenant preview generation binding",
                )
            )
        }
        val command = CreateSchedulingPolicyPreviewCommand(
            scope = tenantScope,
            definitionId = requireNotNull(definition.id),
            draftRevision = definition.revision,
            generation = PolicyGenerationVector(0L, 0L),
            horizonFrom = now,
            horizonUntil = now.plus(Duration.ofDays(30)),
            requestedAt = now,
        )
        val job = requireNotNull(
            store.tryCreate(command, capacity = 1, jobDeadline = Duration.ofMinutes(5))
        ).job
        val jobId = requireNotNull(job.id)
        val databaseNow = store.databaseNow()
        val evidenceToken = "tenant-preview-evidence-$suffix"
        transaction {
            jobRepository.claimDuePreview(
                jobId,
                owner = "preview-worker",
                now = databaseNow,
                leaseUntil = databaseNow.plusSeconds(30),
            ).shouldBeTrue()
            jobRepository.completePreview(
                jobId = jobId,
                owner = "preview-worker",
                resultHash = "b".repeat(64),
                activationEvidenceToken = evidenceToken,
                progress = PolicyPreviewProgress(0L, 0L),
                completedAt = databaseNow,
            ).shouldBeTrue()
        }
        val evidence = PolicyPreviewEvidence(
            definitionId = job.definitionId,
            draftRevision = job.draftRevision,
            tenantGeneration = job.tenantGeneration,
            clinicGeneration = job.clinicGeneration,
            evidenceId = evidenceToken,
        )
        return CompletedTenantPreviewFixture(
            policyRepository = policyRepository,
            store = store,
            definition = definition,
            command = command,
            job = job,
            evidence = evidence,
            verifier = PersistedPolicyPreviewEvidenceVerifier(jobRepository, policyRepository),
            clinicScope = clinicScope,
        )
    }

    private fun insertTenantWithClinic(
        tenantGroupId: Long,
        clinicId: Long,
    ) {
        TenantGroups.insert {
            it[id] = EntityID(tenantGroupId, TenantGroups)
            it[tenantCode] = "tenant-$tenantGroupId"
            it[displayName] = "Tenant $tenantGroupId"
        }
        Clinics.insert {
            it[id] = EntityID(clinicId, Clinics)
            it[Clinics.tenantGroupId] = EntityID(tenantGroupId, TenantGroups)
            it[name] = "Clinic $clinicId"
            it[timezone] = "Asia/Seoul"
        }
    }

    private data class CompletedTenantPreviewFixture(
        val policyRepository: SchedulingPolicyRepository,
        val store: ExposedSchedulingPolicyPreviewStore,
        val definition: SchedulingPolicyDefinitionRecord,
        val command: CreateSchedulingPolicyPreviewCommand,
        val job: SchedulingPolicyPreviewJobRecord,
        val evidence: PolicyPreviewEvidence,
        val verifier: PersistedPolicyPreviewEvidenceVerifier,
        val clinicScope: PolicyScopeRef,
    )
}
