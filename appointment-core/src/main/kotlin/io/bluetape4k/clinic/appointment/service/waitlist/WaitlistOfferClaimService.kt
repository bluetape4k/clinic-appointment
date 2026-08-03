package io.bluetape4k.clinic.appointment.service.waitlist

import io.bluetape4k.clinic.appointment.model.waitlist.ClaimWaitlistOfferCommand
import io.bluetape4k.clinic.appointment.model.waitlist.DecisionStale
import io.bluetape4k.clinic.appointment.model.waitlist.DecisionUnavailable
import io.bluetape4k.clinic.appointment.model.waitlist.DecisionStamp
import io.bluetape4k.clinic.appointment.model.waitlist.HoldScopeMismatch
import io.bluetape4k.clinic.appointment.model.waitlist.NewHold
import io.bluetape4k.clinic.appointment.model.waitlist.OfferClaimed
import io.bluetape4k.clinic.appointment.model.waitlist.OfferExpired
import io.bluetape4k.clinic.appointment.model.waitlist.OfferScopeMismatch
import io.bluetape4k.clinic.appointment.model.waitlist.OfferStateConflict
import io.bluetape4k.clinic.appointment.model.waitlist.OfferWithdrawn
import io.bluetape4k.clinic.appointment.model.waitlist.ReleaseWaitlistOfferCommand
import io.bluetape4k.clinic.appointment.model.waitlist.VersionConflict
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistCapacityHoldRecord
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistCapacityHoldState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistEntryRecord
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistEntryState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistOfferEventRecord
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistOfferRecord
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistOfferState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistReasonCode
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistScope
import io.bluetape4k.clinic.appointment.model.waitlist.SlotOccupied
import io.bluetape4k.clinic.appointment.repository.ResourceAllocationConflictException
import io.bluetape4k.clinic.appointment.repository.ResourceAllocationRepository
import io.bluetape4k.clinic.appointment.repository.waitlist.WaitlistDeliveryRepository
import io.bluetape4k.clinic.appointment.repository.waitlist.WaitlistRepository
import io.bluetape4k.clinic.appointment.service.reliability.BookingReliabilityDecisionBatchPort
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import java.time.Clock
import java.time.Instant

/**
 * waitlist offer claim/release를 caller-owned Exposed transaction 안에서 처리합니다.
 *
 * appointment 생성은 이 서비스의 책임이 아닙니다. 성공한 claim은 후속 replacement command가
 * 소비해야 하는 durable hold ID만 반환합니다.
 */
class WaitlistOfferClaimService(
    private val waitlistRepository: WaitlistRepository,
    private val resourceAllocationRepository: ResourceAllocationRepository,
    private val decisionBatchPort: BookingReliabilityDecisionBatchPort,
    private val clock: Clock,
    private val deliveryRepository: WaitlistDeliveryRepository? = null,
) {
    /** 고객 claim을 decision 재검증과 capacity hold ACCEPTED 전이까지 원자적으로 처리합니다. */
    fun claim(command: ClaimWaitlistOfferCommand): OfferClaimed {
        val now = clock.instant()
        val offer = waitlistRepository.findOfferForUpdate(command.scope, command.offerId)
            ?: throw OfferScopeMismatch(command.offerId)
        val entry = waitlistRepository.findEntryForUpdate(command.scope, offer.waitlistEntryId)
            ?: throw OfferScopeMismatch(command.offerId)
        val holdCandidate = waitlistRepository.findHoldByOfferForUpdate(command.scope, command.offerId)
        val hold = if (holdCandidate != null) {
            resourceAllocationRepository.lockWaitlistHoldResource(holdCandidate)
            holdCandidate
        } else {
            resourceAllocationRepository.lockWaitlistResource(
                scope = command.scope,
                resourceType = offer.resourceType,
                resourceId = offer.resourceId,
            )
            repairMissingOfferedHold(command.scope, offer, now)
        }
        verifyLinkedRows(offer, hold, entry)

        if (offer.status == WaitlistOfferState.ACCEPTED) {
            if (now >= offer.expiresAt || now >= offer.startsAt || now >= hold.holdExpiresAt) {
                expireLockedRows(
                    offer = offer,
                    hold = hold,
                    entry = entry,
                    now = now,
                    reasonCode = WaitlistReasonCode.offerExpired,
                    correlationId = command.correlationId,
                    actorRef = command.actorRef,
                )
                throw OfferExpired()
            }
            return replayAccepted(offer, hold)
        }
        if (offer.status != WaitlistOfferState.OFFERED || hold.status != WaitlistCapacityHoldState.OFFERED) {
            throw OfferStateConflict(offer.id)
        }
        if (offer.version != command.expectedVersion) {
            throw VersionConflict(offer.id)
        }
        if (now >= offer.expiresAt || now >= offer.startsAt || now >= hold.holdExpiresAt) {
            expireLockedRows(
                offer = offer,
                hold = hold,
                entry = entry,
                now = now,
                reasonCode = WaitlistReasonCode.offerExpired,
                correlationId = command.correlationId,
                actorRef = command.actorRef,
            )
            throw OfferExpired()
        }
        if (!offer.decisionStamp.isUsableAt(now)) {
            throw DecisionStale(offer.decisionStamp.decisionId)
        }

        verifyDecisionStamp(offer, now)
        if (!waitlistRepository.casHold(
                scope = command.scope,
                holdId = hold.id,
                expectedVersion = hold.version,
                from = WaitlistCapacityHoldState.OFFERED,
                to = WaitlistCapacityHoldState.ACCEPTED,
                now = now,
            )
        ) {
            throw VersionConflict(hold.id)
        }
        if (!waitlistRepository.casOffer(
                scope = command.scope,
                offerId = offer.id,
                expectedVersion = offer.version,
                from = WaitlistOfferState.OFFERED,
                to = WaitlistOfferState.ACCEPTED,
                now = now,
            )
        ) {
            throw VersionConflict(offer.id)
        }
        if (!waitlistRepository.casEntry(
                scope = command.scope,
                entryId = entry.id,
                expectedVersion = entry.version,
                from = WaitlistEntryState.OFFERED,
                to = WaitlistEntryState.ACCEPTED,
                now = now,
            )
        ) {
            throw VersionConflict(entry.id)
        }
        appendEvent(
            entry = entry,
            offer = offer,
            hold = hold,
            toState = WaitlistEntryState.ACCEPTED,
            reasonCode = WaitlistReasonCode("CLAIM_ACCEPTED"),
            occurredAt = now,
            correlationId = command.correlationId,
            actorRef = command.actorRef,
        )
        log.info {
            "Waitlist offer claimed: tenantGroupId=${command.scope.tenantGroupId}, clinicId=${command.scope.clinicId}, " +
                "offerId=${offer.id}, holdId=${hold.id}, correlationId=${command.correlationId.value}"
        }
        return OfferClaimed(
            offerId = offer.id,
            holdId = hold.id,
            memberId = command.scope.memberId,
            holdExpiresAt = minOf(hold.holdExpiresAt, hold.startsAt),
        )
    }

    /** 고객 또는 운영자의 release를 hold 반환과 offer/entry terminal 전이로 기록합니다. */
    fun release(command: ReleaseWaitlistOfferCommand): OfferWithdrawn {
        val now = clock.instant()
        val offer = waitlistRepository.findOfferForUpdate(command.scope, command.offerId)
            ?: throw OfferScopeMismatch(command.offerId)
        val entry = waitlistRepository.findEntryForUpdate(command.scope, offer.waitlistEntryId)
            ?: throw OfferScopeMismatch(command.offerId)
        val lockedHold = waitlistRepository.findHoldByOfferForUpdate(command.scope, command.offerId)
            ?: throw HoldScopeMismatch(offer.id)
        verifyLinkedRows(offer, lockedHold, entry)
        if (!offer.status.isActive || !lockedHold.status.isActive) {
            throw OfferStateConflict(offer.id)
        }
        if (offer.version != command.expectedVersion) {
            throw VersionConflict(offer.id)
        }
        resourceAllocationRepository.lockWaitlistHoldResource(lockedHold)
        val released = resourceAllocationRepository.releaseWaitlistCapacityHold(
            scope = command.scope,
            holdId = lockedHold.id,
            terminal = WaitlistCapacityHoldState.RELEASED,
            releasedAt = now,
        )
        if (!released) {
            throw VersionConflict(lockedHold.id)
        }
        if (!waitlistRepository.casOffer(
                scope = command.scope,
                offerId = offer.id,
                expectedVersion = offer.version,
                from = offer.status,
                to = WaitlistOfferState.WITHDRAWN,
                now = now,
            )
        ) {
            throw VersionConflict(offer.id)
        }
        if (!waitlistRepository.casEntry(
                scope = command.scope,
                entryId = entry.id,
                expectedVersion = entry.version,
                from = entry.status,
                to = WaitlistEntryState.WITHDRAWN,
                now = now,
            )
        ) {
            throw VersionConflict(entry.id)
        }
        appendEvent(
            entry = entry,
            offer = offer,
            hold = lockedHold,
            toState = WaitlistEntryState.WITHDRAWN,
            reasonCode = command.reason,
            occurredAt = now,
            correlationId = command.correlationId,
            actorRef = command.actorRef,
        )
        progressVacancyAfterTerminal(offer, now)
        log.info {
            "Waitlist offer released: tenantGroupId=${command.scope.tenantGroupId}, clinicId=${command.scope.clinicId}, " +
                "offerId=${offer.id}, holdId=${lockedHold.id}, correlationId=${command.correlationId.value}"
        }
        return OfferWithdrawn(offerId = offer.id, holdId = lockedHold.id, reason = command.reason)
    }

    internal fun expireLockedRows(
        offer: WaitlistOfferRecord,
        hold: WaitlistCapacityHoldRecord,
        entry: WaitlistEntryRecord,
        now: Instant,
        reasonCode: WaitlistReasonCode,
        correlationId: io.bluetape4k.clinic.appointment.model.waitlist.CorrelationId,
        actorRef: io.bluetape4k.clinic.appointment.model.waitlist.ActorRef,
    ) {
        if (!resourceAllocationRepository.releaseWaitlistCapacityHold(
                scope = offer.scope,
                holdId = hold.id,
                terminal = WaitlistCapacityHoldState.EXPIRED,
                releasedAt = now,
            )
        ) {
            throw VersionConflict(hold.id)
        }
        if (!waitlistRepository.casOffer(
                scope = offer.scope,
                offerId = offer.id,
                expectedVersion = offer.version,
                from = offer.status,
                to = WaitlistOfferState.EXPIRED,
                now = now,
            )
        ) {
            throw VersionConflict(offer.id)
        }
        if (!waitlistRepository.casEntry(
                scope = offer.scope,
                entryId = entry.id,
                expectedVersion = entry.version,
                from = entry.status,
                to = WaitlistEntryState.EXPIRED,
                now = now,
            )
        ) {
            throw VersionConflict(entry.id)
        }
        appendEvent(
            entry = entry,
            offer = offer,
            hold = hold,
            toState = WaitlistEntryState.EXPIRED,
            reasonCode = reasonCode,
            occurredAt = now,
            correlationId = correlationId,
            actorRef = actorRef,
        )
        progressVacancyAfterTerminal(offer, now)
        log.info {
            "Waitlist offer expired during claim: tenantGroupId=${offer.scope.tenantGroupId}, " +
                "clinicId=${offer.scope.clinicId}, offerId=${offer.id}, holdId=${hold.id}, " +
                "correlationId=${correlationId.value}"
        }
    }

    private fun replayAccepted(
        offer: WaitlistOfferRecord,
        hold: WaitlistCapacityHoldRecord,
    ): OfferClaimed {
        if (hold.status != WaitlistCapacityHoldState.ACCEPTED) {
            throw OfferStateConflict(offer.id)
        }
        return OfferClaimed(
            offerId = offer.id,
            holdId = hold.id,
            memberId = offer.scope.memberId,
            holdExpiresAt = hold.holdExpiresAt,
        )
    }

    private fun repairMissingOfferedHold(
        scope: WaitlistScope,
        offer: WaitlistOfferRecord,
        now: Instant,
    ): WaitlistCapacityHoldRecord {
        if (offer.status != WaitlistOfferState.OFFERED) {
            throw HoldScopeMismatch(offer.id)
        }
        val hold = NewHold(
            vacancyKey = offer.vacancyKey,
            activeVacancyKey = checkNotNull(offer.activeVacancyKey) {
                "offered waitlist offer must retain activeVacancyKey"
            },
            resourceType = offer.resourceType,
            resourceId = offer.resourceId,
            startsAt = offer.startsAt,
            endsAt = offer.endsAt,
            capacityUnits = offer.capacityUnits,
            maximumCapacity = offer.maximumCapacity,
            holdExpiresAt = offer.expiresAt,
        )
        try {
            resourceAllocationRepository.validateWaitlistCapacityAfterResourceLock(scope, hold)
            return resourceAllocationRepository.reserveWaitlistCapacityHold(
                scope = scope,
                offerId = offer.id,
                hold = hold,
                now = now,
            )
        } catch (ex: ResourceAllocationConflictException) {
            throw SlotOccupied()
        }
    }

    private fun verifyDecisionStamp(offer: WaitlistOfferRecord, now: Instant) {
        val latest = decisionBatchPort
            .findLatestDecisionStamps(offer.scope, listOf(offer.scope.memberId), now)
            .get(offer.scope.memberId)
            ?: throw DecisionUnavailable(offer.scope.memberId)
        if (!latest.isUsableAt(now) || !latest.matches(offer.decisionStamp)) {
            throw DecisionStale(offer.decisionStamp.decisionId)
        }
    }

    private fun DecisionStamp.matches(other: DecisionStamp): Boolean =
        scope == other.scope &&
            decisionId == other.decisionId &&
            policyVersionId == other.policyVersionId &&
            policyHash == other.policyHash &&
            evaluationDigest == other.evaluationDigest &&
            expiresAt == other.expiresAt

    private fun verifyLinkedRows(
        offer: WaitlistOfferRecord,
        hold: WaitlistCapacityHoldRecord,
        entry: WaitlistEntryRecord,
    ) {
        if (offer.scope != hold.scope || offer.scope != entry.scope || hold.offerId != offer.id || offer.waitlistEntryId != entry.id) {
            throw OfferScopeMismatch(offer.id)
        }
    }

    private fun appendEvent(
        entry: WaitlistEntryRecord,
        offer: WaitlistOfferRecord,
        hold: WaitlistCapacityHoldRecord,
        toState: WaitlistEntryState,
        reasonCode: WaitlistReasonCode,
        occurredAt: Instant,
        correlationId: io.bluetape4k.clinic.appointment.model.waitlist.CorrelationId,
        actorRef: io.bluetape4k.clinic.appointment.model.waitlist.ActorRef,
    ) {
        waitlistRepository.appendEvent(
            WaitlistOfferEventRecord(
                waitlistEntryId = entry.id,
                offerId = offer.id,
                holdId = hold.id,
                fromState = entry.status,
                toState = toState,
                reasonCode = reasonCode,
                actorRef = actorRef,
                correlationId = correlationId,
                occurredAt = occurredAt,
                eventVersion = entry.version + 1L,
            ),
        )
    }

    /** release/expiry 직후 slot이 유효하면 같은 vacancy의 다음 generation을 멱등 생성합니다. */
    private fun progressVacancyAfterTerminal(
        offer: WaitlistOfferRecord,
        now: Instant,
    ) {
        val delivery = deliveryRepository ?: return
        val previous = delivery.findTerminalVacancy(
            tenantGroupId = offer.scope.tenantGroupId,
            clinicId = offer.scope.clinicId,
            vacancyKey = offer.vacancyKey,
        ) ?: delivery.findVacancy(
            tenantGroupId = offer.scope.tenantGroupId,
            clinicId = offer.scope.clinicId,
            vacancyKey = offer.vacancyKey,
        ) ?: return
        val closed = delivery.terminalizeForProgress(
            jobId = previous.id,
            now = now,
            status = if (now < previous.vacancyStartsAt && now < previous.vacancyEndsAt) {
                io.bluetape4k.clinic.appointment.model.waitlist.VacancyJobState.NO_CANDIDATE
            } else {
                io.bluetape4k.clinic.appointment.model.waitlist.VacancyJobState.EXPIRED
            },
        ) ?: return
        if (now < closed.vacancyStartsAt && now < closed.vacancyEndsAt) {
            delivery.nextGeneration(closed.id, now)
        }
    }

    companion object : KLogging()
}
