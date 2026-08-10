package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.clinic.appointment.event.AppointmentDomainEvent
import io.bluetape4k.clinic.appointment.event.notification.NotificationEventType
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.system.measureTimeMillis

internal class NotificationEventListenerTest {

    private fun scope(clinicId: Long, tenantGroupId: Long = 1L) = TenantClinicScope(tenantGroupId, clinicId)

    @Test
    fun `legacy domain event는 개인정보 없이 해당 outbox event를 direct port에 전달한다`() {
        val calls = mutableListOf<DirectCall>()
        val listener = NotificationEventListener(
            delivery = NotificationDirectDeliveryPort { eventScope, appointmentId, eventType ->
                calls += DirectCall(eventScope, appointmentId, eventType)
                NotificationDirectDeliveryResult.NotFound
            },
            properties = NotificationProperties(),
        )

        listener.onCreated(AppointmentDomainEvent.Created(appointmentId = 1L, scope = scope(7L)))
        listener.onStatusChanged(
            AppointmentDomainEvent.StatusChanged(
                appointmentId = 2L,
                scope = scope(7L),
                fromState = "REQUESTED",
                toState = "CONFIRMED",
            )
        )
        listener.onCancelled(AppointmentDomainEvent.Cancelled(appointmentId = 3L, scope = scope(7L), reason = "private"))
        listener.onRescheduled(AppointmentDomainEvent.Rescheduled(originalId = 4L, newId = 5L, scope = scope(7L)))

        calls shouldBeEqualTo listOf(
            DirectCall(scope(7L), 1L, NotificationEventType.CREATED),
            DirectCall(scope(7L), 2L, NotificationEventType.CONFIRMED),
            DirectCall(scope(7L), 3L, NotificationEventType.CANCELLED),
            DirectCall(scope(7L), 4L, NotificationEventType.RESCHEDULED),
        )
    }

    @Test
    fun `비대상 상태와 비활성 event는 direct port를 호출하지 않는다`() {
        var calls = 0
        val listener = NotificationEventListener(
            delivery = NotificationDirectDeliveryPort { _, _, _ ->
                calls++
                NotificationDirectDeliveryResult.NotFound
            },
            properties = NotificationProperties(
                events = NotificationProperties.EventProperties(created = false),
            ),
        )

        listener.onCreated(AppointmentDomainEvent.Created(appointmentId = 1L, scope = scope(7L)))
        listener.onStatusChanged(
            AppointmentDomainEvent.StatusChanged(
                appointmentId = 2L,
                scope = scope(7L),
                fromState = "REQUESTED",
                toState = "HELD",
            )
        )

        calls shouldBeEqualTo 0
    }

    @Test
    fun `bounded executor를 쓰는 event listener는 요청 thread를 기다리게 하지 않는다`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val executor = NotificationDirectDeliveryExecutor(concurrency = 1, queueCapacity = 1)
        val listener = NotificationEventListener(
            delivery = NotificationDirectDeliveryPort { _, _, _ ->
                suspendCoroutine { continuation ->
                    Thread.ofVirtual().start {
                        started.countDown()
                        release.await()
                        continuation.resume(NotificationDirectDeliveryResult.NotFound)
                        completed.countDown()
                    }
                }
            },
            properties = NotificationProperties(),
            executor = executor,
        )

        try {
            listener.onCreated(AppointmentDomainEvent.Created(appointmentId = 1L, scope = scope(7L)))

            started.await(1, TimeUnit.SECONDS) shouldBeEqualTo true
            completed.count shouldBeEqualTo 1L
            release.countDown()
            completed.await(1, TimeUnit.SECONDS) shouldBeEqualTo true
        } finally {
            executor.close()
        }
    }

    @Test
    fun `direct executor 포화는 예약 event thread에서 provider를 실행하지 않고 pending 행을 남긴다`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val calls = AtomicInteger()
        val executor = NotificationDirectDeliveryExecutor(concurrency = 1, queueCapacity = 1)
        val listener = NotificationEventListener(
            delivery = NotificationDirectDeliveryPort { _, _, _ ->
                suspendCoroutine { continuation ->
                    calls.incrementAndGet()
                    started.countDown()
                    Thread.ofVirtual().start {
                        release.await()
                        continuation.resume(NotificationDirectDeliveryResult.NotFound)
                        completed.countDown()
                    }
                }
            },
            properties = NotificationProperties(),
            executor = executor,
        )

        try {
            listener.onCreated(AppointmentDomainEvent.Created(appointmentId = 1L, scope = scope(7L)))
            started.await(1, TimeUnit.SECONDS) shouldBeEqualTo true
            listener.onCreated(AppointmentDomainEvent.Created(appointmentId = 2L, scope = scope(7L)))
            listener.onCreated(AppointmentDomainEvent.Created(appointmentId = 3L, scope = scope(7L)))

            calls.get() shouldBeEqualTo 1
            release.countDown()
            completed.await(1, TimeUnit.SECONDS) shouldBeEqualTo true
            calls.get() shouldBeEqualTo 2
        } finally {
            release.countDown()
            executor.close()
        }
    }

    @Test
    fun `direct delivery 실패는 이미 commit된 예약 event 호출자에게 전파하지 않는다`() {
        val listener = NotificationEventListener(
            delivery = NotificationDirectDeliveryPort { _, _, _ -> error("provider unavailable") },
            properties = NotificationProperties(),
        )

        listener.onCreated(AppointmentDomainEvent.Created(appointmentId = 1L, scope = scope(7L)))
    }

    @Test
    fun `never-resuming suspend bridge는 bounded deadline 안에 취소되어야 한다`() {
        val task = CompletableFuture.supplyAsync<NotificationDirectDeliveryResult> {
            runSynchronously(Duration.ofMillis(100)) { awaitCancellation() }
        }

        try {
            val failure = assertFailsWith<ExecutionException> {
                task.get(2, TimeUnit.SECONDS)
            }
            failure.cause.shouldBeInstanceOf<NotificationSuspendBridgeTimeoutException>()
        } finally {
            task.cancel(true)
        }
    }

    @Test
    fun `suspend bridge는 실제 cancellation을 domain timeout으로 변환하지 않는다`() {
        val cancellation = CancellationException("caller cancelled")

        val failure = assertFailsWith<CancellationException> {
            runSynchronously(Duration.ofSeconds(1)) {
                throw cancellation
            }
        }

        check(failure::class.java == cancellation::class.java)
        check(failure.message == cancellation.message)
    }

    @Test
    fun `interrupted suspend bridge는 InterruptedException과 interrupt flag를 보존한다`() {
        val started = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        val worker = Thread.ofPlatform()
            .name("notification-suspend-bridge-interrupt-test")
            .start {
                try {
                    started.countDown()
                    runSynchronously(Duration.ofSeconds(5)) { awaitCancellation() }
                } catch (e: InterruptedException) {
                    interrupted.set(Thread.currentThread().isInterrupted)
                }
            }

        try {
            check(started.await(1, TimeUnit.SECONDS)) { "bridge test worker did not start" }
            worker.interrupt()
            worker.join(2_000L)
            check(!worker.isAlive) { "interrupted bridge worker did not terminate" }
            check(interrupted.get()) { "bridge did not preserve the interrupt flag" }
        } finally {
            if (worker.isAlive) worker.interrupt()
        }
    }

    @Test
    fun `event listener는 worker 설정의 suspend bridge deadline을 사용한다`() {
        val listener = NotificationEventListener(
            delivery = NotificationDirectDeliveryPort { _, _, _ -> awaitCancellation() },
            properties = NotificationProperties(
                worker = NotificationProperties.WorkerProperties(
                    suspendBridgeTimeout = Duration.ofMillis(100),
                )
            ),
        )

        val elapsed = measureTimeMillis {
            listener.onCreated(AppointmentDomainEvent.Created(appointmentId = 1L, scope = scope(7L)))
        }

        check(elapsed < 500L) { "configured suspend bridge timeout was not applied: elapsed=${elapsed}ms" }
    }

    @Test
    fun `Kafka notification route는 Java serialization 계약을 유지한다`() {
        val routeClass = NotificationAppointmentEventConsumer::class.java.declaredClasses
            .single { it.simpleName == "NotificationRoute" }
        check(java.io.Serializable::class.java.isAssignableFrom(routeClass))

        val constructor = routeClass.getDeclaredConstructor(Long::class.javaPrimitiveType, NotificationEventType::class.java)
            .apply { isAccessible = true }
        val route = constructor.newInstance(42L, NotificationEventType.CREATED)
        val bytes = ByteArrayOutputStream().also { output ->
            ObjectOutputStream(output).use { it.writeObject(route) }
        }.toByteArray()
        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() }

        restored shouldBeEqualTo route
    }

    @Test
    fun `ACTIVE와 PAUSED는 executor queue에 direct 작업을 넣지 않는다`() {
        listOf(NotificationRolloutMode.ACTIVE, NotificationRolloutMode.PAUSED).forEach { mode ->
            val submitted = AtomicInteger()
            val listener = NotificationEventListener(
                delivery = NotificationDirectDeliveryPort { _, _, _ ->
                    error("route gate 뒤에서는 호출되면 안 된다")
                },
                properties = NotificationProperties(),
                executor = Executor { submitted.incrementAndGet() },
                routeGate = NotificationDeliveryRouteGate(
                    NotificationProperties.RolloutProperties(mode = mode)
                ),
            )

            listener.onCreated(AppointmentDomainEvent.Created(appointmentId = 1L, scope = scope(7L)))

            submitted.get() shouldBeEqualTo 0
        }
    }

    private data class DirectCall(
        val scope: TenantClinicScope,
        val appointmentId: Long,
        val eventType: NotificationEventType,
    )
}
