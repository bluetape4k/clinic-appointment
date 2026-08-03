package io.bluetape4k.clinic.appointment.service.waitlist

import io.bluetape4k.clinic.appointment.model.waitlist.ActorRef
import io.bluetape4k.clinic.appointment.model.waitlist.CorrelationId
import io.bluetape4k.clinic.appointment.model.waitlist.HoldScopeMismatch
import io.bluetape4k.clinic.appointment.model.waitlist.NoEligibleCandidate
import io.bluetape4k.clinic.appointment.model.waitlist.OfferScopeMismatch
import io.bluetape4k.clinic.appointment.model.waitlist.OfferStateConflict
import io.bluetape4k.clinic.appointment.model.waitlist.OutboxWriteFailed
import io.bluetape4k.clinic.appointment.model.waitlist.VacancyGenerationConflict
import io.bluetape4k.clinic.appointment.model.waitlist.VacancyJobState
import io.bluetape4k.clinic.appointment.model.waitlist.VacancyLeaseFenced
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistCapacityHoldState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistEntryState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistOfferEventRecord
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistOfferState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistReasonCode
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistScope
import io.bluetape4k.clinic.appointment.repository.ResourceAllocationRepository
import io.bluetape4k.clinic.appointment.repository.waitlist.VacancyClaim
import io.bluetape4k.clinic.appointment.repository.waitlist.WaitlistDeliveryRepository
import io.bluetape4k.clinic.appointment.repository.waitlist.WaitlistRepository
import java.time.Clock
import java.time.Instant

/**
 * durable vacancy claim 하나를 offer/hold/notification/job terminal 결과로 수렴시킵니다.
 *
 * 이 서비스는 transaction을 열지 않습니다. 호출자는 claim부터 notification enqueue와
 * terminal fence까지를 하나의 Exposed transaction으로 감싸야 하며, enqueue 실패를 바깥으로
 * 전파해 offer/hold와 processing job이 함께 rollback되도록 해야 합니다.
 */
class WaitlistDeliveryService(
    private val deliveryRepository: WaitlistDeliveryRepository,
    private val offerService: WaitlistOfferService,
    private val notificationPort: WaitlistOfferNotificationPort = NoopWaitlistOfferNotificationPort,
    private val resourceAllocationRepository: ResourceAllocationRepository = ResourceAllocationRepository(),
    private val waitlistRepository: WaitlistRepository = WaitlistRepository(),
    private val clock: Clock = Clock.systemUTC(),
) {
    /** lease를 재검증한 뒤 한 vacancy generation만 처리합니다. */
    fun process(
        claim: VacancyClaim,
        now: Instant = clock.instant(),
        correlationId: CorrelationId = CorrelationId("vacancy:${claim.jobId}"),
        actorRef: ActorRef = ActorRef("SYSTEM"),
    ): WaitlistDeliveryResult {
        deliveryRepository.requireValidFence(claim, now)
        val job = deliveryRepository.lockVacancy(claim, now)
        if (now >= job.vacancyStartsAt || now >= job.vacancyEndsAt) {
            if (!deliveryRepository.markExpired(claim, now)) {
                throw VacancyLeaseFenced()
            }
            return WaitlistDeliveryResult.Expired(job.id)
        }

        val vacancy = job.toVacancyDescriptor(now)
        val created = try {
            offerService.selectAndOfferDetailed(vacancy, correlationId, actorRef)
        } catch (_: NoEligibleCandidate) {
            if (!deliveryRepository.completeNoCandidate(claim, now)) {
                throw VacancyLeaseFenced()
            }
            return WaitlistDeliveryResult.NoCandidate(job.id)
        }

        val draft = WaitlistOfferNotificationDraft.of(
            created = created,
            reasonCode = WaitlistReasonCode("OFFER_CREATED"),
            correlationId = correlationId,
            occurredAt = now,
        )
        try {
            notificationPort.enqueue(draft)
        } catch (failure: OutboxWriteFailed) {
            throw failure
        } catch (failure: Exception) {
            throw OutboxWriteFailed(failure)
        }

        if (!deliveryRepository.completeOffer(claim, now, created.offerId)) {
            throw VacancyLeaseFenced()
        }
        return WaitlistDeliveryResult.Offered(
            offerId = created.offerId,
            holdId = created.holdId,
            vacancyJobId = job.id,
        )
    }

    /** expired offer와 hold를 terminal 처리하고 slot이 아직 유효하면 N+1 generation을 만듭니다. */
    fun expireOffer(
        scope: WaitlistScope,
        offerId: Long,
        now: Instant = clock.instant(),
        correlationId: CorrelationId = CorrelationId("offer-expiry:$offerId"),
        actorRef: ActorRef = ActorRef("recovery:waitlist-expiry"),
    ): WaitlistGenerationProgression =
        terminalizeOffer(
            scope = scope,
            offerId = offerId,
            now = now,
            terminalOfferState = WaitlistOfferState.EXPIRED,
            terminalHoldState = WaitlistCapacityHoldState.EXPIRED,
            terminalEntryState = WaitlistEntryState.EXPIRED,
            reasonCode = WaitlistReasonCode("OFFER_EXPIRED"),
            correlationId = correlationId,
            actorRef = actorRef,
            requireExpired = true,
        )

    /** active offer를 철회하고 slot이 유효하면 다음 candidate generation을 만듭니다. */
    fun withdrawOffer(
        scope: WaitlistScope,
        offerId: Long,
        now: Instant = clock.instant(),
        reasonCode: WaitlistReasonCode = WaitlistReasonCode("ENTRY_WITHDRAWN"),
        correlationId: CorrelationId = CorrelationId("offer-withdraw:$offerId"),
        actorRef: ActorRef = ActorRef("staff:waitlist-withdraw"),
    ): WaitlistGenerationProgression =
        terminalizeOffer(
            scope = scope,
            offerId = offerId,
            now = now,
            terminalOfferState = WaitlistOfferState.WITHDRAWN,
            terminalHoldState = WaitlistCapacityHoldState.RELEASED,
            terminalEntryState = WaitlistEntryState.WITHDRAWN,
            reasonCode = reasonCode,
            correlationId = correlationId,
            actorRef = actorRef,
            requireExpired = false,
        )

    /** staff decline을 별도 terminal reason으로 기록하는 convenience alias입니다. */
    fun declineOffer(
        scope: WaitlistScope,
        offerId: Long,
        now: Instant = clock.instant(),
        reasonCode: WaitlistReasonCode = WaitlistReasonCode("OFFER_DECLINED"),
        correlationId: CorrelationId = CorrelationId("offer-decline:$offerId"),
        actorRef: ActorRef = ActorRef("staff:waitlist-decline"),
    ): WaitlistGenerationProgression =
        terminalizeOffer(
            scope = scope,
            offerId = offerId,
            now = now,
            terminalOfferState = WaitlistOfferState.DECLINED,
            terminalHoldState = WaitlistCapacityHoldState.RELEASED,
            terminalEntryState = WaitlistEntryState.DECLINED,
            reasonCode = reasonCode,
            correlationId = correlationId,
            actorRef = actorRef,
            requireExpired = false,
        )

    private fun terminalizeOffer(
        scope: WaitlistScope,
        offerId: Long,
        now: Instant,
        terminalOfferState: WaitlistOfferState,
        terminalHoldState: WaitlistCapacityHoldState,
        terminalEntryState: WaitlistEntryState,
        reasonCode: WaitlistReasonCode,
        correlationId: CorrelationId,
        actorRef: ActorRef,
        requireExpired: Boolean,
    ): WaitlistGenerationProgression {
        val offer = waitlistRepository.findOfferForUpdate(scope, offerId)
            ?: throw OfferScopeMismatch(offerId)
        val entry = waitlistRepository.findEntryForUpdate(scope, offer.waitlistEntryId)
            ?: throw OfferScopeMismatch(offerId)
        val hold = waitlistRepository.findHoldByOfferForUpdate(scope, offer.id)
            ?: throw HoldScopeMismatch(offer.id)

        if (requireExpired && now < minOf(offer.expiresAt, offer.startsAt, hold.holdExpiresAt)) {
            return currentProgression(scope, offer.vacancyKey, reasonCode)
        }
        if (offer.status.isActive != hold.status.isActive) {
            throw OfferStateConflict(offer.id)
        }
        if (!offer.status.isActive && !hold.status.isActive) {
            if (!entry.status.isTerminal) {
                throw OfferStateConflict(offer.id)
            }
            return progressGeneration(scope, offer.vacancyKey, now, reasonCode)
        }
        if (offer.status.isActive && hold.status.isActive) {
            val expectedEntryState = when (offer.status) {
                WaitlistOfferState.OFFERED -> WaitlistEntryState.OFFERED
                WaitlistOfferState.ACCEPTED -> WaitlistEntryState.ACCEPTED
                else -> throw OfferStateConflict(offer.id)
            }
            if (entry.status != expectedEntryState) {
                throw OfferStateConflict(offer.id)
            }
            resourceAllocationRepository.lockWaitlistHoldResource(hold)
            if (!resourceAllocationRepository.releaseWaitlistCapacityHold(
                    scope = scope,
                    holdId = hold.id,
                    terminal = terminalHoldState,
                    releasedAt = now,
                )
            ) {
                throw IllegalStateException("WAITLIST_HOLD_RELEASE_FENCED")
            }
            if (!waitlistRepository.casOffer(
                    scope = scope,
                    offerId = offer.id,
                    expectedVersion = offer.version,
                    from = offer.status,
                    to = terminalOfferState,
                    now = now,
                )
            ) {
                return progressGeneration(scope, offer.vacancyKey, now, reasonCode)
            }
            if (!waitlistRepository.casEntry(
                    scope = scope,
                    entryId = entry.id,
                    expectedVersion = entry.version,
                    from = entry.status,
                    to = terminalEntryState,
                    now = now,
                )
            ) {
                throw IllegalStateException("WAITLIST_ENTRY_TERMINAL_FENCED")
            }
            waitlistRepository.appendEvent(
                WaitlistOfferEventRecord(
                    waitlistEntryId = entry.id,
                    offerId = offer.id,
                    holdId = hold.id,
                    fromState = entry.status,
                    toState = terminalEntryState,
                    reasonCode = reasonCode,
                    actorRef = actorRef,
                    correlationId = correlationId,
                    occurredAt = now,
                    eventVersion = entry.version + 1L,
                ),
            )
        }
        return progressGeneration(scope, offer.vacancyKey, now, reasonCode)
    }

    /** 아직 만료되지 않은 offer에 대한 recovery tick은 durable state를 건드리지 않습니다. */
    private fun currentProgression(
        scope: WaitlistScope,
        vacancyKey: String,
        reasonCode: WaitlistReasonCode,
    ): WaitlistGenerationProgression {
        val current = deliveryRepository.findVacancy(scope.tenantGroupId, scope.clinicId, vacancyKey)
            ?: deliveryRepository.findTerminalVacancy(scope.tenantGroupId, scope.clinicId, vacancyKey)
            ?: throw VacancyGenerationConflict()
        return WaitlistGenerationProgression(
            previousGeneration = current.vacancyGeneration,
            nextGeneration = null,
            reasonCode = reasonCode,
            vacancyJobId = current.id,
        )
    }

    private fun progressGeneration(
        scope: WaitlistScope,
        vacancyKey: String,
        now: Instant,
        reasonCode: WaitlistReasonCode,
    ): WaitlistGenerationProgression {
        val previous = deliveryRepository.findTerminalVacancy(scope.tenantGroupId, scope.clinicId, vacancyKey)
            ?: deliveryRepository.findVacancy(scope.tenantGroupId, scope.clinicId, vacancyKey)
            ?: throw VacancyGenerationConflict()
        val slotValid = now < previous.vacancyStartsAt && now < previous.vacancyEndsAt
        val closed = deliveryRepository.terminalizeForProgress(
            jobId = previous.id,
            now = now,
            status = if (slotValid) VacancyJobState.NO_CANDIDATE else VacancyJobState.EXPIRED,
        ) ?: throw VacancyGenerationConflict()
        if (!slotValid) {
            return WaitlistGenerationProgression(
                previousGeneration = closed.vacancyGeneration,
                nextGeneration = null,
                reasonCode = reasonCode,
                vacancyJobId = closed.id,
            )
        }
        val next = deliveryRepository.nextGeneration(closed.id, now)
        return WaitlistGenerationProgression(
            previousGeneration = closed.vacancyGeneration,
            nextGeneration = next.vacancyGeneration,
            reasonCode = reasonCode,
            vacancyJobId = closed.id,
        )
    }
}

private fun io.bluetape4k.clinic.appointment.repository.waitlist.VacancyJobRecord.toVacancyDescriptor(
    now: Instant,
) = io.bluetape4k.clinic.appointment.model.waitlist.VacancyDescriptor(
    tenantGroupId = tenantGroupId,
    clinicId = clinicId,
    treatmentTypeId = treatmentTypeId,
    doctorId = doctorId,
    startsAt = vacancyStartsAt,
    endsAt = vacancyEndsAt,
    resourceType = resourceType,
    resourceId = resourceId,
    capacityUnits = capacityUnits,
    maximumCapacity = maximumCapacity,
    now = now,
)
