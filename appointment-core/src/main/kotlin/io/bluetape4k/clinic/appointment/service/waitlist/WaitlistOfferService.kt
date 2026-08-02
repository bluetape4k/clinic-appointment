package io.bluetape4k.clinic.appointment.service.waitlist

import io.bluetape4k.clinic.appointment.model.waitlist.ActorRef
import io.bluetape4k.clinic.appointment.model.waitlist.CandidateFound
import io.bluetape4k.clinic.appointment.model.waitlist.CorrelationId
import io.bluetape4k.clinic.appointment.model.waitlist.NewHold
import io.bluetape4k.clinic.appointment.model.waitlist.NewOffer
import io.bluetape4k.clinic.appointment.model.waitlist.NoEligibleCandidate
import io.bluetape4k.clinic.appointment.model.waitlist.OfferAlreadyExists
import io.bluetape4k.clinic.appointment.model.waitlist.OfferScopeMismatch
import io.bluetape4k.clinic.appointment.model.waitlist.SlotOccupied
import io.bluetape4k.clinic.appointment.model.waitlist.VacancyDescriptor
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistEntryState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistOfferEventRecord
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistReasonCode
import io.bluetape4k.clinic.appointment.model.waitlist.VersionConflict
import io.bluetape4k.clinic.appointment.repository.ResourceAllocationConflictException
import io.bluetape4k.clinic.appointment.repository.ResourceAllocationRepository
import io.bluetape4k.clinic.appointment.repository.waitlist.WaitlistRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import java.time.Duration
import java.time.Instant

/**
 * vacancy 하나에 대해 대기 후보 offer와 capacity hold를 같은 transaction에서 생성합니다.
 *
 * 이 서비스는 Exposed transaction을 직접 열지 않습니다. 호출자는 하나의 transaction 안에서
 * [selectAndOffer]를 호출해야 하며, 이 메서드는 자원 mutex, candidate recheck, capacity
 * preflight, offer/hold insert, entry CAS, history append 순서를 유지합니다.
 */
class WaitlistOfferService(
    private val matcher: WaitlistCandidateMatcher,
    private val waitlistRepository: WaitlistRepository,
    private val resourceAllocationRepository: ResourceAllocationRepository,
    private val offerTtl: Duration = DEFAULT_OFFER_TTL,
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
) {
    init {
        require(!offerTtl.isNegative && !offerTtl.isZero) { "offerTtl must be positive" }
        require(maxRetries in 1..DEFAULT_MAX_RETRIES) { "maxRetries must be in 1..3" }
    }

    /**
     * 한 vacancy를 대상으로 후보를 선택하고 offer·capacity hold를 발행합니다.
     * 반환된 ID만 후속 알림/adapter 경계로 전달하며 회원 프로필 정보는 생성하지 않습니다.
     */
    fun selectAndOffer(
        vacancy: VacancyDescriptor,
        correlationId: CorrelationId,
        actorRef: ActorRef = ActorRef("SYSTEM"),
    ): CandidateFound {
        repeat(maxRetries) { attempt ->
            val page = matcher.findCandidates(vacancy)
            if (page.candidates.isEmpty()) {
                throw NoEligibleCandidate
            }
            page.candidates.forEach { candidate ->
                val result = tryCreateOffer(vacancy, candidate, correlationId, actorRef)
                if (result != null) {
                    return result
                }
            }
            if (page.exhausted) {
                throw NoEligibleCandidate
            }
            log.info { "waitlist offer retry ${attempt + 1} found no promotable candidate" }
        }
        throw OfferAlreadyExists()
    }

    private fun tryCreateOffer(
        vacancy: VacancyDescriptor,
        candidate: WaitlistCandidate,
        correlationId: CorrelationId,
        actorRef: ActorRef,
    ): CandidateFound? {
        val lockedEntry = waitlistRepository.findEntryForUpdate(candidate.entry.scope, candidate.entry.id)
            ?: throw OfferScopeMismatch(candidate.entry.id)
        if (
            lockedEntry.status != WaitlistEntryState.WAITING ||
            lockedEntry.version != candidate.entry.version ||
            lockedEntry.treatmentTypeId != vacancy.treatmentTypeId ||
            lockedEntry.doctorId != vacancy.doctorId && lockedEntry.doctorId != null
        ) {
            return null
        }

        val vacancyKey = WaitlistVacancyKeyHasher.hash(vacancy)
        val hold = newHold(vacancy, vacancyKey, expiresAt(vacancy, candidate.decisionStamp.expiresAt))
        try {
            resourceAllocationRepository.lockAndValidateWaitlistCapacity(lockedEntry.scope, hold)
        } catch (ex: ResourceAllocationConflictException) {
            log.info { "waitlist offer skipped occupied slot for clinic=${vacancy.clinicId}" }
            throw SlotOccupied()
        }

        val offer = NewOffer(
            vacancyKey = vacancyKey,
            activeEntryKey = activeEntryKey(lockedEntry.id),
            activeVacancyKey = vacancyKey,
            doctorId = vacancy.doctorId,
            treatmentTypeId = vacancy.treatmentTypeId,
            startsAt = vacancy.startsAt,
            endsAt = vacancy.endsAt,
            expiresAt = hold.holdExpiresAt,
            decisionStamp = candidate.decisionStamp,
            candidateRank = candidate.rank,
            selectionReasonCode = WaitlistReasonCode("AUTO_SELECTED"),
        )
        val ids =
            try {
                waitlistRepository.insertOfferAndHold(
                    scope = lockedEntry.scope,
                    entry = lockedEntry,
                    offer = offer,
                    hold = hold,
                    now = vacancy.now,
                )
            } catch (ex: OfferAlreadyExists) {
                log.info { "waitlist active offer already exists for clinic=${vacancy.clinicId}" }
                return null
            }

        val transitioned = waitlistRepository.casEntry(
            scope = lockedEntry.scope,
            entryId = lockedEntry.id,
            expectedVersion = lockedEntry.version,
            from = WaitlistEntryState.WAITING,
            to = WaitlistEntryState.OFFERED,
            now = vacancy.now,
        )
        if (!transitioned) {
            throw VersionConflict(lockedEntry.id)
        }

        waitlistRepository.appendEvent(
            WaitlistOfferEventRecord(
                waitlistEntryId = lockedEntry.id,
                offerId = ids.offerId,
                holdId = ids.holdId,
                fromState = WaitlistEntryState.WAITING,
                toState = WaitlistEntryState.OFFERED,
                reasonCode = WaitlistReasonCode("AUTO_SELECTED"),
                actorRef = actorRef,
                correlationId = correlationId,
                occurredAt = vacancy.now,
                eventVersion = lockedEntry.version + 1L,
            ),
        )
        return CandidateFound(offerId = ids.offerId, holdId = ids.holdId, rank = candidate.rank)
    }

    private fun newHold(
        vacancy: VacancyDescriptor,
        vacancyKey: String,
        expiresAt: Instant,
    ): NewHold =
        NewHold(
            vacancyKey = vacancyKey,
            activeVacancyKey = vacancyKey,
            resourceType = vacancy.resourceType,
            resourceId = vacancy.resourceId,
            startsAt = vacancy.startsAt,
            endsAt = vacancy.endsAt,
            capacityUnits = vacancy.capacityUnits,
            maximumCapacity = vacancy.maximumCapacity,
            holdExpiresAt = expiresAt,
        )

    private fun expiresAt(vacancy: VacancyDescriptor, decisionExpiresAt: Instant?): Instant =
        listOfNotNull(vacancy.now.plus(offerTtl), vacancy.startsAt, vacancy.endsAt, decisionExpiresAt).min()

    private fun activeEntryKey(entryId: Long): String = "entry:$entryId"

    private companion object : KLogging() {
        private const val DEFAULT_MAX_RETRIES = 3
        private val DEFAULT_OFFER_TTL: Duration = Duration.ofMinutes(15)
    }
}
