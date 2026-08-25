package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.kafka.spring.suspendSend
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.asCompletableFuture
import org.apache.kafka.common.KafkaException
import org.apache.kafka.common.config.ConfigException
import org.apache.kafka.common.errors.AuthenticationException
import org.apache.kafka.common.errors.AuthorizationException
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.kafka.core.KafkaTemplate
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Spring Kafka 4의 broker send를 bluetape4k-kafka4 `suspendSend`로 감싸
 * relay의 `CompletionStage` 계약으로 변환한다.
 *
 * 현재 envelope는 Spring Kafka `StringSerializer`의 wire 계약을 유지한다.
 * `KafkaCodecs.String`은 기본 설정에서 타입 헤더를 추가하므로 이 파일럿에서
 * producer serializer를 교체하면 기존 소비자와의 header 계약이 바뀌어 사용하지 않는다.
 *
 * @param publishDispatcher broker 발송 coroutine이 사용할 dispatcher
 */
class SpringKafkaAppointmentPublisher @JvmOverloads constructor(
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
    private val publishDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AppointmentKafkaPublisher, AppointmentKafkaReadiness, AutoCloseable {
    private val publishScope = CoroutineScope(SupervisorJob() + publishDispatcher)

    @Volatile
    private var lastProbeFailureCode: String? = null

    init {
        require(!metadataTimeout.isNegative && !metadataTimeout.isZero) { "metadataTimeout must be positive" }
        // admin client는 topic을 생성하지 않는 metadata 경로입니다. producer metadata request는
        // topic 생성을 허용할 수 있으므로 readiness에서 KafkaTemplate.partitionsFor를 사용하면 안 됩니다.
        kafkaAdmin.setAutoCreate(false)
        kafkaAdmin.setOperationTimeout(metadataTimeout.toKafkaTimeoutSeconds())
    }

    override fun publish(
        topic: AppointmentTopic,
        key: AppointmentPartitionKey,
        value: String,
    ): CompletionStage<*> = publishScope.async {
        kafkaTemplate.suspendSend(topic.value, key.value, value)
    }.asCompletableFuture()

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
        publishScope.cancel()
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
