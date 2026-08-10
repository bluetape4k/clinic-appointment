package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AppointmentOutboxRelayLifecycleTest {
    @Test
    fun `disabled relay does not accept work`() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        try {
            val lifecycle = AppointmentOutboxRelayLifecycle(
                relay = emptyRelay(),
                properties = AppointmentMessagingProperties(enabled = false),
                scheduler = executor,
            )

            lifecycle.start()

            lifecycle.isRunning.shouldBeFalse()
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `started relay drains scheduler on stop`() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val lifecycle = AppointmentOutboxRelayLifecycle(
            relay = emptyRelay(),
            properties = AppointmentMessagingProperties(
                pollInterval = Duration.ofMillis(5),
                shutdownTimeout = Duration.ofSeconds(1),
            ),
            scheduler = executor,
        )

        lifecycle.start()
        lifecycle.isRunning.shouldBeTrue()
        val stopped = CompletableFuture<Unit>()
        lifecycle.stop { stopped.complete(Unit) }
        stopped.get(2, TimeUnit.SECONDS)

        lifecycle.isRunning.shouldBeFalse()
        executor.isTerminated.shouldBeTrue()
    }

    private fun emptyRelay(): AppointmentOutboxRelay = AppointmentOutboxRelay(
        store = object : AppointmentOutboxStore {
            override fun claim(owner: String, limit: Int, leaseDuration: Duration): List<AppointmentOutboxClaim> = emptyList()
            override fun markPublished(claim: AppointmentOutboxClaim): Boolean = false
            override fun markRetry(claim: AppointmentOutboxClaim, retryAfter: Duration, failureCode: String): Boolean = false
            override fun markFailed(claim: AppointmentOutboxClaim, failureCode: String): Boolean = false
        },
        publisher = AppointmentKafkaPublisher { _, _, _ -> CompletableFuture.completedFuture(Unit) },
    )
}
