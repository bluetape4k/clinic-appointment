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
) : NotificationChannel {

    companion object : KLogging() {
        private const val CB_NAME = "notification-channel"
        private const val RETRY_NAME = "notification-retry"
        private const val BULKHEAD_NAME = "notification-bulkhead"

        fun create(
            delegate: NotificationChannel,
            properties: NotificationResilienceProperties = NotificationResilienceProperties(),
        ): ResilientNotificationChannel {
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
                    .maxAttempts(properties.retry.maxAttempts)
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

            return ResilientNotificationChannel(delegate, cb, retry, bulkhead)
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
            return decorated.get()
        } catch (e: CancellationException) {
            throw e
        } catch (e: NotificationProviderException) {
            throw e
        } catch (e: Exception) {
            log.warn { "알림 발송 실패: channel=${request.channel}, cbState=${circuitBreaker.state}" }
            throw NotificationProviderException(NotificationProviderFailureMapper.fromException(e))
        }
    }
}
