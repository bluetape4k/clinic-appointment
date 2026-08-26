package io.bluetape4k.clinic.appointment.api.waitlist

import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.bluetape4k.redis.lettuce.lock.FencedBootstrapResult
import io.bluetape4k.redis.lettuce.lock.FencedLockConfig
import io.bluetape4k.redis.lettuce.lock.LettuceFencedLock
import io.bluetape4k.redis.lettuce.lock.LockConfig
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

/** fencing token을 가진 lease만 vacancy dispatch에 전달하는 application port입니다. */
fun interface WaitlistFencedVacancyDispatcher {
    fun dispatch(limit: Int, now: Instant, lease: WaitlistLeaseHandle): Int
}

/** typed runner가 이번 tick에서 관측한 lease 상태입니다. */
enum class WaitlistFencedLeaseOutcome {
    ACQUIRED,
    REENTERED,
    CONTENDED,
    TIMEOUT,
    AMBIGUOUS,
    FAILED,
    CLOSED,
}

/** fenced scheduler의 bounded 실행 결과입니다. */
data class WaitlistFencedDeliveryTickResult(
    val mode: DeliveryMode,
    val leaseOutcome: WaitlistFencedLeaseOutcome,
    val dispatchCount: Int,
    val expiryCount: Int,
    val suppressionCount: Int,
    val holdReconcileCount: Int,
    val leaderAcquired: Boolean,
    val duration: Duration,
    val budgetExceeded: Boolean = false,
) {
    init {
        require(dispatchCount >= 0) { "dispatchCount must be non-negative" }
        require(expiryCount >= 0) { "expiryCount must be non-negative" }
        require(suppressionCount >= 0) { "suppressionCount must be non-negative" }
        require(holdReconcileCount >= 0) { "holdReconcileCount must be non-negative" }
        require(!duration.isNegative) { "duration must be non-negative" }
        require(leaderAcquired == (leaseOutcome == WaitlistFencedLeaseOutcome.ACQUIRED ||
            leaseOutcome == WaitlistFencedLeaseOutcome.REENTERED)) {
            "leaderAcquired must match a recovered lease handle"
        }
    }
}

/**
 * 기존 Boolean runner와 분리된 fenced scheduler입니다.
 *
 * Redis acquire가 명확한 handle을 반환한 경우에만 safety 작업과 typed dispatch를 시작합니다.
 * ambiguous 결과는 동일 owner/request reconcile을 한 번 수행하고, 회복되지 않으면 DB
 * mutation 없이 tick을 종료합니다.
 */
internal class WaitlistFencedDeliverySchedulingRunner(
    private val lease: FencedWaitlistLeaderLease,
    private val dispatcher: WaitlistFencedVacancyDispatcher,
    private val expiry: WaitlistOfferExpiryRunner,
    private val suppression: WaitlistNotificationSuppressionRunner,
    private val holdReconciler: WaitlistHoldReconciler,
    private val properties: WaitlistDeliveryProperties,
    private val metrics: WaitlistDeliveryMetrics,
    private val clock: Clock = Clock.systemUTC(),
    private val monotonicNanos: () -> Long = System::nanoTime,
) : AutoCloseable {

    private val closed = AtomicBoolean(false)
    private val tickInFlight = AtomicBoolean(false)

    fun tick(clinicId: Long = WaitlistDeliverySchedulingRunner.ALLOW_ALL_CLINICS): WaitlistFencedDeliveryTickResult {
        val startedAtNanos = monotonicNanos()
        val started = clock.instant()
        val mode = modeFor(clinicId)
        if (closed.get()) {
            return terminalResult(mode, WaitlistFencedLeaseOutcome.CLOSED, elapsedSince(startedAtNanos))
        }
        if (!tickInFlight.compareAndSet(false, true)) {
            metrics.recordLeaseAcquire(WaitlistLeaseMetricOutcome.FAILED, elapsedSince(startedAtNanos))
            return terminalResult(mode, WaitlistFencedLeaseOutcome.FAILED, elapsedSince(startedAtNanos))
        }

        try {
            val attempt = acquireOrReconcile(started)
            val outcome = attempt.outcome()
            metrics.recordLeaseAcquire(outcome.metricOutcome, elapsedSince(startedAtNanos))
            val handle = attempt.handleOrNull()
                ?: return terminalResult(mode, outcome, elapsedSince(startedAtNanos)).also {
                    log.debug { "Waitlist fenced tick skipped: lease_outcome=${outcome.name.lowercase()}" }
                }

            return try {
                var budgetExceeded = false
                var expiryCount = 0
                var suppressionCount = 0
                var holdReconcileCount = 0
                var dispatchCount = 0
                if (withinBudget(startedAtNanos)) {
                    expiryCount = expiry.expire(properties.batchSize, started)
                } else {
                    budgetExceeded = true
                }
                if (!budgetExceeded && withinBudget(startedAtNanos)) {
                    suppressionCount = suppression.suppress(properties.batchSize, started)
                } else {
                    budgetExceeded = true
                }
                if (!budgetExceeded && withinBudget(startedAtNanos)) {
                    holdReconcileCount = holdReconciler.reconcile(properties.batchSize, started)
                } else {
                    budgetExceeded = true
                }
                if (!budgetExceeded && mode == DeliveryMode.ACTIVE && withinBudget(startedAtNanos)) {
                    dispatchCount = dispatcher.dispatch(properties.batchSize, started, handle)
                } else {
                    if (mode == DeliveryMode.ACTIVE) budgetExceeded = true
                }
                requireNonNegative(expiryCount, "expiry")
                requireNonNegative(suppressionCount, "suppression")
                requireNonNegative(holdReconcileCount, "holdReconcile")
                requireNonNegative(dispatchCount, "dispatch")
                val tickResult = WaitlistFencedDeliveryTickResult(
                    mode = mode,
                    leaseOutcome = outcome,
                    dispatchCount = dispatchCount,
                    expiryCount = expiryCount,
                    suppressionCount = suppressionCount,
                    holdReconcileCount = holdReconcileCount,
                    leaderAcquired = true,
                    duration = elapsedSince(startedAtNanos),
                    budgetExceeded = budgetExceeded,
                )
                metrics.recordTick(mode, dispatchCount, expiryCount, suppressionCount, holdReconcileCount)
                tickResult
            } finally {
                releaseHandle(handle)
            }
        } finally {
            tickInFlight.set(false)
            metrics.recordSchedulerTick(mode, elapsedSince(startedAtNanos))
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            lease.close()
        }
    }

    private fun acquireOrReconcile(now: Instant): WaitlistLeaseAttempt {
        val acquired = runCatching { lease.tryAcquire(now) }
            .getOrElse { failure ->
                log.warn {
                    "Waitlist fenced lease acquire failed: exception=${failure::class.simpleName}"
                }
                return WaitlistLeaseAttempt.Failed(WaitlistLeaseFailure.BACKEND_FAILURE)
            }
        return if (acquired is WaitlistLeaseAttempt.Ambiguous) {
            runCatching { lease.reconcile(now) }
                .getOrElse { failure ->
                    log.warn {
                        "Waitlist fenced lease reconcile failed: exception=${failure::class.simpleName}"
                    }
                    WaitlistLeaseAttempt.Failed(WaitlistLeaseFailure.RECONCILE_FAILED)
                }
        } else {
            acquired
        }
    }

    private fun modeFor(clinicId: Long): DeliveryMode = if (clinicId == WaitlistDeliverySchedulingRunner.ALLOW_ALL_CLINICS) {
        when {
            !properties.enabled -> DeliveryMode.GLOBAL_OFF
            properties.clinicAllowlist.isNotEmpty() -> DeliveryMode.CLINIC_DISABLED
            else -> DeliveryMode.ACTIVE
        }
    } else {
        properties.modeFor(clinicId)
    }

    private fun terminalResult(
        mode: DeliveryMode,
        outcome: WaitlistFencedLeaseOutcome,
        duration: Duration,
    ): WaitlistFencedDeliveryTickResult = WaitlistFencedDeliveryTickResult(
        mode = mode,
        leaseOutcome = outcome,
        dispatchCount = 0,
        expiryCount = 0,
        suppressionCount = 0,
        holdReconcileCount = 0,
        leaderAcquired = false,
        duration = duration,
    )

    private fun releaseHandle(handle: WaitlistLeaseHandle) {
        when (val release = lease.release(handle)) {
            WaitlistLeaseRelease.UNKNOWN -> when (val retry = lease.release(handle)) {
                WaitlistLeaseRelease.OWNERSHIP_LOST -> metrics.recordOwnershipLoss(WaitlistOwnershipLossSource.REDIS)
                WaitlistLeaseRelease.RELEASED,
                WaitlistLeaseRelease.ALREADY_RELEASED,
                -> Unit
                else -> log.warn { "Waitlist fenced lease release incomplete: outcome=${retry.name.lowercase()}" }
            }
            WaitlistLeaseRelease.OWNERSHIP_LOST -> metrics.recordOwnershipLoss(WaitlistOwnershipLossSource.REDIS)
            WaitlistLeaseRelease.RELEASED,
            WaitlistLeaseRelease.ALREADY_RELEASED,
            -> Unit
            else -> log.warn { "Waitlist fenced lease release incomplete: outcome=${release.name.lowercase()}" }
        }
    }

    private fun elapsedSince(startedAtNanos: Long): Duration =
        Duration.ofNanos((monotonicNanos() - startedAtNanos).coerceAtLeast(0L))

    private fun withinBudget(startedAtNanos: Long): Boolean = elapsedSince(startedAtNanos) < properties.tickBudget

    private fun requireNonNegative(value: Int, name: String) {
        require(value >= 0) { "$name count must be non-negative" }
    }

    private fun WaitlistLeaseAttempt.handleOrNull(): WaitlistLeaseHandle? = when (this) {
        is WaitlistLeaseAttempt.Acquired -> handle
        is WaitlistLeaseAttempt.Reentered -> handle
        is WaitlistLeaseAttempt.Contended,
        is WaitlistLeaseAttempt.TimedOut,
        is WaitlistLeaseAttempt.Ambiguous,
        is WaitlistLeaseAttempt.Failed,
        -> null
    }

    private fun WaitlistLeaseAttempt.outcome(): WaitlistFencedLeaseOutcome = when (this) {
        is WaitlistLeaseAttempt.Acquired -> WaitlistFencedLeaseOutcome.ACQUIRED
        is WaitlistLeaseAttempt.Reentered -> WaitlistFencedLeaseOutcome.REENTERED
        is WaitlistLeaseAttempt.Contended -> WaitlistFencedLeaseOutcome.CONTENDED
        is WaitlistLeaseAttempt.TimedOut -> WaitlistFencedLeaseOutcome.TIMEOUT
        is WaitlistLeaseAttempt.Ambiguous -> WaitlistFencedLeaseOutcome.AMBIGUOUS
        is WaitlistLeaseAttempt.Failed -> when (category) {
            WaitlistLeaseFailure.CLOSED -> WaitlistFencedLeaseOutcome.CLOSED
            else -> WaitlistFencedLeaseOutcome.FAILED
        }
    }

    private val WaitlistFencedLeaseOutcome.metricOutcome: WaitlistLeaseMetricOutcome
        get() = when (this) {
            WaitlistFencedLeaseOutcome.ACQUIRED,
            WaitlistFencedLeaseOutcome.REENTERED,
            -> WaitlistLeaseMetricOutcome.ACQUIRED
            WaitlistFencedLeaseOutcome.CONTENDED -> WaitlistLeaseMetricOutcome.CONTENDED
            WaitlistFencedLeaseOutcome.TIMEOUT -> WaitlistLeaseMetricOutcome.TIMEOUT
            WaitlistFencedLeaseOutcome.AMBIGUOUS -> WaitlistLeaseMetricOutcome.AMBIGUOUS
            WaitlistFencedLeaseOutcome.FAILED,
            WaitlistFencedLeaseOutcome.CLOSED,
            -> WaitlistLeaseMetricOutcome.FAILED
        }

    private companion object : KLogging()
}

/** V31 fence column이 실제 DB에 존재하는지 zero-row query로 확인하는 readiness probe입니다. */
internal class WaitlistFencingReadiness(
    private val dataSource: DataSource,
) {
    fun verify() {
        try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT fence_epoch, fence_sequence FROM scheduling_waitlist_vacancy_jobs WHERE 1 = 0",
                ).use { statement ->
                    statement.executeQuery().use { }
                }
            }
        } catch (cause: Exception) {
            log.warn {
                "Waitlist V31 fencing readiness failed: exception=${cause::class.simpleName}"
            }
            throw WaitlistFencingReadinessException(cause)
        }
    }

    private companion object : KLogging()
}

/** V31 readiness를 통과하지 못한 fenced scheduler의 조용한 기동을 막습니다. */
class WaitlistFencingReadinessException(cause: Throwable) :
    IllegalStateException("Waitlist V31 fencing columns are not ready", cause)

/** Redis lock bootstrap 실패를 startup failure로 노출하는 예외입니다. */
class WaitlistFencedLockBootstrapException(result: FencedBootstrapResult) :
    IllegalStateException("Waitlist fenced lock bootstrap failed: ${result::class.simpleName}")

/** 명시적으로 모든 외부 port와 V31 readiness가 준비된 경우에만 fenced scheduler를 조립합니다. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(WaitlistDeliveryProperties::class)
@ConditionalOnProperty(
    prefix = "appointment.waitlist.delivery",
    name = ["enabled"],
    havingValue = "true",
)
@ConditionalOnBean(
    RedisClient::class,
    DataSource::class,
    WaitlistFencedVacancyDispatcher::class,
    WaitlistOfferExpiryRunner::class,
    WaitlistNotificationSuppressionRunner::class,
    WaitlistHoldReconciler::class,
    MeterRegistry::class,
)
internal class WaitlistFencedSchedulingConfiguration {

    @Bean
    @ConditionalOnMissingBean
    internal fun waitlistFencingReadiness(dataSource: DataSource): WaitlistFencingReadiness =
        WaitlistFencingReadiness(dataSource)

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    internal fun waitlistFencedRedisConnection(
        redisClient: RedisClient,
        readiness: WaitlistFencingReadiness,
    ): StatefulRedisConnection<String, String> {
        readiness.verify()
        return redisClient.connect()
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    internal fun waitlistFencedLock(
        connection: StatefulRedisConnection<String, String>,
        properties: WaitlistDeliveryProperties,
        readiness: WaitlistFencingReadiness,
    ): LettuceFencedLock {
        readiness.verify()
        val lock = LettuceFencedLock.create(
            connection,
            WAITLIST_LOCK_RESOURCE,
            FencedLockConfig(
                lock = LockConfig(namespace = WAITLIST_LOCK_NAMESPACE),
                epoch = properties.fenceEpoch,
            ),
        )
        when (val bootstrap = lock.bootstrapFencing()) {
            FencedBootstrapResult.Initialized,
            FencedBootstrapResult.AlreadyInitialized,
            -> Unit
            else -> {
                lock.close()
                log.warn { "Waitlist fenced lock bootstrap failed: result=${bootstrap::class.simpleName}" }
                throw WaitlistFencedLockBootstrapException(bootstrap)
            }
        }
        return lock
    }

    @Bean
    @ConditionalOnMissingBean
    internal fun waitlistFencedLockOperations(lock: LettuceFencedLock): WaitlistFencedLockOperations =
        LettuceWaitlistFencedLockOperations(lock)

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    internal fun fencedWaitlistLeaderLease(
        operations: WaitlistFencedLockOperations,
        properties: WaitlistDeliveryProperties,
    ): FencedWaitlistLeaderLease = FencedWaitlistLeaderLease(operations, properties)

    @Bean
    @ConditionalOnMissingBean
    internal fun waitlistDeliveryMetrics(meterRegistry: MeterRegistry): WaitlistDeliveryMetrics =
        WaitlistDeliveryMetrics(meterRegistry)

    @Bean
    @ConditionalOnMissingBean
    internal fun waitlistFencedDeliveryScheduler(
        lease: FencedWaitlistLeaderLease,
        dispatcher: WaitlistFencedVacancyDispatcher,
        expiry: WaitlistOfferExpiryRunner,
        suppression: WaitlistNotificationSuppressionRunner,
        holdReconciler: WaitlistHoldReconciler,
        properties: WaitlistDeliveryProperties,
        metrics: WaitlistDeliveryMetrics,
    ): WaitlistFencedDeliverySchedulingRunner = WaitlistFencedDeliverySchedulingRunner(
        lease = lease,
        dispatcher = dispatcher,
        expiry = expiry,
        suppression = suppression,
        holdReconciler = holdReconciler,
        properties = properties,
        metrics = metrics,
    )

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    internal fun waitlistFencedScheduler(
        runner: WaitlistFencedDeliverySchedulingRunner,
    ): WaitlistFencedScheduler = WaitlistFencedScheduler(runner)

    companion object : KLogging() {
        const val WAITLIST_LOCK_NAMESPACE = "bt4k:coord:v1"
        // LettuceFencedLock가 resource 구성요소를 검증하므로 구분자는 namespace에 둡니다.
        const val WAITLIST_LOCK_RESOURCE = "waitlist-delivery"
    }
}

/** scheduler thread와 fenced runner lifecycle을 분리하는 Spring adapter입니다. */
internal class WaitlistFencedScheduler(
    private val runner: WaitlistFencedDeliverySchedulingRunner,
) : AutoCloseable {
    @Scheduled(fixedDelayString = "\${appointment.waitlist.delivery.poll-interval:PT1S}")
    fun poll(): WaitlistFencedDeliveryTickResult = runner.tick()

    override fun close() = runner.close()
}
