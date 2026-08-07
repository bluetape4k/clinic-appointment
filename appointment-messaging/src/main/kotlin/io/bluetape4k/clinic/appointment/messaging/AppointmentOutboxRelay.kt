package io.bluetape4k.clinic.appointment.messaging

import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletionStage
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import org.apache.kafka.common.KafkaException
import org.apache.kafka.common.errors.AuthenticationException
import org.apache.kafka.common.errors.AuthorizationException
import org.apache.kafka.common.config.ConfigException
import org.apache.kafka.common.errors.SerializationException
import kotlin.math.min

/** Kafka broker로 전달하는 최소 publisher 계약이다. DB transaction 경계 밖에서만 호출한다. */
fun interface AppointmentKafkaPublisher {
    fun publish(
        topic: AppointmentTopic,
        key: AppointmentPartitionKey,
        value: String,
    ): CompletionStage<*>
}

/** allow-list topic의 metadata를 조회해 broker/topic/ACL readiness를 확인한다. */
fun interface AppointmentKafkaReadiness {
    fun probe(topic: AppointmentTopic): Boolean

    /** 마지막 probe 실패를 개인정보 없는 stable category로 노출한다. */
    fun failureCode(): String? = null

    /** 모든 allow-listed topic의 metadata/ACL을 확인한다. */
    fun probe(topics: Set<AppointmentTopic>): Boolean {
        require(topics.isNotEmpty()) { "topics must not be empty" }
        return topics.all(::probe)
    }
}

/** 한 번의 relay tick 결과를 bounded metric/report로 표현한다. */
data class AppointmentRelayTickResult(
    val claimed: Int,
    val published: Int,
    val retried: Int,
    val failed: Int,
    val rejected: Int,
)

/**
 * claim → Kafka send → owner/token fenced terminal transition을 수행하는 relay다.
 * Kafka I/O 중에는 Exposed transaction을 열지 않는다.
 */
class AppointmentOutboxRelay internal constructor(
    private val store: AppointmentOutboxStore,
    private val publisher: AppointmentKafkaPublisher,
    private val codec: AppointmentEventEnvelopeCodec = AppointmentEventEnvelopeCodec(),
    private val allowedTopics: Set<AppointmentTopic> = setOf(AppointmentTopic(DefaultAppointmentOutboxWriter.DEFAULT_TOPIC)),
    private val claimSize: Int = 2,
    private val leaseDuration: Duration = Duration.ofSeconds(30),
    private val sendTimeout: Duration = Duration.ofSeconds(5),
    private val retryBaseDelay: Duration = Duration.ofSeconds(2),
    private val maxRetryDelay: Duration = Duration.ofMinutes(1),
    private val kafkaClientRetryBudget: Duration = Duration.ofSeconds(5),
    private val terminalDbUpdateBudget: Duration = Duration.ofSeconds(3),
    private val safetyMargin: Duration = Duration.ofSeconds(10),
    private val maxInFlight: Int = 1,
    private val maxClinicBatch: Int = 1,
    private val maxAttempts: Int = 8,
    private val readiness: AppointmentMessagingReadinessProbe = AppointmentMessagingReadinessProbe(),
    private val readinessValidator: AppointmentMessagingReadinessValidator? = null,
    private val metrics: AppointmentOutboxMetrics = NoopAppointmentOutboxMetrics,
    private val brokerReadiness: AppointmentKafkaReadiness =
        (publisher as? AppointmentKafkaReadiness) ?: AppointmentKafkaReadiness { false },
    private val clock: java.time.Clock = java.time.Clock.systemUTC(),
    private val brokerFailurePauseThreshold: Int = 3,
    private val brokerFailurePauseDuration: Duration = Duration.ofSeconds(30),
) {
    init {
        require(claimSize in 1..32) { "claimSize must be between 1 and 32" }
        require(maxInFlight == 1) { "maxInFlight must be 1 until structured concurrent relay is enabled" }
        require(maxClinicBatch in 1..4) { "maxClinicBatch must be between 1 and 4" }
        require(maxAttempts in 1..100) { "maxAttempts must be bounded" }
        require(leaseDuration > sendTimeout) { "leaseDuration must exceed sendTimeout" }
        require(!sendTimeout.isNegative && !sendTimeout.isZero) { "sendTimeout must be positive" }
        require(!retryBaseDelay.isNegative && !retryBaseDelay.isZero) { "retryBaseDelay must be positive" }
        require(!maxRetryDelay.isNegative && !maxRetryDelay.isZero) { "maxRetryDelay must be positive" }
        require(maxRetryDelay >= retryBaseDelay) { "maxRetryDelay must cover retryBaseDelay" }
        require(!kafkaClientRetryBudget.isNegative && !kafkaClientRetryBudget.isZero) {
            "kafkaClientRetryBudget must be positive"
        }
        require(!terminalDbUpdateBudget.isNegative && !terminalDbUpdateBudget.isZero) {
            "terminalDbUpdateBudget must be positive"
        }
        require(!safetyMargin.isNegative && !safetyMargin.isZero) { "safetyMargin must be positive" }
        require(leaseDuration >= sendTimeout.multipliedBy(claimSize.toLong())
            .plus(kafkaClientRetryBudget)
            .plus(terminalDbUpdateBudget)
            .plus(safetyMargin)
        ) {
            "leaseDuration must cover the sequential claim window, terminal update budget, and safety margin"
        }
        require(brokerFailurePauseThreshold > 0) { "brokerFailurePauseThreshold must be positive" }
        require(!brokerFailurePauseDuration.isNegative && !brokerFailurePauseDuration.isZero) {
            "brokerFailurePauseDuration must be positive"
        }
    }

    @Volatile
    private var pausedUntil: Instant? = null
    private var consecutiveBrokerFailures: Int = 0

    fun tick(owner: String): AppointmentRelayTickResult {
        val now = Instant.now(clock)
        readinessValidator?.validate(readiness)
        runCatching { metrics.recordBacklog(store.backlogSnapshot(now)) }
        if (pausedUntil != null && pausedUntil?.isAfter(now) != true) {
            pausedUntil = null
            readiness.markAutomaticRelayResumed()
        }
        val state = readiness.snapshot()
        if (!state.enabled || !state.configurationValid || !state.schemaValid || !state.serializerValid ||
            state.relayPaused ||
            state.relayHeld || pausedUntil?.isAfter(now) == true
        ) {
            return AppointmentRelayTickResult(0, 0, 0, 0, 0)
        }
        if (!ensureBrokerReady()) {
            return AppointmentRelayTickResult(0, 0, 0, 0, 0)
        }
        val claims = store.claim(owner, claimSize, leaseDuration)
        var published = 0
        var retried = 0
        var failed = 0
        var rejected = 0
        for (claim in claims) {
            if (claim.topic !in allowedTopics) {
                if (store.markFailed(claim, FAILURE_DISALLOWED_TOPIC)) {
                    failed++
                    metrics.publishFailed(FAILURE_DISALLOWED_TOPIC)
                    metrics.contractRejected(FAILURE_DISALLOWED_TOPIC)
                } else {
                    metrics.leaseLost()
                }
                rejected++
                continue
            }
            val envelope = try {
                codec.decode(claim.payloadJson)
            } catch (_: Exception) {
                if (store.markFailed(claim, FAILURE_INVALID_PAYLOAD)) {
                    failed++
                    metrics.publishFailed(FAILURE_INVALID_PAYLOAD)
                    metrics.contractRejected(FAILURE_INVALID_PAYLOAD)
                } else {
                    metrics.leaseLost()
                }
                rejected++
                continue
            }
            if (!isMetadataConsistent(claim, envelope)) {
                if (store.markFailed(claim, FAILURE_METADATA_MISMATCH)) {
                    failed++
                    metrics.publishFailed(FAILURE_METADATA_MISMATCH)
                    metrics.contractRejected(FAILURE_METADATA_MISMATCH)
                } else {
                    metrics.leaseLost()
                }
                rejected++
                continue
            }
            try {
                // 이 await는 transaction 밖에서 실행되어 DB connection을 점유하지 않는다.
                publisher.publish(claim.topic, claim.partitionKey, claim.payloadJson)
                    .toCompletableFuture()
                    .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS)
                if (store.markPublished(claim)) {
                    published++
                    metrics.publishSuccess(claim.eventType)
                    readiness.markBrokerAvailable()
                } else {
                    metrics.leaseLost()
                }
                consecutiveBrokerFailures = 0
            } catch (_: InterruptedException) {
                // cancellation 시 lease는 만료/reclaim을 위해 남겨야 하므로 terminal state를 쓰지 않습니다.
                Thread.currentThread().interrupt()
                break
            } catch (_: CancellationException) {
                // 취소된 future도 중단된 relay와 동일한 lease recovery 계약을 따릅니다.
                throw CancellationException("appointment outbox publish was cancelled")
            } catch (ex: Exception) {
                val failureCode = publishFailureCode(ex)
                if (failureCode.isPermanent()) {
                    if (store.markFailed(claim, failureCode)) {
                        failed++
                        metrics.publishFailed(failureCode)
                        metrics.contractRejected(failureCode)
                    } else {
                        metrics.leaseLost()
                    }
                    readiness.markConfigurationInvalid()
                    metrics.readinessFailed(failureCode)
                } else if (claim.attemptNumber >= maxAttempts) {
                    if (store.markFailed(claim, FAILURE_ATTEMPT_EXHAUSTED)) {
                        failed++
                        metrics.publishFailed(FAILURE_ATTEMPT_EXHAUSTED)
                    } else {
                        metrics.leaseLost()
                    }
                } else if (store.markRetry(claim, backoff(claim.attemptNumber), failureCode)) {
                    retried++
                    metrics.publishRetry(failureCode)
                } else {
                    metrics.leaseLost()
                }
                if (!failureCode.isPermanent()) {
                    readiness.markBrokerUnavailable()
                    consecutiveBrokerFailures++
                }
                if (!failureCode.isPermanent() && consecutiveBrokerFailures >= brokerFailurePauseThreshold) {
                    pausedUntil = Instant.now(clock).plus(brokerFailurePauseDuration)
                    readiness.markAutomaticRelayPaused()
                    metrics.brokerPaused()
                    break
                }
            }
        }
        return AppointmentRelayTickResult(
            claimed = claims.size,
            published = published,
            retried = retried,
            failed = failed,
            rejected = rejected,
        )
    }

    private fun ensureBrokerReady(): Boolean {
        if (readiness.snapshot().brokerAvailable) return true
        if (allowedTopics.isEmpty()) return false
        val available = runCatching { brokerReadiness.probe(allowedTopics) }.getOrDefault(false)
        if (available) {
            readiness.markBrokerAvailable()
        } else {
            readiness.markBrokerUnavailable()
            metrics.readinessFailed(
                brokerReadiness.failureCode() ?: FAILURE_BROKER_METADATA_UNAVAILABLE,
            )
        }
        return available
    }

    private fun isMetadataConsistent(
        claim: AppointmentOutboxClaim,
        envelope: AppointmentEventEnvelope,
    ): Boolean {
        if (claim.eventId != envelope.eventId) return false
        if (claim.tenantGroupId != envelope.tenantGroupId || claim.clinicId != envelope.clinicId) return false
        if (claim.aggregateType != envelope.aggregateType || claim.aggregateId != envelope.aggregateId) return false
        if (envelope.tenantGroupId <= 0 || envelope.clinicId <= 0) return false
        val expectedKey = AppointmentPartitionKeyFactory.create(
            envelope.tenantGroupId,
            envelope.clinicId,
            envelope.aggregateId.value,
        )
        return claim.partitionKey == expectedKey &&
            envelope.eventType == claim.eventType &&
            envelope.aggregateType == AppointmentEventEnvelope.AGGREGATE_TYPE
    }

    private fun backoff(attemptNumber: Int): Duration {
        val baseMillis = retryBaseDelay.toMillis().coerceAtLeast(1)
        val maxMillis = maxRetryDelay.toMillis().coerceAtLeast(baseMillis)
        val multiplier = 1L shl min(attemptNumber.coerceAtLeast(1) - 1, 10)
        val candidateMillis = if (baseMillis > Long.MAX_VALUE / multiplier) {
            Long.MAX_VALUE
        } else {
            baseMillis * multiplier
        }
        return Duration.ofMillis(min(maxMillis, candidateMillis))
    }

    private fun publishFailureCode(failure: Throwable): String {
        val cause = generateSequence(failure) { it.cause }.last()
        return when (cause) {
            is AuthenticationException, is AuthorizationException -> FAILURE_BROKER_AUTHORIZATION
            is ConfigException -> FAILURE_BROKER_CONFIGURATION
            is SerializationException -> FAILURE_SERIALIZATION
            is KafkaException -> FAILURE_BROKER_UNAVAILABLE
            else -> FAILURE_BROKER_UNAVAILABLE
        }
    }

    private fun String.isPermanent(): Boolean =
        this == FAILURE_BROKER_AUTHORIZATION ||
            this == FAILURE_BROKER_CONFIGURATION ||
            this == FAILURE_SERIALIZATION

    companion object {
        const val FAILURE_BROKER_UNAVAILABLE = "BROKER_UNAVAILABLE"
        const val FAILURE_DISALLOWED_TOPIC = "DISALLOWED_TOPIC"
        const val FAILURE_INVALID_PAYLOAD = "INVALID_PAYLOAD"
        const val FAILURE_METADATA_MISMATCH = "METADATA_MISMATCH"
        const val FAILURE_INVALID_METADATA = "INVALID_METADATA"
        const val FAILURE_ATTEMPT_EXHAUSTED = "ATTEMPT_EXHAUSTED"
        const val FAILURE_BROKER_METADATA_UNAVAILABLE = "BROKER_METADATA_UNAVAILABLE"
        const val FAILURE_BROKER_METADATA_TIMEOUT = "BROKER_METADATA_TIMEOUT"
        const val FAILURE_SCHEMA_CONTRACT = "SCHEMA_CONTRACT_INVALID"
        const val FAILURE_SERIALIZER_CONTRACT = "SERIALIZER_CONTRACT_INVALID"
        const val FAILURE_BROKER_AUTHORIZATION = "BROKER_AUTHORIZATION"
        const val FAILURE_BROKER_CONFIGURATION = "BROKER_CONFIGURATION"
        const val FAILURE_SERIALIZATION = "SERIALIZATION_FAILURE"
    }
}
