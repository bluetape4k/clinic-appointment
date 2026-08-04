package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.event.AppointmentDomainEvent
import io.bluetape4k.clinic.appointment.event.notification.NotificationEventType
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

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
