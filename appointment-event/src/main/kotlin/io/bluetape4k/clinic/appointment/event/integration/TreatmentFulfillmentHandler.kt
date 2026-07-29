package io.bluetape4k.clinic.appointment.event.integration

import io.bluetape4k.clinic.appointment.model.dto.AppointmentPlanAggregateRecord
import io.bluetape4k.clinic.appointment.model.dto.AppointmentPlanRevisionAggregateRecord
import io.bluetape4k.clinic.appointment.model.dto.PlanRevisionDependencyRecord
import io.bluetape4k.clinic.appointment.model.dto.PlanRevisionGroupingConstraintRecord
import io.bluetape4k.clinic.appointment.model.dto.PlanRevisionTreatmentRecord
import io.bluetape4k.clinic.appointment.model.operation.AppointmentOperationalException
import io.bluetape4k.clinic.appointment.model.operation.AppointmentOperationalExceptionStatus
import io.bluetape4k.clinic.appointment.model.operation.AppointmentOperationalExceptionType
import io.bluetape4k.clinic.appointment.model.plan.AppointmentPlanRevision
import io.bluetape4k.clinic.appointment.model.plan.AppointmentPlanStatus
import io.bluetape4k.clinic.appointment.model.plan.ExecutionDependency
import io.bluetape4k.clinic.appointment.model.plan.PlanTreatmentStatus
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlans
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.repository.AppointmentOperationalExceptionRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentPlanRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentPlanRevisionRepository
import io.bluetape4k.clinic.appointment.service.PlanDirtySetResolver
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * 완료·부분 이행·자원 장애·환불 사실을 불변 Plan revision으로 수렴시킵니다.
 *
 * 원본 revision row를 update하지 않습니다. 현재 활성 revision을 Plan root lock 아래
 * 복사해 결과 상태와 잔여 진료를 반영한 새 revision을 append한 뒤 활성화합니다.
 * 부분 이행은 원 항목을 완료로 남기고 producer가 제공한 잔여 실행 항목을 새 key로
 * 추가합니다. 환불은 [PlanDirtySetResolver]로 `BLOCKING` 후속만 취소하며 독립
 * `NON_BLOCKING` 항목은 계속 예약 가능하게 둡니다.
 */
class TreatmentFulfillmentHandler(
    private val eventRepository: SchedulingEventRepository,
    private val quarantineRepository: SchedulingQuarantineRepository,
    private val planRepository: AppointmentPlanRepository,
    private val revisionRepository: AppointmentPlanRevisionRepository,
    private val operationalExceptionRepository: AppointmentOperationalExceptionRepository,
    private val dirtySetResolver: PlanDirtySetResolver,
    private val versionVerifier: SourceAggregateVersionVerifier,
    private val clock: Clock,
    private val quarantineRetention: Duration = Duration.ofDays(30),
    private val maxGapAttempts: Int = 5,
    private val initialGapBackoff: Duration = Duration.ofMinutes(1),
    private val maxGapBackoff: Duration = Duration.ofHours(1),
) {
    init {
        require(maxGapAttempts > 0) { "maxGapAttempts must be positive" }
        require(!initialGapBackoff.isNegative && !initialGapBackoff.isZero) {
            "initialGapBackoff must be positive"
        }
        require(maxGapBackoff >= initialGapBackoff) {
            "maxGapBackoff must not be shorter than initialGapBackoff"
        }
    }

    companion object : KLogging()

    /**
     * trusted 외부 사실 묶음을 하나의 새 revision으로 원자적으로 반영합니다.
     */
    internal fun handle(
        envelope: TrustedSchedulingEventEnvelope<TreatmentFulfillmentEvent>,
        protectedQuarantineEnvelope: ProtectedQuarantineEnvelope,
        versionProof: SourceAuthorityVersionProof? = null,
    ): PurchaseHandleResult {
        val result = transaction {
            val payload = envelope.payload
            val plan = findPlanAndLock(payload)
            val existing = eventRepository.findInbox(envelope.eventId)
            if (existing != null && existing.status != SchedulingInboxStatus.WAITING_GAP) {
                return@transaction PurchaseHandleResult(
                    PurchaseHandleStatus.DUPLICATE,
                    "EVENT_ALREADY_TERMINAL",
                )
            }
            val inboxId = existing?.id ?: eventRepository.insertReceivedTreatmentFulfillment(envelope)
            if (plan == null) {
                quarantine(
                    inboxId,
                    envelope,
                    protectedQuarantineEnvelope,
                    "APPOINTMENT_PLAN_NOT_FOUND",
                )
                return@transaction PurchaseHandleResult(
                    PurchaseHandleStatus.QUARANTINED,
                    "APPOINTMENT_PLAN_NOT_FOUND",
                )
            }
            val planId = checkNotNull(plan.plan.id)
            val localVersion = eventRepository.latestProcessedSourceVersion(
                tenantGroupId = payload.tenantGroupId,
                clinicId = payload.clinicId,
                producer = envelope.producer,
                sourceAuthority = payload.sourcePurchaseAuthority,
                sourceAggregateId = payload.sourceAggregateId,
            )
            when (versionVerifier.verify(envelope.producer, payload, localVersion, versionProof)) {
                SourceVersionDecision.STALE_OR_DUPLICATE ->
                    when (
                    eventRepository.classifyProcessedSourceVersion(
                        tenantGroupId = payload.tenantGroupId,
                        clinicId = payload.clinicId,
                        producer = envelope.producer,
                        sourceAuthority = payload.sourcePurchaseAuthority,
                        sourceAggregateId = payload.sourceAggregateId,
                        sourceAggregateVersion = payload.sourceAggregateVersion,
                        payloadHash = envelope.payloadHash,
                    )
                ) {
                    SchedulingSourceVersionMatch.SAME_HASH -> {
                        eventRepository.markProcessed(
                            inboxId,
                            clock.instant(),
                            "SOURCE_VERSION_REPLAY",
                        )
                        return@transaction PurchaseHandleResult(
                            PurchaseHandleStatus.DUPLICATE,
                            "SOURCE_VERSION_REPLAY",
                            planId,
                        )
                    }

                    SchedulingSourceVersionMatch.DIFFERENT_HASH -> {
                        quarantine(
                            inboxId,
                            envelope,
                            protectedQuarantineEnvelope,
                            "SOURCE_VERSION_HASH_CONFLICT",
                        )
                        return@transaction PurchaseHandleResult(
                            PurchaseHandleStatus.QUARANTINED,
                            "SOURCE_VERSION_HASH_CONFLICT",
                            planId,
                        )
                    }

                    SchedulingSourceVersionMatch.NOT_FOUND -> {
                        quarantine(
                            inboxId,
                            envelope,
                            protectedQuarantineEnvelope,
                            "STALE_SOURCE_VERSION",
                        )
                        return@transaction PurchaseHandleResult(
                            PurchaseHandleStatus.QUARANTINED,
                            "STALE_SOURCE_VERSION",
                            planId,
                        )
                    }
                }

                SourceVersionDecision.WAITING_GAP ->
                    return@transaction waitForGap(
                        inboxId,
                        existing,
                        envelope,
                        protectedQuarantineEnvelope,
                    )

                SourceVersionDecision.ACCEPT -> Unit
            }
            val active = revisionRepository.findActive(planId)
            if (active == null) {
                quarantine(
                    inboxId,
                    envelope,
                    protectedQuarantineEnvelope,
                    "ACTIVE_PLAN_REVISION_NOT_FOUND",
                )
                return@transaction PurchaseHandleResult(
                    PurchaseHandleStatus.QUARANTINED,
                    "ACTIVE_PLAN_REVISION_NOT_FOUND",
                    planId,
                )
            }
            val projection = try {
                projectFacts(
                    treatments = active.treatments,
                    dependencies = active.dependencies,
                    groupingConstraints = active.groupingConstraints,
                    facts = payload.facts,
                )
            } catch (_: IllegalArgumentException) {
                quarantine(
                    inboxId,
                    envelope,
                    protectedQuarantineEnvelope,
                    "FULFILLMENT_FACT_INVALID",
                )
                return@transaction PurchaseHandleResult(
                    PurchaseHandleStatus.QUARANTINED,
                    "FULFILLMENT_FACT_INVALID",
                    planId,
                )
            }
            val persisted = revisionRepository.append(
                AppointmentPlanRevisionAggregateRecord(
                    revision = AppointmentPlanRevision(
                        planId = planId,
                        revision = active.revision.revision + 1L,
                        productVersionId = active.revision.productVersionId,
                        snapshotHash = envelope.payloadHash,
                        active = false,
                    ),
                    treatments = projection.treatments,
                    dependencies = projection.dependencies,
                    groupingConstraints = projection.groupingConstraints,
                ),
            )
            check(revisionRepository.activate(planId, active.revision.id, persisted.revision.id)) {
                "fulfillment revision activation lost its locked compare-and-set invariant"
            }
            val nextPlanStatus = derivePlanStatus(plan.plan.status, projection.treatments)
            check(
                planRepository.updateStatus(
                    planId = planId,
                    tenantGroupId = payload.tenantGroupId,
                    clinicId = payload.clinicId,
                    expectedStatus = plan.plan.status,
                    newStatus = nextPlanStatus,
                    updatedAt = clock.instant(),
                ),
            ) {
                "Plan status compare-and-set failed under locked root"
            }
            projection.resourceDisruptions.forEach { disruption ->
                operationalExceptionRepository.append(
                    AppointmentOperationalException(
                        appointmentPlanId = planId,
                        appointmentId = null,
                        type = AppointmentOperationalExceptionType.RESOURCE_DISRUPTION,
                        reasonCode = checkNotNull(disruption.reasonCode),
                        status = AppointmentOperationalExceptionStatus.OPEN,
                        openedAt = disruption.occurredAt,
                        resolvedAt = null,
                    ),
                )
            }
            eventRepository.insertExternalPlanFactOutbox(
                inboundEventId = envelope.eventId,
                correlationId = envelope.correlationId,
                eventType = "TreatmentFulfillmentApplied",
                tenantGroupId = payload.tenantGroupId,
                clinicId = payload.clinicId,
                planId = planId,
                revisionId = persisted.revision.id,
                reasonCode = null,
                changedTreatmentKeys = projection.changedTreatmentKeys,
                dirtyTreatmentKeys = projection.dirtyTreatmentKeys,
                effectiveAtByTreatmentKey = projection.effectiveAtByTreatmentKey,
            )
            eventRepository.markProcessed(inboxId, clock.instant())
            PurchaseHandleResult(PurchaseHandleStatus.CREATED, planId = planId)
        }
        log.info {
            "Treatment fulfillment handled: eventId=${envelope.eventId}, " +
                "correlationId=${envelope.correlationId}, tenantGroupId=${envelope.payload.tenantGroupId}, " +
                "clinicId=${envelope.payload.clinicId}, result=${result.status}, reasonCode=${result.reasonCode}"
        }
        return result
    }

    private fun projectFacts(
        treatments: List<PlanRevisionTreatmentRecord>,
        dependencies: List<PlanRevisionDependencyRecord>,
        groupingConstraints: List<PlanRevisionGroupingConstraintRecord>,
        facts: List<TreatmentFulfillmentFact>,
    ): FulfillmentProjection {
        val mutableTreatments = treatments.associateByTo(
            linkedMapOf(),
            PlanRevisionTreatmentRecord::treatmentKey,
        )
        val replacements = linkedMapOf<String, String>()
        val changed = linkedSetOf<String>()
        val resourceDisruptions = mutableListOf<TreatmentFulfillmentFact>()
        val refunded = linkedSetOf<String>()
        val effectiveAtByTreatmentKey = linkedMapOf<String, Instant>()

        facts.forEach { fact ->
            val current = requireNotNull(mutableTreatments[fact.treatmentKey]) {
                "fulfillment fact references an unknown treatment"
            }
            require(current.status == PlanTreatmentStatus.PENDING) {
                "only pending treatment can receive a new fulfillment fact"
            }
            when (fact.type) {
                TreatmentFulfillmentFactType.COMPLETED -> {
                    mutableTreatments[fact.treatmentKey] = current.copy(
                        status = PlanTreatmentStatus.COMPLETED,
                    )
                    changed += fact.treatmentKey
                    effectiveAtByTreatmentKey[fact.treatmentKey] = fact.occurredAt
                }

                TreatmentFulfillmentFactType.PARTIALLY_FULFILLED,
                TreatmentFulfillmentFactType.RESOURCE_DISRUPTED,
                -> {
                    val completed = checkNotNull(fact.completedTreatment)
                    val remaining = checkNotNull(fact.remainingTreatment)
                    require(remaining.treatmentKey !in mutableTreatments) {
                        "remaining treatment key must be unique in the revision"
                    }
                    mutableTreatments[fact.treatmentKey] = current.copy(
                        status = PlanTreatmentStatus.COMPLETED,
                        componentProductId = completed.componentProductId,
                        componentProductVersionId = completed.componentProductVersionId,
                        sourceBomItemId = completed.sourceBomItemId,
                        sequence = completed.sequence,
                        representativeTreatmentName = completed.representativeTreatmentName,
                        detailedTreatmentCodes = completed.detailedTreatmentCodes,
                        preparationMinutes = completed.preparationMinutes,
                        treatmentMinutes = completed.treatmentMinutes,
                        recoveryMinutes = completed.recoveryMinutes,
                        practitionerQualifications = completed.practitionerQualifications,
                        equipmentTypes = completed.equipmentTypes,
                        spaceCapabilities = completed.spaceCapabilities,
                    )
                    mutableTreatments[remaining.treatmentKey] = PlanRevisionTreatmentRecord(
                        treatmentKey = remaining.treatmentKey,
                        componentProductId = remaining.componentProductId,
                        componentProductVersionId = remaining.componentProductVersionId,
                        productVersionId = current.productVersionId,
                        status = PlanTreatmentStatus.PENDING,
                        sourceBomItemId = remaining.sourceBomItemId,
                        sequence = remaining.sequence,
                        representativeTreatmentName = remaining.representativeTreatmentName,
                        detailedTreatmentCodes = remaining.detailedTreatmentCodes,
                        preparationMinutes = remaining.preparationMinutes,
                        treatmentMinutes = remaining.treatmentMinutes,
                        recoveryMinutes = remaining.recoveryMinutes,
                        practitionerQualifications = remaining.practitionerQualifications,
                        equipmentTypes = remaining.equipmentTypes,
                        spaceCapabilities = remaining.spaceCapabilities,
                    )
                    replacements[fact.treatmentKey] = remaining.treatmentKey
                    changed += fact.treatmentKey
                    changed += remaining.treatmentKey
                    effectiveAtByTreatmentKey[fact.treatmentKey] = fact.occurredAt
                    if (fact.type == TreatmentFulfillmentFactType.RESOURCE_DISRUPTED) {
                        resourceDisruptions += fact
                    }
                }

                TreatmentFulfillmentFactType.REFUNDED -> {
                    refunded += fact.treatmentKey
                    effectiveAtByTreatmentKey[fact.treatmentKey] = fact.occurredAt
                }
            }
        }

        val executionDependencies = dependencies.map { dependency ->
            ExecutionDependency(
                predecessorTreatmentKey = dependency.predecessorTreatmentKey,
                successorTreatmentKey = dependency.successorTreatmentKey,
                type = dependency.type,
                minimumIntervalDays = dependency.minimumIntervalDays,
                preferredIntervalDays = dependency.preferredIntervalDays,
                maximumIntervalDays = dependency.maximumIntervalDays,
            )
        }
        val cancellationSet = dirtySetResolver.resolveCancellationSet(refunded, executionDependencies)
        cancellationSet.forEach { treatmentKey ->
            val current = mutableTreatments[treatmentKey] ?: return@forEach
            if (current.status == PlanTreatmentStatus.PENDING) {
                mutableTreatments[treatmentKey] = current.copy(status = PlanTreatmentStatus.CANCELLED)
                changed += treatmentKey
            }
        }
        val dirtyTreatmentKeys = dirtySetResolver.resolve(
            changedTreatmentKeys = effectiveAtByTreatmentKey.keys,
            dependencies = executionDependencies,
        ) + changed

        val projectedDependencies = dependencies.map { dependency ->
            dependency.copy(
                predecessorTreatmentKey =
                    replacements[dependency.predecessorTreatmentKey] ?: dependency.predecessorTreatmentKey,
                successorTreatmentKey =
                    replacements[dependency.successorTreatmentKey] ?: dependency.successorTreatmentKey,
            )
        }.filter { it.predecessorTreatmentKey != it.successorTreatmentKey }
        val projectedGroupingConstraints = groupingConstraints.map { constraint ->
            constraint.copy(
                firstTreatmentKey =
                    replacements[constraint.firstTreatmentKey] ?: constraint.firstTreatmentKey,
                secondTreatmentKey =
                    replacements[constraint.secondTreatmentKey] ?: constraint.secondTreatmentKey,
            )
        }.filter { it.firstTreatmentKey != it.secondTreatmentKey }

        return FulfillmentProjection(
            treatments = mutableTreatments.values.toList(),
            dependencies = projectedDependencies,
            groupingConstraints = projectedGroupingConstraints,
            changedTreatmentKeys = changed,
            dirtyTreatmentKeys = dirtyTreatmentKeys,
            effectiveAtByTreatmentKey = effectiveAtByTreatmentKey,
            resourceDisruptions = resourceDisruptions,
        )
    }

    private fun waitForGap(
        inboxId: Long,
        existingInbox: SchedulingInboxRecord?,
        envelope: TrustedSchedulingEventEnvelope<TreatmentFulfillmentEvent>,
        protectedQuarantineEnvelope: ProtectedQuarantineEnvelope,
    ): PurchaseHandleResult {
        val attempt = (existingInbox?.attemptCount ?: 0) + 1
        if (attempt >= maxGapAttempts) {
            quarantine(
                inboxId,
                envelope,
                protectedQuarantineEnvelope,
                "SOURCE_VERSION_GAP_EXHAUSTED",
                attempt,
            )
            return PurchaseHandleResult(
                PurchaseHandleStatus.QUARANTINED,
                "SOURCE_VERSION_GAP_EXHAUSTED",
            )
        }
        val multiplier = 1L shl minOf(attempt - 1, 20)
        val delay = minOf(initialGapBackoff.multipliedBy(multiplier), maxGapBackoff)
        val replayAfter = clock.instant().plus(delay)
        eventRepository.markWaitingGap(inboxId, attempt, replayAfter)
        return PurchaseHandleResult(
            PurchaseHandleStatus.WAITING_GAP,
            "SOURCE_VERSION_GAP",
            replayAfter = replayAfter,
        )
    }

    /**
     * 잘못되거나 순서가 복구되지 않은 임상·환불 사실의 암호화 원문과 감사 기록을
     * 남깁니다.
     *
     * inbox terminal 상태와 quarantine row는 caller-owned transaction에서 함께
     * commit되므로, 운영 redrive가 원 `eventId`와 보호된 원문을 잃지 않습니다.
     */
    private fun quarantine(
        inboxId: Long,
        envelope: TrustedSchedulingEventEnvelope<TreatmentFulfillmentEvent>,
        protectedQuarantineEnvelope: ProtectedQuarantineEnvelope,
        reasonCode: String,
        attemptCount: Int? = null,
    ) {
        val detectedAt = clock.instant()
        eventRepository.markQuarantined(inboxId, reasonCode, detectedAt, attemptCount)
        quarantineRepository.recordDetected(
            QuarantineDetection(
                eventId = envelope.eventId,
                eventType = envelope.eventType,
                protectedEnvelope = protectedQuarantineEnvelope,
                producer = envelope.producer,
                sourceAuthority = envelope.payload.sourcePurchaseAuthority,
                schemaVersion = envelope.schemaVersion,
                sourceAggregateId = envelope.payload.sourceAggregateId,
                sourceAggregateVersion = envelope.payload.sourceAggregateVersion,
                tenantGroupId = envelope.payload.tenantGroupId,
                clinicId = envelope.payload.clinicId,
                reasonCode = reasonCode,
                detectedAt = detectedAt,
                correlationId = envelope.correlationId,
                retentionClass = QuarantineRetentionClass.STANDARD,
                payloadExpiresAt = detectedAt.plus(quarantineRetention),
            ),
        )
    }

    private fun derivePlanStatus(
        currentStatus: AppointmentPlanStatus,
        treatments: List<PlanRevisionTreatmentRecord>,
    ): AppointmentPlanStatus {
        val hasPending = treatments.any { it.status == PlanTreatmentStatus.PENDING }
        val hasCompleted = treatments.any { it.status == PlanTreatmentStatus.COMPLETED }
        val hasCancelled = treatments.any { it.status == PlanTreatmentStatus.CANCELLED }
        return when {
            hasPending && (hasCompleted || currentStatus == AppointmentPlanStatus.PARTIALLY_FULFILLED) ->
                AppointmentPlanStatus.PARTIALLY_FULFILLED
            hasPending -> AppointmentPlanStatus.ACTIVE
            hasCancelled -> AppointmentPlanStatus.CANCELLED
            else -> AppointmentPlanStatus.FULFILLED
        }
    }

    private fun findPlanAndLock(payload: TreatmentFulfillmentEvent): AppointmentPlanAggregateRecord? {
        val validClinic = Clinics.selectAll()
            .where {
                (Clinics.id eq payload.clinicId) and
                    (Clinics.tenantGroupId eq payload.tenantGroupId)
            }
            .count() == 1L
        if (!validClinic) return null
        val rootExists = AppointmentPlans.selectAll()
            .where {
                (AppointmentPlans.tenantGroupId eq payload.tenantGroupId) and
                    (AppointmentPlans.clinicId eq payload.clinicId) and
                    (AppointmentPlans.sourcePurchaseAuthority eq payload.sourcePurchaseAuthority) and
                    (AppointmentPlans.sourcePurchaseId eq payload.sourcePurchaseId)
            }
            .forUpdate()
            .singleOrNull() != null
        if (!rootExists) return null
        return planRepository.findBySourcePurchaseAndTenantClinic(
            payload.sourcePurchaseAuthority,
            payload.sourcePurchaseId,
            payload.tenantGroupId,
            payload.clinicId,
        )
    }

    private data class FulfillmentProjection(
        val treatments: List<PlanRevisionTreatmentRecord>,
        val dependencies: List<PlanRevisionDependencyRecord>,
        val groupingConstraints: List<PlanRevisionGroupingConstraintRecord>,
        val changedTreatmentKeys: Set<String>,
        val dirtyTreatmentKeys: Set<String>,
        val effectiveAtByTreatmentKey: Map<String, Instant>,
        val resourceDisruptions: List<TreatmentFulfillmentFact>,
    )
}
