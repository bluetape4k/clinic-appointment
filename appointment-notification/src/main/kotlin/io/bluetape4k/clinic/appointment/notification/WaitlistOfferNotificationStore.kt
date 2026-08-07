package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.clinic.appointment.event.notification.NotificationFailureCode
import io.bluetape4k.clinic.appointment.event.notification.NotificationSuppressionReasonCode
import io.bluetape4k.clinic.appointment.event.waitlist.WaitlistNotificationOutboxEvents
import io.bluetape4k.clinic.appointment.event.waitlist.WaitlistNotificationOutboxStatus
import io.bluetape4k.clinic.appointment.event.waitlist.WaitlistNotificationOutboxCodec
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistCapacityHoldState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistEntryState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistOfferState
import io.bluetape4k.clinic.appointment.repository.waitlist.WaitlistRepository
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.Serializable
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** provider IO와 DB claim 사이를 연결하는 waitlist notification 결과입니다. */
sealed interface WaitlistNotificationDeliveryResult : Serializable {
    data class Sent(
        val providerMessageReference: io.bluetape4k.clinic.appointment.event.notification.NotificationProviderMessageReference? = null,
    ) : WaitlistNotificationDeliveryResult

    data class Retryable(
        val failureCode: NotificationFailureCode,
    ) : WaitlistNotificationDeliveryResult

    data class Suppressed(
        val reason: NotificationSuppressionReasonCode,
    ) : WaitlistNotificationDeliveryResult

    data object Unknown : WaitlistNotificationDeliveryResult
}

/** waitlist outbox 처리의 외부 관측 결과입니다. */
enum class DeliveryOutcome {
    IDLE,
    DISABLED,
    SENT,
    RETRY_SCHEDULED,
    SUPPRESSED,
    UNKNOWN,
    LEASE_LOST,
}

typealias WaitlistDeliveryOutcome = DeliveryOutcome

/** provider 호출 직전 재검증에 필요한 opaque claim snapshot입니다. */
data class WaitlistOfferNotificationClaim(
    val outboxId: Long,
    val tenantGroupId: Long,
    val clinicId: Long,
    val offerId: Long,
    val holdId: Long,
    val waitlistEntryId: Long,
    val memberId: MemberId?,
    val idempotencyKey: String,
    val reasonCode: String,
    val correlationId: String,
    val offerState: WaitlistOfferState,
    val entryState: WaitlistEntryState,
    val holdState: WaitlistCapacityHoldState,
    val offerExpiresAt: Instant,
    val slotStartsAt: Instant,
    val slotEndsAt: Instant = slotStartsAt.plusSeconds(1),
    val holdExpiresAt: Instant?,
    val deliveryDeadline: Instant,
    val attemptNumber: Int,
    val leaseOwner: String,
    val leaseToken: String,
    val leaseUntil: Instant,
    val suppressionReason: NotificationSuppressionReasonCode? = null,
) : Serializable {
    init {
        require(outboxId > 0L) { "outboxId must be positive" }
        require(tenantGroupId > 0L) { "tenantGroupId must be positive" }
        require(clinicId > 0L) { "clinicId must be positive" }
        require(offerId > 0L) { "offerId must be positive" }
        require(holdId > 0L) { "holdId must be positive" }
        require(waitlistEntryId > 0L) { "waitlistEntryId must be positive" }
        require(idempotencyKey.isNotBlank()) { "idempotencyKey must not be blank" }
        require(reasonCode.isNotBlank()) { "reasonCode must not be blank" }
        require(correlationId.isNotBlank()) { "correlationId must not be blank" }
        require(attemptNumber > 0) { "attemptNumber must be positive" }
        require(leaseOwner.isNotBlank()) { "leaseOwner must not be blank" }
        require(leaseToken.isNotBlank()) { "leaseToken must not be blank" }
        require(!deliveryDeadline.isAfter(offerExpiresAt)) {
            "deliveryDeadline must not be after offerExpiresAt"
        }
        require(!deliveryDeadline.isAfter(slotStartsAt)) {
            "deliveryDeadline must not be after slotStartsAt"
        }
        require(slotStartsAt < slotEndsAt) { "slotStartsAt must be before slotEndsAt" }
    }

    val sendable: Boolean
        get() = memberId != null && suppressionReason == null

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** waitlist notification worker가 사용하는 caller-independent store port입니다. */
interface WaitlistOfferNotificationStore {
    suspend fun claim(
        now: Instant,
        owner: String,
    ): WaitlistOfferNotificationClaim?

    suspend fun authorizeSend(
        claim: WaitlistOfferNotificationClaim,
        now: Instant,
    ): Boolean

    suspend fun recordResult(
        claim: WaitlistOfferNotificationClaim,
        result: WaitlistNotificationDeliveryResult,
        now: Instant,
    ): Boolean
}

/**
 * waitlist notification outbox의 JDBC 구현입니다.
 *
 * claim, pre-send authorize, result CAS만 짧은 transaction으로 실행합니다. 회원 directory와
 * provider 호출은 이 클래스 밖에서 수행되므로 외부 latency가 appointment row lock을
 * 보유하지 않습니다.
 */
class JdbcWaitlistOfferNotificationStore(
    private val database: Database,
    private val waitlistRepository: WaitlistRepository,
    private val leaseDuration: Duration = Duration.ofSeconds(60),
    private val maxAttempts: Int = 6,
    private val retryDelay: Duration = Duration.ofSeconds(5),
    private val codec: WaitlistNotificationOutboxCodec = WaitlistNotificationOutboxCodec(),
) : WaitlistOfferNotificationStore {

    init {
        require(!leaseDuration.isZero && !leaseDuration.isNegative) { "leaseDuration must be positive" }
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        require(!retryDelay.isNegative) { "retryDelay must not be negative" }
    }

    override suspend fun claim(
        now: Instant,
        owner: String,
    ): WaitlistOfferNotificationClaim? = transaction(database) {
        validateOwner(owner)
        val row = WaitlistNotificationOutboxEvents
            .selectAll()
            .where {
                readyPredicate(now) or reclaimablePredicate(now)
            }
            .orderBy(
                WaitlistNotificationOutboxEvents.availableAt to SortOrder.ASC,
                WaitlistNotificationOutboxEvents.id to SortOrder.ASC,
            )
            .limit(1)
            .forUpdate()
            .singleOrNull()
            ?: return@transaction null

        val attemptNumber = row[WaitlistNotificationOutboxEvents.attemptNumber] + 1
        val leaseToken = UUID.randomUUID().toString()
        val leaseUntil = now.plus(leaseDuration)
        val claimed = WaitlistNotificationOutboxEvents.update({
            WaitlistNotificationOutboxEvents.id eq row[WaitlistNotificationOutboxEvents.id].value
        }) {
            it[status] = WaitlistNotificationOutboxStatus.PROCESSING
            it[leaseOwner] = owner
            it[WaitlistNotificationOutboxEvents.leaseToken] = leaseToken
            it[this.leaseUntil] = leaseUntil
            it[this.attemptNumber] = attemptNumber
            it[updatedAt] = now
        }
        if (claimed != 1) return@transaction null

        val stored = row.toStoredRow(attemptNumber, owner, leaseToken, leaseUntil)
        val envelope = runCatching { codec.decode(stored.payloadJson) }.getOrNull()
        val offer = waitlistRepository.findOfferByIdForUpdate(
            tenantGroupId = stored.tenantGroupId,
            clinicId = stored.clinicId,
            offerId = stored.offerId,
        )
        if (offer == null || offer.waitlistEntryId != stored.waitlistEntryId) {
            markSuppressed(stored, now)
            return@transaction null
        }

// outbox claim 이후 canonical lock 순서: offer -> entry -> hold
        val entry = waitlistRepository.findEntryForUpdate(offer.scope, offer.waitlistEntryId)
        val hold = waitlistRepository.findHoldByOfferForUpdate(offer.scope, offer.id)
        val payloadMatches = envelope?.let {
            it.tenantGroupId == stored.tenantGroupId &&
                it.clinicId == stored.clinicId &&
                it.offerId == stored.offerId &&
                it.holdId == stored.holdId &&
                it.waitlistEntryId == stored.waitlistEntryId
        } == true
        if (!payloadMatches || entry == null || hold == null || hold.id != stored.holdId) {
            val claim = stored.toClaim(
                offer = offer,
                entryState = entry?.status ?: WaitlistEntryState.WITHDRAWN,
                holdState = hold?.status ?: WaitlistCapacityHoldState.RELEASED,
                holdExpiresAt = hold?.holdExpiresAt,
                suppressionReason = NotificationSuppressionReasonCode.WAITLIST_OFFER_NOT_ACTIVE,
            )
            markSuppressed(stored, now)
            return@transaction claim
        }

        val deadline = minOf(offer.expiresAt, offer.startsAt, hold.holdExpiresAt)
        val suppressionReason = when {
            offer.status != WaitlistOfferState.OFFERED ||
                entry.status != WaitlistEntryState.OFFERED ||
                hold.status != WaitlistCapacityHoldState.OFFERED ->
                NotificationSuppressionReasonCode.WAITLIST_OFFER_NOT_ACTIVE
            !deadline.isAfter(now) -> NotificationSuppressionReasonCode.WAITLIST_OFFER_EXPIRED
            else -> null
        }
        val claim = stored.toClaim(
            offer = offer,
            entryState = entry.status,
            holdState = hold.status,
            holdExpiresAt = hold.holdExpiresAt,
            suppressionReason = suppressionReason,
        )
        if (suppressionReason != null) markSuppressed(stored, now)
        claim
    }

    override suspend fun authorizeSend(
        claim: WaitlistOfferNotificationClaim,
        now: Instant,
    ): Boolean = transaction(database) {
        val row = findClaimRow(claim) ?: return@transaction false
        if (!row.hasLiveFence(claim, now)) return@transaction false
        val offer = waitlistRepository.findOfferByIdForUpdate(
            tenantGroupId = claim.tenantGroupId,
            clinicId = claim.clinicId,
            offerId = claim.offerId,
        ) ?: run {
            markSuppressed(row, now)
            return@transaction false
        }
        if (offer.scope.memberId != claim.memberId || offer.waitlistEntryId != claim.waitlistEntryId) {
            markSuppressed(row, now)
            return@transaction false
        }
        val entry = waitlistRepository.findEntryForUpdate(offer.scope, offer.waitlistEntryId)
        val hold = waitlistRepository.findHoldByOfferForUpdate(offer.scope, offer.id)
        val deadline = hold?.let { minOf(offer.expiresAt, offer.startsAt, it.holdExpiresAt) }
        val valid =
            entry?.status == WaitlistEntryState.OFFERED &&
                offer.status == WaitlistOfferState.OFFERED &&
                hold?.status == WaitlistCapacityHoldState.OFFERED &&
                deadline != null && deadline.isAfter(now) &&
                deadline == claim.deliveryDeadline
        if (!valid) markSuppressed(row, now)
        valid
    }

    override suspend fun recordResult(
        claim: WaitlistOfferNotificationClaim,
        result: WaitlistNotificationDeliveryResult,
        now: Instant,
    ): Boolean = transaction(database) {
        val row = findClaimRow(claim) ?: return@transaction false
        if (!row.hasLiveFence(claim, now)) return@transaction false

        val nextStatus: WaitlistNotificationOutboxStatus
        val nextAvailableAt: Instant
        val terminal: Boolean
        when (result) {
            is WaitlistNotificationDeliveryResult.Sent -> {
                nextStatus = WaitlistNotificationOutboxStatus.SENT
                nextAvailableAt = row.availableAt
                terminal = true
            }
            is WaitlistNotificationDeliveryResult.Suppressed -> {
                nextStatus = WaitlistNotificationOutboxStatus.SUPPRESSED
                nextAvailableAt = row.availableAt
                terminal = true
            }
            WaitlistNotificationDeliveryResult.Unknown -> {
// provider 결과는 자동 replay하기에 안전하지 않다. 전용 history 테이블이 도입될 때까지
// EXHAUSTED를 지속적인 manual-review marker로 사용한다.
                nextStatus = WaitlistNotificationOutboxStatus.EXHAUSTED
                nextAvailableAt = row.availableAt
                terminal = true
            }
            is WaitlistNotificationDeliveryResult.Retryable -> {
                val retryAt = now.plus(retryDelay)
                val beforeDeadline = retryAt.isBefore(claim.deliveryDeadline)
                if (row.attemptNumber >= maxAttempts || !beforeDeadline) {
                    nextStatus = if (!claim.deliveryDeadline.isAfter(now)) {
                        WaitlistNotificationOutboxStatus.SUPPRESSED
                    } else {
                        WaitlistNotificationOutboxStatus.EXHAUSTED
                    }
                    nextAvailableAt = row.availableAt
                    terminal = true
                } else {
                    nextStatus = WaitlistNotificationOutboxStatus.RETRY_WAIT
                    nextAvailableAt = retryAt
                    terminal = false
                }
            }
        }

        WaitlistNotificationOutboxEvents.update({
            WaitlistNotificationOutboxEvents.id eq claim.outboxId
        }) {
            it[status] = nextStatus
            it[availableAt] = nextAvailableAt
            it[leaseOwner] = null
            it[leaseToken] = null
            it[leaseUntil] = null
            it[updatedAt] = now
            it[terminalAt] = now.takeIf { terminal }
        } == 1
    }

    private fun findClaimRow(claim: WaitlistOfferNotificationClaim): StoredOutboxRow? =
        WaitlistNotificationOutboxEvents
            .selectAll()
            .where { WaitlistNotificationOutboxEvents.id eq claim.outboxId }
            .forUpdate()
            .singleOrNull()
            ?.toStoredRow()

    private fun markSuppressed(row: StoredOutboxRow, now: Instant) {
        WaitlistNotificationOutboxEvents.update({
            (WaitlistNotificationOutboxEvents.id eq row.id) and
                (WaitlistNotificationOutboxEvents.status eq WaitlistNotificationOutboxStatus.PROCESSING) and
                (WaitlistNotificationOutboxEvents.leaseOwner eq row.leaseOwner) and
                (WaitlistNotificationOutboxEvents.leaseToken eq row.leaseToken)
        }) {
            it[status] = WaitlistNotificationOutboxStatus.SUPPRESSED
            it[leaseOwner] = null
            it[leaseToken] = null
            it[leaseUntil] = null
            it[updatedAt] = now
            it[terminalAt] = now
        }
    }

    private fun readyPredicate(now: Instant) =
        ((WaitlistNotificationOutboxEvents.status eq WaitlistNotificationOutboxStatus.PENDING) or
            (WaitlistNotificationOutboxEvents.status eq WaitlistNotificationOutboxStatus.RETRY_WAIT)) and
            (WaitlistNotificationOutboxEvents.availableAt lessEq now)

    private fun reclaimablePredicate(now: Instant) =
        (WaitlistNotificationOutboxEvents.status eq WaitlistNotificationOutboxStatus.PROCESSING) and
            WaitlistNotificationOutboxEvents.leaseUntil.isNotNull() and
            (WaitlistNotificationOutboxEvents.leaseUntil lessEq now)

    private fun validateOwner(owner: String) {
        require(owner.isNotBlank() && owner.length <= 128) { "owner must contain 1..128 characters" }
    }

    private data class StoredOutboxRow(
        val id: Long,
        val status: WaitlistNotificationOutboxStatus,
        val idempotencyKey: String,
        val tenantGroupId: Long,
        val clinicId: Long,
        val offerId: Long,
        val holdId: Long,
        val waitlistEntryId: Long,
        val reasonCode: String,
        val correlationId: String,
        val payloadJson: String,
        val availableAt: Instant,
        val attemptNumber: Int,
        val leaseOwner: String?,
        val leaseToken: String?,
        val leaseUntil: Instant?,
    ) {
        fun hasLiveFence(claim: WaitlistOfferNotificationClaim, now: Instant): Boolean =
            status == WaitlistNotificationOutboxStatus.PROCESSING &&
                leaseOwner == claim.leaseOwner &&
                leaseToken == claim.leaseToken &&
                attemptNumber == claim.attemptNumber &&
                leaseUntil != null && leaseUntil.isAfter(now)

        fun toClaim(
            offer: io.bluetape4k.clinic.appointment.model.waitlist.WaitlistOfferRecord,
            entryState: WaitlistEntryState,
            holdState: WaitlistCapacityHoldState,
            holdExpiresAt: Instant?,
            suppressionReason: NotificationSuppressionReasonCode?,
        ): WaitlistOfferNotificationClaim {
            val deadline = minOf(offer.expiresAt, offer.startsAt, holdExpiresAt ?: offer.expiresAt)
            return WaitlistOfferNotificationClaim(
                outboxId = id,
                tenantGroupId = tenantGroupId,
                clinicId = clinicId,
                offerId = offerId,
                holdId = holdId,
                waitlistEntryId = waitlistEntryId,
                memberId = offer.scope.memberId,
                idempotencyKey = idempotencyKey,
                reasonCode = reasonCode,
                correlationId = correlationId,
                offerState = offer.status,
                entryState = entryState,
                holdState = holdState,
                offerExpiresAt = offer.expiresAt,
                slotStartsAt = offer.startsAt,
                slotEndsAt = offer.endsAt,
                holdExpiresAt = holdExpiresAt,
                deliveryDeadline = deadline,
                attemptNumber = attemptNumber,
                leaseOwner = checkNotNull(leaseOwner),
                leaseToken = checkNotNull(leaseToken),
                leaseUntil = checkNotNull(leaseUntil),
                suppressionReason = suppressionReason,
            )
        }
    }

    private fun ResultRow.toStoredRow(
        attemptNumberOverride: Int? = null,
        leaseOwnerOverride: String? = null,
        leaseTokenOverride: String? = null,
        leaseUntilOverride: Instant? = null,
    ): StoredOutboxRow =
        StoredOutboxRow(
            id = this[WaitlistNotificationOutboxEvents.id].value,
            status = this[WaitlistNotificationOutboxEvents.status],
            idempotencyKey = this[WaitlistNotificationOutboxEvents.idempotencyKey],
            tenantGroupId = this[WaitlistNotificationOutboxEvents.tenantGroupId],
            clinicId = this[WaitlistNotificationOutboxEvents.clinicId],
            offerId = this[WaitlistNotificationOutboxEvents.offerId],
            holdId = this[WaitlistNotificationOutboxEvents.holdId],
            waitlistEntryId = this[WaitlistNotificationOutboxEvents.waitlistEntryId],
            reasonCode = this[WaitlistNotificationOutboxEvents.reasonCode],
            correlationId = this[WaitlistNotificationOutboxEvents.correlationId],
            payloadJson = this[WaitlistNotificationOutboxEvents.payloadJson],
            availableAt = this[WaitlistNotificationOutboxEvents.availableAt],
            attemptNumber = attemptNumberOverride ?: this[WaitlistNotificationOutboxEvents.attemptNumber],
            leaseOwner = leaseOwnerOverride ?: this[WaitlistNotificationOutboxEvents.leaseOwner],
            leaseToken = leaseTokenOverride ?: this[WaitlistNotificationOutboxEvents.leaseToken],
            leaseUntil = leaseUntilOverride ?: this[WaitlistNotificationOutboxEvents.leaseUntil],
        )
}
