package io.bluetape4k.clinic.appointment.waitlist

import java.io.Serializable

/** Redis가 발급한 waitlist fencing token의 epoch와 sequence를 함께 표현합니다. */
data class WaitlistFencingToken(
    val epoch: Long,
    val sequence: Long,
) : Serializable {

    init {
        require(epoch >= 0L) { "epoch must be zero or positive" }
        require(sequence >= 0L) { "sequence must be zero or positive" }
    }

    /** 이전 token보다 epoch가 크거나 같은 epoch에서 sequence가 큰지 확인합니다. */
    fun isStrictlyGreaterThan(previous: WaitlistFencingToken): Boolean =
        epoch > previous.epoch || epoch == previous.epoch && sequence > previous.sequence

    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}
