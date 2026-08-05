package io.bluetape4k.clinic.appointment.messaging

import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.KafkaAdmin
import org.apache.kafka.common.KafkaException
import org.apache.kafka.common.config.ConfigException
import org.apache.kafka.common.errors.AuthenticationException
import org.apache.kafka.common.errors.AuthorizationException
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Spring Kafka 4의 broker send를 relay의 최소 publisher 계약으로 감싼다. */
class SpringKafkaAppointmentPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val kafkaAdmin: KafkaAdmin,
    private val metadataTimeout: Duration = Duration.ofSeconds(5),
    private val probeExecutor: ExecutorService = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(1),
        { runnable -> Thread(runnable, "appointment-kafka-readiness").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    ),
) : AppointmentKafkaPublisher, AppointmentKafkaReadiness, AutoCloseable {
    @Volatile
    private var lastProbeFailureCode: String? = null

    init {
        require(!metadataTimeout.isNegative && !metadataTimeout.isZero) { "metadataTimeout must be positive" }
        // The admin client is the non-creating metadata path. Producer metadata requests
        // may allow topic creation, so readiness must never use KafkaTemplate.partitionsFor.
        kafkaAdmin.setAutoCreate(false)
        kafkaAdmin.setOperationTimeout(metadataTimeout.toKafkaTimeoutSeconds())
    }

    override fun publish(
        topic: AppointmentTopic,
        key: AppointmentPartitionKey,
        value: String,
    ): CompletionStage<*> = kafkaTemplate.send(topic.value, key.value, value)

    override fun probe(topic: AppointmentTopic): Boolean {
        val probe: Future<Boolean> = try {
            probeExecutor.submit<Boolean> {
                val description = kafkaAdmin.describeTopics(topic.value)[topic.value]
                description != null && description.partitions().isNotEmpty()
            }
        } catch (_: RejectedExecutionException) {
            lastProbeFailureCode = AppointmentOutboxRelay.FAILURE_BROKER_METADATA_TIMEOUT
            return false
        } catch (ex: Exception) {
            lastProbeFailureCode = classifyProbeFailure(ex)
            return false
        }
        return try {
            probe.get(metadataTimeout.toMillis().coerceAtLeast(1), TimeUnit.MILLISECONDS).also {
                lastProbeFailureCode = null
            }
        } catch (_: TimeoutException) {
            probe.cancel(true)
            lastProbeFailureCode = AppointmentOutboxRelay.FAILURE_BROKER_METADATA_TIMEOUT
            false
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            probe.cancel(true)
            lastProbeFailureCode = AppointmentOutboxRelay.FAILURE_BROKER_METADATA_TIMEOUT
            false
        } catch (ex: ExecutionException) {
            lastProbeFailureCode = classifyProbeFailure(ex)
            false
        } catch (ex: Exception) {
            lastProbeFailureCode = classifyProbeFailure(ex)
            false
        }
    }

    override fun failureCode(): String? = lastProbeFailureCode

    override fun close() {
        probeExecutor.shutdownNow()
    }

    private fun classifyProbeFailure(failure: Throwable): String {
        val cause = generateSequence(failure) { it.cause }.last()
        return when (cause) {
            is AuthenticationException, is AuthorizationException -> AppointmentOutboxRelay.FAILURE_BROKER_AUTHORIZATION
            is ConfigException -> AppointmentOutboxRelay.FAILURE_BROKER_CONFIGURATION
            is KafkaException -> AppointmentOutboxRelay.FAILURE_BROKER_METADATA_UNAVAILABLE
            else -> AppointmentOutboxRelay.FAILURE_BROKER_METADATA_UNAVAILABLE
        }
    }

    private fun Duration.toKafkaTimeoutSeconds(): Int =
        toSeconds().coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
}
