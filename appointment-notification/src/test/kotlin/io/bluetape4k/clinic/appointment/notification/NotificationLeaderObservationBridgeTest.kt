package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.metrics.SkipReason
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import java.util.concurrent.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import org.junit.jupiter.api.Test

internal class NotificationLeaderObservationBridgeTest {

    @Test
    fun `지원 callback은 reminder lifecycle observation을 순서대로 기록한다`() {
        val records = mutableListOf<RecordedObservation>()
        val bridge = NotificationLeaderObservationBridge(registry(records))
        val options = LeaderElectionOptions.Default

        bridge.onLockAcquired(REMINDER_RECOVERY_LOCK_NAME, options, 5.milliseconds)
        bridge.onTaskFinished(REMINDER_RECOVERY_LOCK_NAME, 20.milliseconds)
        bridge.onLockNotAcquired(REMINDER_RECOVERY_LOCK_NAME, options, SkipReason.CONTENTION)
        bridge.onRevoked(REMINDER_RECOVERY_LOCK_NAME)

        records.map { it.operation to it.outcome } shouldBeEqualTo listOf(
            "acquire" to "acquired",
            "execute" to "success",
            "skip" to "skipped",
            "revoke" to "revoked",
        )
        records.forEach { record ->
            record.name shouldBeEqualTo "clinic.notification.leader.lifecycle"
            record.lowCardinality.keys shouldBeEqualTo setOf("lock", "operation", "outcome")
            record.lowCardinality["lock"] shouldBeEqualTo "reminder"
            check(record.highCardinality.isEmpty())
        }
    }

    @Test
    fun `task failure는 error와 cancellation을 구분하지만 예외 정보를 tag로 만들지 않는다`() {
        val records = mutableListOf<RecordedObservation>()
        val bridge = NotificationLeaderObservationBridge(registry(records))
        val options = LeaderElectionOptions.Default

        bridge.onTaskFailed(
            REMINDER_RECOVERY_LOCK_NAME,
            10.milliseconds,
            IllegalStateException("scan failed for tenant-42"),
        )
        bridge.onTaskFailed(
            REMINDER_RECOVERY_LOCK_NAME,
            10.milliseconds,
            CancellationException("cancelled"),
        )

        records.map { it.outcome } shouldBeEqualTo listOf("error", "cancelled")
        records.forEach { record ->
            check(record.lowCardinality.keys.none { key ->
                key.contains("tenant", ignoreCase = true) ||
                    key.contains("exception", ignoreCase = true) ||
                    key.contains("request", ignoreCase = true)
            })
        }
    }

    @Test
    fun `다른 lock callback은 관측하지 않는다`() {
        val records = mutableListOf<RecordedObservation>()
        val bridge = NotificationLeaderObservationBridge(registry(records))
        val options = LeaderElectionOptions.Default

        bridge.onLockAcquired("other-lock", options, 1.milliseconds)
        bridge.onTaskFinished("other-lock", 1.milliseconds)
        bridge.onLockNotAcquired("other-lock", options, SkipReason.BACKEND_ERROR)
        bridge.onRevoked("other-lock")

        check(records.isEmpty())
    }

    @Test
    fun `NOOP registry는 callback을 업무 실패로 바꾸지 않는다`() {
        val bridge = NotificationLeaderObservationBridge(ObservationRegistry.NOOP)
        val options = LeaderElectionOptions.Default

        bridge.onLockAcquired(REMINDER_RECOVERY_LOCK_NAME, options, 1.milliseconds)
        bridge.onTaskFinished(REMINDER_RECOVERY_LOCK_NAME, 1.milliseconds)
        bridge.onLockNotAcquired(REMINDER_RECOVERY_LOCK_NAME, options, SkipReason.CONTENTION)
        bridge.onTaskFailed(REMINDER_RECOVERY_LOCK_NAME, 1.milliseconds, IllegalStateException("ignored"))
        bridge.onRevoked(REMINDER_RECOVERY_LOCK_NAME)
    }

    @Test
    fun `observation handler 오류는 callback 밖으로 전파되지 않는다`() {
        val registry = ObservationRegistry.create()
        registry.observationConfig().observationHandler(
            object : ObservationHandler<Observation.Context> {
                override fun supportsContext(context: Observation.Context): Boolean = true

                override fun onStop(context: Observation.Context) {
                    error("telemetry handler failed")
                }
            },
        )
        val bridge = NotificationLeaderObservationBridge(registry)
        val options = LeaderElectionOptions.Default

        bridge.onLockAcquired(REMINDER_RECOVERY_LOCK_NAME, options, 1.milliseconds)
    }

    private fun registry(records: MutableList<RecordedObservation>): ObservationRegistry =
        ObservationRegistry.create().also { registry ->
            registry.observationConfig().observationHandler(
                object : ObservationHandler<Observation.Context> {
                    override fun supportsContext(context: Observation.Context): Boolean = true

                    override fun onStop(context: Observation.Context) {
                        val low = context.lowCardinalityKeyValues.associate { it.key to it.value }
                        val high = context.highCardinalityKeyValues.associate { it.key to it.value }
                        records += RecordedObservation(
                            name = requireNotNull(context.name),
                            operation = low.getValue("operation"),
                            outcome = low.getValue("outcome"),
                            lowCardinality = low,
                            highCardinality = high,
                        )
                    }
                },
            )
        }

    private data class RecordedObservation(
        val name: String,
        val operation: String,
        val outcome: String,
        val lowCardinality: Map<String, String>,
        val highCardinality: Map<String, String>,
    )
}
