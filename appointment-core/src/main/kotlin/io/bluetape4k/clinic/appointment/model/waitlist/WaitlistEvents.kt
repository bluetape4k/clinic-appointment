package io.bluetape4k.clinic.appointment.model.waitlist

import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable
import java.time.Instant

/** waitlist entry lifecycle에 적용되는 typed event입니다. */
sealed interface WaitlistEvent : Serializable {
    /** offer 생성으로 entry가 active offer에 연결됩니다. */
    data object OfferSelected : WaitlistEvent

    /** 고객 claim이 성공했습니다. */
    data object ClaimAccepted : WaitlistEvent

    /** 고객 또는 운영자가 offer를 거절했습니다. */
    data object OfferDeclined : WaitlistEvent

    /** offer 또는 accepted hold가 만료됐습니다. */
    data object OfferExpired : WaitlistEvent

    /** 직원 또는 recovery command가 철회했습니다. */
    data object StaffWithdrawn : WaitlistEvent
}

/** waitlist entry transition 결과입니다. */
data class WaitlistTransitionResult(
    val previousState: WaitlistEntryState,
    val currentState: WaitlistEntryState,
    val event: WaitlistEvent,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** phase-one waitlist lifecycle transition 함수입니다. */
object WaitlistEntryTransitions {
    /** 현재 entry 상태와 typed event의 허용 전이를 계산합니다. */
    fun transition(
        currentState: WaitlistEntryState,
        event: WaitlistEvent,
    ): WaitlistTransitionResult {
        val nextState = when (currentState to event) {
            WaitlistEntryState.WAITING to WaitlistEvent.OfferSelected -> WaitlistEntryState.OFFERED
            WaitlistEntryState.WAITING to WaitlistEvent.StaffWithdrawn -> WaitlistEntryState.WITHDRAWN
            WaitlistEntryState.OFFERED to WaitlistEvent.ClaimAccepted -> WaitlistEntryState.ACCEPTED
            WaitlistEntryState.OFFERED to WaitlistEvent.OfferDeclined -> WaitlistEntryState.DECLINED
            WaitlistEntryState.OFFERED to WaitlistEvent.OfferExpired -> WaitlistEntryState.EXPIRED
            WaitlistEntryState.OFFERED to WaitlistEvent.StaffWithdrawn -> WaitlistEntryState.WITHDRAWN
            WaitlistEntryState.ACCEPTED to WaitlistEvent.OfferExpired -> WaitlistEntryState.EXPIRED
            WaitlistEntryState.ACCEPTED to WaitlistEvent.StaffWithdrawn -> WaitlistEntryState.WITHDRAWN
            else -> throw IllegalArgumentException("waitlist entry transition is not allowed")
        }
        return WaitlistTransitionResult(
            previousState = currentState,
            currentState = nextState,
            event = event,
        )
    }
}

/** append-only offer event stream record입니다. */
data class WaitlistOfferEventRecord(
    val id: Long? = null,
    val waitlistEntryId: Long,
    val offerId: Long?,
    val holdId: Long?,
    val fromState: WaitlistEntryState?,
    val toState: WaitlistEntryState,
    val reasonCode: WaitlistReasonCode,
    val actorRef: ActorRef,
    val correlationId: CorrelationId,
    val occurredAt: Instant,
    val eventVersion: Long,
) : Serializable {
    init {
        id?.requirePositiveNumber("id")
        waitlistEntryId.requirePositiveNumber("waitlistEntryId")
        offerId?.requirePositiveNumber("offerId")
        holdId?.requirePositiveNumber("holdId")
        eventVersion.requirePositiveNumber("eventVersion")
        check(fromState != toState) { "event must change state" }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}
