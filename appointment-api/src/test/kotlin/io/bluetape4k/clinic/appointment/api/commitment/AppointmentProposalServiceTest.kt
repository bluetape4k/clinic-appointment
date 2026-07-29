package io.bluetape4k.clinic.appointment.api.commitment

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.clinic.appointment.model.catalog.InitialBookingRule
import io.bluetape4k.clinic.appointment.model.commitment.ResourceAllocationMode
import io.bluetape4k.clinic.appointment.model.commitment.ResourceType
import io.bluetape4k.clinic.appointment.model.dto.PlanRevisionDependencyRecord
import io.bluetape4k.clinic.appointment.model.dto.PlanRevisionGroupingConstraintRecord
import io.bluetape4k.clinic.appointment.model.dto.PlanRevisionTreatmentRecord
import io.bluetape4k.clinic.appointment.model.plan.BookingPreferenceSnapshot
import io.bluetape4k.clinic.appointment.model.plan.ExecutionDependencyType
import io.bluetape4k.clinic.appointment.model.plan.PlanTreatmentStatus
import io.bluetape4k.clinic.appointment.model.plan.VisitGroupingType
import io.bluetape4k.clinic.appointment.model.policy.CompiledSchedulingPolicy
import io.bluetape4k.clinic.appointment.model.policy.EffectiveSchedulingPolicy
import io.bluetape4k.clinic.appointment.model.policy.PolicyGenerationVector
import io.bluetape4k.clinic.appointment.service.PackageExecutionLimits
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class AppointmentProposalServiceTest {
    private val service = AppointmentProposalService()
    private val purchasedAt = Instant.parse("2026-08-01T00:00:00Z")

    @Test
    fun `고객이 정확한 희망 시각을 제공하면 상품 N일 fallback보다 우선한다`() {
        val preferred = Instant.parse("2026-08-05T01:00:00Z")
        val request =
            request(
                preference =
                    BookingPreferenceSnapshot.ExactDateTime(
                        originalLocalDateTime = LocalDateTime.of(2026, 8, 5, 10, 0),
                        originalOffset = ZoneOffset.ofHours(9),
                        zoneId = ZoneId.of("Asia/Seoul"),
                        normalizedInstant = preferred,
                    ),
                slots =
                    listOf(
                        slot("2026-08-02T01:00:00Z"),
                        slot("2026-08-05T01:00:00Z"),
                    ),
            )

        val result = service.generate(request)

        result.proposals.shouldHaveSize(1)
        result.proposals
            .single()
            .proposal.startsAt shouldBeEqualTo preferred
    }

    @Test
    fun `희망 일정이 없을 때만 구매 후 N일 이내 상품 규칙을 적용한다`() {
        val result =
            service.generate(
                request(
                    preference = BookingPreferenceSnapshot.NotProvided,
                    initialBookingRule = InitialBookingRule.WithinDaysAfterPurchase(maximumDays = 3),
                    slots =
                        listOf(
                            slot("2026-08-04T00:00:00Z"),
                            slot("2026-08-05T00:00:00Z"),
                        ),
                ),
            )

        result.proposals
            .single()
            .proposal.startsAt shouldBeEqualTo Instant.parse("2026-08-04T00:00:00Z")
    }

    @Test
    fun `고객 희망과 상품 fallback이 모두 없으면 자동 가예약을 만들지 않는다`() {
        val result =
            service.generate(
                request(
                    preference = BookingPreferenceSnapshot.NotProvided,
                    initialBookingRule = null,
                ),
            )

        result.proposals.shouldBeEmpty()
        result.rejections.single().code shouldBeEqualTo ProposalFailureCode.NO_FEASIBLE_SLOT
    }

    @Test
    fun `후속 회차는 구매 시각이 아니라 실제 선행 완료 시각과 최소 간격을 기준으로 한다`() {
        val predecessor = treatment("laser-1", status = PlanTreatmentStatus.COMPLETED)
        val successor = treatment("laser-2")
        val completedAt = Instant.parse("2026-08-10T06:00:00Z")
        val result =
            service.generate(
                request(
                    treatments = listOf(predecessor, successor),
                    dependencies =
                        listOf(
                            dependency("laser-1", "laser-2", minimumIntervalDays = 2),
                        ),
                    completedAtByTreatmentKey = mapOf("laser-1" to completedAt),
                    slots =
                        listOf(
                            slot("2026-08-11T06:00:00Z"),
                            slot("2026-08-12T06:00:00Z"),
                        ),
                ),
            )

        result.proposals
            .single()
            .proposal.startsAt shouldBeEqualTo Instant.parse("2026-08-12T06:00:00Z")
    }

    @Test
    fun `부분 이행 재계산은 완료 항목과 기존 확정 항목과 영향 없는 미래 항목을 변경하지 않는다`() {
        val result =
            service.generate(
                request(
                    treatments =
                        listOf(
                            treatment("completed", status = PlanTreatmentStatus.COMPLETED),
                            treatment("failed-part"),
                            treatment("revisit"),
                            treatment("confirmed-future"),
                            treatment("unaffected-future"),
                        ),
                    dependencies =
                        listOf(
                            dependency("failed-part", "revisit"),
                            dependency("failed-part", "unaffected-future", type = ExecutionDependencyType.NON_BLOCKING),
                        ),
                    changedTreatmentKeys = setOf("failed-part"),
                    confirmedTreatmentKeys = setOf("confirmed-future"),
                    attemptNumberByTreatmentKey = mapOf("failed-part" to 2),
                ),
            )

        val proposedItems = result.proposals.flatMap { it.proposal.items }
        proposedItems.map { it.treatmentKey } shouldBeEqualTo listOf("failed-part")
        proposedItems.single().attemptNumber shouldBeEqualTo 2
    }

    @Test
    fun `항목 capability를 모두 충족하는 실제 자원만 proposal allocation에 고정한다`() {
        val result =
            service.generate(
                request(
                    treatments =
                        listOf(
                            treatment(
                                key = "laser",
                                practitionerQualifications = listOf("DERM"),
                                equipmentTypes = listOf("LASER_X"),
                                spaceCapabilities = listOf("LASER_SAFE"),
                            ),
                        ),
                    slots =
                        listOf(
                            slot(
                                start = "2026-08-02T01:00:00Z",
                                resources =
                                    listOf(
                                        resource(ResourceType.PRACTITIONER, "doctor-general", setOf("GENERAL")),
                                        resource(ResourceType.PRACTITIONER, "doctor-derm", setOf("DERM")),
                                        resource(ResourceType.EQUIPMENT, "laser-x-01", setOf("LASER_X")),
                                        resource(ResourceType.TREATMENT_SPACE, "room-3", setOf("LASER_SAFE")),
                                    ),
                            ),
                        ),
                ),
            )

        result.proposals.single().proposal.allocations.mapTo(mutableSetOf()) {
            it.resourceId
        } shouldBeEqualTo setOf("doctor-derm", "laser-x-01", "room-3")
    }

    @Test
    fun `겹치는 capability에 같은 exclusive 자원을 중복 배정하지 않는다`() {
        val result =
            service.generate(
                request(
                    treatments =
                        listOf(
                            treatment(
                                key = "laser",
                                equipmentTypes = listOf("LASER_X", "COOLING"),
                            ),
                        ),
                    slots =
                        listOf(
                            slot(
                                start = "2026-08-02T01:00:00Z",
                                resources =
                                    listOf(
                                        resource(
                                            ResourceType.EQUIPMENT,
                                            "laser-combined-01",
                                            setOf("LASER_X", "COOLING"),
                                        ),
                                    ),
                            ),
                        ),
                ),
            )

        result.proposals.shouldBeEmpty()
        result.rejections.single().code shouldBeEqualTo ProposalFailureCode.NO_FEASIBLE_SLOT
    }

    @Test
    fun `같은 방문의 양수 간격 선후행은 실행 불가능한 BOM 조합으로 거부한다`() {
        val result =
            service.generate(
                request(
                    treatments = listOf(treatment("first"), treatment("second")),
                    dependencies = listOf(dependency("first", "second", minimumIntervalDays = 1)),
                    groupingConstraints =
                        listOf(
                            PlanRevisionGroupingConstraintRecord(
                                firstTreatmentKey = "first",
                                secondTreatmentKey = "second",
                                type = VisitGroupingType.MUST_SAME_VISIT,
                            ),
                        ),
                ),
            )

        result.proposals.shouldBeEmpty()
        result.rejections.single().code shouldBeEqualTo ProposalFailureCode.NO_FEASIBLE_SLOT
    }

    @Test
    fun `요청 scope와 다른 clinic의 candidate slot은 거부한다`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                service.generate(
                    request(
                        slots =
                            listOf(
                                slot("2026-08-02T01:00:00Z", clinicId = 11L),
                            ),
                    ),
                )
            }

        failure.message shouldBeEqualTo "candidate slot scope must match proposal request scope"
    }

    @Test
    fun `후보 slot과 전체 요청의 자원 cardinality 상한을 계산 전에 거부한다`() {
        val boundedService =
            AppointmentProposalService(
                limits =
                    PackageExecutionLimits(
                        maximumResourcesPerSlot = 2,
                        maximumCandidateResourceCount = 3,
                    ),
            )
        val resources =
            listOf(
                resource(ResourceType.PRACTITIONER, "doctor-1", setOf("DERM")),
                resource(ResourceType.EQUIPMENT, "laser-1", setOf("LASER_X")),
                resource(ResourceType.TREATMENT_SPACE, "room-1", setOf("LASER_SAFE")),
            )

        val perSlotFailure = assertFailsWith<ProposalGenerationException> {
            boundedService.generate(request(slots = listOf(slot("2026-08-02T01:00:00Z", resources = resources))))
        }
        val totalFailure = assertFailsWith<ProposalGenerationException> {
            boundedService.generate(
                request(
                    slots =
                        listOf(
                            slot("2026-08-02T01:00:00Z", resources = resources.take(2)),
                            slot("2026-08-03T01:00:00Z", resources = resources.take(2)),
                        ),
                ),
            )
        }

        perSlotFailure.code shouldBeEqualTo ProposalFailureCode.PLAN_LIMIT_EXCEEDED
        totalFailure.code shouldBeEqualTo ProposalFailureCode.PLAN_LIMIT_EXCEEDED
    }

    @Test
    fun `전개 항목 또는 관계 상한을 넘으면 계산 전에 전체 요청을 거부한다`() {
        val treatmentFailure =
            assertFailsWith<ProposalGenerationException> {
                service.generate(
                    request(
                        treatments = List(501) { index -> treatment("treatment-$index") },
                    ),
                )
            }
        val edgeFailure =
            assertFailsWith<ProposalGenerationException> {
                service.generate(
                    request(
                        treatments = listOf(treatment("first"), treatment("second")),
                        dependencies = List(4_001) { dependency("first", "second") },
                    ),
                )
            }

        treatmentFailure.code shouldBeEqualTo ProposalFailureCode.PLAN_LIMIT_EXCEEDED
        edgeFailure.code shouldBeEqualTo ProposalFailureCode.PLAN_LIMIT_EXCEEDED
        treatmentFailure.partialProposals.shouldBeEmpty()
        edgeFailure.partialProposals.shouldBeEmpty()
    }

    @Test
    fun `후보 또는 탐색 기간 상한을 넘으면 부분 proposal 없이 stable reason code로 실패한다`() {
        val tooManySlots =
            List(2_001) { index ->
                slot(purchasedAt.plusSeconds(index.toLong() * 60L).toString())
            }

        val slotFailure =
            assertFailsWith<ProposalGenerationException> {
                service.generate(request(slots = tooManySlots))
            }
        val periodFailure =
            assertFailsWith<ProposalGenerationException> {
                service.generate(request(searchDays = 366))
            }

        slotFailure.code shouldBeEqualTo ProposalFailureCode.PLAN_LIMIT_EXCEEDED
        periodFailure.code shouldBeEqualTo ProposalFailureCode.PLAN_LIMIT_EXCEEDED
        slotFailure.partialProposals.shouldBeEmpty()
        periodFailure.partialProposals.shouldBeEmpty()
    }

    @Test
    fun `제안은 현재 policy snapshot id와 canonical hash를 고정한다`() {
        val first = service.generate(request(policySnapshotId = 41L)).proposals.single()
        val replay = service.generate(request(policySnapshotId = 41L)).proposals.single()
        val changedPolicy = service.generate(request(policySnapshotId = 42L)).proposals.single()

        first.proposal.policySnapshotId shouldBeEqualTo 41L
        first.proposalHash shouldBeEqualTo replay.proposalHash
        first.proposalHash.shouldNotBeEqualTo(changedPolicy.proposalHash)
        (first.proposalHash.length == 64).shouldBeTrue()
    }

    @Suppress("LongParameterList")
    private fun request(
        treatments: List<PlanRevisionTreatmentRecord> = listOf(treatment("consult")),
        dependencies: List<PlanRevisionDependencyRecord> = emptyList(),
        groupingConstraints: List<PlanRevisionGroupingConstraintRecord> = emptyList(),
        preference: BookingPreferenceSnapshot = BookingPreferenceSnapshot.NotProvided,
        initialBookingRule: InitialBookingRule? = InitialBookingRule.WithinDaysAfterPurchase(30),
        completedAtByTreatmentKey: Map<String, Instant> = emptyMap(),
        attemptNumberByTreatmentKey: Map<String, Int> = emptyMap(),
        changedTreatmentKeys: Set<String> = emptySet(),
        confirmedTreatmentKeys: Set<String> = emptySet(),
        slots: List<ProposalCandidateSlot> = listOf(slot("2026-08-02T01:00:00Z")),
        searchDays: Int = 30,
        policySnapshotId: Long = 41L,
    ): AppointmentProposalRequest =
        AppointmentProposalRequest(
            tenantGroupId = 1L,
            clinicId = 10L,
            appointmentIdSeed = 100L,
            proposalRevision = 1L,
            planRevisionId = 7L,
            treatments = treatments,
            dependencies = dependencies,
            groupingConstraints = groupingConstraints,
            bookingPreference = preference,
            purchasedAt = purchasedAt,
            initialBookingRule = initialBookingRule,
            completedAtByTreatmentKey = completedAtByTreatmentKey,
            attemptNumberByTreatmentKey = attemptNumberByTreatmentKey,
            changedTreatmentKeys = changedTreatmentKeys,
            confirmedTreatmentKeys = confirmedTreatmentKeys,
            candidateSlots = slots,
            searchDays = searchDays,
            policySnapshot = CurrentPolicySnapshot(policySnapshotId, effectivePolicy()),
        )

    private fun treatment(
        key: String,
        status: PlanTreatmentStatus = PlanTreatmentStatus.PENDING,
        practitionerQualifications: List<String> = emptyList(),
        equipmentTypes: List<String> = emptyList(),
        spaceCapabilities: List<String> = emptyList(),
    ): PlanRevisionTreatmentRecord =
        PlanRevisionTreatmentRecord(
            treatmentKey = key,
            componentProductId = "component-$key",
            componentProductVersionId = "component-$key-v1",
            productVersionId = "package-v1",
            status = status,
            sourceBomItemId = "bom-$key",
            sequence = 1,
            representativeTreatmentName = key,
            detailedTreatmentCodes = listOf("CODE-$key"),
            preparationMinutes = 5,
            treatmentMinutes = 20,
            recoveryMinutes = 5,
            practitionerQualifications = practitionerQualifications,
            equipmentTypes = equipmentTypes,
            spaceCapabilities = spaceCapabilities,
        )

    private fun dependency(
        predecessor: String,
        successor: String,
        type: ExecutionDependencyType = ExecutionDependencyType.BLOCKING,
        minimumIntervalDays: Int = 0,
    ): PlanRevisionDependencyRecord =
        PlanRevisionDependencyRecord(
            predecessorTreatmentKey = predecessor,
            successorTreatmentKey = successor,
            type = type,
            minimumIntervalDays = minimumIntervalDays,
            preferredIntervalDays = null,
            maximumIntervalDays = null,
        )

    private fun slot(
        start: String,
        resources: List<AvailableProposalResource> = emptyList(),
        clinicId: Long = 10L,
    ): ProposalCandidateSlot =
        ProposalCandidateSlot(
            tenantGroupId = 1L,
            clinicId = clinicId,
            startsAt = Instant.parse(start),
            availableResources = resources,
        )

    private fun resource(
        type: ResourceType,
        id: String,
        capabilities: Set<String>,
    ): AvailableProposalResource =
        AvailableProposalResource(
            resourceType = type,
            resourceId = id,
            capabilities = capabilities,
            allocationMode = ResourceAllocationMode.EXCLUSIVE,
            capacityUnits = 1,
        )

    private fun effectivePolicy(): EffectiveSchedulingPolicy =
        EffectiveSchedulingPolicy(
            id = "policy-hash",
            tenantGroupId = 1L,
            clinicId = 10L,
            decisionAt = purchasedAt,
            serviceAt = purchasedAt,
            generation = PolicyGenerationVector(1L, 0L),
            sourceVersions = emptyMap(),
            sourceByPath = emptyMap(),
            disabledFeatures = emptySet(),
            warnings = emptyList(),
            payload = CompiledSchedulingPolicy(),
            snapshotHash = "policy-hash",
        )
}
