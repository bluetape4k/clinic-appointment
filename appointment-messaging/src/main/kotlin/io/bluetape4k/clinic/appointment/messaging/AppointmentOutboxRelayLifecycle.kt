package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.context.SmartLifecycle
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Kafka relay를 non-blocking scheduler와 bounded IO coroutine으로 실행하는 lifecycle이다.
 *
 * scheduler thread는 trigger만 처리하고, Exposed JDBC와 Kafka future 대기는
 * [Dispatchers.IO]의 [runInterruptible] 경계 안에서 수행한다. 종료 시 새 claim을 중지하고
 * 현재 tick이 shutdown timeout 안에 끝나지 않으면 cancellation interrupt를 전파한다.
 */
class AppointmentOutboxRelayLifecycle(
    private val relay: AppointmentOutboxRelay,
    private val properties: AppointmentMessagingProperties,
    private val owner: String = "appointment-relay-${UUID.randomUUID()}",
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "appointment-outbox-relay").apply { isDaemon = true }
        },
) : SmartLifecycle {
    private val running = AtomicBoolean(false)
    private val tickInFlight = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scheduledTask: ScheduledFuture<*>? = null
    private var activeTick: Job? = null

    override fun start() {
        if (!properties.enabled || !running.compareAndSet(false, true)) return
        scheduledTask = scheduler.scheduleWithFixedDelay(
            { runTickSafely() },
            0,
            properties.pollInterval.toMillis().coerceAtLeast(1),
            TimeUnit.MILLISECONDS,
        )
    }

    override fun stop() {
        stop {
            // SmartLifecycle의 callback 없는 호환 경로다.
        }
    }

    override fun stop(callback: Runnable) {
        if (!running.compareAndSet(true, false)) {
            scheduledTask?.cancel(false)
            scheduledTask = null
            scheduler.shutdownNow()
            scope.cancel()
            callback.run()
            return
        }
        scheduledTask?.cancel(false)
        scheduledTask = null
        scheduler.shutdown()
        try {
            val timeoutMillis = properties.shutdownTimeout.toMillis().coerceAtLeast(1)
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            awaitTermination(scheduler, remainingMillis(deadline))
            runBlocking {
                val tick = activeTick
                if (tick != null && tick.isActive) {
                    val completed = withTimeoutOrNull(remainingMillis(deadline)) { tick.join() }
                    if (completed == null && tick.isActive) {
                        // Do not join after the hard deadline: a JDBC driver or Kafka future
                        // that ignores interruption must not hold SmartLifecycle.stop forever.
                        tick.cancel(CancellationException("appointment relay shutdown timeout"))
                    }
                }
                scope.cancel()
            }
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            scheduler.shutdownNow()
            scope.cancel()
            log.warn(ex) { "Appointment outbox relay shutdown was interrupted" }
        } finally {
            callback.run()
        }
    }

    override fun isRunning(): Boolean = running.get()

    override fun isAutoStartup(): Boolean = true

    override fun getPhase(): Int = Int.MAX_VALUE - 100

    private fun runTickSafely() {
        if (!running.get() || !tickInFlight.compareAndSet(false, true)) return
        activeTick = scope.launch(Dispatchers.IO) {
            try {
                runInterruptible(Dispatchers.IO) { relay.tick(owner) }
            } catch (ex: CancellationException) {
                if (running.get()) log.warn(ex) { "Appointment outbox relay tick was cancelled" }
            } catch (ex: Exception) {
                log.warn(ex) { "Appointment outbox relay tick failed" }
            } finally {
                tickInFlight.set(false)
            }
        }
    }

    private fun awaitTermination(executor: ScheduledExecutorService, timeoutMillis: Long) {
        if (timeoutMillis <= 0 || executor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)) return
        executor.shutdownNow()
        executor.awaitTermination(FORCED_SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS)
    }

    private fun remainingMillis(deadlineNanos: Long): Long =
        TimeUnit.NANOSECONDS.toMillis((deadlineNanos - System.nanoTime()).coerceAtLeast(0)).coerceAtLeast(1)

    companion object : KLogging() {
        private val FORCED_SHUTDOWN_WAIT: Duration = Duration.ofSeconds(1)
    }
}
