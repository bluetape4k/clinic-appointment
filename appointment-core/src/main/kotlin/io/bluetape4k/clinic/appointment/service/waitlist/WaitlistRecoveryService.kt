package io.bluetape4k.clinic.appointment.service.waitlist

import io.bluetape4k.clinic.appointment.model.waitlist.CapacityHoldExpired
import io.bluetape4k.clinic.appointment.model.waitlist.ReconcileWaitlistHoldsCommand
import io.bluetape4k.clinic.appointment.model.waitlist.VersionConflict
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistCapacityHoldRecord
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistCapacityHoldState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistEntryRecord
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistEntryState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistOfferEventRecord
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistOfferRecord
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistOfferState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistReasonCode
import io.bluetape4k.clinic.appointment.repository.ResourceAllocationRepository
import io.bluetape4k.clinic.appointment.repository.waitlist.WaitlistDeliveryRepository
import io.bluetape4k.clinic.appointment.repository.waitlist.WaitlistRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import java.time.Clock

/** 만료된 waitlist hold를 bounded batch로 terminal 상태에 수렴시킵니다. */
class WaitlistRecoveryService(
    private val waitlistRepository: WaitlistRepository,
    private val resourceAllocationRepository: ResourceAllocationRepository,
    private val clock: Clock,
    private val deliveryRepository: WaitlistDeliveryRepository? = null,
) {
    /** bounded 후보를 재조회·재잠금한 뒤 만료된 hold와 연결 row를 terminal로 수렴시킵니다. */
    fun reconcileWaitlistHolds(command: ReconcileWaitlistHoldsCommand): CapacityHoldExpired {
        val now = clock.instant()
        val batchLimit = minOf(command.limit, DEFAULT_BATCH_LIMIT)
        val expired = waitlistRepository.findExpiredHolds(limit = batchLimit, now = now)
        var count = 0
        var lastId: Long? = null

        expired.forEach { candidate ->
            val offer = waitlistRepository.findOfferForUpdate(candidate.scope, candidate.offerId) ?: return@forEach
            val entry = waitlistRepository.findEntryForUpdate(candidate.scope, offer.waitlistEntryId) ?: return@forEach
            val hold = waitlistRepository.findHoldForUpdate(candidate.scope, candidate.id) ?: return@forEach
            if (!canExpire(offer, hold, entry, now)) {
                return@forEach
            }
            resourceAllocationRepository.lockWaitlistHoldResource(hold)
            expireLockedRows(command, offer, hold, entry, now)
            count += 1
            lastId = hold.id
        }
        log.info {
            "Waitlist recovery completed: count=$count, lastId=$lastId, correlationId=${command.correlationId.value}"
        }
        return CapacityHoldExpired(count = count, lastId = lastId)
    }

    private fun canExpire(
        offer: WaitlistOfferRecord,
        hold: WaitlistCapacityHoldRecord,
        entry: WaitlistEntryRecord,
        now: java.time.Instant,
    ): Boolean =
        offer.scope == hold.scope &&
            offer.scope == entry.scope &&
            hold.offerId == offer.id &&
            offer.waitlistEntryId == entry.id &&
            offer.status.isActive &&
            hold.status.isActive &&
            entry.status in setOf(WaitlistEntryState.OFFERED, WaitlistEntryState.ACCEPTED) &&
            when (hold.status) {
                WaitlistCapacityHoldState.OFFERED -> hold.holdExpiresAt <= now || hold.startsAt <= now
                WaitlistCapacityHoldState.ACCEPTED -> hold.startsAt <= now
                else -> false
            }

    private fun expireLockedRows(
        command: ReconcileWaitlistHoldsCommand,
        offer: WaitlistOfferRecord,
        hold: WaitlistCapacityHoldRecord,
        entry: WaitlistEntryRecord,
        now: java.time.Instant,
    ) {
        if (!resourceAllocationRepository.releaseWaitlistCapacityHold(
                scope = hold.scope,
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
                scope = entry.scope,
                entryId = entry.id,
                expectedVersion = entry.version,
                from = entry.status,
                to = WaitlistEntryState.EXPIRED,
                now = now,
            )
        ) {
            throw VersionConflict(entry.id)
        }
        waitlistRepository.appendEvent(
            WaitlistOfferEventRecord(
                waitlistEntryId = entry.id,
                offerId = offer.id,
                holdId = hold.id,
                fromState = entry.status,
                toState = WaitlistEntryState.EXPIRED,
                reasonCode = WaitlistReasonCode.offerExpired,
                actorRef = command.actorRef,
                correlationId = command.correlationId,
                occurredAt = now,
                eventVersion = entry.version + 1L,
            ),
        )
        progressVacancyAfterTerminal(offer, now)
    }

    private fun progressVacancyAfterTerminal(
        offer: WaitlistOfferRecord,
        now: java.time.Instant,
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
        val slotValid = now < previous.vacancyStartsAt && now < previous.vacancyEndsAt
        val closed = delivery.terminalizeForProgress(
            jobId = previous.id,
            now = now,
            status = if (slotValid) {
                io.bluetape4k.clinic.appointment.model.waitlist.VacancyJobState.NO_CANDIDATE
            } else {
                io.bluetape4k.clinic.appointment.model.waitlist.VacancyJobState.EXPIRED
            },
        ) ?: return
        if (slotValid) {
            delivery.nextGeneration(closed.id, now)
        }
    }

    private companion object : KLogging() {
        const val DEFAULT_BATCH_LIMIT = 100
    }
}
