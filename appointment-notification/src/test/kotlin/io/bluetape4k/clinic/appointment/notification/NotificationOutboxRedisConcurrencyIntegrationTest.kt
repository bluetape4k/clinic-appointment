package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.event.notification.AppointmentId
import io.bluetape4k.clinic.appointment.notification.persistence.ClaimedNotification
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test

internal class NotificationOutboxRedisConcurrencyIntegrationTest {

    @Test
    fun `Redis 8점 8에서 두 dispatcher coordinator가 global과 clinic 상한을 공유한다`() = runBlocking {
        val redis = Redis88Launcher.redis
        val client = Redis88Launcher.client()
        val connectionA = client.connect()
        val connectionB = client.connect()
        val namespace = "clinic-notification-test-${UUID.randomUUID()}"
        val hashTag = "notification-test-${UUID.randomUUID()}"
        val properties = workerProperties()
        val factoryA = LettuceNotificationPermitSemaphoreFactory(
            connectionA,
            properties.leaseDuration,
            properties.pollInterval,
            namespace,
            hashTag,
        )
        val factoryB = LettuceNotificationPermitSemaphoreFactory(
            connectionB,
            properties.leaseDuration,
            properties.pollInterval,
            namespace,
            hashTag,
        )
        val coordinatorA = RedisNotificationOutboxConcurrencyCoordinator(
            properties = properties,
            global = factoryA.create("global", properties.globalConcurrency),
            clinicFactory = factoryA,
        )
        val coordinatorB = RedisNotificationOutboxConcurrencyCoordinator(
            properties = properties,
            global = factoryB.create("global", properties.globalConcurrency),
            clinicFactory = factoryB,
        )
        val globalActive = AtomicInteger()
        val globalPeak = AtomicInteger()
        val clinicActive = ConcurrentHashMap<Long, AtomicInteger>()
        val clinicPeak = ConcurrentHashMap<Long, AtomicInteger>()
        val bothStarted = CompletableDeferred<Unit>()
        val releaseBoth = CompletableDeferred<Unit>()

        try {
            redis.dockerImageName shouldBeEqualTo Redis88Launcher.IMAGE_NAME
            Redis88Launcher.TAG shouldBeEqualTo "8.8"

            val globalResults = coroutineScope {
                val first = async {
                    coordinatorA.withPermit(notification(101L)) {
                        enter(101L, globalActive, globalPeak, clinicActive, clinicPeak)
                        if (globalActive.get() == 2) bothStarted.complete(Unit)
                        releaseBoth.await()
                        leave(101L, globalActive, clinicActive)
                        "first"
                    }
                }
                val second = async {
                    coordinatorB.withPermit(notification(102L)) {
                        enter(102L, globalActive, globalPeak, clinicActive, clinicPeak)
                        if (globalActive.get() == 2) bothStarted.complete(Unit)
                        releaseBoth.await()
                        leave(102L, globalActive, clinicActive)
                        "second"
                    }
                }
                withTimeout(2_000) { bothStarted.await() }
                releaseBoth.complete(Unit)
                listOf(first, second).awaitAll()
            }

            globalResults.all { it is NotificationOutboxAdmission.Acquired }.shouldBeTrue()
            globalPeak.get() shouldBeEqualTo 2

            val sameClinicStarted = CompletableDeferred<Unit>()
            val releaseSameClinic = CompletableDeferred<Unit>()
            val firstClinic = async {
                coordinatorA.withPermit(notification(103L)) {
                    enter(103L, globalActive, globalPeak, clinicActive, clinicPeak)
                    sameClinicStarted.complete(Unit)
                    releaseSameClinic.await()
                    leave(103L, globalActive, clinicActive)
                    "clinic-first"
                }
            }
            withTimeout(2_000) { sameClinicStarted.await() }
            val secondClinic = coordinatorB.withPermit(notification(103L)) {
                error("per-clinic semaphore must not admit an overlapping action")
            }
            (secondClinic is NotificationOutboxAdmission.Backpressured) shouldBeEqualTo true
            releaseSameClinic.complete(Unit)
            firstClinic.await() shouldBeEqualTo NotificationOutboxAdmission.Acquired("clinic-first")
            clinicPeak.values.maxOf { it.get() } shouldBeEqualTo 1

            val keys = connectionA.sync().keys("$namespace:*")
            keys.isNotEmpty().shouldBeTrue()
            keys.all { ":{$hashTag}:" in it }.shouldBeTrue()
        } finally {
            coordinatorA.close()
            coordinatorB.close()
            connectionA.isOpen.shouldBeTrue()
            connectionB.isOpen.shouldBeTrue()
            connectionA.close()
            connectionB.close()
        }
        Unit
    }

    @Test
    fun `capacity contract가 Redis에 고정되어 다른 capacity 설정을 차단한다`() = runBlocking {
        Redis88Launcher.redis
        val client = Redis88Launcher.client()
        val connection = client.connect()
        val namespace = "clinic-notification-capacity-${UUID.randomUUID()}"
        val hashTag = "notification-capacity-${UUID.randomUUID()}"
        val factory = LettuceNotificationPermitSemaphoreFactory(
            connection,
            Duration.ofSeconds(2),
            Duration.ofMillis(50),
            namespace,
            hashTag,
        )
        val first = factory.create("global", 2)
        val conflicting = factory.create("global", 3)
        try {
            first.initialize(2) shouldBeEqualTo NotificationPermitInitialization.AlreadyInitialized
            conflicting.initialize(3) shouldBeEqualTo NotificationPermitInitialization.CapacityMismatch
        } finally {
            first.close()
            conflicting.close()
            connection.close()
        }
        Unit
    }

    @Test
    fun `expirable permit은 lease가 만료되면 다른 owner가 다시 취득할 수 있다`() = runBlocking {
        Redis88Launcher.redis
        val client = Redis88Launcher.client()
        val connectionA = client.connect()
        val connectionB = client.connect()
        val namespace = "clinic-notification-expiry-${UUID.randomUUID()}"
        val hashTag = "notification-expiry-${UUID.randomUUID()}"
        val factoryA = LettuceNotificationPermitSemaphoreFactory(
            connectionA,
            Duration.ofSeconds(1),
            Duration.ofMillis(25),
            namespace,
            hashTag,
        )
        val factoryB = LettuceNotificationPermitSemaphoreFactory(
            connectionB,
            Duration.ofSeconds(1),
            Duration.ofMillis(25),
            namespace,
            hashTag,
        )
        val first = factoryA.create("expiry", 1)
        val second = factoryB.create("expiry", 1)
        try {
            first.initialize(1)
            second.initialize(1)
            val acquired = first.acquire(
                owner = SemaphoreOwnerId.random(),
                request = SemaphoreRequestId.random(),
                waitTime = Duration.ofMillis(50),
            )
            (acquired is NotificationPermitAcquire.Acquired) shouldBeEqualTo true
            delay(1_500)
            val afterExpiry = second.acquire(
                owner = SemaphoreOwnerId.random(),
                request = SemaphoreRequestId.random(),
                waitTime = Duration.ofMillis(100),
            )
            (afterExpiry is NotificationPermitAcquire.Acquired) shouldBeEqualTo true
        } finally {
            first.close()
            second.close()
            connectionA.close()
            connectionB.close()
        }
        Unit
    }

    @Test
    fun `닫힌 Redis connection은 coordinator admission을 backpressure로 전환한다`() = runBlocking {
        Redis88Launcher.redis
        val client = Redis88Launcher.client()
        val connection = client.connect()
        val properties = workerProperties()
        val factory = LettuceNotificationPermitSemaphoreFactory(
            connection,
            properties.leaseDuration,
            properties.pollInterval,
            "clinic-notification-closed-${UUID.randomUUID()}",
            "notification-closed-${UUID.randomUUID()}",
        )
        val coordinator = RedisNotificationOutboxConcurrencyCoordinator(
            properties = properties,
            global = factory.create("global", properties.globalConcurrency),
            clinicFactory = factory,
        )
        try {
            connection.close()
            val result = coordinator.withPermit(notification(104L)) { error("closed Redis must not invoke action") }
            result shouldBeEqualTo NotificationOutboxAdmission.Backpressured(
                NotificationPermitFailureReason.BACKEND_FAILURE,
            )
        } finally {
            coordinator.close()
        }
        Unit
    }

    @Test
    fun `in-flight action은 Redis connection 단절 후 중단되고 다른 coordinator가 lease 만료 뒤 회복한다`() = runBlocking {
        Redis88Launcher.redis
        val client = Redis88Launcher.client()
        val connectionA = client.connect()
        val connectionB = client.connect()
        val namespace = "clinic-notification-inflight-${UUID.randomUUID()}"
        val hashTag = "notification-inflight-${UUID.randomUUID()}"
        val properties = workerProperties().copy(
            leaseDuration = Duration.ofSeconds(1),
            pollInterval = Duration.ofMillis(25),
        )
        val factoryA = LettuceNotificationPermitSemaphoreFactory(
            connectionA,
            properties.leaseDuration,
            properties.pollInterval,
            namespace,
            hashTag,
        )
        val factoryB = LettuceNotificationPermitSemaphoreFactory(
            connectionB,
            properties.leaseDuration,
            properties.pollInterval,
            namespace,
            hashTag,
        )
        val coordinatorA = RedisNotificationOutboxConcurrencyCoordinator(
            properties = properties,
            global = factoryA.create("global", properties.globalConcurrency),
            clinicFactory = factoryA,
        )
        val coordinatorB = RedisNotificationOutboxConcurrencyCoordinator(
            properties = properties,
            global = factoryB.create("global", properties.globalConcurrency),
            clinicFactory = factoryB,
        )
        val actionStarted = CompletableDeferred<Unit>()
        val actionCompleted = AtomicBoolean()

        try {
            val inFlight = async {
                coordinatorA.withPermit(notification(105L)) {
                    actionStarted.complete(Unit)
                    delay(5_000)
                    actionCompleted.set(true)
                    "must-not-complete"
                }
            }
            withTimeout(2_000) { actionStarted.await() }
            connectionA.close()

            inFlight.await() shouldBeEqualTo NotificationOutboxAdmission.Backpressured(
                NotificationPermitFailureReason.BACKEND_FAILURE,
            )
            actionCompleted.get() shouldBeEqualTo false

            delay(1_500)
            coordinatorB.withPermit(notification(105L)) { "recovered" } shouldBeEqualTo
                NotificationOutboxAdmission.Acquired("recovered")
        } finally {
            coordinatorA.close()
            coordinatorB.close()
            connectionB.close()
        }
        Unit
    }

    private fun workerProperties() = NotificationProperties.WorkerProperties(
        concurrencyMode = NotificationConcurrencyMode.REDIS,
        leaseDuration = Duration.ofSeconds(2),
        providerTimeout = Duration.ofMillis(100),
        pollInterval = Duration.ofMillis(100),
        globalConcurrency = 2,
        perClinicConcurrency = 1,
        channels = mapOf(
            "dummy" to NotificationProperties.ChannelWorkerProperties(providerTimeout = Duration.ofMillis(100)),
        ),
    )

    private fun enter(
        clinicId: Long,
        globalActive: AtomicInteger,
        globalPeak: AtomicInteger,
        clinicActive: ConcurrentHashMap<Long, AtomicInteger>,
        clinicPeak: ConcurrentHashMap<Long, AtomicInteger>,
    ) {
        globalPeak.accumulateAndGet(globalActive.incrementAndGet(), ::maxOf)
        val active = clinicActive.computeIfAbsent(clinicId) { AtomicInteger() }
        val peak = clinicPeak.computeIfAbsent(clinicId) { AtomicInteger() }
        peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
    }

    private fun leave(
        clinicId: Long,
        globalActive: AtomicInteger,
        clinicActive: ConcurrentHashMap<Long, AtomicInteger>,
    ) {
        clinicActive.getValue(clinicId).decrementAndGet()
        globalActive.decrementAndGet()
    }

    private fun notification(clinicId: Long) = ClaimedNotification(
        id = clinicId,
        tenantGroupId = TenantGroupId(1L),
        clinicId = ClinicId(clinicId),
        appointmentId = AppointmentId(clinicId),
        memberId = MemberId("member-$clinicId"),
        idempotencyKey = NotificationIdempotencyKey("idem-$clinicId"),
        owner = "redis-integration-test",
        token = "token-$clinicId",
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
        eventId = NotificationEventId("event-$clinicId"),
        parametersJson = "{}",
    )
}
