package io.bluetape4k.clinic.appointment.messaging

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * consumer 운영 신호의 bounded metric port입니다.
 *
 * identity의 실제 tenant/clinic/event 값과 payload는 metric tag로 노출하지 않습니다.
 */
interface AppointmentConsumerMetrics {
    fun processed() = Unit
    fun duplicate() = Unit
    fun retry(failureCode: AppointmentConsumerFailureCode) = Unit
    fun quarantined(failureCode: AppointmentConsumerFailureCode) = Unit
    fun recordLag(lag: Long) = Unit
    fun recordOldestAge(age: Duration) = Unit
    fun recordInboxTransaction(operation: String, duration: Duration) = Unit
    fun replay(status: AppointmentReplayAuditStatus) = Unit
    fun retentionDeleted(table: AppointmentRetentionTable, count: Int) = Unit
    fun lagUnavailable() = Unit
}

/** Micrometer가 없는 library/test 환경의 no-op 구현입니다. */
object NoopAppointmentConsumerMetrics : AppointmentConsumerMetrics

/** low-cardinality outcome/failure code만 허용하는 consumer Micrometer adapter입니다. */
class MicrometerAppointmentConsumerMetrics(
    private val registry: MeterRegistry,
) : AppointmentConsumerMetrics {
    private val lag = AtomicLong(0)
    private val oldestAgeSeconds = AtomicReference(0.0)

    init {
        Gauge.builder("appointment_consumer_lag", lag) { it.toDouble() }
            .description("Latest sampled Kafka consumer lag")
            .register(registry)
        Gauge.builder("appointment_consumer_oldest_age_seconds", oldestAgeSeconds) { it.get() }
            .description("Age of the oldest active consumer inbox row")
            .register(registry)
    }

    override fun processed() = counter("processed").increment()

    override fun duplicate() = counter("duplicate").increment()

    override fun retry(failureCode: AppointmentConsumerFailureCode) =
        counter("retry", "failure_code", failureCode.name).increment()

    override fun quarantined(failureCode: AppointmentConsumerFailureCode) =
        counter("quarantined", "failure_code", failureCode.name).increment()

    override fun recordLag(lag: Long) {
        this.lag.set(lag.coerceAtLeast(0))
    }

    override fun recordOldestAge(age: Duration) {
        oldestAgeSeconds.set(age.toMillis().coerceAtLeast(0).toDouble() / 1_000)
    }

    override fun recordInboxTransaction(operation: String, duration: Duration) {
        require(operation in ALLOWED_OPERATIONS) { "inbox metric operation is not allow-listed" }
        Timer.builder("appointment_consumer_inbox_transaction")
            .tag("operation", operation)
            .description("Consumer inbox transaction latency")
            .register(registry)
            .record(duration)
    }

    override fun replay(status: AppointmentReplayAuditStatus) =
        counter("replay", "status", status.name).increment()

    override fun retentionDeleted(table: AppointmentRetentionTable, count: Int) {
        Counter.builder("appointment_consumer_retention_deleted")
            .tag("table", table.metricName)
            .description("Rows removed by bounded consumer retention")
            .register(registry)
            .increment(count.toDouble().coerceAtLeast(0.0))
    }

    override fun lagUnavailable() = counter("lag_unavailable").increment()

    private fun counter(name: String, vararg tags: String): Counter =
        Counter.builder("appointment_consumer_$name")
            .tags(*tags)
            .description("Appointment consumer operational counter")
            .register(registry)

    companion object {
        private val ALLOWED_OPERATIONS = setOf(
            "begin", "processed", "failure", "quarantine", "rejected", "cleanup", "oldest_age",
        )
    }
}

enum class AppointmentRetentionTable(val metricName: String) {
    INBOX("inbox"),
    REJECTED("rejected"),
    QUARANTINE("quarantine"),
    REPLAY_AUDIT("replay_audit"),
}
