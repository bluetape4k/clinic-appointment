package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.event.notification.ClinicId
import io.bluetape4k.clinic.appointment.event.notification.NotificationChannelType
import io.bluetape4k.clinic.appointment.event.notification.NotificationFailureCode
import io.bluetape4k.clinic.appointment.event.notification.NotificationSuppressionReasonCode
import io.bluetape4k.clinic.appointment.event.notification.TenantGroupId
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistCapacityHoldState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistEntryState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistOfferState
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

internal class WaitlistOfferNotificationWorkerTest {

    @Test
    fun `provider 호출 직전 만료되면 전송하지 않는다`() = runBlocking {
        val clock = MutableTestClock(Instant.parse("2026-08-03T01:00:00Z"))
        val store = FakeWaitlistNotificationStore(
            claimResult = claimedOffer(clock.instant().plusMillis(50)),
        )
        store.authorize = { claim, now ->
            val allowed = claim.deliveryDeadline.isAfter(now)
            if (!allowed) store.recordedResult = WaitlistNotificationDeliveryResult.Suppressed(
                NotificationSuppressionReasonCode.WAITLIST_OFFER_EXPIRED,
            )
            allowed
        }
        clock.advanceMillis(51)
        val channel = RecordingNotificationChannel()
        val worker = worker(store, channel, clock)

        worker.runOnce().outcome shouldBeEqualTo DeliveryOutcome.SUPPRESSED
        channel.requests.size shouldBeEqualTo 0
        store.recordedResult shouldBeEqualTo WaitlistNotificationDeliveryResult.Suppressed(
            NotificationSuppressionReasonCode.WAITLIST_OFFER_EXPIRED,
        )
        Unit
    }

    @Test
    fun `profile과 provider IO는 store transaction 밖에서 실행된다`() = runBlocking {
        val clock = MutableTestClock(Instant.parse("2026-08-03T01:00:00Z"))
        val store = FakeWaitlistNotificationStore(claimedOffer(clock.instant().plusSeconds(30)))
        var profileInTransaction = false
        var providerInTransaction = false
        val resolver = MemberNotificationProfileResolver {
            profileInTransaction = store.inTransaction
            MemberNotificationProfileResult.Resolved(resolvedProfile())
        }
        val channel = RecordingNotificationChannel(beforeSend = { providerInTransaction = store.inTransaction })
        val worker = worker(store, channel, clock, resolver)

        worker.runOnce().outcome shouldBeEqualTo DeliveryOutcome.SENT
        profileInTransaction shouldBeEqualTo false
        providerInTransaction shouldBeEqualTo false
        store.recordedResult shouldBeEqualTo WaitlistNotificationDeliveryResult.Sent()
        Unit
    }

    @Test
    fun `unknown provider 결과는 자동 재전송하지 않고 manual review 결과로 남긴다`() = runBlocking {
        val clock = MutableTestClock(Instant.parse("2026-08-03T01:00:00Z"))
        val store = FakeWaitlistNotificationStore(claimedOffer(clock.instant().plusSeconds(30)))
        val channel = RecordingNotificationChannel(providerResult = { error("provider outcome unknown") })
        val worker = worker(store, channel, clock)

        worker.runOnce().outcome shouldBeEqualTo DeliveryOutcome.UNKNOWN
        store.recordedResult shouldBeEqualTo WaitlistNotificationDeliveryResult.Unknown
        channel.requests.size shouldBeEqualTo 1
        Unit
    }

    @Test
    fun `lease를 잃은 늦은 provider 결과는 offer를 되살리지 않는다`() = runBlocking {
        val clock = MutableTestClock(Instant.parse("2026-08-03T01:00:00Z"))
        val store = FakeWaitlistNotificationStore(claimedOffer(clock.instant().plusSeconds(30)))
        store.recordResultAccepted = false
        val channel = RecordingNotificationChannel()
        val worker = worker(store, channel, clock)

        worker.runOnce().outcome shouldBeEqualTo DeliveryOutcome.LEASE_LOST
        store.recordedResult shouldBeEqualTo WaitlistNotificationDeliveryResult.Sent()
        store.offerState shouldBeEqualTo WaitlistOfferState.OFFERED
        Unit
    }

    @Test
    fun `feature off는 claim과 provider를 모두 건너뛴다`() = runBlocking {
        val store = FakeWaitlistNotificationStore(null)
        val channel = RecordingNotificationChannel()
        val worker = worker(
            store = store,
            channel = channel,
            clock = MutableTestClock(Instant.parse("2026-08-03T01:00:00Z")),
            enabled = false,
        )

        worker.runOnce().outcome shouldBeEqualTo DeliveryOutcome.DISABLED
        store.claimCalls shouldBeEqualTo 0
        channel.requests.size shouldBeEqualTo 0
        Unit
    }

    private fun worker(
        store: FakeWaitlistNotificationStore,
        channel: RecordingNotificationChannel,
        clock: MutableTestClock,
        resolver: MemberNotificationProfileResolver = MemberNotificationProfileResolver {
            MemberNotificationProfileResult.Resolved(resolvedProfile())
        },
        enabled: Boolean = true,
    ): WaitlistOfferNotificationWorker =
        WaitlistOfferNotificationWorker(
            store = store,
            profileResolver = resolver,
            channel = channel,
            requestRenderer = WaitlistOfferNotificationRequestRenderer { _, profile ->
                NotificationProviderRequest(
                    channel = channel.channelType,
                    destination = checkNotNull(profile.destination),
                    idempotencyKey = NotificationProviderIdempotencyKey("hmac-v1.${"A".repeat(43)}"),
                    templateKey = WAITLIST_SLOT_OFFER_TEMPLATE_KEY,
                    templateVersion = WAITLIST_SLOT_OFFER_TEMPLATE_VERSION,
                    rendered = RenderedNotificationTemplate(null, "waitlist offer", null),
                )
            },
            clock = clock,
            enabled = enabled,
        )

    private class RecordingNotificationChannel(
        private val beforeSend: () -> Unit = {},
        private val providerResult: () -> NotificationProviderResult = {
            NotificationProviderResult.accepted()
        },
    ) : NotificationChannel {
        override val channelType: NotificationChannelType = NotificationChannelType.DUMMY
        val requests = mutableListOf<NotificationProviderRequest>()

        override fun send(request: NotificationProviderRequest): NotificationProviderResult {
            requests += request
            beforeSend()
            return providerResult()
        }
    }

    private class FakeWaitlistNotificationStore(
        var claimResult: WaitlistOfferNotificationClaim?,
    ) : WaitlistOfferNotificationStore {
        var claimCalls: Int = 0
        var inTransaction: Boolean = false
        var recordedResult: WaitlistNotificationDeliveryResult? = null
        var recordResultAccepted: Boolean = true
        var offerState: WaitlistOfferState = WaitlistOfferState.OFFERED
        var authorize: (WaitlistOfferNotificationClaim, Instant) -> Boolean = { _, _ -> true }

        override suspend fun claim(now: Instant, owner: String): WaitlistOfferNotificationClaim? {
            claimCalls++
            inTransaction = true
            val result = claimResult
            inTransaction = false
            return result
        }

        override suspend fun authorizeSend(claim: WaitlistOfferNotificationClaim, now: Instant): Boolean {
            inTransaction = true
            val result = authorize(claim, now)
            inTransaction = false
            return result
        }

        override suspend fun recordResult(
            claim: WaitlistOfferNotificationClaim,
            result: WaitlistNotificationDeliveryResult,
            now: Instant,
        ): Boolean {
            inTransaction = true
            recordedResult = result
            inTransaction = false
            return recordResultAccepted
        }
    }

    private class MutableTestClock(
        private var current: Instant,
    ) : Clock() {
        override fun instant(): Instant = current
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId): Clock = this

        fun advanceMillis(millis: Long) {
            current = current.plusMillis(millis)
        }
    }

    private companion object {
        fun claimedOffer(deadline: Instant) =
            WaitlistOfferNotificationClaim(
                outboxId = 1L,
                tenantGroupId = 10L,
                clinicId = 20L,
                offerId = 30L,
                holdId = 40L,
                waitlistEntryId = 50L,
                memberId = MemberId("member-1"),
                idempotencyKey = "wl-notification-v1:${"a".repeat(64)}",
                reasonCode = "OFFER_CREATED",
                correlationId = "corr-1",
                offerState = WaitlistOfferState.OFFERED,
                entryState = WaitlistEntryState.OFFERED,
                holdState = WaitlistCapacityHoldState.OFFERED,
                offerExpiresAt = deadline.plusSeconds(10),
                slotStartsAt = deadline.plusSeconds(20),
                slotEndsAt = deadline.plusSeconds(40),
                holdExpiresAt = deadline.plusSeconds(5),
                deliveryDeadline = deadline,
                attemptNumber = 1,
                leaseOwner = "test-worker",
                leaseToken = "token-1",
                leaseUntil = deadline.plusSeconds(60),
            )

        fun resolvedProfile() = MemberNotificationProfile(
            displayName = "Member",
            destination = "opaque-destination",
            locale = Locale.KOREA,
            consent = NotificationConsent(),
            tenantGroupId = TenantGroupId(10L),
            clinicId = ClinicId(20L),
        )
    }
}
