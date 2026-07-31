package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.event.notification.AppointmentConfirmedParameters
import io.bluetape4k.clinic.appointment.event.notification.AppointmentId
import io.bluetape4k.clinic.appointment.event.notification.ClaimedNotification
import io.bluetape4k.clinic.appointment.event.notification.ClinicId
import io.bluetape4k.clinic.appointment.event.notification.CompleteNotificationCommand
import io.bluetape4k.clinic.appointment.event.notification.NotificationAuditFingerprint
import io.bluetape4k.clinic.appointment.event.notification.NotificationCandidate
import io.bluetape4k.clinic.appointment.event.notification.NotificationChannelType
import io.bluetape4k.clinic.appointment.event.notification.NotificationEventId
import io.bluetape4k.clinic.appointment.event.notification.NotificationEventType
import io.bluetape4k.clinic.appointment.event.notification.NotificationFailureCode
import io.bluetape4k.clinic.appointment.event.notification.NotificationFairCursor
import io.bluetape4k.clinic.appointment.event.notification.NotificationIdempotencyKey
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxCodec
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxEnvelope
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxStatus
import io.bluetape4k.clinic.appointment.event.notification.NotificationParameterType
import io.bluetape4k.clinic.appointment.event.notification.NotificationProviderMessageReference
import io.bluetape4k.clinic.appointment.event.notification.NotificationSlot
import io.bluetape4k.clinic.appointment.event.notification.NotificationSuppressionReasonCode
import io.bluetape4k.clinic.appointment.event.notification.NotificationTemplateKey
import io.bluetape4k.clinic.appointment.event.notification.NotificationTemplateVersion
import io.bluetape4k.clinic.appointment.event.notification.RetryNotificationCommand
import io.bluetape4k.clinic.appointment.event.notification.TenantGroupId
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

internal class NotificationOutboxEndToEndTest {

    private val now = Instant.parse("2026-07-31T00:00:00Z")
    private val codec = NotificationOutboxCodec()
    private val persistedDigest = "a".repeat(64)

    @Test
    fun `profile template provider 성공은 fenced SENT로 완료하고 provider idempotency key는 raw ID를 포함하지 않는다`() {
        runBlocking {
            val store = E2EWorkStore()
            val provider = CapturingChannel(NotificationProviderResult.accepted(NotificationProviderMessageReference("provider-1")))
            val worker = worker(store, provider)

            val result = worker.process(claimed())

            result shouldBeEqualTo NotificationOutboxWorkerResult.COMPLETED
            store.completed.single().terminalStatus shouldBeEqualTo NotificationOutboxStatus.SENT
            provider.requests.single().rendered.textBody.contains("서울클리닉") shouldBeEqualTo true
            provider.requests.single().idempotencyKey.value.contains("member-1") shouldBeEqualTo false
            provider.requests.single().idempotencyKey.value.contains("appointment") shouldBeEqualTo false
        }
    }

    @Test
    fun `동의 거부 profile은 provider를 호출하지 않고 SUPPRESSED로 fenced complete 한다`() {
        runBlocking {
            val store = E2EWorkStore()
            val provider = CapturingChannel(NotificationProviderResult.accepted())
            val worker = worker(
                store = store,
                provider = provider,
                profile = profile(consent = NotificationConsent(sms = false)),
            )

            val result = worker.process(claimed())

            result shouldBeEqualTo NotificationOutboxWorkerResult.COMPLETED
            provider.requests.size shouldBeEqualTo 0
            store.completed.single().terminalStatus shouldBeEqualTo NotificationOutboxStatus.SUPPRESSED
            store.completed.single().suppressionReason shouldBeEqualTo NotificationSuppressionReasonCode.CONSENT_DENIED
        }
    }

    @Test
    fun `provider retryable failure는 retry wait 후 budget 소진 시 exhausted가 된다`() {
        runBlocking {
            val retryStore = E2EWorkStore()
            val exhaustedStore = E2EWorkStore()

            worker(retryStore, CapturingChannel(NotificationProviderResult.retry(NotificationFailureCode.PROVIDER_UNAVAILABLE)))
                .process(claimed(attemptNumber = 2)) shouldBeEqualTo NotificationOutboxWorkerResult.RETRY_SCHEDULED
            worker(exhaustedStore, CapturingChannel(NotificationProviderResult.retry(NotificationFailureCode.PROVIDER_UNAVAILABLE)))
                .process(claimed(attemptNumber = 6)) shouldBeEqualTo NotificationOutboxWorkerResult.EXHAUSTED

            retryStore.retried.single().failureCode shouldBeEqualTo NotificationFailureCode.PROVIDER_UNAVAILABLE
            exhaustedStore.completed.single().terminalStatus shouldBeEqualTo NotificationOutboxStatus.EXHAUSTED
        }
    }

    @Test
    fun `provider 성공 뒤 completion 실패 crash window 복구는 같은 provider idempotency key를 사용한다`() {
        runBlocking {
            val firstStore = E2EWorkStore(completeResult = false)
            val firstProvider = CapturingChannel(NotificationProviderResult.accepted())
            worker(firstStore, firstProvider).process(claimed(attemptNumber = 1)) shouldBeEqualTo
                NotificationOutboxWorkerResult.LEASE_LOST

            val secondStore = E2EWorkStore()
            val secondProvider = CapturingChannel(NotificationProviderResult.retry(NotificationFailureCode.DELIVERY_RESULT_UNKNOWN))
            worker(secondStore, secondProvider).process(claimed(attemptNumber = 2)) shouldBeEqualTo
                NotificationOutboxWorkerResult.RETRY_SCHEDULED

            firstProvider.requests.single().idempotencyKey shouldBeEqualTo secondProvider.requests.single().idempotencyKey
            secondStore.retried.single().failureCode shouldBeEqualTo NotificationFailureCode.DELIVERY_RESULT_UNKNOWN
        }
    }

    @Test
    fun `provider typed exception은 durable retry로 닫는다`() {
        runBlocking {
            val store = E2EWorkStore()
            val provider = CapturingChannel {
                throw NotificationProviderException(NotificationFailureCode.PROVIDER_RATE_LIMITED)
            }

            worker(store, provider).process(claimed(attemptNumber = 2)) shouldBeEqualTo
                NotificationOutboxWorkerResult.RETRY_SCHEDULED

            store.retried.single().failureCode shouldBeEqualTo NotificationFailureCode.PROVIDER_RATE_LIMITED
        }
    }

    @Test
    fun `provider cancellation은 retry로 삼키지 않고 그대로 전파한다`() {
        val store = E2EWorkStore()
        val provider = CapturingChannel {
            throw CancellationException("provider cancelled")
        }

        assertThrows<CancellationException> {
            runBlocking { worker(store, provider).process(claimed()) }
        }

        store.completed.size shouldBeEqualTo 0
        store.retried.size shouldBeEqualTo 0
    }

    @Test
    fun `malformed payload는 worker 밖으로 새지 않고 닫힌 TEMPLATE_PARAMETER_INVALID retry가 된다`() {
        runBlocking {
            val store = E2EWorkStore()
            val provider = CapturingChannel(NotificationProviderResult.accepted())

            worker(store, provider).process(claimed(parametersJson = "{not-json")) shouldBeEqualTo
                NotificationOutboxWorkerResult.RETRY_SCHEDULED

            provider.requests.size shouldBeEqualTo 0
            store.retried.single().failureCode shouldBeEqualTo NotificationFailureCode.TEMPLATE_PARAMETER_INVALID
        }
    }

    @Test
    fun `runtime delivery dependency는 일부만 주입할 수 없다`() {
        assertThrows<IllegalArgumentException> {
            NotificationOutboxWorker(
                workStore = E2EWorkStore(),
                leaseOwner = "worker-a",
                profileResolver = MemberNotificationProfileResolver { MemberNotificationProfileResult.Resolved(profile()) },
            )
        }
    }

    private fun worker(
        store: E2EWorkStore,
        provider: CapturingChannel,
        profile: MemberNotificationProfile = profile(),
    ): NotificationOutboxWorker =
        NotificationOutboxWorker(
            workStore = store,
            leaseOwner = "worker-a",
            profileResolver = MemberNotificationProfileResolver { MemberNotificationProfileResult.Resolved(profile) },
            templateRenderer = NotificationTemplateRenderer(
                NotificationTemplateCatalog { key, version, channel ->
                    NotificationTemplate(
                        key = key,
                        version = version,
                        channel = channel,
                        fields = setOf("clinicDisplayName", "appointmentDate", "startTime"),
                        textTemplate = "{{profile.displayName}} {{clinicDisplayName}} {{appointmentDate}}",
                    )
                }
            ),
            providerChannel = provider,
            providerIdempotencyKeyFactory = NotificationProviderIdempotencyKeyFactory("s".repeat(32).toByteArray()),
            outboxCodec = codec,
        )

    private fun claimed(
        attemptNumber: Int = 1,
        parametersJson: String? = null,
    ): ClaimedNotification {
        val envelope = NotificationOutboxEnvelope(
            schemaVersion = NotificationOutboxEnvelope.CURRENT_SCHEMA_VERSION,
            eventId = NotificationEventId("event-1"),
            idempotencyKey = NotificationIdempotencyKey("opaque-key-1"),
            tenantGroupId = TenantGroupId(1L),
            clinicId = ClinicId(1L),
            appointmentId = AppointmentId(10L),
            memberId = MemberId("member-1"),
            channel = NotificationChannelType.SMS,
            eventType = NotificationEventType.CONFIRMED,
            notificationSlot = NotificationSlot.CONFIRMED,
            templateKey = NotificationTemplateKey("appointment.confirmed"),
            templateVersion = NotificationTemplateVersion(1),
            parameterType = NotificationParameterType.APPOINTMENT_CONFIRMED,
            parameters = AppointmentConfirmedParameters(
                clinicDisplayName = "서울클리닉",
                appointmentDate = LocalDate.parse("2026-08-01"),
                startTime = LocalTime.parse("09:00:00"),
            ),
            occurredAt = now,
            availableAt = now,
        )
        return ClaimedNotification(
            id = 100L,
            tenantGroupId = TenantGroupId(1L),
            clinicId = ClinicId(1L),
            appointmentId = AppointmentId(10L),
            memberId = MemberId("member-1"),
            idempotencyKey = NotificationIdempotencyKey(persistedDigest),
            owner = "worker-a",
            token = "token-1",
            attemptNumber = attemptNumber,
            leaseUntil = now.plusSeconds(30),
            firstAttemptAt = now.minus(Duration.ofMinutes(10)),
            claimedAt = now,
            channel = NotificationChannelType.SMS,
            eventType = NotificationEventType.CONFIRMED,
            notificationSlot = NotificationSlot.CONFIRMED,
            providerKey = "sms",
            templateKey = NotificationTemplateKey("appointment.confirmed"),
            templateVersion = NotificationTemplateVersion(1),
            parameterType = NotificationParameterType.APPOINTMENT_CONFIRMED,
            eventId = NotificationEventId("event-1"),
            parametersJson = parametersJson ?: codec.encode(envelope),
        )
    }

    private fun profile(consent: NotificationConsent = NotificationConsent()): MemberNotificationProfile =
        MemberNotificationProfile(
            displayName = "홍길동",
            destination = "+821012345678",
            locale = Locale.KOREAN,
            consent = consent,
            tenantGroupId = TenantGroupId(1L),
            clinicId = ClinicId(1L),
        )

    private class CapturingChannel(
        private val action: (NotificationProviderRequest) -> NotificationProviderResult,
    ) : NotificationChannel {
        constructor(result: NotificationProviderResult) : this({ result })

        val requests = mutableListOf<NotificationProviderRequest>()
        override val channelType: NotificationChannelType = NotificationChannelType.SMS

        override fun send(request: NotificationProviderRequest): NotificationProviderResult {
            requests += request
            return action(request)
        }
    }

    private class E2EWorkStore(
        private val completeResult: Boolean = true,
    ) : NotificationOutboxWorkStore {
        val completed = mutableListOf<CompleteNotificationCommand>()
        val retried = mutableListOf<RetryNotificationCommand>()

        override suspend fun findFairCandidates(limit: Int, cursor: NotificationFairCursor?): NotificationCandidatePage =
            NotificationCandidatePage(emptyList(), null)

        override suspend fun claim(id: Long, owner: String): ClaimedNotification? = null

        override suspend fun recoverExpired(limit: Int, owner: String): List<ClaimedNotification> = emptyList()

        override suspend fun complete(command: CompleteNotificationCommand): Boolean {
            completed += command
            return completeResult
        }

        override suspend fun retry(command: RetryNotificationCommand): Boolean {
            retried += command
            return true
        }

        override suspend fun currentDatabaseTime(): Instant = Instant.parse("2026-07-31T00:00:00Z")

        override suspend fun deleteTerminalBatch(
            status: NotificationOutboxStatus,
            retention: Duration,
            limit: Int,
        ): Int = 0
    }
}
