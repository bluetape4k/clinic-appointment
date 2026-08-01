package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.clinic.appointment.event.notification.NotificationChannelType
import io.bluetape4k.clinic.appointment.event.notification.NotificationDeliveryAttemptOutcome
import io.bluetape4k.clinic.appointment.event.notification.NotificationEventType
import io.bluetape4k.clinic.appointment.event.notification.NotificationFailureCode
import io.bluetape4k.clinic.appointment.event.notification.NotificationSuppressionReasonCode
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.io.Serializable
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * notification outbox 운영 metric을 낮은 cardinality tag로만 기록합니다.
 *
 * tenant, clinic, member, appointment, outbox 식별자는 공용 metric tag에 올리지 않는다.
 * pending/oldest gauge는 [NotificationOutboxObservationStore]가 제공하는 제한된 snapshot을
 * 별도 주기로 갱신한 캐시만 읽는다.
 */
class NotificationOutboxMetrics(
    private val registry: MeterRegistry,
    private val observationStore: NotificationOutboxObservationStore,
) {
    private val pendingReady = AtomicLong()
    private val oldestActiveAgeSeconds = AtomicLong()
    private val backlogCapped = AtomicBoolean()
    private val deliveryAttemptCounters = ConcurrentHashMap<DeliveryMeterKey, Counter>()
    private val deliveryLatencyTimers = ConcurrentHashMap<DeliveryMeterKey, Timer>()
    private val deliveryRetryCounters = ConcurrentHashMap<FailureMeterKey, Counter>()
    private val suppressedCounters = ConcurrentHashMap<NotificationSuppressionReasonCode, Counter>()
    private val exhaustedCounters = ConcurrentHashMap<FailureMeterKey, Counter>()
    private val leaseRecoveredCounters = ConcurrentHashMap<ChannelEventMeterKey, Counter>()
    private val reminderRecoveryCounters = ConcurrentHashMap<String, Counter>()

    init {
        Gauge.builder(PENDING, pendingReady) { it.get().toDouble() }
            .register(registry)
        Gauge.builder(OLDEST_AGE, oldestActiveAgeSeconds) { it.get().toDouble() }
            .baseUnit("seconds")
            .register(registry)
    }

    suspend fun refreshSnapshot(): NotificationOutboxObservationSnapshot {
        val snapshot = observationStore.loadBoundedSnapshot()
        pendingReady.set(snapshot.pendingReady)
        oldestActiveAgeSeconds.set(snapshot.oldestActiveAge?.seconds ?: 0L)
        backlogCapped.set(snapshot.capped)
        return snapshot
    }

    fun currentSnapshot(): NotificationOutboxObservationSnapshot =
        NotificationOutboxObservationSnapshot(
            pendingReady = pendingReady.get(),
            oldestActiveAge = Duration.ofSeconds(oldestActiveAgeSeconds.get()),
            capped = backlogCapped.get(),
        )

    fun recordDeliveryAttempt(
        channel: NotificationChannelType,
        eventType: NotificationEventType,
        outcome: NotificationDeliveryAttemptOutcome,
    ) {
        val key = DeliveryMeterKey(channel, eventType, outcome)
        deliveryAttemptCounters.computeIfAbsent(key) {
            Counter.builder(DELIVERY_ATTEMPTS)
                .tags(
                    "channel",
                    channel.metricValue(),
                    "event_type",
                    eventType.metricValue(),
                    "outcome",
                    outcome.metricValue(),
                )
                .register(registry)
        }.increment()
    }

    fun recordDeliveryLatency(
        channel: NotificationChannelType,
        eventType: NotificationEventType,
        outcome: NotificationDeliveryAttemptOutcome,
        duration: Duration,
    ) {
        val key = DeliveryMeterKey(channel, eventType, outcome)
        deliveryLatencyTimers.computeIfAbsent(key) {
            Timer.builder(DELIVERY_LATENCY)
                .publishPercentileHistogram()
                .tags(
                    "channel",
                    channel.metricValue(),
                    "event_type",
                    eventType.metricValue(),
                    "outcome",
                    outcome.metricValue(),
                )
                .register(registry)
        }.record(duration)
    }

    fun recordDeliveryRetry(
        channel: NotificationChannelType,
        eventType: NotificationEventType,
        reasonCode: NotificationFailureCode,
    ) {
        val key = FailureMeterKey(channel, eventType, reasonCode)
        deliveryRetryCounters.computeIfAbsent(key) {
            Counter.builder(DELIVERY_RETRIES)
                .tags(
                    "channel",
                    channel.metricValue(),
                    "event_type",
                    eventType.metricValue(),
                    "reason_code",
                    reasonCode.metricValue(),
                )
                .register(registry)
        }.increment()
    }

    fun recordSuppressed(reasonCode: NotificationSuppressionReasonCode) {
        suppressedCounters.computeIfAbsent(reasonCode) {
            Counter.builder(DELIVERY_SUPPRESSED)
                .tag("reason_code", reasonCode.metricValue())
                .register(registry)
        }.increment()
    }

    fun recordExhausted(
        channel: NotificationChannelType,
        eventType: NotificationEventType,
        reasonCode: NotificationFailureCode,
    ) {
        val key = FailureMeterKey(channel, eventType, reasonCode)
        exhaustedCounters.computeIfAbsent(key) {
            Counter.builder(DELIVERY_EXHAUSTED)
                .tags(
                    "channel",
                    channel.metricValue(),
                    "event_type",
                    eventType.metricValue(),
                    "reason_code",
                    reasonCode.metricValue(),
                )
                .register(registry)
        }.increment()
    }

    fun recordLeaseRecovered(
        channel: NotificationChannelType,
        eventType: NotificationEventType,
    ) {
        val key = ChannelEventMeterKey(channel, eventType)
        leaseRecoveredCounters.computeIfAbsent(key) {
            Counter.builder(DELIVERY_LEASE_RECOVERED)
                .tags(
                    "channel",
                    channel.metricValue(),
                    "event_type",
                    eventType.metricValue(),
                )
                .register(registry)
        }.increment()
    }

    /** 한 보정 tick의 비식별 결과 건수를 낮은 cardinality result tag로 기록합니다. */
    fun recordReminderRecovery(result: ReminderRecoveryScanResult) {
        incrementReminderRecovery("enqueued", result.enqueued)
        incrementReminderRecovery("suppressed", result.suppressed)
        incrementReminderRecovery("already_exists", result.alreadyExists)
        incrementReminderRecovery("not_yet_due", result.notYetDue)
    }

    private fun incrementReminderRecovery(result: String, count: Int) {
        if (count <= 0) return
        reminderRecoveryCounters.computeIfAbsent(result) {
            Counter.builder(REMINDER_RECOVERY)
                .tag("result", result)
                .register(registry)
        }.increment(count.toDouble())
    }

    companion object {
        const val PENDING = "clinic.notification.outbox.pending"
        const val OLDEST_AGE = "clinic.notification.outbox.oldest.age"
        const val DELIVERY_ATTEMPTS = "clinic.notification.delivery.attempts"
        const val DELIVERY_LATENCY = "clinic.notification.delivery.latency"
        const val DELIVERY_RETRIES = "clinic.notification.delivery.retries"
        const val DELIVERY_SUPPRESSED = "clinic.notification.delivery.suppressed"
        const val DELIVERY_EXHAUSTED = "clinic.notification.delivery.exhausted"
        const val DELIVERY_LEASE_RECOVERED = "clinic.notification.delivery.lease.recovered"
        const val REMINDER_RECOVERY = "clinic.notification.reminder.recovery"

        val METER_NAMES: Set<String> = setOf(
            PENDING,
            OLDEST_AGE,
            DELIVERY_ATTEMPTS,
            DELIVERY_LATENCY,
            DELIVERY_RETRIES,
            DELIVERY_SUPPRESSED,
            DELIVERY_EXHAUSTED,
            DELIVERY_LEASE_RECOVERED,
            REMINDER_RECOVERY,
        )
    }
}

private data class DeliveryMeterKey(
    val channel: NotificationChannelType,
    val eventType: NotificationEventType,
    val outcome: NotificationDeliveryAttemptOutcome,
)

private data class FailureMeterKey(
    val channel: NotificationChannelType,
    val eventType: NotificationEventType,
    val reasonCode: NotificationFailureCode,
)

private data class ChannelEventMeterKey(
    val channel: NotificationChannelType,
    val eventType: NotificationEventType,
)

/**
 * metric gauge가 읽을 bounded/cached 관측 snapshot을 제공하는 port입니다.
 *
 * 구현체는 indexed active-row query, worker 갱신 cache, 또는 상한이 있는 sampler만 사용해야
 * 하며 scrape마다 전체 table exact scan을 수행하지 않는다.
 */
fun interface NotificationOutboxObservationStore {
    suspend fun loadBoundedSnapshot(): NotificationOutboxObservationSnapshot
}

/** gauge가 읽는 상한 있는 outbox 관측값입니다. */
data class NotificationOutboxObservationSnapshot(
    val pendingReady: Long,
    val oldestActiveAge: Duration?,
    val capped: Boolean = false,
) : Serializable {
    init {
        require(pendingReady >= 0) { "pendingReady must be non-negative" }
        require(oldestActiveAge == null || !oldestActiveAge.isNegative) {
            "oldestActiveAge must be non-negative"
        }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

internal fun NotificationChannelType.metricValue(): String = name.lowercase()

internal fun NotificationEventType.metricValue(): String = name.lowercase()

internal fun NotificationDeliveryAttemptOutcome.metricValue(): String = name.lowercase()

internal fun NotificationFailureCode.metricValue(): String = name

internal fun NotificationSuppressionReasonCode.metricValue(): String = name
