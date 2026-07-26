package io.bluetape4k.clinic.appointment.event.integration

import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.repository.AppointmentPlanRepository
import io.bluetape4k.clinic.appointment.repository.ProductCatalogRepository
import io.bluetape4k.clinic.appointment.service.AppointmentPlanFactory
import io.bluetape4k.clinic.appointment.service.AppointmentPlanFactoryInput
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

enum class PurchaseHandlingMode {
    OFF,
    SHADOW,
    WRITE,
}

enum class PurchaseHandleStatus {
    CREATED,
    DUPLICATE,
    STALE,
    WAITING_GAP,
    QUARANTINED,
    SHADOW,
    OFF,
}

data class PurchaseHandleResult(
    val status: PurchaseHandleStatus,
    val reasonCode: String? = null,
    val planId: Long? = null,
    val replayAfter: java.time.Instant? = null,
)

fun interface AtomicPlanWriteObserver {
    /**
     * Transaction-local test/diagnostic hook. Implementations must not perform external I/O.
     */
    fun afterPlanSaved(planId: Long)

    companion object {
        val NOOP = AtomicPlanWriteObserver { }
    }
}

fun interface PurchasePlanMetrics {
    fun record(result: String, reason: String?)

    companion object {
        val NOOP = PurchasePlanMetrics { _, _ -> }
    }
}

fun interface InboxInsertObserver {
    /**
     * Transaction-local diagnostic hook used to exercise read-before-insert races.
     */
    fun beforeInsert(eventId: String)

    companion object {
        val NOOP = InboxInsertObserver { }
    }
}

fun interface PurchaseTransactionObserver {
    /**
     * Transaction-local diagnostic hook invoked before the first happy-path read.
     * Implementations must not perform external I/O.
     */
    fun afterTransactionStarted()

    companion object {
        val NOOP = PurchaseTransactionObserver { }
    }
}

class PurchaseCompletedHandler(
    private val eventRepository: SchedulingEventRepository,
    private val quarantineRepository: SchedulingQuarantineRepository,
    private val catalogRepository: ProductCatalogRepository,
    private val planRepository: AppointmentPlanRepository,
    private val planFactory: AppointmentPlanFactory,
    private val versionVerifier: SourceAggregateVersionVerifier,
    private val clock: Clock,
    private val mode: PurchaseHandlingMode,
    private val maxAttempts: Int = 5,
    private val initialBackoff: Duration = Duration.ofSeconds(5),
    private val maxBackoff: Duration = Duration.ofMinutes(5),
    private val jitter: Double = 0.20,
    private val quarantineRetention: Duration = Duration.ofDays(30),
    private val writeObserver: AtomicPlanWriteObserver = AtomicPlanWriteObserver.NOOP,
    private val metrics: PurchasePlanMetrics = PurchasePlanMetrics.NOOP,
    private val inboxInsertObserver: InboxInsertObserver = InboxInsertObserver.NOOP,
    private val transactionObserver: PurchaseTransactionObserver = PurchaseTransactionObserver.NOOP,
) {
    companion object : KLogging()

    init {
        require(maxAttempts > 0)
        require(!initialBackoff.isNegative && !initialBackoff.isZero)
        require(maxBackoff >= initialBackoff)
        require(jitter in 0.0..1.0)
        require(!quarantineRetention.isNegative && !quarantineRetention.isZero)
    }

    fun handle(
        envelope: TrustedSchedulingEventEnvelope<PurchaseCompletedEvent>,
        versionProof: SourceAuthorityVersionProof?,
        protectedPatientReference: ProtectedPatientReference,
        protectedQuarantineEnvelope: ProtectedQuarantineEnvelope,
    ): PurchaseHandleResult {
        PurchaseEventBounds.validate(envelope)
        val handled = when (mode) {
            PurchaseHandlingMode.OFF ->
                result(PurchaseHandleStatus.OFF, "CONSUMER_OFF")

            PurchaseHandlingMode.SHADOW -> {
                evaluateShadow(envelope, protectedPatientReference)
                result(PurchaseHandleStatus.SHADOW, "SHADOW_NO_WRITE")
            }

            PurchaseHandlingMode.WRITE ->
                try {
                    transaction {
                        transactionObserver.afterTransactionStarted()
                        convergeInTransaction(
                            envelope,
                            versionProof,
                            protectedPatientReference,
                            protectedQuarantineEnvelope,
                        )
                    }
                } catch (failure: ExposedSQLException) {
                    if (!failure.isConstraintConflict()) throw failure
                    reconcileConstraintRace(envelope, protectedPatientReference, protectedQuarantineEnvelope)
                }
        }
        metrics.record(handled.status.name, handled.reasonCode)
        log.info {
            "Purchase plan event handled: eventId=${envelope.eventId}, " +
                "correlationId=${envelope.correlationId}, producer=${envelope.producer}, " +
                "schemaVersion=${envelope.schemaVersion}, " +
                "sourceAggregateVersion=${envelope.payload.sourceAggregateVersion}, " +
                "tenantGroupId=${envelope.payload.tenantGroupId}, clinicId=${envelope.payload.clinicId}, " +
                "result=${handled.status}, reasonCode=${handled.reasonCode}"
            }
        return handled
    }

    /**
     * Persists only bounded retry state after the external source-authority
     * adapter times out or opens its circuit. No plan-write transaction starts.
     */
    fun stageAuthorityUnavailable(
        envelope: TrustedSchedulingEventEnvelope<PurchaseCompletedEvent>,
        failureReason: SourceAuthorityFailureReason,
        protectedQuarantineEnvelope: ProtectedQuarantineEnvelope,
    ): PurchaseHandleResult {
        PurchaseEventBounds.validate(envelope)
        val handled = try {
            transaction {
                maxAttempts = 1
                val existingInbox = eventRepository.findInbox(envelope.eventId)
                if (existingInbox != null && existingInbox.status != SchedulingInboxStatus.WAITING_GAP) {
                    return@transaction result(PurchaseHandleStatus.DUPLICATE, "EVENT_ALREADY_TERMINAL")
                }
                val inboxId = existingInbox?.id ?: run {
                    inboxInsertObserver.beforeInsert(envelope.eventId)
                    eventRepository.insertReceived(envelope)
                }
                waitForGap(
                    inboxId = inboxId,
                    existingInbox = existingInbox,
                    eventId = envelope.eventId,
                    waitingReason = failureReason.reasonCode,
                    exhaustedReason = "${failureReason.reasonCode}_EXHAUSTED",
                    envelope = envelope,
                    protectedQuarantineEnvelope = protectedQuarantineEnvelope,
                )
            }
        } catch (failure: ExposedSQLException) {
            if (!failure.isConstraintConflict()) throw failure
            reconcileInboxRace(envelope.eventId)
        }
        metrics.record(handled.status.name, handled.reasonCode)
        return handled
    }

    fun quarantineRejectedEnvelope(
        envelope: TrustedSchedulingEventEnvelope<PurchaseCompletedEvent>,
        protectedQuarantineEnvelope: ProtectedQuarantineEnvelope,
        reasonCode: String,
    ): PurchaseHandleResult {
        PurchaseEventBounds.validate(envelope)
        val handled = try {
            transaction {
                maxAttempts = 1
                eventRepository.findInbox(envelope.eventId)?.let {
                    return@transaction result(PurchaseHandleStatus.DUPLICATE, "EVENT_ALREADY_TERMINAL")
                }
                inboxInsertObserver.beforeInsert(envelope.eventId)
                val inboxId = eventRepository.insertReceived(envelope)
                quarantine(inboxId, envelope, protectedQuarantineEnvelope, reasonCode)
                result(PurchaseHandleStatus.QUARANTINED, reasonCode)
            }
        } catch (failure: ExposedSQLException) {
            if (!failure.isConstraintConflict()) throw failure
            reconcileInboxRace(envelope.eventId)
        }
        metrics.record(handled.status.name, handled.reasonCode)
        return handled
    }

    private fun convergeInTransaction(
        envelope: TrustedSchedulingEventEnvelope<PurchaseCompletedEvent>,
        versionProof: SourceAuthorityVersionProof?,
        protectedPatientReference: ProtectedPatientReference,
        protectedQuarantineEnvelope: ProtectedQuarantineEnvelope,
    ): PurchaseHandleResult {
        val existingInbox = eventRepository.findInbox(envelope.eventId)
        if (existingInbox != null && existingInbox.status != SchedulingInboxStatus.WAITING_GAP) {
            return result(PurchaseHandleStatus.DUPLICATE, "EVENT_ALREADY_TERMINAL")
        }

        val payload = envelope.payload
        val inboxId = existingInbox?.id ?: run {
            inboxInsertObserver.beforeInsert(envelope.eventId)
            eventRepository.insertReceived(envelope)
        }
        if (!clinicBelongsToTenant(payload.tenantGroupId, payload.clinicId)) {
            quarantine(inboxId, envelope, protectedQuarantineEnvelope, "TENANT_CLINIC_MISMATCH")
            return result(PurchaseHandleStatus.QUARANTINED, "TENANT_CLINIC_MISMATCH")
        }

        val existingPlan = planRepository.findBySourcePurchaseAndTenantClinic(
            sourcePurchaseAuthority = payload.sourcePurchaseAuthority,
            sourcePurchaseId = payload.sourcePurchaseId,
            tenantGroupId = payload.tenantGroupId,
            clinicId = payload.clinicId,
        )
        if (existingPlan != null) {
            return convergeExistingPurchase(
                inboxId,
                existingPlan.plan,
                envelope,
                protectedPatientReference,
                protectedQuarantineEnvelope,
            )
        }

        val localVersion = eventRepository.latestProcessedSourceVersion(
            envelope.producer,
            payload.sourceAggregateId,
        )
        when (versionVerifier.verify(payload, localVersion, versionProof)) {
            SourceVersionDecision.STALE_OR_DUPLICATE -> {
                eventRepository.markProcessed(inboxId, clock.instant(), "STALE_SOURCE_VERSION")
                return result(PurchaseHandleStatus.STALE, "STALE_SOURCE_VERSION")
            }
            SourceVersionDecision.WAITING_GAP ->
                return waitForGap(
                    inboxId = inboxId,
                    existingInbox = existingInbox,
                    eventId = envelope.eventId,
                    envelope = envelope,
                    protectedQuarantineEnvelope = protectedQuarantineEnvelope,
                )
            SourceVersionDecision.ACCEPT -> Unit
        }

        val catalog = catalogRepository.findByScopeVersion(
            tenantGroupId = payload.tenantGroupId,
            clinicId = payload.clinicId,
            sourceAuthority = payload.catalogSourceAuthority,
            productId = payload.productId,
            catalogVersion = payload.catalogVersion,
        )
        if (catalog == null) {
            quarantine(inboxId, envelope, protectedQuarantineEnvelope, "CATALOG_VERSION_UNAVAILABLE")
            return result(PurchaseHandleStatus.QUARANTINED, "CATALOG_VERSION_UNAVAILABLE")
        }
        if (catalog.definition.status ==
            io.bluetape4k.clinic.appointment.model.catalog.CatalogProjectionStatus.RETIRED
        ) {
            quarantine(inboxId, envelope, protectedQuarantineEnvelope, "CATALOG_RETIRED")
            return result(PurchaseHandleStatus.QUARANTINED, "CATALOG_RETIRED")
        }

        val draft = planFactory.create(
            catalog = catalog,
            input = AppointmentPlanFactoryInput(
                sourcePurchaseAuthority = payload.sourcePurchaseAuthority,
                sourcePurchaseId = payload.sourcePurchaseId,
                patientReferenceCiphertext = protectedPatientReference.ciphertext,
                patientReferenceKeyId = protectedPatientReference.keyId,
                patientReferenceFingerprint = protectedPatientReference.fingerprint,
                bookingPreference = payload.bookingPreference,
            ),
        )
        val saved = planRepository.saveAggregate(draft)
        val planId = requireNotNull(saved.plan.id)
        writeObserver.afterPlanSaved(planId)
        eventRepository.insertPlanCreatedOutbox(envelope, planId)
        eventRepository.markProcessed(inboxId, clock.instant())
        return result(PurchaseHandleStatus.CREATED, planId = planId)
    }

    private fun convergeExistingPurchase(
        inboxId: Long,
        existing: io.bluetape4k.clinic.appointment.model.dto.AppointmentPlanRecord,
        envelope: TrustedSchedulingEventEnvelope<PurchaseCompletedEvent>,
        protectedPatientReference: ProtectedPatientReference,
        protectedQuarantineEnvelope: ProtectedQuarantineEnvelope,
    ): PurchaseHandleResult {
        val payload = envelope.payload
        val immutableOwnershipMatches =
            existing.tenantGroupId == payload.tenantGroupId &&
                existing.clinicId == payload.clinicId &&
                existing.catalogSourceAuthority == payload.catalogSourceAuthority &&
                existing.productId == payload.productId &&
                existing.catalogVersion == payload.catalogVersion &&
                existing.patientReferenceFingerprint == protectedPatientReference.fingerprint
        return if (immutableOwnershipMatches) {
            eventRepository.markProcessed(inboxId, clock.instant(), "PURCHASE_ALREADY_PLANNED")
            result(PurchaseHandleStatus.DUPLICATE, "PURCHASE_ALREADY_PLANNED", existing.id)
        } else {
            quarantine(inboxId, envelope, protectedQuarantineEnvelope, "PURCHASE_OWNERSHIP_CONFLICT")
            result(PurchaseHandleStatus.QUARANTINED, "PURCHASE_OWNERSHIP_CONFLICT")
        }
    }

    private fun waitForGap(
        inboxId: Long,
        existingInbox: SchedulingInboxRecord?,
        eventId: String,
        waitingReason: String = "SOURCE_VERSION_GAP",
        exhaustedReason: String = "SOURCE_VERSION_GAP_EXHAUSTED",
        envelope: TrustedSchedulingEventEnvelope<PurchaseCompletedEvent>,
        protectedQuarantineEnvelope: ProtectedQuarantineEnvelope,
    ): PurchaseHandleResult {
        val attempt = (existingInbox?.attemptCount ?: 0) + 1
        if (attempt >= maxAttempts) {
            quarantine(
                inboxId = inboxId,
                envelope = envelope,
                protectedQuarantineEnvelope = protectedQuarantineEnvelope,
                reasonCode = exhaustedReason,
                attemptCount = attempt,
            )
            return result(PurchaseHandleStatus.QUARANTINED, exhaustedReason)
        }
        val replayAfter = clock.instant().plus(backoff(eventId, attempt))
        eventRepository.markWaitingGap(inboxId, attempt, replayAfter, waitingReason)
        return result(PurchaseHandleStatus.WAITING_GAP, waitingReason, replayAfter = replayAfter)
    }

    private fun backoff(eventId: String, attempt: Int): Duration {
        val baseSeconds = initialBackoff.seconds * 2.0.pow((attempt - 1).toDouble())
        val capped = minOf(baseSeconds, maxBackoff.seconds.toDouble())
        val normalizedHash = (eventId.hashCode().toLong() and 0x7fffffff) / Int.MAX_VALUE.toDouble()
        val multiplier = 1.0 + ((normalizedHash * 2.0) - 1.0) * jitter
        return Duration.ofMillis((capped * multiplier * 1_000.0).toLong())
    }

    private fun evaluateShadow(
        envelope: TrustedSchedulingEventEnvelope<PurchaseCompletedEvent>,
        protectedPatientReference: ProtectedPatientReference,
    ) {
        transaction {
            val payload = envelope.payload
            if (!clinicBelongsToTenant(payload.tenantGroupId, payload.clinicId)) return@transaction
            val catalog = catalogRepository.findByScopeVersion(
                tenantGroupId = payload.tenantGroupId,
                clinicId = payload.clinicId,
                sourceAuthority = payload.catalogSourceAuthority,
                productId = payload.productId,
                catalogVersion = payload.catalogVersion,
            ) ?: return@transaction
            planFactory.create(
                catalog,
                AppointmentPlanFactoryInput(
                    sourcePurchaseAuthority = payload.sourcePurchaseAuthority,
                    sourcePurchaseId = payload.sourcePurchaseId,
                    patientReferenceCiphertext = protectedPatientReference.ciphertext,
                    patientReferenceKeyId = protectedPatientReference.keyId,
                    patientReferenceFingerprint = protectedPatientReference.fingerprint,
                    bookingPreference = payload.bookingPreference,
                ),
            )
        }
    }

    private fun reconcileConstraintRace(
        envelope: TrustedSchedulingEventEnvelope<PurchaseCompletedEvent>,
        protectedPatientReference: ProtectedPatientReference,
        protectedQuarantineEnvelope: ProtectedQuarantineEnvelope,
    ): PurchaseHandleResult =
        transaction {
            eventRepository.findInbox(envelope.eventId)?.let {
                return@transaction result(PurchaseHandleStatus.DUPLICATE, "EVENT_RACE_CONVERGED")
            }
            val payload = envelope.payload
            val existingPlan = planRepository.findBySourcePurchaseAndTenantClinic(
                sourcePurchaseAuthority = payload.sourcePurchaseAuthority,
                sourcePurchaseId = payload.sourcePurchaseId,
                tenantGroupId = payload.tenantGroupId,
                clinicId = payload.clinicId,
            ) ?: throw IllegalStateException("Constraint conflict could not be classified")
            val inboxId = eventRepository.insertReceived(envelope)
            convergeExistingPurchase(
                inboxId,
                existingPlan.plan,
                envelope,
                protectedPatientReference,
                protectedQuarantineEnvelope,
            )
        }

    private fun reconcileInboxRace(eventId: String): PurchaseHandleResult =
        transaction {
            val existingInbox = requireNotNull(eventRepository.findInbox(eventId)) {
                "Inbox constraint conflict could not be classified: $eventId"
            }
            if (existingInbox.status == SchedulingInboxStatus.WAITING_GAP) {
                result(
                    status = PurchaseHandleStatus.WAITING_GAP,
                    reason = existingInbox.failureCode,
                    replayAfter = existingInbox.replayAfter,
                )
            } else {
                result(PurchaseHandleStatus.DUPLICATE, "EVENT_RACE_CONVERGED")
            }
        }

    private fun quarantine(
        inboxId: Long,
        envelope: TrustedSchedulingEventEnvelope<PurchaseCompletedEvent>,
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

    private fun result(
        status: PurchaseHandleStatus,
        reason: String? = null,
        planId: Long? = null,
        replayAfter: java.time.Instant? = null,
    ): PurchaseHandleResult =
        PurchaseHandleResult(status, reason, planId, replayAfter)

    private fun ExposedSQLException.isConstraintConflict(): Boolean =
        sqlState.startsWith("23")
}
