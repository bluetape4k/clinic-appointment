package io.bluetape4k.clinic.appointment.model.waitlist

import io.bluetape4k.clinic.appointment.model.commitment.ResourceType
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable
import java.time.Instant

/**
 * 로그·metric·exception에 원문 PII를 남기지 않는 correlation token입니다.
 */
data class CorrelationId(
    val value: String,
) : Serializable {
    init {
        require(CORRELATION_REGEX.matches(value) && !PROFILE_SHAPED_REGEX.containsMatchIn(value)) {
            "correlationId must be an opaque 1..128 character token"
        }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * command actor의 bounded reference입니다.
 */
data class ActorRef(
    val value: String,
) : Serializable {
    init {
        require(isAllowedActor(value)) {
            "actorRef must be SYSTEM, staff, recovery, or HMAC reference"
        }
    }

    companion object {
        private const val serialVersionUID = 1L

        private fun isAllowedActor(value: String): Boolean =
            value == SYSTEM_ACTOR ||
                STAFF_ACTOR_REGEX.matches(value) ||
                RECOVERY_ACTOR_REGEX.matches(value) ||
                HMAC_ACTOR_REGEX.matches(value)
    }
}

/**
 * 상태 전이와 recovery 결과에 남기는 bounded reason code입니다.
 */
data class WaitlistReasonCode(
    val code: String,
) : Serializable {
    init {
        require(REASON_CODE_REGEX.matches(code)) {
            "reasonCode must be an uppercase bounded code"
        }
    }

    companion object {
        private const val serialVersionUID = 1L

        val noEligibleCandidate: WaitlistReasonCode = WaitlistReasonCode("NO_ELIGIBLE_CANDIDATE")
        val offerAlreadyExists: WaitlistReasonCode = WaitlistReasonCode("OFFER_ALREADY_EXISTS")
        val offerExpired: WaitlistReasonCode = WaitlistReasonCode("OFFER_EXPIRED")
        val offerStateConflict: WaitlistReasonCode = WaitlistReasonCode("OFFER_STATE_CONFLICT")
        val versionConflict: WaitlistReasonCode = WaitlistReasonCode("VERSION_CONFLICT")
        val slotOccupied: WaitlistReasonCode = WaitlistReasonCode("SLOT_OCCUPIED")
        val offerScopeMismatch: WaitlistReasonCode = WaitlistReasonCode("OFFER_SCOPE_MISMATCH")
        val holdScopeMismatch: WaitlistReasonCode = WaitlistReasonCode("HOLD_SCOPE_MISMATCH")
        val decisionStale: WaitlistReasonCode = WaitlistReasonCode("DECISION_STALE")
        val decisionUnavailable: WaitlistReasonCode = WaitlistReasonCode("DECISION_UNAVAILABLE")
        val recoveryConflict: WaitlistReasonCode = WaitlistReasonCode("RECOVERY_CONFLICT")
        val recoveryBudgetExceeded: WaitlistReasonCode = WaitlistReasonCode("RECOVERY_BUDGET_EXCEEDED")
    }
}

/**
 * 새 offer row를 만들 때 repository로 넘기는 immutable command fragment입니다.
 */
data class NewOffer(
    val vacancyKey: String,
    val activeEntryKey: String,
    val activeVacancyKey: String,
    val doctorId: Long?,
    val treatmentTypeId: Long,
    val startsAt: Instant,
    val endsAt: Instant,
    val expiresAt: Instant,
    val decisionStamp: DecisionStamp,
    val candidateRank: Int,
    val selectionReasonCode: WaitlistReasonCode,
) : Serializable {
    init {
        vacancyKey.requireNotBlank("vacancyKey")
        activeEntryKey.requireNotBlank("activeEntryKey")
        activeVacancyKey.requireNotBlank("activeVacancyKey")
        doctorId?.requirePositiveNumber("doctorId")
        treatmentTypeId.requirePositiveNumber("treatmentTypeId")
        require(startsAt < endsAt) { "startsAt must be before endsAt" }
        require(expiresAt <= endsAt) { "expiresAt must be on or before endsAt" }
        require(candidateRank > 0) { "candidateRank must be positive" }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 새 capacity hold row를 만들 때 repository로 넘기는 immutable command fragment입니다.
 */
data class NewHold(
    val vacancyKey: String,
    val activeVacancyKey: String,
    val resourceType: ResourceType,
    val resourceId: String,
    val startsAt: Instant,
    val endsAt: Instant,
    val capacityUnits: Int,
    val maximumCapacity: Int,
    val holdExpiresAt: Instant,
) : Serializable {
    init {
        vacancyKey.requireNotBlank("vacancyKey")
        activeVacancyKey.requireNotBlank("activeVacancyKey")
        resourceId.requireNotBlank("resourceId")
        capacityUnits.requirePositiveNumber("capacityUnits")
        maximumCapacity.requirePositiveNumber("maximumCapacity")
        require(startsAt < endsAt) { "startsAt must be before endsAt" }
        require(capacityUnits <= maximumCapacity) {
            "capacityUnits must not exceed maximumCapacity"
        }
        require(resourceType == ResourceType.CAPACITY_BUCKET || capacityUnits == 1) {
            "non-bucket resources must consume exactly one capacity unit"
        }
        require(holdExpiresAt <= endsAt) { "holdExpiresAt must be on or before endsAt" }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * offer claim, release, withdraw 계열 command의 공통 scope와 감사 boundary입니다.
 */
sealed interface WaitlistOfferCommand : Serializable {
    val offerId: Long
    val scope: WaitlistScope
    val expectedVersion: Long
    val correlationId: CorrelationId
    val actorRef: ActorRef
}

/** 고객 claim command입니다. */
data class ClaimWaitlistOfferCommand(
    override val offerId: Long,
    override val scope: WaitlistScope,
    override val expectedVersion: Long,
    override val correlationId: CorrelationId,
    override val actorRef: ActorRef,
) : WaitlistOfferCommand {
    init {
        offerId.requirePositiveNumber("offerId")
        require(expectedVersion >= 0L) { "expectedVersion must be zero or positive" }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** offer release 또는 withdraw command입니다. */
data class ReleaseWaitlistOfferCommand(
    override val offerId: Long,
    override val scope: WaitlistScope,
    override val expectedVersion: Long,
    override val correlationId: CorrelationId,
    override val actorRef: ActorRef,
    val reason: WaitlistReasonCode,
    /** 호환용 command 시각입니다. 서비스는 주입된 [java.time.Clock]을 권위로 사용합니다. */
    val now: Instant = Instant.EPOCH,
) : WaitlistOfferCommand {
    init {
        offerId.requirePositiveNumber("offerId")
        require(expectedVersion >= 0L) { "expectedVersion must be zero or positive" }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** bounded hold reconcile command입니다. */
data class ReconcileWaitlistHoldsCommand(
    val limit: Int,
    /** 호환용 command 시각입니다. 서비스는 주입된 [java.time.Clock]을 권위로 사용합니다. */
    val now: Instant = Instant.EPOCH,
    val correlationId: CorrelationId,
    val actorRef: ActorRef,
) : Serializable {
    init {
        require(limit in 1..500) { "limit must be in 1..500" }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

private const val SYSTEM_ACTOR = "SYSTEM"
private val CORRELATION_REGEX = Regex("^[A-Za-z0-9._:-]{1,128}$")
private val PROFILE_SHAPED_REGEX = Regex(
    pattern = "(@|\\b\\d{2,4}-\\d{3,4}-\\d{4}\\b|^eyJ)",
    option = RegexOption.IGNORE_CASE,
)
private val STAFF_ACTOR_REGEX = Regex("^staff:[A-Za-z0-9._:-]{1,120}$")
private val RECOVERY_ACTOR_REGEX = Regex("^recovery:[A-Za-z0-9._:-]{1,117}$")
private val HMAC_ACTOR_REGEX = Regex("^hmac:v[0-9]+:[a-f0-9]{64}$")
private val REASON_CODE_REGEX = Regex("^[A-Z][A-Z0-9_]{0,63}$")
