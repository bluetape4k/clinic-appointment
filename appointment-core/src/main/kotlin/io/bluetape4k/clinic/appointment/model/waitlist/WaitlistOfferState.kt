package io.bluetape4k.clinic.appointment.model.waitlist

/**
 * concrete waitlist offer의 권위 상태입니다.
 */
enum class WaitlistOfferState {
    /** 고객에게 제안됐고 아직 claim되지 않은 active offer입니다. */
    OFFERED,

    /** 고객이 claim했고 durable capacity hold가 후속 command를 기다리는 active offer입니다. */
    ACCEPTED,

    /** 고객 또는 운영자가 거절한 terminal offer입니다. */
    DECLINED,

    /** 시작 시각 또는 offer 만료 시각을 지나 닫힌 terminal offer입니다. */
    EXPIRED,

    /** 직원 또는 recovery command가 철회한 terminal offer입니다. */
    WITHDRAWN,
    ;

    val isActive: Boolean
        get() = this in activeStates

    val isTerminal: Boolean
        get() = this in terminalStates

    companion object {
        val activeStates: Set<WaitlistOfferState> = setOf(OFFERED, ACCEPTED)
        val terminalStates: Set<WaitlistOfferState> = setOf(DECLINED, EXPIRED, WITHDRAWN)
    }
}
