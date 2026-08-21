package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.event.notification.AppointmentId
import io.bluetape4k.clinic.appointment.event.notification.ClaimedNotification
import io.bluetape4k.clinic.appointment.event.notification.ClinicId
import io.bluetape4k.clinic.appointment.event.notification.NotificationChannelType
import io.bluetape4k.clinic.appointment.event.notification.NotificationEventId
import io.bluetape4k.clinic.appointment.event.notification.NotificationEventType
import io.bluetape4k.clinic.appointment.event.notification.NotificationIdempotencyKey
import io.bluetape4k.clinic.appointment.event.notification.NotificationParameterType
import io.bluetape4k.clinic.appointment.event.notification.NotificationSlot
import io.bluetape4k.clinic.appointment.event.notification.NotificationTemplateKey
import io.bluetape4k.clinic.appointment.event.notification.NotificationTemplateVersion
import io.bluetape4k.clinic.appointment.event.notification.TenantGroupId
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.redis.lettuce.synchronizer.SemaphoreOwnerId
import io.bluetape4k.redis.lettuce.synchronizer.SemaphoreRequestId
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

internal class NotificationOutboxConcurrencyCoordinatorTest {

    @Test
    fun `clinic admission failure rolls back the global permit without invoking the action`() = runBlocking {
        val global = FakeNotificationPermitSemaphore(
            acquireResults = ArrayDeque(listOf(NotificationPermitAcquire.Acquired(handle("global")))),
        )
        val clinic = FakeNotificationPermitSemaphore(
            acquireResults = ArrayDeque(listOf(NotificationPermitAcquire.Unavailable)),
        )
        val coordinator = RedisNotificationOutboxConcurrencyCoordinator(
            properties = workerProperties(),
            global = global,
            clinicFactory = { clinic },
        )
        val actionCalls = AtomicInteger()

        val result = coordinator.withPermit(notification()) {
            actionCalls.incrementAndGet()
            Unit
        }

        result shouldBeEqualTo NotificationOutboxAdmission.Backpressured(NotificationPermitFailureReason.UNAVAILABLE)
        actionCalls.get() shouldBeEqualTo 0
        global.releaseCalls shouldBeEqualTo 1
    }

    @Test
    fun `ambiguous clinic acquire is reconciled with the same request before action`() = runBlocking {
        val global = FakeNotificationPermitSemaphore(
            acquireResults = ArrayDeque(listOf(NotificationPermitAcquire.Acquired(handle("global")))),
        )
        val clinic = FakeNotificationPermitSemaphore(
            acquireResults = ArrayDeque(listOf(NotificationPermitAcquire.Ambiguous)),
            reconcileResults = ArrayDeque(listOf(NotificationPermitReconcile.Owned(handle("clinic")))),
        )
        val coordinator = RedisNotificationOutboxConcurrencyCoordinator(
            properties = workerProperties(),
            global = global,
            clinicFactory = { clinic },
        )

        val result = coordinator.withPermit(notification()) { "sent" }

        result shouldBeEqualTo NotificationOutboxAdmission.Acquired("sent")
        clinic.reconciledRequests.size shouldBeEqualTo 1
        global.releaseCalls shouldBeEqualTo 1
        clinic.releaseCalls shouldBeEqualTo 1
        clinic.reconciledRequests.single() shouldBeEqualTo clinic.acquiredRequests.single()
    }

    @Test
    fun `cancellation releases both permits`() = runBlocking {
        val global = FakeNotificationPermitSemaphore(
            acquireResults = ArrayDeque(listOf(NotificationPermitAcquire.Acquired(handle("global")))),
        )
        val clinic = FakeNotificationPermitSemaphore(
            acquireResults = ArrayDeque(listOf(NotificationPermitAcquire.Acquired(handle("clinic")))),
        )
        val coordinator = RedisNotificationOutboxConcurrencyCoordinator(
            properties = workerProperties(),
            global = global,
            clinicFactory = { clinic },
        )

        val thrown = runCatching {
            coordinator.withPermit(notification()) {
                throw java.util.concurrent.CancellationException("caller cancelled")
            }
        }.exceptionOrNull()

        thrown!!::class shouldBeEqualTo java.util.concurrent.CancellationException::class
        clinic.releaseCalls shouldBeEqualTo 1
        global.releaseCalls shouldBeEqualTo 1
    }

    private fun workerProperties() = NotificationProperties.WorkerProperties(
        leaseDuration = Duration.ofSeconds(2),
        providerTimeout = Duration.ofMillis(100),
        pollInterval = Duration.ofMillis(50),
        channels = mapOf(
            "dummy" to NotificationProperties.ChannelWorkerProperties(providerTimeout = Duration.ofMillis(100)),
        ),
    )

    private fun handle(label: String) = NotificationPermitHandle(
        value = label,
        owner = SemaphoreOwnerId.random(),
        request = SemaphoreRequestId.random(),
    )

    private fun notification() = ClaimedNotification(
        id = 1L,
        tenantGroupId = TenantGroupId(1L),
        clinicId = ClinicId(7L),
        appointmentId = AppointmentId(1L),
        memberId = MemberId("member-1"),
        idempotencyKey = NotificationIdempotencyKey("idem-1"),
        owner = "dispatcher-test",
        token = "token-1",
        attemptNumber = 1,
        leaseUntil = Instant.parse("2026-07-31T00:01:00Z"),
        firstAttemptAt = Instant.parse("2026-07-31T00:00:00Z"),
        claimedAt = Instant.parse("2026-07-31T00:00:00Z"),
        channel = NotificationChannelType.DUMMY,
        eventType = NotificationEventType.CONFIRMED,
        notificationSlot = NotificationSlot.CONFIRMED,
        providerKey = "dummy",
        templateKey = NotificationTemplateKey("appointment.confirmed"),
        templateVersion = NotificationTemplateVersion(1),
        parameterType = NotificationParameterType.APPOINTMENT_CONFIRMED,
        eventId = NotificationEventId("event-1"),
        parametersJson = "{}",
    )

    private class FakeNotificationPermitSemaphore(
        private val acquireResults: ArrayDeque<NotificationPermitAcquire>,
        private val reconcileResults: ArrayDeque<NotificationPermitReconcile> = ArrayDeque(),
    ) : NotificationPermitSemaphore {
        val acquiredRequests = mutableListOf<SemaphoreRequestId>()
        val reconciledRequests = mutableListOf<SemaphoreRequestId>()
        var releaseCalls: Int = 0

        override suspend fun initialize(capacity: Int): NotificationPermitInitialization =
            NotificationPermitInitialization.Initialized

        override suspend fun acquire(
            owner: SemaphoreOwnerId,
            request: SemaphoreRequestId,
            waitTime: Duration,
        ): NotificationPermitAcquire {
            acquiredRequests += request
            return acquireResults.removeFirst()
        }

        override suspend fun reconcile(
            owner: SemaphoreOwnerId,
            request: SemaphoreRequestId,
        ): NotificationPermitReconcile {
            reconciledRequests += request
            return reconcileResults.removeFirst()
        }

        override suspend fun renew(handle: NotificationPermitHandle, extension: Duration): NotificationPermitRenew =
            NotificationPermitRenew.Renewed(handle)

        override suspend fun release(handle: NotificationPermitHandle): NotificationPermitMutation {
            releaseCalls++
            return NotificationPermitMutation.Released
        }

        override fun close() = Unit
    }
}
