package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.leader.LeaderElectionListener
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.metrics.LeaderAopMetricsRecorder
import io.bluetape4k.leader.metrics.SkipReason
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import java.util.concurrent.CancellationException
import kotlin.time.Duration

/** 0.5.0에서 실제로 제공되는 reminder leader callback만 notification Observation으로 연결합니다. */
internal class NotificationLeaderObservationBridge(
    private val registry: ObservationRegistry,
    private val lockName: String = REMINDER_RECOVERY_LOCK_NAME,
) : LeaderAopMetricsRecorder, LeaderElectionListener {

    override fun onLockAcquired(
        name: String,
        options: LeaderElectionOptions,
        acquireElapsed: Duration,
    ) {
        observe(name, Operation.ACQUIRE, Outcome.ACQUIRED)
    }

    override fun onLockNotAcquired(
        name: String,
        options: LeaderElectionOptions,
        reason: SkipReason,
    ) {
        observe(name, Operation.SKIP, Outcome.SKIPPED)
    }

    override fun onTaskFinished(name: String, executionTime: Duration) {
        observe(name, Operation.EXECUTE, Outcome.SUCCESS)
    }

    override fun onTaskFailed(name: String, executionTime: Duration, throwable: Throwable) {
        val outcome = if (throwable is CancellationException) Outcome.CANCELLED else Outcome.ERROR
        observe(name, Operation.EXECUTE, outcome, throwable)
    }

    override fun onRevoked(lockName: String) {
        observe(lockName, Operation.REVOKE, Outcome.REVOKED)
    }

    private fun observe(
        name: String,
        operation: Operation,
        outcome: Outcome,
        error: Throwable? = null,
    ) {
        if (name != lockName || registry.isNoop) return

        runCatching {
            var observation = Observation.createNotStarted(OBSERVATION_NAME, registry)
                .lowCardinalityKeyValue(TAG_LOCK, LOCK_VALUE)
                .lowCardinalityKeyValue(TAG_OPERATION, operation.value)
                .lowCardinalityKeyValue(TAG_OUTCOME, outcome.value)
            if (error != null && error !is CancellationException) {
                observation = observation.error(error)
            }
            observation.start().stop()
        }.onFailure { failure ->
            log.warn(failure) {
                "notification leader observation failed: operation=${operation.value}, outcome=${outcome.value}"
            }
        }
    }

    private enum class Operation(val value: String) {
        ACQUIRE("acquire"),
        EXECUTE("execute"),
        SKIP("skip"),
        REVOKE("revoke"),
    }

    private enum class Outcome(val value: String) {
        ACQUIRED("acquired"),
        SUCCESS("success"),
        ERROR("error"),
        CANCELLED("cancelled"),
        SKIPPED("skipped"),
        REVOKED("revoked"),
    }

    private companion object : KLogging() {
        const val OBSERVATION_NAME = "clinic.notification.leader.lifecycle"
        const val TAG_LOCK = "lock"
        const val TAG_OPERATION = "operation"
        const val TAG_OUTCOME = "outcome"
        const val LOCK_VALUE = "reminder"
    }
}
