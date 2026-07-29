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
import io.bluetape4k.clinic.appointment.model.plan.AppointmentPlanRevisionDraft
import io.bluetape4k.clinic.appointment.model.plan.PlanTreatment
import io.bluetape4k.clinic.appointment.model.plan.PlanTreatmentStatus
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlans
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.repository.AppointmentOperationalExceptionRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentPlanRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentPlanRevisionRepository
import io.bluetape4k.clinic.appointment.service.PackageExecutionPlanner
import io.bluetape4k.clinic.appointment.service.ProductVersionMigrationPlanner
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Clock
import java.time.Duration

/**
 * 승인된 상품 version 전환과 고객 일정 변경 거부를 예약 Plan에 수렴시킵니다.
 *
 * 전환 성공 시 기존 구매와 같은 Plan에 새 immutable revision을 append하고 활성화합니다.
 * 완료 항목은 기존 revision에 남겨 provenance를 수정하지 않습니다. 이 handler는
 * appointment, commitment, allocation을 갱신하지 않으므로 확정 일정은 별도 proposal과
 * 고객 동의 없이는 바뀌지 않습니다.
 *
 * 전환표·동의·from version·목표 실행 BOM이 맞지 않으면 활성 revision을 그대로 두고
 * encrypted quarantine과 redacted `ProductVersionMigrationRejected` outbox를 만듭니다.
 * 모든 storage 효과는 caller가 아닌 handler가 연 하나의 Exposed transaction에서
 * commit되거나 rollback됩니다.
 */
class ProductVersionMigrationHandler(
    private val eventRepository: SchedulingEventRepository,
    private val quarantineRepository: SchedulingQuarantineRepository,
    private val planRepository: AppointmentPlanRepository,
    private val executionPlanner: PackageExecutionPlanner,
    private val migrationPlanner: ProductVersionMigrationPlanner,
    private val revisionRepository: AppointmentPlanRevisionRepository,
    private val operationalExceptionRepository: AppointmentOperationalExceptionRepository,
    private val versionVerifier: SourceAggregateVersionVerifier,
    private val clock: Clock,
    private val quarantineRetention: Duration = Duration.ofDays(30),
    private val maximumConsentAge: Duration = Duration.ofDays(30),
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
        require(!maximumConsentAge.isNegative && !maximumConsentAge.isZero) {
            "maximumConsentAge must be positive"
        }
    }

    companion object : KLogging()

    /**
     * trusted migration event 하나를 처리합니다.
     *
     * @return 새 revision 생성, replay, 또는 격리 결과입니다. 업무 validation 실패는
     * exception을 외부로 던지지 않고 안정 reason code로 격리합니다.
     */
    internal fun handle(
        envelope: TrustedSchedulingEventEnvelope<ProductVersionMigrationApprovedEvent>,
        protectedQuarantineEnvelope: ProtectedQuarantineEnvelope,
        versionProof: SourceAuthorityVersionProof? = null,
    ): PurchaseHandleResult {
        val result = transaction {
            val payload = envelope.payload
            val plan = findPlanAndLock(
                payload.tenantGroupId,
                payload.clinicId,
                payload.sourcePurchaseAuthority,
                payload.sourcePurchaseId,
            )
            val existing = eventRepository.findInbox(envelope.eventId)
            if (existing != null && existing.status != SchedulingInboxStatus.WAITING_GAP) {
                return@transaction PurchaseHandleResult(
                    PurchaseHandleStatus.DUPLICATE,
                    "EVENT_ALREADY_TERMINAL",
                )
            }
            val inboxId = existing?.id ?: eventRepository.insertReceivedProductVersionMigration(envelope)
            if (plan == null) {
                quarantine(
                    inboxId,
                    envelope,
                    protectedQuarantineEnvelope,
                    "APPOINTMENT_PLAN_NOT_FOUND",
                    null,
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

                    SchedulingSourceVersionMatch.DIFFERENT_HASH ->
                        return@transaction reject(
                            inboxId,
                            envelope,
                            protectedQuarantineEnvelope,
                            planId,
                            "SOURCE_VERSION_HASH_CONFLICT",
                        )

                    SchedulingSourceVersionMatch.NOT_FOUND ->
                        return@transaction reject(
                            inboxId,
                            envelope,
                            protectedQuarantineEnvelope,
                            planId,
                            "STALE_SOURCE_VERSION",
                        )
                }

                SourceVersionDecision.WAITING_GAP ->
                    return@transaction waitForMigrationGap(
                        inboxId = inboxId,
                        existingInbox = existing,
                        envelope = envelope,
                        protectedQuarantineEnvelope = protectedQuarantineEnvelope,
                    )

                SourceVersionDecision.ACCEPT -> Unit
            }
            val active = revisionRepository.findActive(planId)
            if (active == null || active.revision.productVersionId != payload.fromProductVersionId) {
                return@transaction reject(
                    inboxId,
                    envelope,
                    protectedQuarantineEnvelope,
                    planId,
                    "PRODUCT_VERSION_MISMATCH",
                )
            }
            if (!validConsent(payload, envelope.occurredAt)) {
                return@transaction reject(
                    inboxId,
                    envelope,
                    protectedQuarantineEnvelope,
                    planId,
                    "CONSENT_SUBJECT_MISMATCH",
                )
            }
            val draft = try {
                validateAndPlan(payload, plan, active.treatments.map {
                    PlanTreatment(it.treatmentKey, it.productVersionId, it.status)
                })
            } catch (_: IllegalArgumentException) {
                return@transaction reject(
                    inboxId,
                    envelope,
                    protectedQuarantineEnvelope,
                    planId,
                    "BOM_MAPPING_INVALID",
                )
            }
            val nextRevision = active.revision.revision + 1L
            val persisted = revisionRepository.append(
                draft.toAggregate(
                    planId = planId,
                    revisionNumber = nextRevision,
                    active = false,
                    authoritySnapshotHash = envelope.payloadHash,
                ),
            )
            check(revisionRepository.activate(planId, active.revision.id, persisted.revision.id)) {
                "migration revision activation lost its locked compare-and-set invariant"
            }
            eventRepository.insertExternalPlanFactOutbox(
                inboundEventId = envelope.eventId,
                correlationId = envelope.correlationId,
                eventType = "ProductVersionMigrationApplied",
                tenantGroupId = payload.tenantGroupId,
                clinicId = payload.clinicId,
                planId = planId,
                revisionId = persisted.revision.id,
                reasonCode = null,
                changedTreatmentKeys = persisted.treatments.mapTo(linkedSetOf()) {
                    it.treatmentKey
                },
                sourceFactReference = payload.migrationId,
                sourceFactHash = payload.mappingHash,
                evidenceReferenceHash = payload.consent.evidenceReferenceHash,
            )
            eventRepository.markProcessed(inboxId, clock.instant())
            PurchaseHandleResult(PurchaseHandleStatus.CREATED, planId = planId)
        }
        log.info {
            "Product version migration handled: eventId=${envelope.eventId}, " +
                "correlationId=${envelope.correlationId}, tenantGroupId=${envelope.payload.tenantGroupId}, " +
                "clinicId=${envelope.payload.clinicId}, result=${result.status}, reasonCode=${result.reasonCode}"
        }
        return result
    }

    /**
     * 고객의 새 일정 거부를 기존 예약 변경 없이 운영 handoff로 기록합니다.
     */
    internal fun handleRescheduleDeclined(
        envelope: TrustedSchedulingEventEnvelope<ProductVersionMigrationRescheduleDeclinedEvent>,
        protectedQuarantineEnvelope: ProtectedQuarantineEnvelope,
        versionProof: SourceAuthorityVersionProof? = null,
    ): PurchaseHandleResult {
        val result = transaction {
            val payload = envelope.payload
            val plan = findPlanAndLock(
                payload.tenantGroupId,
                payload.clinicId,
                payload.sourcePurchaseAuthority,
                payload.sourcePurchaseId,
            )
            val existing = eventRepository.findInbox(envelope.eventId)
            if (existing != null && existing.status != SchedulingInboxStatus.WAITING_GAP) {
                return@transaction PurchaseHandleResult(
                    PurchaseHandleStatus.DUPLICATE,
                    "EVENT_ALREADY_TERMINAL",
                )
            }
            val inboxId = existing?.id ?: eventRepository.insertReceivedMigrationDecline(envelope)
            if (plan == null) {
                quarantineDecline(
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
                SourceVersionDecision.STALE_OR_DUPLICATE -> {
                    val versionMatch = eventRepository.classifyProcessedSourceVersion(
                        tenantGroupId = payload.tenantGroupId,
                        clinicId = payload.clinicId,
                        producer = envelope.producer,
                        sourceAuthority = payload.sourcePurchaseAuthority,
                        sourceAggregateId = payload.sourceAggregateId,
                        sourceAggregateVersion = payload.sourceAggregateVersion,
                        payloadHash = envelope.payloadHash,
                    )
                    return@transaction when (versionMatch) {
                        SchedulingSourceVersionMatch.SAME_HASH -> {
                            eventRepository.markProcessed(inboxId, clock.instant(), "SOURCE_VERSION_REPLAY")
                            PurchaseHandleResult(
                                PurchaseHandleStatus.DUPLICATE,
                                "SOURCE_VERSION_REPLAY",
                                planId,
                            )
                        }
                        SchedulingSourceVersionMatch.DIFFERENT_HASH -> {
                            quarantineDecline(
                                inboxId,
                                envelope,
                                protectedQuarantineEnvelope,
                                "SOURCE_VERSION_HASH_CONFLICT",
                            )
                            PurchaseHandleResult(
                                PurchaseHandleStatus.QUARANTINED,
                                "SOURCE_VERSION_HASH_CONFLICT",
                                planId,
                            )
                        }
                        SchedulingSourceVersionMatch.NOT_FOUND -> {
                            quarantineDecline(
                                inboxId,
                                envelope,
                                protectedQuarantineEnvelope,
                                "STALE_SOURCE_VERSION",
                            )
                            PurchaseHandleResult(
                                PurchaseHandleStatus.QUARANTINED,
                                "STALE_SOURCE_VERSION",
                                planId,
                            )
                        }
                    }
                }
                SourceVersionDecision.WAITING_GAP ->
                    return@transaction waitForDeclineGap(
                        inboxId,
                        existing,
                        envelope,
                        protectedQuarantineEnvelope,
                    )
                SourceVersionDecision.ACCEPT -> Unit
            }
            operationalExceptionRepository.append(
                AppointmentOperationalException(
                    appointmentPlanId = planId,
                    appointmentId = payload.appointmentId,
                    type = AppointmentOperationalExceptionType.CUSTOMER_DECLINED_RESCHEDULE,
                    reasonCode = payload.reasonCode,
                    status = AppointmentOperationalExceptionStatus.OPEN,
                    openedAt = clock.instant(),
                    resolvedAt = null,
                ),
            )
            eventRepository.insertExternalPlanFactOutbox(
                inboundEventId = envelope.eventId,
                correlationId = envelope.correlationId,
                eventType = "CustomerRescheduleDeclined",
                tenantGroupId = payload.tenantGroupId,
                clinicId = payload.clinicId,
                planId = planId,
                revisionId = null,
                reasonCode = payload.reasonCode,
            )
            eventRepository.markProcessed(inboxId, clock.instant())
            PurchaseHandleResult(PurchaseHandleStatus.CREATED, planId = planId)
        }
        log.info {
            "Migration reschedule decline handled: eventId=${envelope.eventId}, " +
                "correlationId=${envelope.correlationId}, result=${result.status}, " +
                "reasonCode=${result.reasonCode}"
        }
        return result
    }

    private fun validateAndPlan(
        payload: ProductVersionMigrationApprovedEvent,
        plan: AppointmentPlanAggregateRecord,
        currentTreatments: List<PlanTreatment>,
    ): AppointmentPlanRevisionDraft {
        require(payload.mappingHash == ProductVersionMigrationPayloadHasher.mappingHash(payload.mappings)) {
            "mappingHash does not match canonical mappings"
        }
        require(payload.targetExecutionSnapshot.packageProductId == plan.plan.productId) {
            "target package product does not match Plan product"
        }
        require(payload.targetExecutionSnapshot.packageProductVersionId == payload.toProductVersionId) {
            "target execution snapshot version does not match migration target"
        }
        val migration = migrationPlanner.migrate(
            currentTreatments = currentTreatments,
            mappings = payload.mappings,
            targetProductVersionId = payload.toProductVersionId,
        )
        val draft = executionPlanner.plan(payload.targetExecutionSnapshot)
        require(
            migration.futureTreatments.map(PlanTreatment::treatmentKey).toSet() ==
                draft.treatments.map { it.treatmentKey }.toSet(),
        ) {
            "migration targets must exactly match target execution snapshot"
        }
        return draft
    }

    private fun validConsent(
        payload: ProductVersionMigrationApprovedEvent,
        eventOccurredAt: java.time.Instant,
    ): Boolean =
        payload.consent.migrationId == payload.migrationId &&
            payload.consent.fromProductVersionId == payload.fromProductVersionId &&
            payload.consent.toProductVersionId == payload.toProductVersionId &&
            payload.consent.mappingHash == payload.mappingHash &&
            !payload.consent.consentedAt.isAfter(eventOccurredAt) &&
            !payload.consent.consentedAt.isBefore(eventOccurredAt.minus(maximumConsentAge))

    private fun reject(
        inboxId: Long,
        envelope: TrustedSchedulingEventEnvelope<ProductVersionMigrationApprovedEvent>,
        protectedQuarantineEnvelope: ProtectedQuarantineEnvelope,
        planId: Long,
        reasonCode: String,
    ): PurchaseHandleResult {
        quarantine(
            inboxId,
            envelope,
            protectedQuarantineEnvelope,
            reasonCode,
            planId,
        )
        return PurchaseHandleResult(PurchaseHandleStatus.QUARANTINED, reasonCode, planId)
    }

    private fun quarantine(
        inboxId: Long,
        envelope: TrustedSchedulingEventEnvelope<ProductVersionMigrationApprovedEvent>,
        protectedQuarantineEnvelope: ProtectedQuarantineEnvelope,
        reasonCode: String,
        planId: Long?,
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
        planId?.let {
            eventRepository.insertExternalPlanFactOutbox(
                inboundEventId = envelope.eventId,
                correlationId = envelope.correlationId,
                eventType = "ProductVersionMigrationRejected",
                tenantGroupId = envelope.payload.tenantGroupId,
                clinicId = envelope.payload.clinicId,
                planId = it,
                revisionId = null,
                reasonCode = reasonCode,
            )
        }
    }

    private fun waitForMigrationGap(
        inboxId: Long,
        existingInbox: SchedulingInboxRecord?,
        envelope: TrustedSchedulingEventEnvelope<ProductVersionMigrationApprovedEvent>,
        protectedQuarantineEnvelope: ProtectedQuarantineEnvelope,
    ): PurchaseHandleResult {
        val attempt = (existingInbox?.attemptCount ?: 0) + 1
        if (attempt >= maxGapAttempts) {
            quarantine(
                inboxId,
                envelope,
                protectedQuarantineEnvelope,
                "SOURCE_VERSION_GAP_EXHAUSTED",
                null,
                attempt,
            )
            return PurchaseHandleResult(
                PurchaseHandleStatus.QUARANTINED,
                "SOURCE_VERSION_GAP_EXHAUSTED",
            )
        }
        val replayAfter = clock.instant().plus(gapBackoff(attempt))
        eventRepository.markWaitingGap(inboxId, attempt, replayAfter)
        return PurchaseHandleResult(
            PurchaseHandleStatus.WAITING_GAP,
            "SOURCE_VERSION_GAP",
            replayAfter = replayAfter,
        )
    }

    private fun waitForDeclineGap(
        inboxId: Long,
        existingInbox: SchedulingInboxRecord?,
        envelope: TrustedSchedulingEventEnvelope<ProductVersionMigrationRescheduleDeclinedEvent>,
        protectedQuarantineEnvelope: ProtectedQuarantineEnvelope,
    ): PurchaseHandleResult {
        val attempt = (existingInbox?.attemptCount ?: 0) + 1
        if (attempt >= maxGapAttempts) {
            quarantineDecline(
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
        val replayAfter = clock.instant().plus(gapBackoff(attempt))
        eventRepository.markWaitingGap(inboxId, attempt, replayAfter)
        return PurchaseHandleResult(
            PurchaseHandleStatus.WAITING_GAP,
            "SOURCE_VERSION_GAP",
            replayAfter = replayAfter,
        )
    }

    private fun gapBackoff(attempt: Int): Duration {
        val multiplier = 1L shl minOf(attempt - 1, 20)
        val candidate = initialGapBackoff.multipliedBy(multiplier)
        return minOf(candidate, maxGapBackoff)
    }

    /**
     * 고객 일정 거부 사실도 migration과 같은 암호화 원문·감사 기록으로 격리합니다.
     *
     * 이 경로는 기존 확정 예약, 활성 revision, 운영 예외를 만들거나 변경하지 않습니다.
     */
    private fun quarantineDecline(
        inboxId: Long,
        envelope: TrustedSchedulingEventEnvelope<ProductVersionMigrationRescheduleDeclinedEvent>,
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

    private fun findPlanAndLock(
        tenantGroupId: Long,
        clinicId: Long,
        sourcePurchaseAuthority: String,
        sourcePurchaseId: String,
    ): AppointmentPlanAggregateRecord? {
        val validClinic = Clinics.selectAll()
            .where {
                (Clinics.id eq clinicId) and
                    (Clinics.tenantGroupId eq tenantGroupId)
            }
            .count() == 1L
        if (!validClinic) return null
        val rootExists = AppointmentPlans.selectAll()
            .where {
                (AppointmentPlans.tenantGroupId eq tenantGroupId) and
                    (AppointmentPlans.clinicId eq clinicId) and
                    (AppointmentPlans.sourcePurchaseAuthority eq sourcePurchaseAuthority) and
                    (AppointmentPlans.sourcePurchaseId eq sourcePurchaseId)
            }
            .forUpdate()
            .singleOrNull() != null
        if (!rootExists) return null
        return planRepository.findBySourcePurchaseAndTenantClinic(
            sourcePurchaseAuthority,
            sourcePurchaseId,
            tenantGroupId,
            clinicId,
        )
    }

    private fun AppointmentPlanRevisionDraft.toAggregate(
        planId: Long,
        revisionNumber: Long,
        active: Boolean,
        authoritySnapshotHash: String,
    ): AppointmentPlanRevisionAggregateRecord =
        AppointmentPlanRevisionAggregateRecord(
            revision = AppointmentPlanRevision(
                planId = planId,
                revision = revisionNumber,
                productVersionId = packageProductVersionId,
                snapshotHash = authoritySnapshotHash,
                active = active,
            ),
            treatments = treatments.map { treatment ->
                PlanRevisionTreatmentRecord(
                    treatmentKey = treatment.treatmentKey,
                    componentProductId = treatment.componentProductId,
                    componentProductVersionId = treatment.componentProductVersionId,
                    productVersionId = packageProductVersionId,
                    status = PlanTreatmentStatus.PENDING,
                    sourceBomItemId = treatment.sourceBomItemId,
                    sequence = treatment.sequence,
                    representativeTreatmentName = treatment.representativeTreatmentName,
                    detailedTreatmentCodes = treatment.detailedTreatmentCodes,
                    preparationMinutes = treatment.preparationMinutes,
                    treatmentMinutes = treatment.treatmentMinutes,
                    recoveryMinutes = treatment.recoveryMinutes,
                    practitionerQualifications = treatment.practitionerQualifications,
                    equipmentTypes = treatment.equipmentTypes,
                    spaceCapabilities = treatment.spaceCapabilities,
                )
            },
            dependencies = dependencies.map { dependency ->
                PlanRevisionDependencyRecord(
                    predecessorTreatmentKey = dependency.predecessorTreatmentKey,
                    successorTreatmentKey = dependency.successorTreatmentKey,
                    type = dependency.type,
                    minimumIntervalDays = dependency.minimumIntervalDays,
                    preferredIntervalDays = dependency.preferredIntervalDays,
                    maximumIntervalDays = dependency.maximumIntervalDays,
                )
            },
            groupingConstraints = visitGroupingConstraints.map { grouping ->
                PlanRevisionGroupingConstraintRecord(
                    firstTreatmentKey = grouping.firstTreatmentKey,
                    secondTreatmentKey = grouping.secondTreatmentKey,
                    type = grouping.type,
                )
            },
        )
}
