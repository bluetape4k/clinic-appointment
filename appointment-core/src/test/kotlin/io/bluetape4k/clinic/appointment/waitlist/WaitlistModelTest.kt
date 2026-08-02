package io.bluetape4k.clinic.appointment.waitlist

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.model.commitment.ResourceType
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.clinic.appointment.model.waitlist.ActorRef
import io.bluetape4k.clinic.appointment.model.waitlist.CandidateFound
import io.bluetape4k.clinic.appointment.model.waitlist.CapacityHoldCreated
import io.bluetape4k.clinic.appointment.model.waitlist.ClaimWaitlistOfferCommand
import io.bluetape4k.clinic.appointment.model.waitlist.CorrelationId
import io.bluetape4k.clinic.appointment.model.waitlist.DecisionUnavailable
import io.bluetape4k.clinic.appointment.model.waitlist.HoldScopeMismatch
import io.bluetape4k.clinic.appointment.model.waitlist.NewHold
import io.bluetape4k.clinic.appointment.model.waitlist.NoEligibleCandidate
import io.bluetape4k.clinic.appointment.model.waitlist.OfferClaimed
import io.bluetape4k.clinic.appointment.model.waitlist.SlotOccupied
import io.bluetape4k.clinic.appointment.model.waitlist.VacancyDescriptor
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistCapacityHoldState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistEntryState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistEntryRecord
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistEntryTransitions
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistEvent
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistOfferState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistReasonCode
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistResult
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistScope
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import org.junit.jupiter.api.Test

class WaitlistModelTest {

    @Test
    fun `entry lifecycle accepts only the phase one terminal transitions`() {
        val offered = WaitlistEntryTransitions.transition(
            currentState = WaitlistEntryState.WAITING,
            event = WaitlistEvent.OfferSelected,
        )

        offered.previousState shouldBeEqualTo WaitlistEntryState.WAITING
        offered.currentState shouldBeEqualTo WaitlistEntryState.OFFERED

        WaitlistEntryTransitions.transition(
            currentState = WaitlistEntryState.OFFERED,
            event = WaitlistEvent.ClaimAccepted,
        ).currentState shouldBeEqualTo WaitlistEntryState.ACCEPTED

        assertFailsWith<IllegalArgumentException> {
            WaitlistEntryTransitions.transition(
                currentState = WaitlistEntryState.ACCEPTED,
                event = WaitlistEvent.OfferSelected,
            )
        }
    }

    @Test
    fun `scope rejects blank member and non-positive tenant or clinic`() {
        assertFailsWith<IllegalArgumentException> {
            WaitlistScope(tenantGroupId = 0L, clinicId = 1L, memberId = MemberId("member-1"))
        }
        assertFailsWith<IllegalArgumentException> {
            WaitlistScope(tenantGroupId = 1L, clinicId = -1L, memberId = MemberId("member-1"))
        }
        assertFailsWith<IllegalArgumentException> {
            WaitlistScope(tenantGroupId = 1L, clinicId = 1L, memberId = MemberId(" "))
        }
    }

    @Test
    fun `correlation id rejects newline and profile-shaped input without echoing raw value`() {
        val newline = assertFailsWith<IllegalArgumentException> {
            CorrelationId("line1\nline2")
        }
        newline.message shouldBeEqualTo "correlationId must be an opaque 1..128 character token"

        val profile = assertFailsWith<IllegalArgumentException> {
            CorrelationId("patient@example.com")
        }
        profile.message shouldBeEqualTo "correlationId must be an opaque 1..128 character token"
    }

    @Test
    fun `actor ref rejects raw PII shaped values without echoing raw value`() {
        val actor = assertFailsWith<IllegalArgumentException> {
            ActorRef("010-1234-5678")
        }

        actor.message shouldBeEqualTo "actorRef must be SYSTEM, staff, recovery, or HMAC reference"
    }

    @Test
    fun `vacancy and hold descriptors validate UTC instant range and capacity`() {
        val now = Instant.parse("2026-08-01T08:00:00Z")
        val startsAt = Instant.parse("2026-08-01T09:00:00Z")
        val endsAt = Instant.parse("2026-08-01T09:30:00Z")

        val vacancy = VacancyDescriptor(
            tenantGroupId = 1L,
            clinicId = 2L,
            treatmentTypeId = 3L,
            doctorId = null,
            startsAt = startsAt,
            endsAt = endsAt,
            resourceType = ResourceType.PRACTITIONER,
            resourceId = "doctor-7",
            capacityUnits = 1,
            maximumCapacity = 1,
            now = now,
        )

        vacancy.startsAt shouldBeEqualTo startsAt

        assertFailsWith<IllegalArgumentException> {
            vacancy.copy(startsAt = endsAt)
        }
        assertFailsWith<IllegalArgumentException> {
            vacancy.copy(capacityUnits = 2, maximumCapacity = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            NewHold(
                vacancyKey = "vacancy-1",
                activeVacancyKey = "vacancy-1",
                resourceType = ResourceType.PRACTITIONER,
                resourceId = "doctor-7",
                startsAt = startsAt,
                endsAt = endsAt,
                capacityUnits = 1,
                maximumCapacity = 1,
                holdExpiresAt = endsAt.plusSeconds(1),
            )
        }
    }

    @Test
    fun `offer and hold states expose closed active and terminal sets`() {
        WaitlistOfferState.activeStates shouldBeEqualTo setOf(WaitlistOfferState.OFFERED, WaitlistOfferState.ACCEPTED)
        WaitlistOfferState.terminalStates shouldBeEqualTo setOf(
            WaitlistOfferState.DECLINED,
            WaitlistOfferState.EXPIRED,
            WaitlistOfferState.WITHDRAWN,
        )
        WaitlistCapacityHoldState.activeStates shouldBeEqualTo
            setOf(WaitlistCapacityHoldState.OFFERED, WaitlistCapacityHoldState.ACCEPTED)
        WaitlistCapacityHoldState.terminalStates shouldBeEqualTo setOf(
            WaitlistCapacityHoldState.CONSUMED,
            WaitlistCapacityHoldState.RELEASED,
            WaitlistCapacityHoldState.EXPIRED,
        )
    }

    @Test
    fun `bounded results and exceptions keep stable non PII values`() {
        val memberId = MemberId("member-176")
        val holdExpiresAt = Instant.parse("2026-08-01T09:30:00Z")
        val reason = WaitlistReasonCode("STAFF_WITHDRAWN")

        val results: List<WaitlistResult> = listOf(
            CandidateFound(offerId = 10L, holdId = 20L, rank = 1),
            OfferClaimed(offerId = 10L, holdId = 20L, memberId = memberId, holdExpiresAt = holdExpiresAt),
            CapacityHoldCreated(offerId = 10L, holdId = 20L, expiresAt = holdExpiresAt),
        )

        results.size shouldBeEqualTo 3
        SlotOccupied(reason).reason shouldBeEqualTo reason
        NoEligibleCandidate.reason.code shouldBeEqualTo "NO_ELIGIBLE_CANDIDATE"
        DecisionUnavailable(MemberId("other-member")).message shouldBeEqualTo "waitlist decision is unavailable"
        HoldScopeMismatch(holdId = 20L).message shouldBeEqualTo "waitlist hold scope mismatch"
    }

    @Test
    fun `new records and commands accept the initial zero version`() {
        val now = Instant.parse("2026-08-01T08:00:00Z")
        val scope = WaitlistScope(tenantGroupId = 1L, clinicId = 2L, memberId = MemberId("member-176"))
        val entry = WaitlistEntryRecord(
            id = 10L,
            scope = scope,
            treatmentTypeId = 3L,
            doctorId = null,
            preferredDateFrom = LocalDate.parse("2026-08-02"),
            preferredDateTo = LocalDate.parse("2026-08-02"),
            preferredStartTime = LocalTime.parse("09:00"),
            preferredEndTime = LocalTime.parse("09:30"),
            priorityRank = 0,
            status = WaitlistEntryState.WAITING,
            waitingSince = now,
            version = 0L,
            createdAt = now,
            updatedAt = now,
        )

        entry.version shouldBeEqualTo 0L
        ClaimWaitlistOfferCommand(
            offerId = 20L,
            scope = scope,
            expectedVersion = 0L,
            correlationId = CorrelationId("waitlist-claim-1"),
            actorRef = ActorRef("SYSTEM"),
        ).expectedVersion shouldBeEqualTo 0L
    }
}
