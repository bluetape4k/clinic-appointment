package io.bluetape4k.clinic.appointment.service.waitlist

import io.bluetape4k.clinic.appointment.model.waitlist.CorrelationId
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistCapacityHoldRecord
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistOfferRecord
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistReasonCode
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable
import java.time.Instant

/**
 * waitlist offer notification adapter가 받는 opaque core contract입니다.
 *
 * member profile, 연락처, clinical detail은 이 경계를 넘지 않습니다. adapter는 opaque ID로
 * 필요한 정보를 자기 모듈의 권위에서 조회해야 하며, 이 port는 caller-owned transaction 안에서
 * durable outbox row를 기록하는 데만 사용됩니다.
 */
data class WaitlistOfferNotificationDraft(
    val tenantGroupId: Long,
    val clinicId: Long,
    val offerId: Long,
    val holdId: Long,
    val waitlistEntryId: Long,
    val reasonCode: WaitlistReasonCode,
    val correlationId: CorrelationId,
    val occurredAt: Instant,
) : Serializable {
    init {
        tenantGroupId.requirePositiveNumber("tenantGroupId")
        clinicId.requirePositiveNumber("clinicId")
        offerId.requirePositiveNumber("offerId")
        holdId.requirePositiveNumber("holdId")
        waitlistEntryId.requirePositiveNumber("waitlistEntryId")
    }

    companion object {
        private const val serialVersionUID = 1L

        fun of(
            created: WaitlistOfferCreated,
            reasonCode: WaitlistReasonCode = WaitlistReasonCode("OFFER_CREATED"),
            correlationId: CorrelationId,
            occurredAt: Instant,
        ): WaitlistOfferNotificationDraft =
            WaitlistOfferNotificationDraft(
                tenantGroupId = created.offer.scope.tenantGroupId,
                clinicId = created.offer.scope.clinicId,
                offerId = created.offer.id,
                holdId = created.hold.id,
                waitlistEntryId = created.offer.waitlistEntryId,
                reasonCode = reasonCode,
                correlationId = correlationId,
                occurredAt = occurredAt,
            )

        fun of(
            offer: WaitlistOfferRecord,
            hold: WaitlistCapacityHoldRecord,
            reasonCode: WaitlistReasonCode,
            correlationId: CorrelationId,
            occurredAt: Instant,
        ): WaitlistOfferNotificationDraft =
            of(
                created = WaitlistOfferCreated(offer = offer, hold = hold, rank = offer.candidateRank),
                reasonCode = reasonCode,
                correlationId = correlationId,
                occurredAt = occurredAt,
            )
    }
}

/** offer와 hold를 생성한 core transaction의 opaque 결과입니다. */
data class WaitlistOfferCreated(
    val offer: WaitlistOfferRecord,
    val hold: WaitlistCapacityHoldRecord,
    val rank: Int,
) : Serializable {
    init {
        require(rank > 0) { "rank must be positive" }
        require(offer.id == hold.offerId) { "offer and hold must be linked" }
        require(offer.scope == hold.scope) { "offer and hold must share scope" }
    }

    val offerId: Long get() = offer.id
    val holdId: Long get() = hold.id

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** core transaction에서만 호출되는 notification enqueue port입니다. */
fun interface WaitlistOfferNotificationPort {
    fun enqueue(draft: WaitlistOfferNotificationDraft)
}

/** 알림을 사용하지 않는 단위 경로용 no-op adapter입니다. */
object NoopWaitlistOfferNotificationPort : WaitlistOfferNotificationPort {
    override fun enqueue(draft: WaitlistOfferNotificationDraft) = Unit
}

/** vacancy delivery가 반환하는 bounded terminal 결과입니다. */
sealed interface WaitlistDeliveryResult : Serializable {
    data class Offered(
        val offerId: Long,
        val holdId: Long? = null,
        val vacancyJobId: Long? = null,
    ) : WaitlistDeliveryResult {
        init {
            offerId.requirePositiveNumber("offerId")
            holdId?.requirePositiveNumber("holdId")
            vacancyJobId?.requirePositiveNumber("vacancyJobId")
        }

        private companion object {
            const val serialVersionUID: Long = 1L
        }
    }

    data class NoCandidate(
        val vacancyJobId: Long,
    ) : WaitlistDeliveryResult {
        init {
            vacancyJobId.requirePositiveNumber("vacancyJobId")
        }

        private companion object {
            const val serialVersionUID: Long = 1L
        }
    }

    data class Expired(
        val vacancyJobId: Long,
    ) : WaitlistDeliveryResult {
        init {
            vacancyJobId.requirePositiveNumber("vacancyJobId")
        }

        private companion object {
            const val serialVersionUID: Long = 1L
        }
    }
}

/** terminal offer 이후 다음 vacancy generation 생성 결과입니다. */
data class WaitlistGenerationProgression(
    val previousGeneration: Long,
    val nextGeneration: Long?,
    val reasonCode: WaitlistReasonCode,
    val vacancyJobId: Long? = null,
) : Serializable {
    init {
        require(previousGeneration > 0L) { "previousGeneration must be positive" }
        nextGeneration?.let { require(it > previousGeneration) { "nextGeneration must be later" } }
        vacancyJobId?.requirePositiveNumber("vacancyJobId")
    }

    private companion object {
        const val serialVersionUID: Long = 1L
    }
}
