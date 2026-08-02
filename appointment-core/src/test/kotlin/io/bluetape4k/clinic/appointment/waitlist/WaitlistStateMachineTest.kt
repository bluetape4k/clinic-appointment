package io.bluetape4k.clinic.appointment.waitlist

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistEntryState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistEntryTransitions
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistEvent
import org.junit.jupiter.api.Test

/** Phase-one waitlist entry lifecycle의 허용 전이와 terminal guard를 검증합니다. */
class WaitlistStateMachineTest {

    @Test
    fun `waiting entry moves to offered and then accepted`() {
        val offered = WaitlistEntryTransitions.transition(
            currentState = WaitlistEntryState.WAITING,
            event = WaitlistEvent.OfferSelected,
        )

        offered.previousState shouldBeEqualTo WaitlistEntryState.WAITING
        offered.event shouldBeEqualTo WaitlistEvent.OfferSelected
        offered.currentState shouldBeEqualTo WaitlistEntryState.OFFERED

        val accepted = WaitlistEntryTransitions.transition(
            currentState = offered.currentState,
            event = WaitlistEvent.ClaimAccepted,
        )

        accepted.previousState shouldBeEqualTo WaitlistEntryState.OFFERED
        accepted.event shouldBeEqualTo WaitlistEvent.ClaimAccepted
        accepted.currentState shouldBeEqualTo WaitlistEntryState.ACCEPTED
    }

    @Test
    fun `terminal waitlist entry rejects further events`() {
        val error = assertFailsWith<IllegalArgumentException> {
            WaitlistEntryTransitions.transition(
                currentState = WaitlistEntryState.ACCEPTED,
                event = WaitlistEvent.OfferSelected,
            )
        }

        error.message shouldBeEqualTo "waitlist entry transition is not allowed"
    }

    @Test
    fun `accepted entry can expire or be withdrawn while its hold is active`() {
        WaitlistEntryTransitions.transition(
            currentState = WaitlistEntryState.ACCEPTED,
            event = WaitlistEvent.OfferExpired,
        ).currentState shouldBeEqualTo WaitlistEntryState.EXPIRED

        WaitlistEntryTransitions.transition(
            currentState = WaitlistEntryState.ACCEPTED,
            event = WaitlistEvent.StaffWithdrawn,
        ).currentState shouldBeEqualTo WaitlistEntryState.WITHDRAWN
    }
}
