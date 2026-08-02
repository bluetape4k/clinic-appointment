package io.bluetape4k.clinic.appointment.model.waitlist

import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable
import java.time.Instant

/** 대기 목록 core가 외부 adapter로 반환하는 bounded 결과입니다. */
sealed interface WaitlistResult : Serializable

/** 자동 offer 후보를 찾고 durable hold까지 생성한 결과입니다. */
data class CandidateFound(
    val offerId: Long,
    val holdId: Long,
    val rank: Int,
) : WaitlistResult {
    init {
        offerId.requirePositiveNumber("offerId")
        holdId.requirePositiveNumber("holdId")
        rank.requirePositiveNumber("rank")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** offer claim이 성공해 후속 replacement command에 hold를 넘길 수 있는 결과입니다. */
data class OfferClaimed(
    val offerId: Long,
    val holdId: Long,
    val memberId: MemberId,
    val holdExpiresAt: Instant,
) : WaitlistResult {
    init {
        offerId.requirePositiveNumber("offerId")
        holdId.requirePositiveNumber("holdId")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** offer가 terminal withdraw/decline 계열로 닫힌 결과입니다. */
data class OfferWithdrawn(
    val offerId: Long,
    val holdId: Long,
    val reason: WaitlistReasonCode,
) : WaitlistResult {
    init {
        offerId.requirePositiveNumber("offerId")
        holdId.requirePositiveNumber("holdId")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** capacity hold가 active 상태로 생성된 결과입니다. */
data class CapacityHoldCreated(
    val offerId: Long,
    val holdId: Long,
    val expiresAt: Instant,
) : WaitlistResult {
    init {
        offerId.requirePositiveNumber("offerId")
        holdId.requirePositiveNumber("holdId")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** capacity hold가 terminal release로 반환된 결과입니다. */
data class CapacityHoldReleased(
    val offerId: Long,
    val holdId: Long,
    val reason: WaitlistReasonCode,
) : WaitlistResult {
    init {
        offerId.requirePositiveNumber("offerId")
        holdId.requirePositiveNumber("holdId")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** replacement allocation 성공 후 hold가 소비된 결과입니다. */
data class CapacityHoldConsumed(
    val offerId: Long,
    val holdId: Long,
) : WaitlistResult {
    init {
        offerId.requirePositiveNumber("offerId")
        holdId.requirePositiveNumber("holdId")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** bounded reconcile이 만료 hold를 처리한 결과입니다. */
data class CapacityHoldExpired(
    val count: Int,
    val lastId: Long?,
) : WaitlistResult {
    init {
        require(count >= 0) { "count must be zero or positive" }
        lastId?.requirePositiveNumber("lastId")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}
