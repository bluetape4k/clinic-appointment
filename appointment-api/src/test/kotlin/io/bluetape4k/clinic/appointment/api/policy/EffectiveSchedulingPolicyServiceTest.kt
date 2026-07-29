package io.bluetape4k.clinic.appointment.api.policy

import io.bluetape4k.clinic.appointment.api.service.EffectiveAppointmentCommitmentPolicySnapshotResolver
import io.bluetape4k.clinic.appointment.model.policy.*
import io.bluetape4k.clinic.appointment.model.dto.PolicyScopeRef
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyDefinitionRecord
import io.bluetape4k.clinic.appointment.model.tables.EffectiveSchedulingPolicySnapshots
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyDefinitions
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyScopeHeads
import io.bluetape4k.clinic.appointment.repository.SchedulingPolicyRepository
import io.bluetape4k.clinic.appointment.service.EffectivePolicyCache
import io.bluetape4k.clinic.appointment.service.EffectivePolicyCacheKey
import io.bluetape4k.clinic.appointment.service.EffectivePolicyCacheLimits
import io.bluetape4k.clinic.appointment.service.SchedulingPolicyCompiler
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque

/**
 * 유효 정책 조회가 권위 세대를 먼저 확인하고, 혼합 세대를 폐기하며, 커밋 뒤에만 캐시하는지
 * 검증한다. 캐시 hit가 있어도 데이터베이스 세대 조회 실패를 stale 값으로 우회하지 않는
 * fail-closed 규칙과 제한 재시도 오류 코드도 함께 고정한다.
 */
class EffectiveSchedulingPolicyServiceTest {
    private val decisionAt = Instant.parse("2026-11-01T05:30:00Z")
    private val serviceAt = Instant.parse("2026-11-01T06:30:00Z")

    @Test
    fun `reads authoritative generation before returning an exact cache hit`() {
        val generation = PolicyGenerationVector(7L, 3L)
        val cached = compile(tenantGroupId = 1L, clinicId = 11L, generation = generation)
        val cache = EffectivePolicyCache(EffectivePolicyCacheLimits())
        cache.put(key(cached), cached, estimatedBytes = 1_000)
        val store = FakeEffectivePolicyStore(generations = listOf(generation))
        val service = EffectiveSchedulingPolicyService(store, cache)

        val result = service.getEffective(1L, 11L, decisionAt, serviceAt)

        result shouldBeSameInstanceAs cached
        store.generationReads shouldBeEqualTo 1
        store.definitionReads shouldBeEqualTo 0
        store.saved.size shouldBeEqualTo 0
    }

    @Test
    fun `database generation failure never falls back to a stale cached snapshot`() {
        val generation = PolicyGenerationVector(7L, 3L)
        val cached = compile(tenantGroupId = 1L, clinicId = 11L, generation = generation)
        val cache = EffectivePolicyCache(EffectivePolicyCacheLimits())
        cache.put(key(cached), cached, estimatedBytes = 1_000)
        val store = FakeEffectivePolicyStore(
            generations = emptyList(),
            generationFailure = IllegalStateException("database unavailable"),
        )
        val service = EffectiveSchedulingPolicyService(store, cache)

        val failure = assertFailsWith<EffectivePolicyReadUnavailableException> {
            service.getEffective(1L, 11L, decisionAt, serviceAt)
        }

        failure.code shouldBeEqualTo EffectivePolicyReadUnavailableException.STABLE_CODE
        cache.statistics().hitCount shouldBeEqualTo 0L
        cache.statistics().missCount shouldBeEqualTo 0L
    }

    @Test
    fun `discards a mixed generation and succeeds on a bounded retry`() {
        val first = PolicyGenerationVector(7L, 3L)
        val second = PolicyGenerationVector(8L, 3L)
        val store = FakeEffectivePolicyStore(
            generations = listOf(first, second, second, second),
            input = fullInput(),
        )
        val service = EffectiveSchedulingPolicyService(
            store = store,
            cache = EffectivePolicyCache(EffectivePolicyCacheLimits()),
        )

        val result = service.getEffective(1L, 11L, decisionAt, serviceAt)

        result.generation shouldBeEqualTo second
        store.generationReads shouldBeEqualTo 4
        store.definitionReads shouldBeEqualTo 2
        store.saved.size shouldBeEqualTo 1
        store.saved.single().expectedGeneration shouldBeEqualTo second
    }

    @Test
    fun `three mixed-generation attempts return a stable conflict and persist nothing`() {
        val store = FakeEffectivePolicyStore(
            generations = listOf(
                PolicyGenerationVector(1L, 0L),
                PolicyGenerationVector(2L, 0L),
                PolicyGenerationVector(2L, 0L),
                PolicyGenerationVector(3L, 0L),
                PolicyGenerationVector(3L, 0L),
                PolicyGenerationVector(4L, 0L),
            ),
            input = fullInput(),
        )
        val service = EffectiveSchedulingPolicyService(
            store = store,
            cache = EffectivePolicyCache(EffectivePolicyCacheLimits()),
        )

        val failure = assertFailsWith<EffectivePolicyGenerationConflictException> {
            service.getEffective(1L, 11L, decisionAt, serviceAt)
        }

        failure.code shouldBeEqualTo EffectivePolicyGenerationConflictException.STABLE_CODE
        failure.attempts shouldBeEqualTo 3
        store.saved.isEmpty() shouldBeEqualTo true
    }

    @Test
    fun `snapshot save conflict is retried and cache is populated only after committed save`() {
        val generation = PolicyGenerationVector(9L, 2L)
        val store = FakeEffectivePolicyStore(
            generations = listOf(generation, generation, generation, generation),
            input = fullInput(),
            saveConflictsRemaining = 1,
        )
        val cache = EffectivePolicyCache(EffectivePolicyCacheLimits())
        val service = EffectiveSchedulingPolicyService(store, cache)

        val result = service.getEffective(1L, 11L, decisionAt, serviceAt)

        store.saveAttempts shouldBeEqualTo 2
        store.saved.size shouldBeEqualTo 1
        cache.get(key(result)) shouldBeSameInstanceAs result
    }

    @Test
    fun `identical logical input is isolated by tenant and clinic scope`() {
        val generation = PolicyGenerationVector(5L, 0L)
        val firstStore = FakeEffectivePolicyStore(
            generations = listOf(generation, generation),
            input = fullInput(),
        )
        val secondStore = FakeEffectivePolicyStore(
            generations = listOf(generation, generation),
            input = fullInput(),
        )
        val cache = EffectivePolicyCache(EffectivePolicyCacheLimits())
        val first = EffectiveSchedulingPolicyService(firstStore, cache)
            .getEffective(tenantGroupId = 1L, clinicId = 11L, decisionAt, serviceAt)
        val second = EffectiveSchedulingPolicyService(secondStore, cache)
            .getEffective(tenantGroupId = 1L, clinicId = 12L, decisionAt, serviceAt)

        (first.snapshotHash != second.snapshotHash) shouldBeEqualTo true
        cache.statistics().entryCount shouldBeEqualTo 2
        firstStore.saved.single().snapshot.clinicId shouldBeEqualTo 11L
        secondStore.saved.single().snapshot.clinicId shouldBeEqualTo 12L
    }

    @Test
    fun `cache estimator failure does not overturn a committed authoritative read`() {
        val generation = PolicyGenerationVector(5L, 0L)
        val store = FakeEffectivePolicyStore(
            generations = listOf(generation, generation),
            input = fullInput(),
        )
        val cache = EffectivePolicyCache(EffectivePolicyCacheLimits())
        val service = EffectiveSchedulingPolicyService(
            store = store,
            cache = cache,
            sizeEstimator = EffectivePolicySnapshotSizeEstimator { 0L },
        )

        val result = service.getEffective(1L, 11L, decisionAt, serviceAt)

        store.saved.single().snapshot shouldBeSameInstanceAs result
        cache.statistics().entryCount shouldBeEqualTo 0
    }

    private fun compile(
        tenantGroupId: Long,
        clinicId: Long,
        generation: PolicyGenerationVector,
    ) = SchedulingPolicyCompiler.compile(
        tenantGroupId = tenantGroupId,
        clinicId = clinicId,
        decisionAt = decisionAt,
        serviceAt = serviceAt,
        generation = generation,
        sourceVersions = SchedulingPolicyKind.entries.associateWith { SourceVersion(1L, null) },
        tenant = fullTenantPolicy(),
    )

    private fun key(value: EffectiveSchedulingPolicy) =
        EffectivePolicyCacheKey(
            tenantGroupId = value.tenantGroupId,
            clinicId = value.clinicId,
            generation = value.generation,
            decisionAt = value.decisionAt,
            serviceAt = value.serviceAt,
        )

    private fun fullInput() = EffectivePolicyCompilationInput(
        sourceVersions = SchedulingPolicyKind.entries.associateWith { SourceVersion(1L, null) },
        tenant = fullTenantPolicy(),
        clinic = ClinicSchedulingPolicyOverrides(),
    )

    private fun fullTenantPolicy() = CompiledSchedulingPolicy(
        bookingCommitment = BookingCommitmentPolicy(
            adminBookingMode = AdminBookingMode.DIRECT_CONFIRM_WITH_CONSENT_EVIDENCE,
            patientBookingMode = PatientBookingMode.PROVISIONAL_APPROVAL_REQUIRED,
            provisionalCapacityMode = ProvisionalCapacityMode.NO_HOLD,
            provisionalRequestTtl = Duration.ofHours(24),
            resourceHoldTtl = null,
            approvalRoles = setOf(ActorRole.ADMIN, ActorRole.STAFF),
            adminConsentEvidence = ConsentEvidenceRequirement(
                allowedEvidenceTypes = setOf("SIGNED_FORM"),
                maximumAge = Duration.ofHours(24),
                termsHashRequired = true,
            ),
            confirmedChangeMode = ConfirmedChangeMode.NEW_PROPOSAL_AND_CUSTOMER_CONSENT,
        ),
        holdAndConsent = HoldAndConsentPolicy(true, 86_400L),
        capacityAndOverbooking = CapacityAndOverbookingPolicy(10, 2, 12, true),
        priorityAndReliability = PriorityAndReliabilityPolicy(
            priorityWeights = mapOf("RETURN_VISIT" to 2),
            noShowPenalty = 5,
            sameDayCancellationPenalty = 2,
            minimumPriorityScore = 0,
        ),
        reconfirmation = ReconfirmationPolicy(true, 86_400L, 3),
        disruptionRecovery = DisruptionRecoveryPolicy(true, 3_600L, true),
        operatingExtension = OperatingExtensionPolicy(true, 60, 120),
        notificationAndSla = NotificationAndSlaPolicy(setOf("SMS"), 900L, 3_600L),
    )

    private class FakeEffectivePolicyStore(
        generations: List<PolicyGenerationVector>,
        private val input: EffectivePolicyCompilationInput = errorInput(),
        private val generationFailure: RuntimeException? = null,
        var saveConflictsRemaining: Int = 0,
    ) : EffectivePolicyStore {
        private val generationQueue = ArrayDeque(generations)
        var generationReads = 0
        var definitionReads = 0
        var saveAttempts = 0
        val saved = mutableListOf<SavedEffectivePolicySnapshot>()

        override fun readGeneration(
            tenantGroupId: Long,
            clinicId: Long,
        ): PolicyGenerationVector {
            generationReads++
            generationFailure?.let { throw it }
            return generationQueue.removeFirst()
        }

        override fun loadCompilationInput(
            tenantGroupId: Long,
            clinicId: Long,
            decisionAt: Instant,
            serviceAt: Instant,
        ): EffectivePolicyCompilationInput {
            definitionReads++
            return input
        }

        override fun saveIfGenerationMatches(
            expectedGeneration: PolicyGenerationVector,
            snapshot: EffectiveSchedulingPolicy,
        ): EffectiveSchedulingPolicy {
            saveAttempts++
            if (saveConflictsRemaining-- > 0) {
                throw EffectivePolicyGenerationChangedException(expectedGeneration)
            }
            saved += SavedEffectivePolicySnapshot(expectedGeneration, snapshot)
            return snapshot
        }

        private companion object {
            fun errorInput(): EffectivePolicyCompilationInput =
                EffectivePolicyCompilationInput(
                    emptyMap(),
                    CompiledSchedulingPolicy(),
                    ClinicSchedulingPolicyOverrides(),
                )
        }
    }

    private data class SavedEffectivePolicySnapshot(
        val expectedGeneration: PolicyGenerationVector,
        val snapshot: EffectiveSchedulingPolicy,
    )
}

/**
 * Exposed 저장소 adapter가 모든 정책 kind를 한 트랜잭션에서 읽고, 스코프 세대를
 * compare-and-set으로 재검사한 뒤 canonical 불변 스냅샷 하나를 재사용하는지 검증한다.
 *
 * 테스트는 repository 규칙에 따라 H2 schema를 매번 생성·정리하며, production adapter가
 * 호출자에게 transaction 경계를 누출하지 않는지도 함께 확인한다.
 */
class ExposedEffectivePolicyStoreTest {
    private val decisionAt = Instant.parse("2026-11-01T05:30:00Z")
    private val serviceAt = Instant.parse("2026-11-01T06:30:00Z")
    private val repository = SchedulingPolicyRepository()
    private val store = ExposedEffectivePolicyStore(repository)
    private val tenantScope = PolicyScopeRef(1L, PolicyScope.TENANT_DEFAULT)
    private lateinit var database: Database

    @BeforeEach
    fun setup() {
        database = Database.connect(
            "jdbc:h2:mem:effective_policy_${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver",
        )
        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                SchedulingPolicyDefinitions,
                SchedulingPolicyScopeHeads,
                EffectiveSchedulingPolicySnapshots,
            )
            EffectiveSchedulingPolicySnapshots.deleteAll()
            SchedulingPolicyDefinitions.deleteAll()
            SchedulingPolicyScopeHeads.deleteAll()
            SchedulingPolicyKind.entries.forEachIndexed { index, kind ->
                repository.createDefinition(
                    SchedulingPolicyDefinitionRecord(
                        tenantGroupId = 1L,
                        scope = PolicyScope.TENANT_DEFAULT,
                        kind = kind,
                        version = 1L,
                        schemaVersion = 1,
                        lifecycle = PolicyLifecycle.ACTIVE,
                        effectiveFrom = decisionAt.minusSeconds(86_400),
                        effectiveUntil = null,
                        revision = 1L,
                        payloadHash = (index + 1).toString(16).repeat(64),
                        payloadJson = TENANT_PAYLOADS.getValue(kind),
                        createdByActorId = "policy-admin",
                        createdByActorRole = ActorRole.ADMIN,
                        changeReason = "Effective policy fixture",
                    )
                )
            }
            repository.lockScopeHead(tenantScope)
            repository.compareAndIncrementGeneration(tenantScope, expectedRevision = 0L)
        }
    }

    @Test
    fun `loads all declared evaluation kinds and persists one scoped immutable snapshot`() {
        val service = EffectiveSchedulingPolicyService(
            store = store,
            cache = EffectivePolicyCache(EffectivePolicyCacheLimits()),
        )

        val snapshot = service.getEffective(1L, 41L, decisionAt, serviceAt)

        snapshot.generation shouldBeEqualTo PolicyGenerationVector(1L, 0L)
        snapshot.sourceVersions.keys shouldBeEqualTo SchedulingPolicyKind.entries.toSet()
        snapshot.payload.bookingCommitment.shouldNotBeNull()
        snapshot.payload.capacityAndOverbooking.shouldNotBeNull()
        val persisted = transaction {
            repository.findSnapshot(1L, 41L, snapshot.snapshotHash)
        }
        persisted.shouldNotBeNull()
        persisted.snapshotHash shouldBeEqualTo snapshot.snapshotHash
    }

    @Test
    fun `commitment resolver restores the complete persisted policy without current policy recalculation`() {
        val service = EffectiveSchedulingPolicyService(
            store = store,
            cache = EffectivePolicyCache(EffectivePolicyCacheLimits()),
        )
        val snapshot = service.getEffective(1L, 41L, decisionAt, serviceAt)
        val snapshotId = transaction {
            repository.findSnapshot(1L, 41L, snapshot.snapshotHash)
        }.shouldNotBeNull().id
        val resolver =
            EffectiveAppointmentCommitmentPolicySnapshotResolver(
                database = database,
                effectiveSchedulingPolicyService = service,
                schedulingPolicyRepository = repository,
            )

        val restored = resolver.resolvePersisted(1L, 41L, snapshotId)

        restored.id shouldBeEqualTo snapshotId
        restored.snapshotHash shouldBeEqualTo snapshot.snapshotHash
        restored.sourceVersions shouldBeEqualTo snapshot.sourceVersions
        restored.payload shouldBeEqualTo snapshot.payload
    }

    @Test
    fun `snapshot transaction rejects an expected vector after tenant generation advances`() {
        val generation = store.readGeneration(1L, 41L)
        val input = store.loadCompilationInput(1L, 41L, decisionAt, serviceAt)
        val snapshot = SchedulingPolicyCompiler.compile(
            tenantGroupId = 1L,
            clinicId = 41L,
            decisionAt = decisionAt,
            serviceAt = serviceAt,
            generation = generation,
            sourceVersions = input.sourceVersions,
            tenant = input.tenant,
            clinic = input.clinic,
        )
        transaction {
            repository.compareAndIncrementGeneration(tenantScope, expectedRevision = 1L)
        }

        assertFailsWith<EffectivePolicyGenerationChangedException> {
            store.saveIfGenerationMatches(generation, snapshot)
        }
        val persisted = transaction {
            repository.findSnapshot(1L, 41L, snapshot.snapshotHash)
        }
        persisted.shouldBeNull()
    }

    @Test
    fun `snapshot transaction rejects a snapshot compiled for a different expected vector`() {
        val input = store.loadCompilationInput(1L, 41L, decisionAt, serviceAt)
        val snapshot = SchedulingPolicyCompiler.compile(
            tenantGroupId = 1L,
            clinicId = 41L,
            decisionAt = decisionAt,
            serviceAt = serviceAt,
            generation = PolicyGenerationVector(2L, 0L),
            sourceVersions = input.sourceVersions,
            tenant = input.tenant,
            clinic = input.clinic,
        )

        assertFailsWith<IllegalArgumentException> {
            store.saveIfGenerationMatches(PolicyGenerationVector(1L, 0L), snapshot)
        }
    }

    @Test
    fun `snapshot reuse rejects canonical bytes that disagree with an existing hash`() {
        val generation = store.readGeneration(1L, 41L)
        val input = store.loadCompilationInput(1L, 41L, decisionAt, serviceAt)
        val original = SchedulingPolicyCompiler.compile(
            tenantGroupId = 1L,
            clinicId = 41L,
            decisionAt = decisionAt,
            serviceAt = serviceAt,
            generation = generation,
            sourceVersions = input.sourceVersions,
            tenant = input.tenant,
            clinic = input.clinic,
        )
        store.saveIfGenerationMatches(generation, original)
        val inconsistent = original.copy(warnings = listOf("HASH_COLLISION_FIXTURE"))

        assertFailsWith<IllegalStateException> {
            store.saveIfGenerationMatches(generation, inconsistent)
        }
    }

    private companion object {
        val TENANT_PAYLOADS = mapOf(
            SchedulingPolicyKind.BOOKING_COMMITMENT to
                """
                {
                  "adminBookingMode": "DIRECT_CONFIRM_WITH_CONSENT_EVIDENCE",
                  "patientBookingMode": "PROVISIONAL_APPROVAL_REQUIRED",
                  "provisionalCapacityMode": "NO_HOLD",
                  "provisionalRequestTtlSeconds": 86400,
                  "resourceHoldTtlSeconds": null,
                  "approvalRoles": ["ADMIN", "STAFF"],
                  "adminConsentEvidence": {
                    "allowedEvidenceTypes": ["SIGNED_FORM"],
                    "maximumAgeSeconds": 86400,
                    "termsHashRequired": true
                  },
                  "confirmedChangeMode": "NEW_PROPOSAL_AND_CUSTOMER_CONSENT"
                }
                """.trimIndent(),
            SchedulingPolicyKind.HOLD_AND_CONSENT to
                """{"consentEvidenceRequired":true,"maximumConsentAgeSeconds":86400}""",
            SchedulingPolicyKind.CAPACITY_AND_OVERBOOKING to
                """{"nominalCapacity":10,"overbookingQuota":2,"absoluteBookingLimit":12,"automaticReductionEnabled":true}""",
            SchedulingPolicyKind.PRIORITY_AND_RELIABILITY to
                """{"priorityWeights":{"RETURN_VISIT":2},"noShowPenalty":5,"sameDayCancellationPenalty":2,"minimumPriorityScore":0}""",
            SchedulingPolicyKind.RECONFIRMATION to
                """{"required":true,"leadTimeSeconds":86400,"maximumAttempts":3}""",
            SchedulingPolicyKind.DISRUPTION_RECOVERY to
                """{"automaticProposalEnabled":true,"maximumProposalDelaySeconds":3600,"preserveConfirmedAppointment":true}""",
            SchedulingPolicyKind.OPERATING_EXTENSION to
                """{"extensionEnabled":true,"maximumExtensionMinutes":60,"legalSafetyCeilingMinutes":120}""",
            SchedulingPolicyKind.NOTIFICATION_AND_SLA to
                """{"notificationChannels":["SMS"],"disruptionNoticeSeconds":900,"mandatoryResponseSeconds":3600}""",
        )
    }
}
