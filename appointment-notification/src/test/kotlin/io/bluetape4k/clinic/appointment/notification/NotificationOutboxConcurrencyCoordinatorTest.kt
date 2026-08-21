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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

internal class NotificationOutboxConcurrencyCoordinatorTest {

    @Test
    fun `global admission failure rolls back the clinic permit without invoking the action`() = runBlocking {
        val global = FakeNotificationPermitSemaphore(
            acquireResults = ArrayDeque(listOf(NotificationPermitAcquire.Unavailable)),
        )
        val clinic = FakeNotificationPermitSemaphore(
            acquireResults = ArrayDeque(listOf(NotificationPermitAcquire.Acquired(handle("clinic")))),
        )
        val coordinator = RedisNotificationOutboxConcurrencyCoordinator(
            properties = workerProperties(),
            global = global,
            clinicFactory = { _, _ -> clinic },
        )
        val actionCalls = AtomicInteger()

        val result = coordinator.withPermit(notification()) {
            actionCalls.incrementAndGet()
            Unit
        }

        result shouldBeEqualTo NotificationOutboxAdmission.Backpressured(NotificationPermitFailureReason.UNAVAILABLE)
        actionCalls.get() shouldBeEqualTo 0
        global.releaseCalls shouldBeEqualTo 0
        clinic.releaseCalls shouldBeEqualTo 1
        Unit
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
            clinicFactory = { _, _ -> clinic },
        )

        val result = coordinator.withPermit(notification()) { "sent" }

        result shouldBeEqualTo NotificationOutboxAdmission.Acquired("sent")
        clinic.reconciledRequests.size shouldBeEqualTo 1
        global.releaseCalls shouldBeEqualTo 1
        clinic.releaseCalls shouldBeEqualTo 1
        clinic.reconciledRequests.single() shouldBeEqualTo clinic.acquiredRequests.single()
        Unit
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
            clinicFactory = { _, _ -> clinic },
        )

        val thrown = runCatching {
            coordinator.withPermit(notification()) {
                throw java.util.concurrent.CancellationException("caller cancelled")
            }
        }.exceptionOrNull()

        check(thrown != null) { "cancellation must be propagated" }
        thrown::class shouldBeEqualTo java.util.concurrent.CancellationException::class
        clinic.releaseCalls shouldBeEqualTo 1
        global.releaseCalls shouldBeEqualTo 1
        Unit
    }

    @Test
    fun `renew ownership loss stops provider action and releases both permits`() = runBlocking {
        val global = FakeNotificationPermitSemaphore(
            acquireResults = ArrayDeque(listOf(NotificationPermitAcquire.Acquired(handle("global")))),
        )
        val clinic = FakeNotificationPermitSemaphore(
            acquireResults = ArrayDeque(listOf(NotificationPermitAcquire.Acquired(handle("clinic")))),
            renewResults = ArrayDeque(listOf(NotificationPermitRenew.OwnershipLost)),
        )
        val coordinator = RedisNotificationOutboxConcurrencyCoordinator(
            properties = workerProperties(),
            global = global,
            clinicFactory = { _, _ -> clinic },
        )

        val result = coordinator.withPermit(notification()) {
            delay(2_000)
            "must-not-complete"
        }

        result shouldBeEqualTo NotificationOutboxAdmission.Backpressured(
            NotificationPermitFailureReason.OWNERSHIP_LOST,
        )
        clinic.releaseCalls shouldBeEqualTo 1
        global.releaseCalls shouldBeEqualTo 1
        Unit
    }

    @Test
    fun `ambiguous release는 동일 request reconcile 뒤 한 번만 재시도한다`() = runBlocking {
        val global = FakeNotificationPermitSemaphore(
            acquireResults = ArrayDeque(listOf(NotificationPermitAcquire.Acquired(handle("global")))),
        )
        val clinic = FakeNotificationPermitSemaphore(
            acquireResults = ArrayDeque(listOf(NotificationPermitAcquire.Acquired(handle("clinic")))),
            reconcileResults = ArrayDeque(listOf(NotificationPermitReconcile.Owned(handle("clinic-reconciled")))),
            releaseResults = ArrayDeque(
                listOf(NotificationPermitMutation.Ambiguous, NotificationPermitMutation.Released),
            ),
        )
        val coordinator = RedisNotificationOutboxConcurrencyCoordinator(
            properties = workerProperties(),
            global = global,
            clinicFactory = { _, _ -> clinic },
        )

        coordinator.withPermit(notification()) { "sent" } shouldBeEqualTo NotificationOutboxAdmission.Acquired("sent")
        clinic.releaseCalls shouldBeEqualTo 2
        clinic.reconciledRequests.size shouldBeEqualTo 1
        clinic.releasedHandles.last().value shouldBeEqualTo "clinic-reconciled"
        Unit
    }

    @Test
    fun `renew된 최신 generation handle을 cleanup에서 release한다`() = runBlocking {
        val global = FakeNotificationPermitSemaphore(
            acquireResults = ArrayDeque(listOf(NotificationPermitAcquire.Acquired(handle("global")))),
            renewResults = ArrayDeque(listOf(NotificationPermitRenew.Renewed(handle("global-renewed")))),
        )
        val clinic = FakeNotificationPermitSemaphore(
            acquireResults = ArrayDeque(listOf(NotificationPermitAcquire.Acquired(handle("clinic")))),
            renewResults = ArrayDeque(listOf(NotificationPermitRenew.Renewed(handle("clinic-renewed")))),
        )
        val coordinator = RedisNotificationOutboxConcurrencyCoordinator(
            properties = workerProperties(),
            global = global,
            clinicFactory = { _, _ -> clinic },
        )

        coordinator.withPermit(notification()) {
            delay(900)
            "sent"
        } shouldBeEqualTo NotificationOutboxAdmission.Acquired("sent")

        clinic.releasedHandles.last().value shouldBeEqualTo "clinic-renewed"
        global.releasedHandles.last().value shouldBeEqualTo "global-renewed"
        Unit
    }

    @Test
    fun `idle clinic semaphore는 bounded cache 안에서 재사용된다`() = runBlocking {
        val global = FakeNotificationPermitSemaphore(
            acquireResults = ArrayDeque(
                listOf(
                    NotificationPermitAcquire.Acquired(handle("global-1")),
                    NotificationPermitAcquire.Acquired(handle("global-2")),
                ),
            ),
        )
        val clinic = FakeNotificationPermitSemaphore(
            acquireResults = ArrayDeque(
                listOf(
                    NotificationPermitAcquire.Acquired(handle("clinic-1")),
                    NotificationPermitAcquire.Acquired(handle("clinic-2")),
                ),
            ),
        )
        val factoryCalls = AtomicInteger()
        val coordinator = RedisNotificationOutboxConcurrencyCoordinator(
            properties = workerProperties(),
            global = global,
            clinicFactory = { _, _ ->
                factoryCalls.incrementAndGet()
                clinic
            },
        )

        coordinator.withPermit(notification()) { "first" }
        coordinator.withPermit(notification()) { "second" }

        factoryCalls.get() shouldBeEqualTo 1
        Unit
    }

    @Test
    fun `renew backend hang은 bounded timeout 뒤 backpressure가 된다`() = runBlocking {
        val global = FakeNotificationPermitSemaphore(
            acquireResults = ArrayDeque(listOf(NotificationPermitAcquire.Acquired(handle("global")))),
            renewDelay = Duration.ofSeconds(2),
        )
        val clinic = FakeNotificationPermitSemaphore(
            acquireResults = ArrayDeque(listOf(NotificationPermitAcquire.Acquired(handle("clinic")))),
            renewDelay = Duration.ofSeconds(2),
        )
        val coordinator = RedisNotificationOutboxConcurrencyCoordinator(
            properties = workerProperties(),
            global = global,
            clinicFactory = { _, _ -> clinic },
        )

        coordinator.withPermit(notification()) { delay(5_000) } shouldBeEqualTo
            NotificationOutboxAdmission.Backpressured(NotificationPermitFailureReason.BACKEND_FAILURE)
        Unit
    }

    @Test
    fun `coordinator close는 active clinic entry를 release 전에 제거하지 않는다`() = runBlocking {
        val global = FakeNotificationPermitSemaphore(
            acquireResults = ArrayDeque(listOf(NotificationPermitAcquire.Acquired(handle("global")))),
        )
        val clinic = FakeNotificationPermitSemaphore(
            acquireResults = ArrayDeque(listOf(NotificationPermitAcquire.Acquired(handle("clinic")))),
        )
        val coordinator = RedisNotificationOutboxConcurrencyCoordinator(
            properties = workerProperties(),
            global = global,
            clinicFactory = { _, _ -> clinic },
        )
        val started = CompletableDeferred<Unit>()
        val job = launch {
            coordinator.withPermit(notification()) {
                started.complete(Unit)
                awaitCancellation()
            }
        }

        started.await()
        coordinator.close()
        job.cancelAndJoin()

        clinic.releaseCalls shouldBeEqualTo 1
        global.releaseCalls shouldBeEqualTo 1
        Unit
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
        private val renewResults: ArrayDeque<NotificationPermitRenew> = ArrayDeque(),
        private val releaseResults: ArrayDeque<NotificationPermitMutation> = ArrayDeque(),
        private val renewDelay: Duration = Duration.ZERO,
    ) : NotificationPermitSemaphore {
        val acquiredRequests = mutableListOf<SemaphoreRequestId>()
        val reconciledRequests = mutableListOf<SemaphoreRequestId>()
        val releasedHandles = mutableListOf<NotificationPermitHandle>()
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

        override suspend fun renew(handle: NotificationPermitHandle, extension: Duration): NotificationPermitRenew {
            if (!renewDelay.isZero) delay(renewDelay.toMillis())
            return if (renewResults.isEmpty()) NotificationPermitRenew.Renewed(handle) else renewResults.removeFirst()
        }

        override suspend fun release(handle: NotificationPermitHandle): NotificationPermitMutation {
            releaseCalls++
            releasedHandles += handle
            return if (releaseResults.isEmpty()) NotificationPermitMutation.Released else releaseResults.removeFirst()
        }

        override fun close() = Unit
    }
}
