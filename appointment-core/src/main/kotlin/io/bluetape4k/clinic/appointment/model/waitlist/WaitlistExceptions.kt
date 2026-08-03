package io.bluetape4k.clinic.appointment.model.waitlist

import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable

/** 대기 목록 core의 stable domain conflict입니다. */
sealed class WaitlistException(
    message: String,
    val reason: WaitlistReasonCode,
) : RuntimeException(message), Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** 자동 offer 후보가 없는 bounded 결과입니다. */
data object NoEligibleCandidate : WaitlistException(
    message = "no eligible waitlist candidate",
    reason = WaitlistReasonCode.noEligibleCandidate,
)

/** 동일 entry 또는 vacancy의 active offer가 이미 존재합니다. */
class OfferAlreadyExists(
    val offerId: Long? = null,
) : WaitlistException(
    message = "waitlist offer already exists",
    reason = WaitlistReasonCode.offerAlreadyExists,
) {
    init {
        offerId?.requirePositiveNumber("offerId")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** offer가 claim 가능한 시간 경계를 지났습니다. */
class OfferExpired : WaitlistException(
    message = "waitlist offer is expired",
    reason = WaitlistReasonCode.offerExpired,
) {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** offer, entry, hold 상태가 서로 맞지 않아 recovery가 필요합니다. */
class OfferStateConflict(
    val offerId: Long,
) : WaitlistException(
    message = "waitlist offer state conflict",
    reason = WaitlistReasonCode.offerStateConflict,
) {
    init {
        offerId.requirePositiveNumber("offerId")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** optimistic version이 맞지 않습니다. */
class VersionConflict(
    val id: Long,
) : WaitlistException(
    message = "waitlist version conflict",
    reason = WaitlistReasonCode.versionConflict,
) {
    init {
        id.requirePositiveNumber("id")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** 같은 자원 시간대가 이미 confirmed allocation 또는 active hold로 점유됐습니다. */
class SlotOccupied(
    reason: WaitlistReasonCode = WaitlistReasonCode.slotOccupied,
) : WaitlistException(
    message = "waitlist slot is occupied",
    reason = reason,
) {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** offer와 entry의 scope가 맞지 않습니다. */
class OfferScopeMismatch(
    val offerId: Long,
) : WaitlistException(
    message = "waitlist offer scope mismatch",
    reason = WaitlistReasonCode.offerScopeMismatch,
) {
    init {
        offerId.requirePositiveNumber("offerId")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** hold와 offer/entry의 scope가 맞지 않습니다. */
class HoldScopeMismatch(
    val holdId: Long,
) : WaitlistException(
    message = "waitlist hold scope mismatch",
    reason = WaitlistReasonCode.holdScopeMismatch,
) {
    init {
        holdId.requirePositiveNumber("holdId")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** offer에 저장된 reliability decision이 더 이상 유효하지 않습니다. */
class DecisionStale(
    val decisionId: Long,
) : WaitlistException(
    message = "waitlist decision is stale",
    reason = WaitlistReasonCode.decisionStale,
) {
    init {
        decisionId.requirePositiveNumber("decisionId")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** 후보 또는 claim에 필요한 reliability decision이 없습니다. */
class DecisionUnavailable(
    @Suppress("UNUSED_PARAMETER")
    memberId: MemberId,
) : WaitlistException(
    message = "waitlist decision is unavailable",
    reason = WaitlistReasonCode.decisionUnavailable,
) {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** recovery가 상태를 보존하고 backlog로 넘겨야 하는 conflict입니다. */
class RecoveryConflict(
    val holdId: Long,
) : WaitlistException(
    message = "waitlist recovery conflict",
    reason = WaitlistReasonCode.recoveryConflict,
) {
    init {
        holdId.requirePositiveNumber("holdId")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** bounded recovery batch budget을 초과했습니다. */
class RecoveryBudgetExceeded(
    val limit: Int,
) : WaitlistException(
    message = "waitlist recovery budget exceeded",
    reason = WaitlistReasonCode.recoveryBudgetExceeded,
) {
    init {
        require(limit > 0) { "limit must be positive" }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** waitlist adjustment projection이 현재 상태에서 요청한 결정을 적용할 수 없습니다. */
class WaitlistAdjustmentConflictException(
    message: String,
) : WaitlistException(
    message = message,
    reason = WaitlistReasonCode("WAITLIST_ADJUSTMENT_CONFLICT"),
) {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** waitlist adjustment projection mutation 대상이 현재 scope에 없습니다. */
class WaitlistAdjustmentNotFoundException(
    message: String,
) : WaitlistException(
    message = message,
    reason = WaitlistReasonCode("WAITLIST_ADJUSTMENT_NOT_FOUND"),
) {
    companion object {
        private const val serialVersionUID = 1L
    }
}
