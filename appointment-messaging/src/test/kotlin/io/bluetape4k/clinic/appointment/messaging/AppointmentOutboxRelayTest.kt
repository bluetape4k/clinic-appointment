package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.clinic.appointment.service.AppointmentCommandContext
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.Clock
import java.time.ZoneOffset
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CancellationException
import org.apache.kafka.common.errors.AuthorizationException

class AppointmentOutboxRelayTest {
    @Test
    fun `all allow listed topics must pass metadata readiness`() {
        val envelope = envelope()
        val topicA = AppointmentTopic(DefaultAppointmentOutboxWriter.DEFAULT_TOPIC)
        val topicB = AppointmentTopic("clinic.appointment.secondary")
        val store = RecordingStore(claim(envelope).copy(topic = topicA))
        val publisher = ProbePublisher(availableTopics = setOf(topicA))
        val readiness = AppointmentMessagingReadinessProbe()
        val relay = AppointmentOutboxRelay(
            store = store,
            publisher = publisher,
            allowedTopics = setOf(topicA, topicB),
            readiness = readiness,
        )

        relay.tick("relay-a") shouldBeEqualTo AppointmentRelayTickResult(0, 0, 0, 0, 0)
        store.claimCalls shouldBeEqualTo 0
        publisher.probedTopics.toSet() shouldBeEqualTo setOf(topicA, topicB)
    }

    @Test
    fun `broker metadata readiness prevents claiming before topic is available`() {
        val envelope = envelope()
        val store = RecordingStore(claim(envelope))
        val topic = AppointmentTopic(DefaultAppointmentOutboxWriter.DEFAULT_TOPIC)
        val publisher = ProbePublisher(availableTopics = emptySet())
        val readiness = AppointmentMessagingReadinessProbe()
        val relay = AppointmentOutboxRelay(
            store = store,
            publisher = publisher,
            readiness = readiness,
        )

        relay.tick("relay-a") shouldBeEqualTo AppointmentRelayTickResult(0, 0, 0, 0, 0)
        store.claimCalls shouldBeEqualTo 0
        readiness.snapshot().brokerAvailable shouldBeEqualTo false

        publisher.availableTopics = setOf(topic)
        relay.tick("relay-a") shouldBeEqualTo AppointmentRelayTickResult(1, 1, 0, 0, 0)
        store.claimCalls shouldBeEqualTo 1
        readiness.snapshot().brokerAvailable shouldBeEqualTo true
    }

    @Test
    fun `publisher without readiness is fail closed`() {
        val store = RecordingStore(claim(envelope()))
        val relay = AppointmentOutboxRelay(
            store = store,
            publisher = AppointmentKafkaPublisher { _, _, _ -> CompletableFuture.completedFuture(Unit) },
        )

        relay.tick("relay-a") shouldBeEqualTo AppointmentRelayTickResult(0, 0, 0, 0, 0)
        store.claimCalls shouldBeEqualTo 0
    }

    @Test
    fun `operator hold blocks claims until explicitly released`() {
        val store = RecordingStore(claim(envelope()))
        val readiness = AppointmentMessagingReadinessProbe(brokerAvailable = true)
        readiness.markRelayHeld()
        val relay = AppointmentOutboxRelay(
            store = store,
            publisher = ProbePublisher(availableTopics = setOf(AppointmentTopic(DefaultAppointmentOutboxWriter.DEFAULT_TOPIC))),
            readiness = readiness,
        )

        relay.tick("relay-a") shouldBeEqualTo AppointmentRelayTickResult(0, 0, 0, 0, 0)
        store.claimCalls shouldBeEqualTo 0

        readiness.releaseRelayHold()
        relay.tick("relay-a") shouldBeEqualTo AppointmentRelayTickResult(1, 1, 0, 0, 0)
    }

    @Test
    fun `operator pause blocks claims until explicitly resumed`() {
        val store = RecordingStore(claim(envelope()))
        val readiness = AppointmentMessagingReadinessProbe(brokerAvailable = true)
        readiness.markRelayPaused()
        val relay = AppointmentOutboxRelay(
            store = store,
            publisher = ProbePublisher(
                availableTopics = setOf(AppointmentTopic(DefaultAppointmentOutboxWriter.DEFAULT_TOPIC)),
            ),
            readiness = readiness,
        )

        relay.tick("relay-a") shouldBeEqualTo AppointmentRelayTickResult(0, 0, 0, 0, 0)
        store.claimCalls shouldBeEqualTo 0

        readiness.markRelayResumed()
        relay.tick("relay-a") shouldBeEqualTo AppointmentRelayTickResult(1, 1, 0, 0, 0)
        readiness.snapshot().relayPaused.shouldBeFalse()
    }

    @Test
    fun `disabled readiness blocks a direct relay tick`() {
        val store = RecordingStore(claim(envelope()))
        val readiness = AppointmentMessagingReadinessProbe(enabled = false, brokerAvailable = true)
        val relay = AppointmentOutboxRelay(
            store = store,
            publisher = ProbePublisher(
                availableTopics = setOf(AppointmentTopic(DefaultAppointmentOutboxWriter.DEFAULT_TOPIC)),
            ),
            readiness = readiness,
        )

        relay.tick("relay-a") shouldBeEqualTo AppointmentRelayTickResult(0, 0, 0, 0, 0)
        store.claimCalls shouldBeEqualTo 0
    }

    @Test
    fun `broker ack marks row published`() {
        val envelope = envelope()
        val claim = claim(envelope)
        val store = RecordingStore(claim)
        val relay = AppointmentOutboxRelay(
            store = store,
            publisher = AppointmentKafkaPublisher { _, _, _ -> CompletableFuture.completedFuture(Unit) },
            brokerReadiness = alwaysReady,
        )

        relay.tick("relay-a") shouldBeEqualTo AppointmentRelayTickResult(1, 1, 0, 0, 0)
        store.published shouldBeEqualTo 1
    }

    @Test
    fun `broker outage leaves row retryable`() {
        val envelope = envelope()
        val claim = claim(envelope)
        val store = RecordingStore(claim)
        val relay = AppointmentOutboxRelay(
            store = store,
            publisher = AppointmentKafkaPublisher { _, _, _ ->
                CompletableFuture.failedFuture<Unit>(IllegalStateException("broker unavailable"))
            },
            brokerReadiness = alwaysReady,
        )

        relay.tick("relay-a") shouldBeEqualTo AppointmentRelayTickResult(1, 0, 1, 0, 0)
        store.retried shouldBeEqualTo 1
    }

    @Test
    fun `cancelled send leaves terminal state untouched for lease reclaim`() {
        val store = RecordingStore(claim(envelope()))
        val cancelled = CompletableFuture<Unit>().also { it.cancel(false) }
        val relay = AppointmentOutboxRelay(
            store = store,
            publisher = AppointmentKafkaPublisher { _, _, _ -> cancelled },
            brokerReadiness = alwaysReady,
        )

        try {
            relay.tick("relay-a")
        } catch (_: CancellationException) {
            // A cancelled send deliberately leaves the leased row for expiry/reclaim.
        }

        store.published shouldBeEqualTo 0
        store.retried shouldBeEqualTo 0
        store.failed shouldBeEqualTo 0
    }

    @Test
    fun `broker authorization failure is terminal without retry churn`() {
        val store = RecordingStore(claim(envelope()))
        val relay = AppointmentOutboxRelay(
            store = store,
            publisher = AppointmentKafkaPublisher { _, _, _ ->
                CompletableFuture.failedFuture<Unit>(AuthorizationException("denied"))
            },
            brokerReadiness = alwaysReady,
        )

        relay.tick("relay-a") shouldBeEqualTo AppointmentRelayTickResult(1, 0, 0, 1, 0)
        store.failed shouldBeEqualTo 1
        store.retried shouldBeEqualTo 0
    }

    @Test
    fun `sub second retry delay remains positive`() {
        val store = RecordingStore(claim(envelope()))
        val relay = AppointmentOutboxRelay(
            store = store,
            publisher = AppointmentKafkaPublisher { _, _, _ ->
                CompletableFuture.failedFuture<Unit>(IllegalStateException("broker unavailable"))
            },
            brokerReadiness = alwaysReady,
            retryBaseDelay = Duration.ofMillis(500),
            maxRetryDelay = Duration.ofSeconds(2),
        )

        relay.tick("relay-a")
        store.retryAfter shouldBeEqualTo Duration.ofMillis(500)
    }

    @Test
    fun `metadata mismatch is rejected before publisher`() {
        val envelope = envelope()
        val claim = claim(envelope).copy(partitionKey = AppointmentPartitionKeyFactory.create(7, 31, 925))
        val store = RecordingStore(claim)
        var publishes = 0
        val relay = AppointmentOutboxRelay(
            store = store,
            publisher = AppointmentKafkaPublisher { _, _, _ ->
                publishes++
                CompletableFuture.completedFuture(Unit)
            },
            brokerReadiness = alwaysReady,
        )

        relay.tick("relay-a") shouldBeEqualTo AppointmentRelayTickResult(1, 0, 0, 1, 1)
        publishes shouldBeEqualTo 0
    }

    @Test
    fun `three consecutive broker failures pause the relay before claiming again`() {
        val envelope = envelope()
        val claims = (1L..3L).map { claim(envelope).copy(id = it) }
        val store = RecordingStore(claims)
        val relay = AppointmentOutboxRelay(
            store = store,
            publisher = AppointmentKafkaPublisher { _, _, _ ->
                CompletableFuture.failedFuture<Unit>(IllegalStateException("broker unavailable"))
            },
            brokerReadiness = alwaysReady,
            clock = Clock.fixed(Instant.parse("2026-08-05T08:30:00Z"), ZoneOffset.UTC),
            brokerFailurePauseDuration = Duration.ofSeconds(30),
        )

        relay.tick("relay-a") shouldBeEqualTo AppointmentRelayTickResult(3, 0, 3, 0, 0)
        relay.tick("relay-a") shouldBeEqualTo AppointmentRelayTickResult(0, 0, 0, 0, 0)
        store.claimCalls shouldBeEqualTo 1
    }

    private fun envelope(): AppointmentEventEnvelope = AppointmentEventEnvelope(
        eventId = AppointmentEventId("event-relay-1"),
        eventType = AppointmentEventType.CREATED,
        schemaVersion = 1,
        occurredAt = Instant.parse("2026-08-05T08:30:00Z"),
        tenantGroupId = 7,
        clinicId = 31,
        aggregateType = AppointmentEventEnvelope.AGGREGATE_TYPE,
        aggregateId = AppointmentAggregateId(924),
        correlationId = AppointmentCommandContext.root("relay-1").correlationId,
        causationId = AppointmentCommandContext.root("relay-1").causationId,
        payload = AppointmentCreatedPayload(AppointmentAggregateId(924), 3, AppointmentState.CONFIRMED),
    )

    private fun claim(envelope: AppointmentEventEnvelope): AppointmentOutboxClaim {
        val json = AppointmentEventEnvelopeCodec().encode(envelope)
        return AppointmentOutboxClaim(
            id = 1,
            eventId = envelope.eventId,
            eventType = envelope.eventType,
            tenantGroupId = envelope.tenantGroupId,
            clinicId = envelope.clinicId,
            aggregateType = envelope.aggregateType,
            aggregateId = envelope.aggregateId,
            topic = AppointmentTopic(DefaultAppointmentOutboxWriter.DEFAULT_TOPIC),
            partitionKey = AppointmentPartitionKeyFactory.create(7, 31, 924),
            payloadJson = json,
            attemptNumber = 1,
            owner = "relay-a",
            token = "token-1",
            leaseUntil = Instant.parse("2026-08-05T08:31:00Z"),
        )
    }

    private class RecordingStore(
        private val claims: List<AppointmentOutboxClaim>,
    ) : AppointmentOutboxStore {
        constructor(claim: AppointmentOutboxClaim) : this(listOf(claim))

        var claimCalls = 0
        var published = 0
        var retried = 0
        var failed = 0
        var retryAfter: Duration? = null

        override fun claim(owner: String, limit: Int, leaseDuration: java.time.Duration): List<AppointmentOutboxClaim> {
            claimCalls++
            return claims.map { it.copy(owner = owner) }
        }

        override fun markPublished(claim: AppointmentOutboxClaim): Boolean {
            published++
            return true
        }

        override fun markRetry(claim: AppointmentOutboxClaim, retryAfter: Duration, failureCode: String): Boolean {
            retried++
            this.retryAfter = retryAfter
            return true
        }

        override fun markFailed(claim: AppointmentOutboxClaim, failureCode: String): Boolean {
            failed++
            return true
        }
    }

    private class ProbePublisher(
        var availableTopics: Set<AppointmentTopic>,
    ) : AppointmentKafkaPublisher, AppointmentKafkaReadiness {
        val probedTopics = mutableListOf<AppointmentTopic>()

        override fun publish(
            topic: AppointmentTopic,
            key: AppointmentPartitionKey,
            value: String,
        ): CompletionStage<*> = CompletableFuture.completedFuture(Unit)

        override fun probe(topic: AppointmentTopic): Boolean {
            probedTopics += topic
            return topic in availableTopics
        }
    }

    private val alwaysReady: AppointmentKafkaReadiness = AppointmentKafkaReadiness { true }
}
