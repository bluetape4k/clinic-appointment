package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.assertions.shouldBeEqualTo
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import java.time.Duration

class MicrometerAppointmentConsumerMetricsTest {
    @Test
    fun `consumer metrics expose bounded outcomes lag transactions replay and retention`() {
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerAppointmentConsumerMetrics(registry)

        metrics.processed()
        metrics.duplicate()
        metrics.retry(AppointmentConsumerFailureCode.HANDLER_RETRYABLE)
        metrics.quarantined(AppointmentConsumerFailureCode.INVALID_ENVELOPE)
        metrics.recordLag(42)
        metrics.recordOldestAge(Duration.ofSeconds(3))
        metrics.recordInboxTransaction("begin", Duration.ofMillis(7))
        metrics.recordInboxTransaction("oldest_age", Duration.ofMillis(2))
        metrics.replay(AppointmentReplayAuditStatus.EXECUTED)
        metrics.retentionDeleted(AppointmentRetentionTable.REPLAY_AUDIT, 2)
        metrics.lagUnavailable()

        registry.counter("appointment_consumer_processed").count() shouldBeEqualTo 1.0
        registry.counter("appointment_consumer_duplicate").count() shouldBeEqualTo 1.0
        registry.counter("appointment_consumer_retry", "failure_code", "HANDLER_RETRYABLE").count() shouldBeEqualTo 1.0
        registry.counter("appointment_consumer_quarantined", "failure_code", "INVALID_ENVELOPE").count() shouldBeEqualTo 1.0
        registry.get("appointment_consumer_lag").gauge().value() shouldBeEqualTo 42.0
        registry.get("appointment_consumer_oldest_age_seconds").gauge().value() shouldBeEqualTo 3.0
        registry.timer("appointment_consumer_inbox_transaction", "operation", "begin").count() shouldBeEqualTo 1L
        registry.timer("appointment_consumer_inbox_transaction", "operation", "oldest_age").count() shouldBeEqualTo 1L
        registry.counter("appointment_consumer_replay", "status", "EXECUTED").count() shouldBeEqualTo 1.0
        registry.counter("appointment_consumer_retention_deleted", "table", "replay_audit").count() shouldBeEqualTo 2.0
        registry.counter("appointment_consumer_lag_unavailable").count() shouldBeEqualTo 1.0
    }
}
