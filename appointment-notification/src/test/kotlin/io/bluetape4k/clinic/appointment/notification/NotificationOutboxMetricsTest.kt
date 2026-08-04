package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.clinic.appointment.event.notification.NotificationChannelType
import io.bluetape4k.clinic.appointment.event.notification.NotificationDeliveryAttemptOutcome
import io.bluetape4k.clinic.appointment.event.notification.NotificationEventType
import io.bluetape4k.clinic.appointment.event.notification.NotificationFailureCode
import io.bluetape4k.clinic.appointment.event.notification.NotificationSuppressionReasonCode
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Duration

internal class NotificationOutboxMetricsTest {

    @Test
    fun `설계 기준의 metric 이름만 등록하고 태그는 낮은 cardinality 값만 사용한다`() {
        val registry = SimpleMeterRegistry()
        val metrics = NotificationOutboxMetrics(registry, FixedObservationStore())

        runBlocking {
            metrics.refreshSnapshot()
        }
        metrics.recordDeliveryAttempt(
            channel = NotificationChannelType.SMS,
            eventType = NotificationEventType.REMINDER,
            outcome = NotificationDeliveryAttemptOutcome.SUCCESS,
        )
        metrics.recordDeliveryLatency(
            channel = NotificationChannelType.SMS,
            eventType = NotificationEventType.REMINDER,
            outcome = NotificationDeliveryAttemptOutcome.SUCCESS,
            duration = Duration.ofMillis(250),
        )
        metrics.recordDeliveryRetry(
            channel = NotificationChannelType.SMS,
            eventType = NotificationEventType.REMINDER,
            reasonCode = NotificationFailureCode.PROVIDER_UNAVAILABLE,
        )
        metrics.recordSuppressed(NotificationSuppressionReasonCode.CONSENT_DENIED)
        metrics.recordExhausted(
            channel = NotificationChannelType.SMS,
            eventType = NotificationEventType.REMINDER,
            reasonCode = NotificationFailureCode.DELIVERY_RESULT_UNKNOWN,
        )
        metrics.recordLeaseRecovered(
            channel = NotificationChannelType.SMS,
            eventType = NotificationEventType.REMINDER,
        )
        metrics.recordReminderRecovery(
            ReminderRecoveryScanResult(notYetDue = 0, enqueued = 2, suppressed = 1, alreadyExists = 3),
        )
        metrics.recordEventLogWriteFailure(NotificationOutboxMetrics.EVENT_LOG_WRITE_FAILED)
        metrics.recordDirectEventScopeRejected(NotificationOutboxMetrics.DIRECT_EVENT_SCOPE_REJECTED)

        registry.get(NotificationOutboxMetrics.EVENT_LOG_WRITE_FAILURES)
            .tag("reason_code", NotificationOutboxMetrics.EVENT_LOG_WRITE_FAILED)
            .counter().count() shouldBeEqualTo 1.0
        registry.get(NotificationOutboxMetrics.DIRECT_EVENT_SCOPE_REJECTIONS)
            .tag("reason_code", NotificationOutboxMetrics.DIRECT_EVENT_SCOPE_REJECTED)
            .counter().count() shouldBeEqualTo 1.0

        registry.meters.map { it.id.name }.toSet() shouldBeEqualTo NotificationOutboxMetrics.METER_NAMES

        val forbiddenTagKeys = setOf(
            "member",
            "memberId",
            "appointment",
            "appointmentId",
            "outbox",
            "outboxId",
            "tenant",
            "tenantId",
            "clinic",
            "clinicId",
            "phone",
        )
        registry.meters
            .flatMap { it.id.tags }
            .any { it.key in forbiddenTagKeys }
            .shouldBeFalse()
    }

    @Test
    fun `pending과 oldest gauge는 bounded snapshot만 갱신하고 full scan 포트를 호출하지 않는다`() {
        val store = FixedObservationStore(
            snapshot = NotificationOutboxObservationSnapshot(
                pendingReady = 42,
                oldestActiveAge = Duration.ofMinutes(7),
                capped = true,
            ),
        )
        val registry = SimpleMeterRegistry()
        val metrics = NotificationOutboxMetrics(registry, store)

        runBlocking {
            metrics.refreshSnapshot()
        }

        registry.get(NotificationOutboxMetrics.PENDING).gauge().value() shouldBeEqualTo 42.0
        registry.get(NotificationOutboxMetrics.OLDEST_AGE).gauge().value() shouldBeEqualTo 420.0
        metrics.currentSnapshot().capped shouldBeEqualTo true
        store.boundedSnapshotCalls shouldBeEqualTo 1
        store.fullScanCalls shouldBeEqualTo 0
    }

    @Test
    fun `같은 낮은 cardinality 태그를 반복 기록해도 meter를 다시 등록하지 않는다`() {
        val registry = SimpleMeterRegistry()
        val metrics = NotificationOutboxMetrics(registry, FixedObservationStore())

        fun recordAll() {
            metrics.recordDeliveryAttempt(
                NotificationChannelType.SMS,
                NotificationEventType.REMINDER,
                NotificationDeliveryAttemptOutcome.SUCCESS,
            )
            metrics.recordDeliveryLatency(
                NotificationChannelType.SMS,
                NotificationEventType.REMINDER,
                NotificationDeliveryAttemptOutcome.SUCCESS,
                Duration.ofMillis(100),
            )
            metrics.recordDeliveryRetry(
                NotificationChannelType.SMS,
                NotificationEventType.REMINDER,
                NotificationFailureCode.PROVIDER_UNAVAILABLE,
            )
            metrics.recordSuppressed(NotificationSuppressionReasonCode.CONSENT_DENIED)
            metrics.recordExhausted(
                NotificationChannelType.SMS,
                NotificationEventType.REMINDER,
                NotificationFailureCode.DELIVERY_RESULT_UNKNOWN,
            )
            metrics.recordLeaseRecovered(
                NotificationChannelType.SMS,
                NotificationEventType.REMINDER,
            )
        }

        recordAll()
        val registeredMeterCount = registry.meters.size

        repeat(100) { recordAll() }

        registry.meters.size shouldBeEqualTo registeredMeterCount
    }

    private class FixedObservationStore(
        private val snapshot: NotificationOutboxObservationSnapshot = NotificationOutboxObservationSnapshot(
            pendingReady = 1,
            oldestActiveAge = Duration.ofSeconds(2),
        ),
    ) : NotificationOutboxObservationStore {
        var boundedSnapshotCalls = 0
            private set
        var fullScanCalls = 0
            private set

        override suspend fun loadBoundedSnapshot(): NotificationOutboxObservationSnapshot {
            boundedSnapshotCalls += 1
            return snapshot
        }

        fun fullScanCount(): Long {
            fullScanCalls += 1
            return 0
        }
    }
}
