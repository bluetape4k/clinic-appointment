package io.bluetape4k.clinic.appointment.event.integration

import io.bluetape4k.clinic.appointment.model.dto.AppointmentPlanAggregateRecord
import io.bluetape4k.clinic.appointment.model.dto.AppointmentPlanRevisionAggregateRecord
import io.bluetape4k.clinic.appointment.model.dto.PlanRevisionDependencyRecord
import io.bluetape4k.clinic.appointment.model.dto.PlanRevisionGroupingConstraintRecord
import io.bluetape4k.clinic.appointment.model.dto.PlanRevisionTreatmentRecord
import io.bluetape4k.clinic.appointment.model.plan.AppointmentPlanRevision
import io.bluetape4k.clinic.appointment.model.plan.AppointmentPlanRevisionDraft
import io.bluetape4k.clinic.appointment.model.plan.PlanTreatmentStatus
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlans
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.repository.AppointmentPlanRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentPlanRevisionRepository
import io.bluetape4k.clinic.appointment.service.PackageExecutionPlanner
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Clock
import java.time.Duration
import kotlin.math.pow

/**
 * revision header, child, outbox가 같은 transaction에 저장된 직후 실행되는 진단 hook입니다.
 *
 * 구현체는 외부 I/O를 수행하면 안 되며 예외를 던지면 inbox, revision, outbox 전체가
 * rollback됩니다.
 */
fun interface AtomicPlanRevisionWriteObserver {
    /**
     * revision header, child row, outbox가 저장된 직후 같은 transaction 안에서 호출되는
     * test/diagnostic hook입니다. 구현체는 외부 I/O를 수행하면 안 됩니다.
     */
    fun afterRevisionSaved(revisionId: Long)

    companion object {
        val NOOP = AtomicPlanRevisionWriteObserver { }
    }
}

/**
 * payload를 label에 포함하지 않고 처리 결과와 안정 reason code만 기록합니다.
 */
fun interface VisitPlanningMetrics {
    /**
     * 한 event의 최종 처리 결과를 기록합니다.
     *
     * @param result [PurchaseHandleStatus] 이름입니다.
     * @param reason payload나 예외 메시지가 아닌 안정 reason code이며 성공이면 `null`입니다.
     */
    fun record(result: String, reason: String?)

    companion object {
        val NOOP = VisitPlanningMetrics { _, _ -> }
    }
}

/**
 * 실행 BOM integration event를 기존 구매 Plan의 불변 visit-planning revision으로 수렴시킵니다.
 *
 * 이 handler는 purchase-created writer가 아닙니다. [AppointmentPlanRepository]에서
 * source purchase scope로 기존 Plan을 찾고, [PackageExecutionPlanner]가 검증한 실행
 * snapshot을 [AppointmentPlanRevisionRepository]에 append합니다. `WRITE`에서는 inbox,
 * revision header/children, redacted outbox, processed marker를 하나의 Exposed
 * transaction에서 처리해 부분 commit을 남기지 않습니다.
 *
 * replay 정책은 source aggregate version과 canonical payload hash를 함께 사용합니다.
 * 같은 event replay는 terminal duplicate로 끝나고, 같은 source version과 같은 hash의
     * 다른 event replay는 revision을 늘리지 않습니다. 같은 version의 다른 hash, stale version,
     * 기존 Plan 부재는 원본 payload를 로그에 쓰지 않고 quarantine으로 수렴합니다.
 * source version gap은 bounded backoff로 대기한 뒤 retry 상한에서만 quarantine합니다.
 *
 * @param eventRepository caller transaction을 공유하는 redacted inbox/outbox repository입니다.
 * @param quarantineRepository 암호화된 원문 참조와 안정 reason을 보존하는 repository입니다.
 * @param planRepository source purchase scope로 기존 Plan을 찾는 repository입니다.
 * @param planner 외부 실행 BOM을 재해석하지 않고 구조와 provenance를 검증하는 planner입니다.
 * @param revisionRepository 불변 revision header와 child를 append하는 repository입니다.
 * @param versionVerifier source aggregate 순서와 authority proof를 판정합니다.
 * @param clock retry 및 terminal 시각의 UTC clock입니다.
 * @param mode `OFF`, 무쓰기 `SHADOW`, 원자적 `WRITE` 처리 모드입니다.
 */
class VisitPlanningEventHandler(
    private val eventRepository: SchedulingEventRepository,
    private val quarantineRepository: SchedulingQuarantineRepository,
    private val planRepository: AppointmentPlanRepository,
    private val planner: PackageExecutionPlanner,
    private val revisionRepository: AppointmentPlanRevisionRepository,
    private val versionVerifier: SourceAggregateVersionVerifier,
    private val clock: Clock,
    private val mode: PurchaseHandlingMode,
    private val maxGapAttempts: Int = 5,
    private val initialGapBackoff: Duration = Duration.ofMinutes(1),
    private val maxGapBackoff: Duration = Duration.ofHours(1),
    private val gapJitter: Double = 0.20,
    private val quarantineRetention: Duration = Duration.ofDays(30),
    private val writeObserver: AtomicPlanRevisionWriteObserver = AtomicPlanRevisionWriteObserver.NOOP,
    private val metrics: VisitPlanningMetrics = VisitPlanningMetrics.NOOP,
    private val rejectionRepository: UntrustedSchedulingEventRejectionRepository =
        UntrustedSchedulingEventRejectionRepository(),
) {
    companion object : KLogging()

    init {
        require(maxGapAttempts > 0) { "maxGapAttempts must be positive" }
        require(!initialGapBackoff.isNegative && !initialGapBackoff.isZero) {
            "initialGapBackoff must be positive"
        }
        require(maxGapBackoff >= initialGapBackoff) {
            "maxGapBackoff must not be less than initialGapBackoff"
        }
        require(gapJitter in 0.0..1.0) { "gapJitter must be between 0 and 1" }
        require(!quarantineRetention.isNegative && !quarantineRetention.isZero) {
            "quarantineRetention must be positive"
        }
    }

    /**
     * trusted 실행 BOM event 하나를 처리합니다.
     *
     * `SHADOW`는 동일한 Plan lookup과 planner 검증을 수행하지만 inbox, revision, outbox,
     * quarantine row를 만들지 않습니다. `WRITE`는 성공과 실패 모두 transaction-local
     * repository만 사용하며 raw execution snapshot이나 암호화 payload를 로그에 남기지 않습니다.
     *
     * @param envelope ingress trust gate를 통과한 실행 BOM envelope입니다.
     * @param protectedQuarantineEnvelope 원문 대신 quarantine에 저장할 암호화 참조입니다.
     * @param versionProof gap을 source authority가 확인한 경우의 선택적 증거입니다.
     * @return 생성, replay, 대기, 격리, shadow/off 중 최종 수렴 결과입니다.
     */
    fun handle(
        envelope: TrustedSchedulingEventEnvelope<PackageExecutionEvent>,
        protectedQuarantineEnvelope: ProtectedQuarantineEnvelope,
        versionProof: SourceAuthorityVersionProof? = null,
    ): PurchaseHandleResult {
        PackageExecutionEventBounds.validate(envelope)
        val handled = when (mode) {
            PurchaseHandlingMode.OFF ->
                PurchaseHandleResult(PurchaseHandleStatus.OFF, "CONSUMER_OFF")
            PurchaseHandlingMode.SHADOW ->
                preview(envelope)
            PurchaseHandlingMode.WRITE ->
                try {
                    transaction {
                        convergeInTransaction(envelope, protectedQuarantineEnvelope, versionProof)
                    }
                } catch (failure: ExposedSQLException) {
                    if (!failure.isConstraintConflict()) throw failure
                    retryAfterConstraintRace(envelope, protectedQuarantineEnvelope, versionProof)
                }
        }
        metrics.record(handled.status.name, handled.reasonCode)
        log.info {
            "Visit planning event handled: eventId=${envelope.eventId}, " +
                "correlationId=${envelope.correlationId}, producer=${envelope.producer}, " +
                "schemaVersion=${envelope.schemaVersion}, " +
                "sourceAggregateVersion=${envelope.payload.sourceAggregateVersion}, " +
                "tenantGroupId=${envelope.payload.tenantGroupId}, clinicId=${envelope.payload.clinicId}, " +
                "result=${handled.status}, reasonCode=${handled.reasonCode}"
        }
        return handled
    }

    private fun preview(
        envelope: TrustedSchedulingEventEnvelope<PackageExecutionEvent>,
    ): PurchaseHandleResult =
        transaction {
            val existingInbox = eventRepository.findInbox(envelope.eventId)
            if (existingInbox != null && existingInbox.status != SchedulingInboxStatus.WAITING_GAP) {
                return@transaction PurchaseHandleResult(PurchaseHandleStatus.DUPLICATE, "EVENT_ALREADY_TERMINAL")
            }
            if (rejectionRepository.exists(envelope.eventId)) {
                return@transaction PurchaseHandleResult(PurchaseHandleStatus.DUPLICATE, "EVENT_ALREADY_TERMINAL")
            }
            val payload = envelope.payload
            if (!clinicBelongsToTenant(payload.tenantGroupId, payload.clinicId)) {
                return@transaction PurchaseHandleResult(PurchaseHandleStatus.QUARANTINED, "TENANT_CLINIC_MISMATCH")
            }
            val existingPlan = findPlan(payload)
                ?: return@transaction PurchaseHandleResult(
                    PurchaseHandleStatus.QUARANTINED,
                    "APPOINTMENT_PLAN_NOT_FOUND",
                )
            if (payload.executionSnapshot.packageProductId != existingPlan.plan.productId) {
                return@transaction PurchaseHandleResult(
                    PurchaseHandleStatus.QUARANTINED,
                    "PACKAGE_EXECUTION_PRODUCT_MISMATCH",
                )
            }
            try {
                planner.plan(payload.executionSnapshot)
            } catch (_: IllegalArgumentException) {
                return@transaction PurchaseHandleResult(
                    PurchaseHandleStatus.QUARANTINED,
                    "PACKAGE_EXECUTION_INVALID",
                )
            }
            PurchaseHandleResult(
                status = PurchaseHandleStatus.SHADOW,
                reasonCode = "WOULD_CREATE_PLAN_REVISION",
                planId = existingPlan.plan.id,
            )
        }

    private fun convergeInTransaction(
        envelope: TrustedSchedulingEventEnvelope<PackageExecutionEvent>,
        protectedQuarantineEnvelope: ProtectedQuarantineEnvelope,
        versionProof: SourceAuthorityVersionProof?,
    ): PurchaseHandleResult {
        val existingInbox = eventRepository.findInbox(envelope.eventId)
        if (existingInbox != null && existingInbox.status != SchedulingInboxStatus.WAITING_GAP) {
            return PurchaseHandleResult(PurchaseHandleStatus.DUPLICATE, "EVENT_ALREADY_TERMINAL")
        }
        val payload = envelope.payload
        if (rejectionRepository.exists(envelope.eventId)) {
            return PurchaseHandleResult(PurchaseHandleStatus.DUPLICATE, "EVENT_ALREADY_TERMINAL")
        }
        if (!clinicBelongsToTenant(payload.tenantGroupId, payload.clinicId)) {
            rejectionRepository.record(
                UntrustedEventRejection(
                    eventId = envelope.eventId,
                    eventType = envelope.eventType,
                    producer = envelope.producer,
                    sourceAuthority = payload.sourcePurchaseAuthority,
                    sourceAggregateId = payload.sourceAggregateId,
                    sourceAggregateVersion = payload.sourceAggregateVersion,
                    claimedTenantGroupId = payload.tenantGroupId,
                    claimedClinicId = payload.clinicId,
                    schemaVersion = envelope.schemaVersion,
                    correlationId = envelope.correlationId,
                    reasonCode = "TENANT_CLINIC_MISMATCH",
                    envelopeHash = protectedQuarantineEnvelope.envelopeHash,
                    detectedAt = clock.instant(),
                )
            )
            return PurchaseHandleResult(PurchaseHandleStatus.QUARANTINED, "TENANT_CLINIC_MISMATCH")
        }
        val inboxId = existingInbox?.id ?: eventRepository.insertReceivedPackageExecution(envelope)

        val existingPlan = findPlan(payload, lockRoot = true)
        if (existingPlan == null) {
            quarantine(inboxId, envelope, protectedQuarantineEnvelope, "APPOINTMENT_PLAN_NOT_FOUND")
            return PurchaseHandleResult(PurchaseHandleStatus.QUARANTINED, "APPOINTMENT_PLAN_NOT_FOUND")
        }
        if (payload.executionSnapshot.packageProductId != existingPlan.plan.productId) {
            quarantine(inboxId, envelope, protectedQuarantineEnvelope, "PACKAGE_EXECUTION_PRODUCT_MISMATCH")
            return PurchaseHandleResult(
                PurchaseHandleStatus.QUARANTINED,
                "PACKAGE_EXECUTION_PRODUCT_MISMATCH",
            )
        }

        val localVersion = eventRepository.latestProcessedSourceVersion(
            tenantGroupId = payload.tenantGroupId,
            clinicId = payload.clinicId,
            producer = envelope.producer,
            sourceAuthority = payload.sourcePurchaseAuthority,
            sourceAggregateId = payload.sourceAggregateId,
        )
        when (versionVerifier.verify(envelope.producer, payload, localVersion, versionProof)) {
            SourceVersionDecision.STALE_OR_DUPLICATE ->
                return convergeStaleOrDuplicate(inboxId, envelope, protectedQuarantineEnvelope)
            SourceVersionDecision.WAITING_GAP ->
                return waitForGap(
                    inboxId = inboxId,
                    existingInbox = existingInbox,
                    envelope = envelope,
                    protectedQuarantineEnvelope = protectedQuarantineEnvelope,
                )
            SourceVersionDecision.ACCEPT -> Unit
        }

        val draft = try {
            planner.plan(payload.executionSnapshot)
        } catch (_: IllegalArgumentException) {
            quarantine(inboxId, envelope, protectedQuarantineEnvelope, "PACKAGE_EXECUTION_INVALID")
            return PurchaseHandleResult(PurchaseHandleStatus.QUARANTINED, "PACKAGE_EXECUTION_INVALID")
        }
        val planId = checkNotNull(existingPlan.plan.id) {
            "persisted appointment plan must have an identity"
        }
        val active = revisionRepository.findActive(planId)
        val nextRevision = (active?.revision?.revision ?: 0L) + 1L
        val persisted = revisionRepository.append(draft.toAggregate(existingPlan, nextRevision, active == null))
        if (active != null) {
            check(revisionRepository.activate(planId, active.revision.id, persisted.revision.id)) {
                "plan revision activation did not preserve the locked active revision"
            }
        }
        eventRepository.insertPlanRevisionCreatedOutbox(
            envelope = envelope,
            planId = planId,
            revisionId = persisted.revision.id,
            revision = persisted.revision.revision,
        )
        writeObserver.afterRevisionSaved(persisted.revision.id)
        eventRepository.markProcessed(inboxId, clock.instant())
        return PurchaseHandleResult(PurchaseHandleStatus.CREATED, planId = planId)
    }

    private fun convergeStaleOrDuplicate(
        inboxId: Long,
        envelope: TrustedSchedulingEventEnvelope<PackageExecutionEvent>,
        protectedQuarantineEnvelope: ProtectedQuarantineEnvelope,
    ): PurchaseHandleResult {
        val payload = envelope.payload
        val versionMatch = eventRepository.classifyProcessedSourceVersion(
            tenantGroupId = payload.tenantGroupId,
            clinicId = payload.clinicId,
            producer = envelope.producer,
            sourceAuthority = payload.sourcePurchaseAuthority,
            sourceAggregateId = payload.sourceAggregateId,
            sourceAggregateVersion = payload.sourceAggregateVersion,
            payloadHash = envelope.payloadHash,
        )
        return when (versionMatch) {
            SchedulingSourceVersionMatch.SAME_HASH -> {
                eventRepository.markProcessed(inboxId, clock.instant(), "SOURCE_VERSION_REPLAY")
                PurchaseHandleResult(PurchaseHandleStatus.DUPLICATE, "SOURCE_VERSION_REPLAY")
            }
            SchedulingSourceVersionMatch.NOT_FOUND -> {
                quarantine(inboxId, envelope, protectedQuarantineEnvelope, "STALE_SOURCE_VERSION")
                PurchaseHandleResult(PurchaseHandleStatus.QUARANTINED, "STALE_SOURCE_VERSION")
            }
            SchedulingSourceVersionMatch.DIFFERENT_HASH -> {
                quarantine(inboxId, envelope, protectedQuarantineEnvelope, "SOURCE_VERSION_HASH_CONFLICT")
                PurchaseHandleResult(PurchaseHandleStatus.QUARANTINED, "SOURCE_VERSION_HASH_CONFLICT")
            }
        }
    }

    private fun retryAfterConstraintRace(
        envelope: TrustedSchedulingEventEnvelope<PackageExecutionEvent>,
        protectedQuarantineEnvelope: ProtectedQuarantineEnvelope,
        versionProof: SourceAuthorityVersionProof?,
    ): PurchaseHandleResult =
        transaction {
            convergeInTransaction(envelope, protectedQuarantineEnvelope, versionProof)
        }

    private fun findPlan(
        payload: PackageExecutionEvent,
        lockRoot: Boolean = false,
    ): AppointmentPlanAggregateRecord? {
        if (lockRoot) {
            val rootExists = AppointmentPlans
                .selectAll()
                .where {
                    (AppointmentPlans.tenantGroupId eq payload.tenantGroupId) and
                        (AppointmentPlans.clinicId eq payload.clinicId) and
                        (AppointmentPlans.sourcePurchaseAuthority eq payload.sourcePurchaseAuthority) and
                        (AppointmentPlans.sourcePurchaseId eq payload.sourcePurchaseId)
                }
                .forUpdate()
                .singleOrNull() != null
            if (!rootExists) return null
        }
        return planRepository.findBySourcePurchaseAndTenantClinic(
            sourcePurchaseAuthority = payload.sourcePurchaseAuthority,
            sourcePurchaseId = payload.sourcePurchaseId,
            tenantGroupId = payload.tenantGroupId,
            clinicId = payload.clinicId,
        )
    }

    private fun waitForGap(
        inboxId: Long,
        existingInbox: SchedulingInboxRecord?,
        envelope: TrustedSchedulingEventEnvelope<PackageExecutionEvent>,
        protectedQuarantineEnvelope: ProtectedQuarantineEnvelope,
    ): PurchaseHandleResult {
        val attempt = (existingInbox?.attemptCount ?: 0) + 1
        if (attempt >= maxGapAttempts) {
            quarantine(
                inboxId = inboxId,
                envelope = envelope,
                protectedQuarantineEnvelope = protectedQuarantineEnvelope,
                reasonCode = "SOURCE_VERSION_GAP_EXHAUSTED",
                attemptCount = attempt,
            )
            return PurchaseHandleResult(PurchaseHandleStatus.QUARANTINED, "SOURCE_VERSION_GAP_EXHAUSTED")
        }
        val replayAfter = clock.instant().plus(gapBackoff(envelope.eventId, attempt))
        eventRepository.markWaitingGap(inboxId, attempt, replayAfter)
        return PurchaseHandleResult(
            status = PurchaseHandleStatus.WAITING_GAP,
            reasonCode = "SOURCE_VERSION_GAP",
            replayAfter = replayAfter,
        )
    }

    private fun gapBackoff(eventId: String, attempt: Int): Duration {
        val baseSeconds = initialGapBackoff.seconds * 2.0.pow((attempt - 1).toDouble())
        val capped = minOf(baseSeconds, maxGapBackoff.seconds.toDouble())
        val normalizedHash = (eventId.hashCode().toLong() and 0x7fffffff) / Int.MAX_VALUE.toDouble()
        val multiplier = 1.0 + ((normalizedHash * 2.0) - 1.0) * gapJitter
        return Duration.ofMillis((capped * multiplier * 1_000.0).toLong())
    }

    private fun AppointmentPlanRevisionDraft.toAggregate(
        plan: AppointmentPlanAggregateRecord,
        revision: Long,
        active: Boolean,
    ): AppointmentPlanRevisionAggregateRecord {
        val planId = checkNotNull(plan.plan.id) {
            "persisted appointment plan must have an identity"
        }
        return AppointmentPlanRevisionAggregateRecord(
            revision = AppointmentPlanRevision(
                planId = planId,
                revision = revision,
                productVersionId = packageProductVersionId,
                snapshotHash = sourceSnapshotHash,
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
            groupingConstraints = visitGroupingConstraints.map { constraint ->
                PlanRevisionGroupingConstraintRecord(
                    firstTreatmentKey = constraint.firstTreatmentKey,
                    secondTreatmentKey = constraint.secondTreatmentKey,
                    type = constraint.type,
                )
            },
        )
    }

    private fun quarantine(
        inboxId: Long,
        envelope: TrustedSchedulingEventEnvelope<PackageExecutionEvent>,
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
            )
        )
    }

    private fun clinicBelongsToTenant(tenantGroupId: Long, clinicId: Long): Boolean =
        Clinics.selectAll()
            .where {
                (Clinics.id eq clinicId) and
                    (Clinics.tenantGroupId eq tenantGroupId)
            }
            .count() == 1L

    private fun ExposedSQLException.isConstraintConflict(): Boolean =
        sqlState.startsWith("23")
}
