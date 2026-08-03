package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.event.notification.NotificationChannelType
import io.bluetape4k.clinic.appointment.event.notification.NotificationFailureCode
import io.bluetape4k.clinic.appointment.event.notification.NotificationSuppressionReasonCode
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistCapacityHoldState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistEntryState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistOfferState
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

internal class WaitlistOfferNotificationLifecycleTest {

    @Test
    fun `retry deadline은 offer expiry를 넘지 않는다`() = runBlocking {
        FailureChannel.calls = 0
        val now = Instant.parse("2026-08-03T01:00:00Z")
        val claim = claim(now.plusSeconds(2))
        val store = LifecycleStore(claim)
        val worker = WaitlistOfferNotificationWorker(
            store = store,
            profileResolver = MemberNotificationProfileResolver {
                MemberNotificationProfileResult.Resolved(resolvedProfile())
            },
            channel = FailureChannel,
            requestRenderer = WaitlistOfferNotificationRequestRenderer { _, profile ->
                NotificationProviderRequest(
                    channel = NotificationChannelType.DUMMY,
                    destination = checkNotNull(profile.destination),
                    idempotencyKey = NotificationProviderIdempotencyKey("hmac-v1.${"B".repeat(43)}"),
                    templateKey = WAITLIST_SLOT_OFFER_TEMPLATE_KEY,
                    templateVersion = WAITLIST_SLOT_OFFER_TEMPLATE_VERSION,
                    rendered = RenderedNotificationTemplate(null, "body", null),
                )
            },
            clock = java.time.Clock.fixed(now, java.time.ZoneOffset.UTC),
        )

        worker.runOnce().outcome shouldBeEqualTo DeliveryOutcome.RETRY_SCHEDULED
        store.recordedResult shouldBeEqualTo WaitlistNotificationDeliveryResult.Retryable(
            NotificationFailureCode.PROVIDER_UNAVAILABLE,
        )
        Unit
    }

    @Test
    fun `terminal suppression은 provider를 호출하지 않고 offer state를 변경하지 않는다`() = runBlocking {
        FailureChannel.calls = 0
        val now = Instant.parse("2026-08-03T01:00:00Z")
        val claim = claim(now.plusSeconds(30)).copy(
            suppressionReason = NotificationSuppressionReasonCode.WAITLIST_OFFER_NOT_ACTIVE,
            offerState = WaitlistOfferState.EXPIRED,
        )
        val store = LifecycleStore(claim)
        val worker = WaitlistOfferNotificationWorker(
            store = store,
            profileResolver = MemberNotificationProfileResolver { error("must not resolve terminal offer") },
            channel = FailureChannel,
            clock = java.time.Clock.fixed(now, java.time.ZoneOffset.UTC),
        )

        worker.runOnce().outcome shouldBeEqualTo DeliveryOutcome.SUPPRESSED
        store.recordedResult shouldBeEqualTo null
        store.offerState shouldBeEqualTo WaitlistOfferState.EXPIRED
        FailureChannel.calls shouldBeEqualTo 0
        Unit
    }

    private class LifecycleStore(
        private val claim: WaitlistOfferNotificationClaim,
    ) : WaitlistOfferNotificationStore {
        var recordedResult: WaitlistNotificationDeliveryResult? = null
        var offerState: WaitlistOfferState = claim.offerState

        override suspend fun claim(now: Instant, owner: String): WaitlistOfferNotificationClaim = claim
        override suspend fun authorizeSend(claim: WaitlistOfferNotificationClaim, now: Instant): Boolean = true
        override suspend fun recordResult(
            claim: WaitlistOfferNotificationClaim,
            result: WaitlistNotificationDeliveryResult,
            now: Instant,
        ): Boolean {
            recordedResult = result
            return true
        }
    }

    private object FailureChannel : NotificationChannel {
        override val channelType: NotificationChannelType = NotificationChannelType.DUMMY
        var calls: Int = 0

        override fun send(request: NotificationProviderRequest): NotificationProviderResult {
            calls++
            return NotificationProviderResult.RetryableFailure(NotificationFailureCode.PROVIDER_UNAVAILABLE)
        }
    }

    private companion object {
        fun claim(deadline: Instant) =
            WaitlistOfferNotificationClaim(
                outboxId = 2L,
                tenantGroupId = 10L,
                clinicId = 20L,
                offerId = 30L,
                holdId = 40L,
                waitlistEntryId = 50L,
                memberId = MemberId("member-1"),
                idempotencyKey = "wl-notification-v1:${"c".repeat(64)}",
                reasonCode = "OFFER_CREATED",
                correlationId = "corr-2",
                offerState = WaitlistOfferState.OFFERED,
                entryState = WaitlistEntryState.OFFERED,
                holdState = WaitlistCapacityHoldState.OFFERED,
                offerExpiresAt = deadline.plusSeconds(5),
                slotStartsAt = deadline.plusSeconds(10),
                slotEndsAt = deadline.plusSeconds(20),
                holdExpiresAt = deadline.plusSeconds(2),
                deliveryDeadline = deadline,
                attemptNumber = 1,
                leaseOwner = "test-worker",
                leaseToken = "token-2",
                leaseUntil = deadline.plusSeconds(60),
            )

        fun resolvedProfile() = MemberNotificationProfile(
            displayName = "Member",
            destination = "opaque-destination",
            locale = Locale.KOREA,
            consent = NotificationConsent(),
            tenantGroupId = io.bluetape4k.clinic.appointment.event.notification.TenantGroupId(10L),
            clinicId = io.bluetape4k.clinic.appointment.event.notification.ClinicId(20L),
        )
    }
}
