package io.bluetape4k.clinic.appointment.messaging

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry

/** relay가 기록할 수 있는 bounded, 개인정보 없는 metric 계약이다. */
interface AppointmentOutboxMetrics {
    fun recordBacklog(snapshot: AppointmentOutboxBacklogSnapshot) = Unit

    fun publishSuccess(eventType: AppointmentEventType)

    fun publishRetry(failureCode: String)

    fun publishFailed(failureCode: String)

    fun contractRejected(failureCode: String)

    fun leaseLost()

    fun brokerPaused()

    /** metadata/ACL probe가 실패했음을 stable code로 기록한다. */
    fun readinessFailed(failureCode: String) = Unit
}

/** Micrometer가 없는 테스트/라이브러리 사용자를 위한 no-op metric이다. */
object NoopAppointmentOutboxMetrics : AppointmentOutboxMetrics {
    override fun publishSuccess(eventType: AppointmentEventType) = Unit

    override fun publishRetry(failureCode: String) = Unit

    override fun publishFailed(failureCode: String) = Unit

    override fun contractRejected(failureCode: String) = Unit

    override fun leaseLost() = Unit

    override fun brokerPaused() = Unit

    override fun readinessFailed(failureCode: String) = Unit
}

/** event type와 stable failure code만 tag로 허용하는 Micrometer adapter다. */
class MicrometerAppointmentOutboxMetrics(
    private val registry: MeterRegistry,
) : AppointmentOutboxMetrics {
    private val pending = java.util.concurrent.atomic.AtomicLong()
    private val oldestAgeSeconds = java.util.concurrent.atomic.AtomicReference(0.0)
    private val partitionSkew = java.util.concurrent.atomic.AtomicReference(0.0)

    init {
        Gauge.builder("appointment_outbox_pending", pending) { it.toDouble() }
            .description("Pending appointment outbox rows")
            .register(registry)
        Gauge.builder("appointment_outbox_oldest_age_seconds", oldestAgeSeconds) { it.get() }
            .description("Age of the oldest pending appointment outbox row")
            .register(registry)
        Gauge.builder("appointment_outbox_partition_skew", partitionSkew) { it.get() }
            .description("Maximum-to-average pending appointment partition ratio")
            .register(registry)
    }

    override fun recordBacklog(snapshot: AppointmentOutboxBacklogSnapshot) {
        pending.set(snapshot.pending)
        oldestAgeSeconds.set(snapshot.oldestAgeSeconds)
        partitionSkew.set(snapshot.partitionSkew)
    }

    override fun publishSuccess(eventType: AppointmentEventType) {
        counter("appointment_outbox_publish_success", "event_type", eventType.wireName).increment()
    }

    override fun publishRetry(failureCode: String) {
        counter("appointment_outbox_retry", "failure_code", boundedFailureCode(failureCode)).increment()
    }

    override fun publishFailed(failureCode: String) {
        counter("appointment_outbox_failed", "failure_code", boundedFailureCode(failureCode)).increment()
    }

    override fun contractRejected(failureCode: String) {
        counter("appointment_outbox_contract_rejected", "failure_code", boundedFailureCode(failureCode)).increment()
    }

    override fun leaseLost() {
        counter("appointment_outbox_lease_lost").increment()
    }

    override fun brokerPaused() {
        counter("appointment_outbox_broker_pause").increment()
    }

    override fun readinessFailed(failureCode: String) {
        counter("appointment_outbox_readiness_failed", "failure_code", boundedFailureCode(failureCode)).increment()
    }

    private fun counter(name: String, vararg tags: String): Counter =
        Counter.builder(name)
            .tags(*tags)
            .description("Appointment messaging relay counter")
            .register(registry)

    private fun boundedFailureCode(value: String): String =
        value.takeIf { it in ALLOWED_FAILURE_CODES }
            ?: throw IllegalArgumentException("failureCode is not allow-listed")

    companion object {
        private val ALLOWED_FAILURE_CODES = setOf(
            AppointmentOutboxRelay.FAILURE_BROKER_UNAVAILABLE,
            AppointmentOutboxRelay.FAILURE_DISALLOWED_TOPIC,
            AppointmentOutboxRelay.FAILURE_INVALID_PAYLOAD,
            AppointmentOutboxRelay.FAILURE_METADATA_MISMATCH,
            AppointmentOutboxRelay.FAILURE_INVALID_METADATA,
            AppointmentOutboxRelay.FAILURE_ATTEMPT_EXHAUSTED,
            AppointmentOutboxRelay.FAILURE_BROKER_METADATA_UNAVAILABLE,
            AppointmentOutboxRelay.FAILURE_BROKER_METADATA_TIMEOUT,
            AppointmentOutboxRelay.FAILURE_SCHEMA_CONTRACT,
            AppointmentOutboxRelay.FAILURE_SERIALIZER_CONTRACT,
            AppointmentOutboxRelay.FAILURE_BROKER_AUTHORIZATION,
            AppointmentOutboxRelay.FAILURE_BROKER_CONFIGURATION,
            AppointmentOutboxRelay.FAILURE_SERIALIZATION,
        )
    }
}
