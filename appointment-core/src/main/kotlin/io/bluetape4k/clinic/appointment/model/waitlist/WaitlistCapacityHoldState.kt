package io.bluetape4k.clinic.appointment.model.waitlist

/**
 * `ResourceAllocationRepository`가 점유로 계산하는 durable waitlist hold 상태입니다.
 */
enum class WaitlistCapacityHoldState {
    /** offer가 발행되어 replacement 전까지 자원을 잠근 active hold입니다. */
    OFFERED,

    /** claim이 성공했고 replacement allocation이 소비해야 하는 active hold입니다. */
    ACCEPTED,

    /** replacement allocation 생성과 함께 소비된 terminal hold입니다. */
    CONSUMED,

    /** decline, withdraw 또는 operator release로 반환된 terminal hold입니다. */
    RELEASED,

    /** offer 또는 accepted hold deadline이 지나 반환된 terminal hold입니다. */
    EXPIRED,
    ;

    val isActive: Boolean
        get() = this in activeStates

    val isTerminal: Boolean
        get() = this in terminalStates

    companion object {
        val activeStates: Set<WaitlistCapacityHoldState> = setOf(OFFERED, ACCEPTED)
        val terminalStates: Set<WaitlistCapacityHoldState> = setOf(CONSUMED, RELEASED, EXPIRED)
    }
}
