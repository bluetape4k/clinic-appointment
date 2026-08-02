package io.bluetape4k.clinic.appointment.model.waitlist

/**
 * 대기 항목 aggregate의 1차 lifecycle 상태입니다.
 */
enum class WaitlistEntryState {
    /** 아직 concrete offer를 받지 않은 대기 상태입니다. */
    WAITING,

    /** 하나의 active offer와 연결된 상태입니다. */
    OFFERED,

    /** offer가 claim되어 durable hold를 후속 appointment command에 넘길 수 있는 상태입니다. */
    ACCEPTED,

    /** 고객 또는 운영자가 offer를 거절한 terminal 상태입니다. */
    DECLINED,

    /** offer 또는 accepted hold가 만료된 terminal 상태입니다. */
    EXPIRED,

    /** 직원 또는 recovery command가 대기 항목을 철회한 terminal 상태입니다. */
    WITHDRAWN,
    ;

    val isTerminal: Boolean
        get() = this in terminalStates

    companion object {
        val activeStates: Set<WaitlistEntryState> = setOf(WAITING, OFFERED)
        val terminalStates: Set<WaitlistEntryState> = setOf(ACCEPTED, DECLINED, EXPIRED, WITHDRAWN)
    }
}
