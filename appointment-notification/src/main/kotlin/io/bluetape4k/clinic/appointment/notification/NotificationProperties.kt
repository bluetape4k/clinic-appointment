package io.bluetape4k.clinic.appointment.notification

import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.time.Duration

/**
 * 알림 설정 프로퍼티.
 *
 * ```yaml
 * clinic:
 *   notification:
 *     enabled: true
 *     events:
 *       created: true
 *       confirmed: true
 *       cancelled: true
 *       rescheduled: true
 *     reminder:
 *       enabled: true
 *       day-before: true
 *       same-day: true
 *       same-day-hours-before: 2
 * ```
 *
 * @property enabled 알림 모듈 활성화 여부
 * @property events 예약 이벤트별 알림 설정
 * @property reminder 예약 리마인더 설정
 */
@ConfigurationProperties(prefix = "clinic.notification")
data class NotificationProperties(
    val enabled: Boolean = true,
    val events: EventProperties = EventProperties(),
    val reminder: ReminderProperties = ReminderProperties(),
    val worker: WorkerProperties = WorkerProperties(),
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    /**
     * 예약 이벤트별 알림 설정.
     *
     * @property created 예약 생성 알림 활성화 여부
     * @property confirmed 예약 확정 알림 활성화 여부
     * @property cancelled 예약 취소 알림 활성화 여부
     * @property rescheduled 예약 재배정 알림 활성화 여부
     */
    data class EventProperties(
        val created: Boolean = true,
        val confirmed: Boolean = true,
        val cancelled: Boolean = true,
        val rescheduled: Boolean = true,
    ) : Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    /**
     * 예약 리마인더 설정.
     *
     * @property enabled 리마인더 활성화 여부
     * @property dayBefore 전일 리마인더 발송 여부
     * @property sameDay 당일 리마인더 발송 여부
     * @property sameDayHoursBefore 당일 리마인더 기준 시간(예약 전 N시간)
     */
    data class ReminderProperties(
        val enabled: Boolean = true,
        val dayBefore: Boolean = true,
        val sameDay: Boolean = true,
        val sameDayHoursBefore: Int = 2,
    ) : Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    /**
     * durable notification outbox worker 설정입니다.
     *
     * provider 호출은 lease 안에서 제한된 횟수만 수행하고, 전체 worker 동시성은 회원 조회와
     * channel provider 상한보다 작거나 같아야 합니다.
     */
    data class WorkerProperties(
        val enabled: Boolean = true,
        val maxAttempts: Int = 6,
        val maxElapsed: Duration = Duration.ofHours(24),
        val providerAttemptsPerLease: Int = 1,
        val catchUpWindow: Duration = Duration.ofMinutes(30),
        val leaseDuration: Duration = Duration.ofSeconds(60),
        val providerTimeout: Duration = Duration.ofSeconds(30),
        val batchSize: Int = 100,
        val globalConcurrency: Int = 4,
        val perClinicConcurrency: Int = 1,
        val dbClaimMaxConcurrency: Int = 4,
        val memberResolverMaxConcurrency: Int = 4,
        val memberResolverTimeout: Duration = Duration.ofSeconds(5),
        val memberResolverRateLimitPerSecond: Int = 100,
        val memberResolverCircuitBreakerFailureRateThreshold: Int = 50,
        val channels: Map<String, ChannelWorkerProperties> =
            mapOf("dummy" to ChannelWorkerProperties()),
    ) : Serializable {
        fun validate(): WorkerProperties {
            check(maxAttempts in 1..10) { "maxAttempts must be between 1 and 10" }
            check(maxElapsed in MIN_ELAPSED..MAX_ELAPSED) {
                "maxElapsed must be between 15 minutes and 72 hours"
            }
            check(providerAttemptsPerLease in 1..2) {
                "providerAttemptsPerLease must be between 1 and 2"
            }
            check(maxAttempts * providerAttemptsPerLease <= 12) {
                "maxAttempts * providerAttemptsPerLease must not exceed 12"
            }
            check(!catchUpWindow.isNegative && !catchUpWindow.isZero) {
                "catchUpWindow must be positive"
            }
            check(!leaseDuration.isNegative && !leaseDuration.isZero) {
                "leaseDuration must be positive"
            }
            check(!providerTimeout.isNegative && !providerTimeout.isZero) {
                "providerTimeout must be positive"
            }
            check(batchSize > 0) { "batchSize must be positive" }
            check(globalConcurrency > 0) { "globalConcurrency must be positive" }
            check(perClinicConcurrency in 1..globalConcurrency) {
                "perClinicConcurrency must be between 1 and globalConcurrency"
            }
            check(dbClaimMaxConcurrency > 0) {
                "dbClaimMaxConcurrency must be positive"
            }
            check(memberResolverMaxConcurrency > 0) {
                "memberResolverMaxConcurrency must be positive"
            }
            check(!memberResolverTimeout.isNegative && !memberResolverTimeout.isZero) {
                "memberResolverTimeout must be positive"
            }
            check(memberResolverRateLimitPerSecond > 0) {
                "memberResolverRateLimitPerSecond must be positive"
            }
            check(memberResolverCircuitBreakerFailureRateThreshold in 1..100) {
                "memberResolverCircuitBreakerFailureRateThreshold must be between 1 and 100"
            }
            check(channels.isNotEmpty()) { "at least one channel worker limit is required" }
            channels.values.forEach(ChannelWorkerProperties::validate)
            val longestProviderTimeout = channels.values
                .map(ChannelWorkerProperties::providerTimeout)
                .plus(providerTimeout)
                .maxOrNull()!!
            val inProcessProviderBound = longestProviderTimeout.multipliedBy(providerAttemptsPerLease.toLong())
            check(leaseDuration > inProcessProviderBound) {
                "leaseDuration must exceed the in-process provider retry bound"
            }
            val providerLimit = channels.values.minOf(ChannelWorkerProperties::effectiveConcurrency)
            check(
                globalConcurrency <= dbClaimMaxConcurrency &&
                    globalConcurrency <= memberResolverMaxConcurrency &&
                    globalConcurrency <= providerLimit,
            ) {
                "globalConcurrency must not exceed DB claim, member resolver, and provider capacities"
            }
            return this
        }

        companion object {
            private const val serialVersionUID = 1L
            private val MIN_ELAPSED: Duration = Duration.ofMinutes(15)
            private val MAX_ELAPSED: Duration = Duration.ofHours(72)
        }
    }

    /**
     * channel provider별 독립 동시성·timeout·rate-limit 경계입니다.
     */
    data class ChannelWorkerProperties(
        val providerMaxConcurrency: Int = 4,
        val bulkheadMaxConcurrentCalls: Int = providerMaxConcurrency,
        val providerTimeout: Duration = Duration.ofSeconds(30),
        val rateLimitPerSecond: Int = 100,
        val circuitBreakerFailureRateThreshold: Int = 50,
    ) : Serializable {
        val effectiveConcurrency: Int
            get() = minOf(providerMaxConcurrency, bulkheadMaxConcurrentCalls)

        fun validate(): ChannelWorkerProperties {
            check(providerMaxConcurrency > 0) { "providerMaxConcurrency must be positive" }
            check(bulkheadMaxConcurrentCalls > 0) { "bulkheadMaxConcurrentCalls must be positive" }
            check(!providerTimeout.isNegative && !providerTimeout.isZero) {
                "providerTimeout must be positive"
            }
            check(rateLimitPerSecond > 0) { "rateLimitPerSecond must be positive" }
            check(circuitBreakerFailureRateThreshold in 1..100) {
                "circuitBreakerFailureRateThreshold must be between 1 and 100"
            }
            return this
        }

        companion object {
            private const val serialVersionUID = 1L
        }
    }
}
