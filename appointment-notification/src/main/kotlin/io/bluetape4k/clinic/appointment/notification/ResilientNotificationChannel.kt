package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.github.resilience4j.bulkhead.Bulkhead
import io.github.resilience4j.bulkhead.BulkheadConfig
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.kotlin.bulkhead.executeSuspendFunction
import io.github.resilience4j.kotlin.retry.executeSuspendFunction
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import kotlinx.coroutines.CancellationException
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * Resilience4j 기반 알림 채널 데코레이터.
 *
 * 실제 [NotificationChannel] 구현체를 감싸서
 * CircuitBreaker, Retry, Bulkhead를 적용합니다.
 * 외부 알림 서비스 호출 시 장애 격리를 보장합니다.
 *
 * @param delegate 실제 알림 채널 구현체
 * @param circuitBreaker 알림 호출 CircuitBreaker
 * @param retry 알림 호출 Retry
 * @param bulkhead 알림 호출 Bulkhead
 */
class ResilientNotificationChannel(
    private val delegate: NotificationChannel,
    private val circuitBreaker: CircuitBreaker,
    private val retry: Retry,
    private val bulkhead: Bulkhead,
    private val providerTimeout: Duration,
    private val executor: ThreadPoolExecutor,
    private val healthSignals: NotificationRuntimeHealthSignals? = null,
) : NotificationChannel, AutoCloseable {

    companion object : KLogging() {
        private const val CB_NAME = "notification-channel"
        private const val RETRY_NAME = "notification-retry"
        private const val BULKHEAD_NAME = "notification-bulkhead"

        fun create(
            delegate: NotificationChannel,
            properties: NotificationResilienceProperties = NotificationResilienceProperties(),
            providerAttemptsPerLease: Int = 1,
            providerTimeout: Duration = Duration.ofSeconds(30),
            healthSignals: NotificationRuntimeHealthSignals? = null,
        ): ResilientNotificationChannel {
            require(providerAttemptsPerLease in 1..2) {
                "providerAttemptsPerLease must be between 1 and 2"
            }
            require(!providerTimeout.isNegative && !providerTimeout.isZero) {
                "providerTimeout must be positive"
            }
            val cb = CircuitBreaker.of(
                CB_NAME,
                CircuitBreakerConfig.custom()
                    .failureRateThreshold(properties.circuitBreaker.failureRateThreshold)
                    .slowCallRateThreshold(properties.circuitBreaker.slowCallRateThreshold)
                    .waitDurationInOpenState(properties.circuitBreaker.waitDurationInOpenState)
                    .slidingWindowSize(properties.circuitBreaker.slidingWindowSize)
                    .minimumNumberOfCalls(properties.circuitBreaker.minimumNumberOfCalls)
                    .build(),
            )

            val retry = Retry.of(
                RETRY_NAME,
                RetryConfig.custom<Any>()
                    .maxAttempts(providerAttemptsPerLease)
                    .waitDuration(properties.retry.waitDuration)
                    .ignoreExceptions(CancellationException::class.java)
                    .build(),
            )

            val bulkhead = Bulkhead.of(
                BULKHEAD_NAME,
                BulkheadConfig.custom()
                    .maxConcurrentCalls(properties.bulkhead.maxConcurrentCalls)
                    .maxWaitDuration(properties.bulkhead.maxWaitDuration)
                    .build(),
            )

            val sequence = AtomicLong()
            val concurrency = properties.bulkhead.maxConcurrentCalls
            val executor = ThreadPoolExecutor(
                concurrency,
                concurrency,
                0L,
                TimeUnit.MILLISECONDS,
                ArrayBlockingQueue(concurrency),
                { task ->
                    Thread.ofPlatform()
                        .name("notification-provider-${sequence.incrementAndGet()}")
                        .daemon(true)
                        .unstarted(task)
                },
                ThreadPoolExecutor.AbortPolicy(),
            )
            return ResilientNotificationChannel(delegate, cb, retry, bulkhead, providerTimeout, executor, healthSignals)
        }
    }

    override val channelType get() = delegate.channelType

    override fun send(request: NotificationProviderRequest): NotificationProviderResult {
        val decorated = Bulkhead.decorateSupplier(bulkhead) {
            Retry.decorateSupplier(retry) {
                CircuitBreaker.decorateSupplier(circuitBreaker) {
                    delegate.send(request)
                }.get()
            }.get()
        }

        try {
            val future = executor.submit<NotificationProviderResult> { decorated.get() }
            return try {
                future.get(providerTimeout.toMillis(), TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                future.cancel(true)
                throw e
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: NotificationProviderException) {
            throw e
        } catch (e: Exception) {
            log.warn { "알림 발송 실패: channel=${request.channel}, cbState=${circuitBreaker.state}" }
            throw NotificationProviderException(NotificationProviderFailureMapper.fromException(e))
        } finally {
            healthSignals?.setProviderCircuitOpen(circuitBreaker.state == CircuitBreaker.State.OPEN)
        }
    }

    override fun close() {
        executor.shutdownNow()
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
