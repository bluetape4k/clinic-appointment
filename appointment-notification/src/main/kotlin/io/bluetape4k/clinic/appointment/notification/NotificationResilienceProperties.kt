package io.bluetape4k.clinic.appointment.notification

import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.time.Duration

/**
 * 알림 채널 Resilience4j 설정 프로퍼티.
 *
 * ```yaml
 * clinic:
 *   notification:
 *     resilience:
 *       circuit-breaker:
 *         failure-rate-threshold: 50
 *         slow-call-rate-threshold: 80
 *         wait-duration-in-open-state: 30s
 *         sliding-window-size: 10
 *         minimum-number-of-calls: 5
 *       retry:
 *         max-attempts: 3
 *         wait-duration: 500ms
 *       bulkhead:
 *         max-concurrent-calls: 10
 *         max-wait-duration: 1s
 * ```
 *
 * @property circuitBreaker CircuitBreaker 설정
 * @property retry Retry 설정
 * @property bulkhead Bulkhead 설정
 */
@ConfigurationProperties(prefix = "clinic.notification.resilience")
data class NotificationResilienceProperties(
    val circuitBreaker: CircuitBreakerProperties = CircuitBreakerProperties(),
    val retry: RetryProperties = RetryProperties(),
    val bulkhead: BulkheadProperties = BulkheadProperties(),
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    /**
     * CircuitBreaker 설정.
     *
     * @property failureRateThreshold 실패율 임계값
     * @property slowCallRateThreshold 느린 호출 비율 임계값
     * @property waitDurationInOpenState OPEN 상태 유지 시간
     * @property slidingWindowSize 집계 슬라이딩 윈도우 크기
     * @property minimumNumberOfCalls 상태 전환 평가에 필요한 최소 호출 수
     */
    data class CircuitBreakerProperties(
        val failureRateThreshold: Float = 50f,
        val slowCallRateThreshold: Float = 80f,
        val waitDurationInOpenState: Duration = Duration.ofSeconds(30),
        val slidingWindowSize: Int = 10,
        val minimumNumberOfCalls: Int = 5,
    ) : Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    /**
     * Retry 설정.
     *
     * @property maxAttempts 최대 재시도 횟수
     * @property waitDuration 재시도 간 대기 시간
     */
    data class RetryProperties(
        val maxAttempts: Int = 3,
        val waitDuration: Duration = Duration.ofMillis(500),
    ) : Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    /**
     * Bulkhead 설정.
     *
     * @property maxConcurrentCalls 최대 동시 호출 수
     * @property maxWaitDuration bulkhead 진입 대기 시간
     */
    data class BulkheadProperties(
        val maxConcurrentCalls: Int = 10,
        val maxWaitDuration: Duration = Duration.ofSeconds(1),
    ) : Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }
}
