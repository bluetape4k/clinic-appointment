package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.clinic.appointment.event.notification.NotificationChannelType
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
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
 *     worker:
 *       enabled: true
 *       max-attempts: 6
 *       lease-duration: 60s
 *       global-concurrency: 4
 *       per-clinic-concurrency: 1
 * ```
 *
 * @property enabled 알림 모듈 활성화 여부
 * @property events outbox 생성 시 적용하는 예약 이벤트별 알림 설정
 * @property reminder 리마인더 outbox 생성 설정
 * @property worker 내구성 outbox worker 설정
 * @property observation backlog 관측 설정
 * @property retention 종료 outbox 보존·삭제 설정
 * @property rollout 병원별 provider route 전환 설정
 */
@ConfigurationProperties(prefix = "clinic.notification")
data class NotificationProperties(
    val enabled: Boolean = true,
    val events: EventProperties = EventProperties(),
    val reminder: ReminderProperties = ReminderProperties(),
    val worker: WorkerProperties = WorkerProperties(),
    val observation: ObservationProperties = ObservationProperties(),
    val retention: RetentionProperties = RetentionProperties(),
    val rollout: RolloutProperties = RolloutProperties(),
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
     * 내구성 알림 outbox worker 설정입니다.
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
        val reminderRecoveryInterval: Duration = Duration.ofHours(1),
        val reminderRecoveryMaxCandidatesPerRun: Int = 1_000,
        val leaseDuration: Duration = Duration.ofSeconds(60),
        val providerTimeout: Duration = Duration.ofSeconds(30),
        val pollInterval: Duration = Duration.ofSeconds(1),
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
        /** 채널 유형의 소문자 키로 설정한 timeout을 우선하고, 없으면 전역 timeout을 사용합니다. */
        fun providerTimeoutFor(channelType: NotificationChannelType): Duration =
            channels[channelType.name.lowercase()]?.providerTimeout ?: providerTimeout

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
            check(!reminderRecoveryInterval.isNegative && !reminderRecoveryInterval.isZero) {
                "reminderRecoveryInterval must be positive"
            }
            check(reminderRecoveryMaxCandidatesPerRun in batchSize..100_000) {
                "reminderRecoveryMaxCandidatesPerRun must be between batchSize and 100000"
            }
            check(!leaseDuration.isNegative && !leaseDuration.isZero) {
                "leaseDuration must be positive"
            }
            check(!providerTimeout.isNegative && !providerTimeout.isZero) {
                "providerTimeout must be positive"
            }
            check(!pollInterval.isNegative && !pollInterval.isZero) {
                "pollInterval must be positive"
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
            val longestProviderTimeout = maxOf(
                providerTimeout,
                channels.values.maxOf(ChannelWorkerProperties::providerTimeout),
            )
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

    /** ready backlog snapshot의 조회 주기와 상한입니다. */
    data class ObservationProperties(
        val pollInterval: Duration = Duration.ofSeconds(10),
        val limit: Int = 10_001,
    ) : Serializable {
        fun validate(): ObservationProperties {
            check(!pollInterval.isNegative && !pollInterval.isZero) {
                "observation pollInterval must be positive"
            }
            check(limit > 0) { "observation limit must be positive" }
            return this
        }

        companion object {
            private const val serialVersionUID = 1L
        }
    }

    /** 종료 outbox와 attempt의 상태별 보존·삭제 설정입니다. */
    data class RetentionProperties(
        val pollInterval: Duration = Duration.ofHours(1),
        val sent: Duration = Duration.ofDays(7),
        val suppressed: Duration = Duration.ofDays(7),
        val exhausted: Duration = Duration.ofDays(30),
        val pageSize: Int = 100,
        val maxPagesPerStatus: Int = 10,
        val backpressure: Duration = Duration.ofMillis(100),
    ) : Serializable {
        fun validate(): RetentionProperties {
            check(!pollInterval.isNegative && !pollInterval.isZero) { "retention pollInterval must be positive" }
            listOf(sent, suppressed, exhausted).forEach {
                check(!it.isNegative && !it.isZero) { "retention duration must be positive" }
            }
            check(pageSize > 0) { "retention pageSize must be positive" }
            check(maxPagesPerStatus > 0) { "retention maxPagesPerStatus must be positive" }
            check(!backpressure.isNegative) { "retention backpressure must be non-negative" }
            return this
        }

        companion object {
            private const val serialVersionUID = 1L
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

    /**
     * 병원별 알림 발송 route 전환 설정입니다.
     *
     * 기본 `SHADOW`는 background worker 발송을 막고 privacy-safe 전환기 event route를
     * 유지합니다. `CANARY`만 병원 allowlist를 허용합니다.
     */
    data class RolloutProperties(
        val mode: NotificationRolloutMode = NotificationRolloutMode.SHADOW,
        /** 신버전 route와 DB eligibility가 사용하는 tenant/clinic scope allowlist입니다. */
        val canaryScopes: Set<TenantClinicScope> = emptySet(),
        /**
         * 구버전 node rolling drain을 위한 임시 호환 필드입니다.
         * 신버전 route 결정에는 사용하지 않으며 [canaryScopes]와 clinic 집합이 같아야 합니다.
         */
        @Deprecated("Use canaryScopes")
        val canaryClinicIds: Set<Long> = emptySet(),
    ) : Serializable {
        fun validate(): RolloutProperties {
            check(canaryScopes.all { it.tenantGroupId > 0L && it.clinicId > 0L }) {
                "canaryScopes must contain only positive IDs"
            }
            check(canaryClinicIds.all { it > 0L }) { "canaryClinicIds must contain only positive IDs" }
            if (canaryClinicIds.isNotEmpty() && canaryScopes.isNotEmpty()) {
                check(canaryScopes.mapTo(mutableSetOf()) { it.clinicId } == canaryClinicIds) {
                    "canaryClinicIds and canaryScopes must contain the same clinic set"
                }
            }
            if (mode == NotificationRolloutMode.CANARY) {
                check(canaryScopes.isNotEmpty()) { "CANARY mode requires at least one canary scope" }
            } else {
                check(canaryScopes.isEmpty()) { "canaryScopes are only allowed in CANARY mode" }
                check(canaryClinicIds.isEmpty()) { "canaryClinicIds are only allowed in CANARY mode" }
            }
            return this
        }

        companion object {
            private const val serialVersionUID = 1L
        }
    }
}
