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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

internal class NotificationDirectOutboxDeliveryTest {

    @Test
    fun `전환기 route는 같은 outbox 행을 claim한 경우에만 worker에 전달한다`() {
        var claimCount = 0
        var processCount = 0
        val claimed = claimed()
        val store = NotificationDirectOutboxStore { clinicId, appointmentId, eventType, _ ->
            claimCount++
            claimed.takeIf {
                claimCount == 1 &&
                    clinicId.value == 7L &&
                    appointmentId.value == 101L &&
                    eventType == NotificationEventType.CONFIRMED
            }
        }
        val delivery = NotificationDirectOutboxDelivery(
            store = store,
            worker = NotificationOutboxJobWorker {
                processCount++
                NotificationOutboxWorkerResult.COMPLETED
            },
            routeGate = NotificationDeliveryRouteGate(NotificationProperties.RolloutProperties()),
        )

        val first = runBlocking {
            delivery.deliver(7L, 101L, NotificationEventType.CONFIRMED)
        }
        val second = runBlocking {
            delivery.deliver(7L, 101L, NotificationEventType.CONFIRMED)
        }

        first shouldBeEqualTo NotificationDirectDeliveryResult.Processed(NotificationOutboxWorkerResult.COMPLETED)
        second shouldBeEqualTo NotificationDirectDeliveryResult.NotFound
        processCount shouldBeEqualTo 1
    }

    @Test
    fun `CANARY 병원의 direct route는 outbox를 claim하지 않는다`() {
        var claimCount = 0
        val delivery = NotificationDirectOutboxDelivery(
            store = NotificationDirectOutboxStore { _, _, _, _ ->
                claimCount++
                claimed()
            },
            worker = NotificationOutboxJobWorker { NotificationOutboxWorkerResult.COMPLETED },
            routeGate = NotificationDeliveryRouteGate(
                NotificationProperties.RolloutProperties(
                    mode = NotificationRolloutMode.CANARY,
                    canaryClinicIds = setOf(7L),
                )
            ),
        )

        val result = runBlocking {
            delivery.deliver(7L, 101L, NotificationEventType.CONFIRMED)
        }

        result shouldBeEqualTo NotificationDirectDeliveryResult.RouteRejected
        claimCount shouldBeEqualTo 0
    }

    @Test
    fun `direct route도 전역 및 병원별 worker 상한을 지킨다`() = runBlocking {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val delivery = NotificationDirectOutboxDelivery(
            store = NotificationDirectOutboxStore { clinicId, appointmentId, _, owner ->
                claimed(
                    id = appointmentId.value,
                    clinicId = clinicId.value,
                    appointmentId = appointmentId.value,
                    owner = owner,
                )
            },
            worker = NotificationOutboxJobWorker {
                val current = active.incrementAndGet()
                maximum.accumulateAndGet(current, ::maxOf)
                delay(25)
                active.decrementAndGet()
                NotificationOutboxWorkerResult.COMPLETED
            },
            routeGate = NotificationDeliveryRouteGate(NotificationProperties.RolloutProperties()),
            globalConcurrency = 2,
            perClinicConcurrency = 1,
        )

        coroutineScope {
            (1L..6L).map { appointmentId ->
                async { delivery.deliver(7L, appointmentId, NotificationEventType.CONFIRMED) }
            }.awaitAll()
        }

        maximum.get() shouldBeEqualTo 1
        Unit
    }

    private fun claimed(
        id: Long = 1L,
        clinicId: Long = 7L,
        appointmentId: Long = 101L,
        owner: String = "notification-direct-event",
    ): ClaimedNotification =
        ClaimedNotification(
            id = id,
            tenantGroupId = TenantGroupId(1L),
            clinicId = ClinicId(clinicId),
            appointmentId = AppointmentId(appointmentId),
            memberId = MemberId("member-101"),
            idempotencyKey = NotificationIdempotencyKey("idem-101"),
            owner = owner,
            token = "token-101",
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
            eventId = NotificationEventId("event-101"),
            parametersJson = "{}",
        )
}
