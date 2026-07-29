package io.bluetape4k.clinic.appointment.api.commitment

import io.bluetape4k.clinic.appointment.model.catalog.InitialBookingRule
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentItemDraft
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentProposalDraft
import io.bluetape4k.clinic.appointment.model.commitment.ResourceAllocationDraft
import io.bluetape4k.clinic.appointment.model.commitment.ResourceAllocationMode
import io.bluetape4k.clinic.appointment.model.commitment.ResourceType
import io.bluetape4k.clinic.appointment.model.dto.PlanRevisionDependencyRecord
import io.bluetape4k.clinic.appointment.model.dto.PlanRevisionGroupingConstraintRecord
import io.bluetape4k.clinic.appointment.model.dto.PlanRevisionTreatmentRecord
import io.bluetape4k.clinic.appointment.model.plan.BookingPreferenceSnapshot
import io.bluetape4k.clinic.appointment.model.plan.ExecutionDependency
import io.bluetape4k.clinic.appointment.model.plan.ExecutionDependencyType
import io.bluetape4k.clinic.appointment.model.plan.ExecutionTreatment
import io.bluetape4k.clinic.appointment.model.plan.PlanTreatmentStatus
import io.bluetape4k.clinic.appointment.model.plan.VisitGroupingConstraint
import io.bluetape4k.clinic.appointment.model.policy.EffectiveSchedulingPolicy
import io.bluetape4k.clinic.appointment.service.PackageExecutionLimits
import io.bluetape4k.clinic.appointment.service.PackageExecutionPlanner
import io.bluetape4k.clinic.appointment.service.PlanDirtySetResolver
import io.bluetape4k.clinic.appointment.service.ProposalHasher
import io.bluetape4k.clinic.appointment.service.VisitGroupingPlanner
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 활성 Plan revision의 미래 의무를 제한된 방문 제안으로 계산합니다.
 *
 * 이 서비스는 상품 BOM을 다시 해석하거나 정책을 현재값으로 재조회하지 않습니다.
 * 호출자가 [EffectiveSchedulingPolicy]와 그 값을 영속화한 양수 snapshot ID를 한 쌍으로
 * 전달해야 하며, 생성된 모든 제안과 canonical hash는 그 ID에 고정됩니다.
 *
 * 완료·부분 이행 뒤의 증분 계산에서는 `BLOCKING` dirty-set만 확장하고 완료 항목,
 * 현재 확정 제안에 속한 항목, 영향받지 않은 미래 항목은 후보에서 제외합니다. 이 필터는
 * 기존 확정 약정이나 완료 provenance를 변경하는 command가 아니며 새 가예약 초안만
 * 반환합니다.
 */
class AppointmentProposalService(
    private val limits: PackageExecutionLimits = PackageExecutionLimits(),
    private val executionPlanner: PackageExecutionPlanner = PackageExecutionPlanner(limits),
    private val groupingPlanner: VisitGroupingPlanner = VisitGroupingPlanner(),
    private val dirtySetResolver: PlanDirtySetResolver = PlanDirtySetResolver(),
) {
    /**
     * [request]의 미래 방문별 첫 번째 실행 가능한 슬롯을 proposal로 반환합니다.
     *
     * 고객 희망 정보가 있으면 그 범위만 평가하고 상품 최초 예약 규칙을 함께 적용하지
     * 않습니다. 후속 회차의 최소·최대 간격은 구매 시각이 아니라 권위 있는 실제 선행
     * 완료 시각에서 계산합니다. 어떤 상한이든 넘으면 일부 결과를 반환하지 않고
     * [ProposalFailureCode.PLAN_LIMIT_EXCEEDED]로 전체 요청을 거부합니다.
     *
     * @throws ProposalGenerationException 탐색 기간, candidate slot 또는 proposal 수가
     * platform 상한을 넘을 때 발생합니다.
     */
    fun generate(request: AppointmentProposalRequest): ProposalGenerationResult {
        validateRequest(request)
        val dependencies = request.dependencies.map(PlanRevisionDependencyRecord::toDomain)
        val eligibleKeys = eligibleTreatmentKeys(request, dependencies)
        if (eligibleKeys.isEmpty()) {
            return ProposalGenerationResult(emptyList(), emptyList())
        }

        val eligibleTreatments = request.treatments.filter { it.treatmentKey in eligibleKeys }
        val groupingConstraints =
            request.groupingConstraints
                .filter { it.firstTreatmentKey in eligibleKeys && it.secondTreatmentKey in eligibleKeys }
                .map(PlanRevisionGroupingConstraintRecord::toDomain)
        val visits =
            groupingPlanner.group(
                treatments = eligibleTreatments.map(PlanRevisionTreatmentRecord::toExecutionTreatment),
                constraints = groupingConstraints,
            )
        validateBounds(request.candidateSlots.size, visits.size)

        val generated = mutableListOf<GeneratedAppointmentProposal>()
        val failures = mutableListOf<ProposalRejection>()
        visits.forEachIndexed { visitIndex, visit ->
            val selected =
                request.candidateSlots
                    .asSequence()
                    .filter { request.acceptsPreference(it.startsAt) }
                    .filter { request.acceptsSearchHorizon(it.startsAt) }
                    .filter { request.acceptsPredecessorWindows(visit.treatments, it.startsAt) }
                    .mapNotNull { slot -> buildProposal(request, visitIndex, visit.treatments, slot) }
                    .firstOrNull()

            if (selected == null) {
                failures +=
                    ProposalRejection(
                        treatmentKeys = visit.treatments.map(ExecutionTreatment::treatmentKey),
                        code = ProposalFailureCode.NO_FEASIBLE_SLOT,
                    )
            } else {
                generated += selected
            }
        }
        return ProposalGenerationResult(generated, failures)
    }

    private fun validateRequest(request: AppointmentProposalRequest) {
        if (request.searchDays !in 1..MAXIMUM_SEARCH_DAYS) {
            throw planLimitExceeded()
        }
        if (
            request.treatments.size > limits.maximumTreatmentCount ||
            request.dependencies.size + request.groupingConstraints.size > limits.maximumEdgeCount
        ) {
            throw planLimitExceeded()
        }
        require(
            request.policySnapshot.policy.tenantGroupId == request.tenantGroupId &&
                request.policySnapshot.policy.clinicId == request.clinicId,
        ) {
            "effective policy scope must match proposal request scope"
        }
        require(
            request.candidateSlots.all {
                it.tenantGroupId == request.tenantGroupId && it.clinicId == request.clinicId
            },
        ) {
            "candidate slot scope must match proposal request scope"
        }
        if (
            request.candidateSlots.any { it.availableResources.size > limits.maximumResourcesPerSlot } ||
            request.candidateSlots.sumOf { it.availableResources.size } > limits.maximumCandidateResourceCount
        ) {
            throw planLimitExceeded()
        }
        validateBounds(request.candidateSlots.size, 0)
    }

    private fun validateBounds(
        candidateSlotCount: Int,
        proposalCount: Int,
    ) {
        try {
            executionPlanner.validateSearchBounds(candidateSlotCount, proposalCount)
        } catch (_: IllegalArgumentException) {
            throw planLimitExceeded()
        }
    }

    private fun eligibleTreatmentKeys(
        request: AppointmentProposalRequest,
        dependencies: List<ExecutionDependency>,
    ): Set<String> {
        val dirtyKeys =
            if (request.changedTreatmentKeys.isEmpty()) {
                request.treatments.mapTo(linkedSetOf(), PlanRevisionTreatmentRecord::treatmentKey)
            } else {
                dirtySetResolver.resolve(request.changedTreatmentKeys, dependencies)
            }
        return request.treatments
            .asSequence()
            .filter { it.status == PlanTreatmentStatus.PENDING }
            .map(PlanRevisionTreatmentRecord::treatmentKey)
            .filter { it in dirtyKeys }
            .filterNot { it in request.confirmedTreatmentKeys }
            .toCollection(linkedSetOf())
    }

    private fun AppointmentProposalRequest.acceptsPreference(startsAt: Instant): Boolean =
        when (val preference = bookingPreference) {
            is BookingPreferenceSnapshot.ExactDateTime -> {
                startsAt == preference.normalizedInstant
            }

            is BookingPreferenceSnapshot.DateRange -> {
                val date = startsAt.atZone(preference.zoneId).toLocalDate()
                date in preference.startDate..preference.endDate
            }

            is BookingPreferenceSnapshot.PreferredWeekdaysAndWindows -> {
                val local = startsAt.atZone(preference.zoneId)
                local.dayOfWeek in preference.weekdays &&
                    preference.localTimeWindows.any { local.toLocalTime() >= it.start && local.toLocalTime() < it.end }
            }

            BookingPreferenceSnapshot.NotProvided -> {
                when (val rule = initialBookingRule) {
                    null -> {
                        false
                    }

                    is InitialBookingRule.WithinDaysAfterPurchase -> {
                        !startsAt.isBefore(purchasedAt) &&
                            !startsAt.isAfter(purchasedAt.plus(rule.maximumDays.toLong(), ChronoUnit.DAYS))
                    }
                }
            }
        }

    private fun AppointmentProposalRequest.acceptsSearchHorizon(startsAt: Instant): Boolean =
        !startsAt.isBefore(policySnapshot.policy.decisionAt) &&
            !startsAt.isAfter(policySnapshot.policy.decisionAt.plus(searchDays.toLong(), ChronoUnit.DAYS))

    private fun AppointmentProposalRequest.acceptsPredecessorWindows(
        visitTreatments: List<ExecutionTreatment>,
        startsAt: Instant,
    ): Boolean {
        val visitKeys = visitTreatments.mapTo(hashSetOf(), ExecutionTreatment::treatmentKey)
        if (
            dependencies.any {
                it.predecessorTreatmentKey in visitKeys &&
                    it.successorTreatmentKey in visitKeys &&
                    it.minimumIntervalDays > 0
            }
        ) {
            return false
        }
        val inbound =
            dependencies.filter {
                it.type == ExecutionDependencyType.BLOCKING &&
                    it.successorTreatmentKey in visitKeys &&
                    it.predecessorTreatmentKey !in visitKeys
            }
        return inbound.all { dependency ->
            val predecessor =
                treatments.singleOrNull {
                    it.treatmentKey == dependency.predecessorTreatmentKey
                } ?: return@all false
            if (predecessor.status != PlanTreatmentStatus.COMPLETED) {
                return@all false
            }
            val completedAt =
                completedAtByTreatmentKey[dependency.predecessorTreatmentKey]
                    ?: return@all false
            val earliest = completedAt.plus(dependency.minimumIntervalDays.toLong(), ChronoUnit.DAYS)
            val latest =
                dependency.maximumIntervalDays?.let {
                    completedAt.plus(it.toLong(), ChronoUnit.DAYS)
                }
            !startsAt.isBefore(earliest) && (latest == null || !startsAt.isAfter(latest))
        }
    }

    private fun buildProposal(
        request: AppointmentProposalRequest,
        visitIndex: Int,
        treatments: List<ExecutionTreatment>,
        slot: ProposalCandidateSlot,
    ): GeneratedAppointmentProposal? {
        val allocationResult = allocateResources(treatments, slot) ?: return null
        val items =
            treatments.map { treatment ->
                AppointmentItemDraft(
                    planRevisionId = request.planRevisionId,
                    treatmentKey = treatment.treatmentKey,
                    representativeTreatmentName = treatment.representativeTreatmentName,
                    detailedTreatmentCodes = treatment.detailedTreatmentCodes,
                    preparationMinutes = treatment.preparationMinutes,
                    treatmentMinutes = treatment.treatmentMinutes,
                    recoveryMinutes = treatment.recoveryMinutes,
                    attemptNumber = request.attemptNumberByTreatmentKey[treatment.treatmentKey] ?: 1,
                )
            }
        val proposal =
            AppointmentProposalDraft(
                appointmentId = request.appointmentIdSeed + visitIndex,
                revision = request.proposalRevision,
                startsAt = slot.startsAt,
                endsAt =
                    slot.startsAt.plus(
                        treatments.sumOf(ExecutionTreatment::totalDurationMinutes).toLong(),
                        ChronoUnit.MINUTES,
                    ),
                items = items,
                allocations = allocationResult,
                policySnapshotId = request.policySnapshot.id,
                supersedesProposalId = null,
            )
        return GeneratedAppointmentProposal(proposal, ProposalHasher.hash(proposal))
    }

    private fun allocateResources(
        treatments: List<ExecutionTreatment>,
        slot: ProposalCandidateSlot,
    ): List<ResourceAllocationDraft>? {
        val allocations = mutableListOf<ResourceAllocationDraft>()
        var itemStartsAt = slot.startsAt
        for (treatment in treatments) {
            val itemEndsAt = itemStartsAt.plus(treatment.totalDurationMinutes.toLong(), ChronoUnit.MINUTES)
            val requirements =
                buildList {
                    treatment.practitionerQualifications.forEach { add(ResourceType.PRACTITIONER to it) }
                    treatment.equipmentTypes.forEach { add(ResourceType.EQUIPMENT to it) }
                    treatment.spaceCapabilities.forEach { add(ResourceType.TREATMENT_SPACE to it) }
                }
            for ((resourceType, capability) in requirements) {
                val resource =
                    slot.availableResources.firstOrNull {
                        it.resourceType == resourceType &&
                            capability in it.capabilities &&
                            allocations.none { allocation ->
                                allocation.resourceType == it.resourceType &&
                                    allocation.resourceId == it.resourceId &&
                                    allocation.startsAt < itemEndsAt &&
                                    allocation.endsAt > itemStartsAt &&
                                    (
                                        allocation.allocationMode == ResourceAllocationMode.EXCLUSIVE ||
                                            it.allocationMode == ResourceAllocationMode.EXCLUSIVE
                                    )
                            }
                    } ?: return null
                allocations +=
                    ResourceAllocationDraft(
                        resourceType = resource.resourceType,
                        resourceId = resource.resourceId,
                        startsAt = itemStartsAt,
                        endsAt = itemEndsAt,
                        capacityUnits = resource.capacityUnits,
                        allocationMode = resource.allocationMode,
                        appointmentItemKey = treatment.treatmentKey,
                    )
            }
            itemStartsAt = itemEndsAt
        }
        return allocations
    }

    private fun planLimitExceeded() =
        ProposalGenerationException(
            code = ProposalFailureCode.PLAN_LIMIT_EXCEEDED,
            partialProposals = emptyList(),
        )

    private companion object {
        const val MAXIMUM_SEARCH_DAYS = 365
    }
}

/**
 * `EffectiveSchedulingPolicyService`가 현재 결정 시각에 영속화한 정책과 row ID의 결합입니다.
 *
 * [EffectiveSchedulingPolicy.id]는 canonical hash이므로 약정 FK로 사용할 수 없습니다.
 * 따라서 호출자는 정책 서비스가 반환한 값과 같은 transaction 경계에서 확인한 양수
 * snapshot row [id]를 함께 전달해야 합니다.
 */
data class CurrentPolicySnapshot(
    val id: Long,
    val policy: EffectiveSchedulingPolicy,
) : Serializable {
    init {
        id.requirePositiveNumber("id")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 제한된 제안 계산의 완전한 입력입니다.
 *
 * @property appointmentIdSeed 방문 후보별 임시 appointment ID를 안정적으로 만들기
 * 위한 양수 시작값입니다. 실제 영속 ID 배정 전략은 Task 6 command 경계가 소유합니다.
 * @property bookingPreference 구매 시 캡처한 고객 희망 일정입니다. `NotProvided`가 아닌
 * variant는 [initialBookingRule]보다 항상 우선하며 두 범위를 교집합으로 축소하지 않습니다.
 * @property purchasedAt 상품 최초 예약 fallback의 기준인 권위 있는 구매 UTC 시각입니다.
 * 후속 회차의 임상 선행 시각이나 일반 탐색 기간 시작으로 재사용하지 않습니다.
 * @property initialBookingRule [BookingPreferenceSnapshot.NotProvided]일 때만 평가하는 상품
 * 규칙입니다. `null`이면 고객 희망 일정도 없는 요청에서 자동 가예약을 만들지 않습니다.
 * 고객 희망 일정을 덮어쓰거나 자동 확정을 허용하지 않습니다.
 * @property changedTreatmentKeys 비어 있으면 최초 계산이며 모든 미완료 항목을 대상으로
 * 합니다. 비어 있지 않으면 해당 키와 `BLOCKING` 후속 경로만 다시 계산합니다.
 * @property confirmedTreatmentKeys 현재 확정 proposal이 이미 보호하는 미래 항목입니다.
 * 새 제안 확정 전까지 이 항목을 재계산하거나 기존 자원 점유를 해제하지 않습니다.
 * @property completedAtByTreatmentKey 실제 이행 원장이 확정한 완료 UTC 시각입니다.
 * 구매·결제·제안 생성 시각으로 대신 채우면 안 됩니다.
 * @property attemptNumberByTreatmentKey 부분 이행 뒤 같은 Plan 의무를 다시 방문할 때
 * 사용할 2 이상의 시도 번호입니다. 항목 키와 Plan provenance는 바꾸지 않습니다.
 * @property candidateSlots 같은 tenant/clinic 범위의 자원 가용성 계산기가 반환한 실제
 * 시작 시각과 자원 후보입니다. 2,000개 platform 상한을 넘으면 일부 제안을 만들지
 * 않고 전체 요청을 거부합니다.
 * @property searchDays [policySnapshot]의 정책 결정 시각부터 탐색할 1..365일 범위입니다.
 * 구매 시각이 오래된 후속 회차도 실제 완료 사실 뒤 현재 결정 시각에서 다시 탐색합니다.
 * @property policySnapshot 현재 `EffectiveSchedulingPolicyService` 결과와 같은 영속 snapshot
 * row ID입니다. 모든 제안과 hash가 이 값에 고정되며 계산 중 현재 정책을 재조회하지 않습니다.
 */
data class AppointmentProposalRequest(
    val tenantGroupId: Long,
    val clinicId: Long,
    val appointmentIdSeed: Long,
    val proposalRevision: Long,
    val planRevisionId: Long,
    val treatments: List<PlanRevisionTreatmentRecord>,
    val dependencies: List<PlanRevisionDependencyRecord>,
    val groupingConstraints: List<PlanRevisionGroupingConstraintRecord>,
    val bookingPreference: BookingPreferenceSnapshot,
    val purchasedAt: Instant,
    val initialBookingRule: InitialBookingRule?,
    val completedAtByTreatmentKey: Map<String, Instant>,
    val attemptNumberByTreatmentKey: Map<String, Int>,
    val changedTreatmentKeys: Set<String>,
    val confirmedTreatmentKeys: Set<String>,
    val candidateSlots: List<ProposalCandidateSlot>,
    val searchDays: Int,
    val policySnapshot: CurrentPolicySnapshot,
) : Serializable {
    init {
        tenantGroupId.requirePositiveNumber("tenantGroupId")
        clinicId.requirePositiveNumber("clinicId")
        appointmentIdSeed.requirePositiveNumber("appointmentIdSeed")
        proposalRevision.requirePositiveNumber("proposalRevision")
        planRevisionId.requirePositiveNumber("planRevisionId")
        require(attemptNumberByTreatmentKey.values.all { it >= 2 }) {
            "explicit attempt numbers must be at least two"
        }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 한 방문 시작 시각과 그 시각에 실제로 점유 가능한 자원 목록입니다.
 *
 * 자원 종류나 표시용 room type만 전달하지 않고, [tenantGroupId]와 [clinicId] 범위에서
 * 충돌 잠금에 사용할 실제 자원 ID와 capability를 함께 제공합니다. [availableResources]는
 * 단순히 [startsAt] 순간만 비어 있다는 뜻이 아니라, 이 후보가 만드는 전체 방문과 각
 * 항목 interval에 실제로 배정할 수 있음을 upstream availability 계산기가 보증해야 합니다.
 */
data class ProposalCandidateSlot(
    val tenantGroupId: Long,
    val clinicId: Long,
    val startsAt: Instant,
    val availableResources: List<AvailableProposalResource>,
) : Serializable {
    init {
        tenantGroupId.requirePositiveNumber("tenantGroupId")
        clinicId.requirePositiveNumber("clinicId")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * candidate slot에서 점유 가능한 실제 병원 자원입니다.
 *
 * @property capabilities 자원 종류별 qualification, equipment type 또는 공간 capability
 * 코드입니다. 요구 코드 하나도 포함하지 않는 자원을 표시명만으로 선택하지 않습니다.
 */
data class AvailableProposalResource(
    val resourceType: ResourceType,
    val resourceId: String,
    val capabilities: Set<String>,
    val allocationMode: ResourceAllocationMode,
    val capacityUnits: Int,
) : Serializable {
    init {
        resourceId.requireNotBlank("resourceId")
        capacityUnits.requirePositiveNumber("capacityUnits")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** canonical hash가 결합된 한 방문 가예약 제안입니다. */
data class GeneratedAppointmentProposal(
    val proposal: AppointmentProposalDraft,
    val proposalHash: String,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** 제안된 방문과 제안하지 못한 방문의 stable reason을 함께 반환합니다. */
data class ProposalGenerationResult(
    val proposals: List<GeneratedAppointmentProposal>,
    val rejections: List<ProposalRejection>,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** 부분 제안을 만들 수 없었던 방문의 항목 키와 안정적인 사유입니다. */
data class ProposalRejection(
    val treatmentKeys: List<String>,
    val code: ProposalFailureCode,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** API 경계가 locale 독립적으로 매핑할 수 있는 제안 계산 실패 코드입니다. */
enum class ProposalFailureCode {
    /** platform의 동기 계산 상한을 넘어 전체 요청이 거부됐습니다. */
    PLAN_LIMIT_EXCEEDED,

    /** 기간·완료 간격·실제 자원 조건을 모두 충족하는 candidate slot이 없습니다. */
    NO_FEASIBLE_SLOT,
}

/**
 * 일부 결과를 반환하면 안 되는 제안 계산 실패입니다.
 *
 * [partialProposals]는 상한 실패에서 항상 비어 있으며, 이 invariant를 예외 타입에
 * 드러내 호출자가 이미 계산한 일부 값을 실수로 응답하지 못하게 합니다.
 */
class ProposalGenerationException(
    val code: ProposalFailureCode,
    val partialProposals: List<GeneratedAppointmentProposal>,
) : IllegalArgumentException(code.name)

private fun PlanRevisionTreatmentRecord.toExecutionTreatment() =
    ExecutionTreatment(
        treatmentKey = treatmentKey,
        componentProductId = componentProductId,
        componentProductVersionId = componentProductVersionId,
        sourceBomItemId = sourceBomItemId,
        sequence = sequence,
        representativeTreatmentName = representativeTreatmentName,
        detailedTreatmentCodes = detailedTreatmentCodes,
        preparationMinutes = preparationMinutes,
        treatmentMinutes = treatmentMinutes,
        recoveryMinutes = recoveryMinutes,
        practitionerQualifications = practitionerQualifications,
        equipmentTypes = equipmentTypes,
        spaceCapabilities = spaceCapabilities,
    )

private fun PlanRevisionDependencyRecord.toDomain() =
    ExecutionDependency(
        predecessorTreatmentKey = predecessorTreatmentKey,
        successorTreatmentKey = successorTreatmentKey,
        type = type,
        minimumIntervalDays = minimumIntervalDays,
        preferredIntervalDays = preferredIntervalDays,
        maximumIntervalDays = maximumIntervalDays,
    )

private fun PlanRevisionGroupingConstraintRecord.toDomain() =
    VisitGroupingConstraint(
        firstTreatmentKey = firstTreatmentKey,
        secondTreatmentKey = secondTreatmentKey,
        type = type,
    )
