package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.clinic.appointment.event.notification.NotificationEventType
import io.bluetape4k.clinic.appointment.messaging.AppointmentCancelledPayload
import io.bluetape4k.clinic.appointment.messaging.AppointmentConsumerContext
import io.bluetape4k.clinic.appointment.messaging.AppointmentConsumerHandler
import io.bluetape4k.clinic.appointment.messaging.AppointmentCreatedPayload
import io.bluetape4k.clinic.appointment.messaging.AppointmentEventEnvelope
import io.bluetape4k.clinic.appointment.messaging.AppointmentRescheduledPayload
import io.bluetape4k.clinic.appointment.messaging.AppointmentStatusChangedPayload
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState

/** Kafka appointment event를 이미 존재하는 durable notification outbox 전달 경계로 변환합니다. */
class NotificationAppointmentEventConsumer(
    private val delivery: NotificationDirectDeliveryPort,
) : AppointmentConsumerHandler {

    override fun handle(envelope: AppointmentEventEnvelope, context: AppointmentConsumerContext) {
        val route = routeFor(envelope) ?: return
        val scope = TenantClinicScope(envelope.tenantGroupId, envelope.clinicId)
        runSynchronously {
            delivery.deliver(scope, route.appointmentId, route.eventType)
        }
    }

    private fun routeFor(envelope: AppointmentEventEnvelope): NotificationRoute? = when (val payload = envelope.payload) {
        is AppointmentCreatedPayload ->
            NotificationRoute(payload.appointmentId.value, NotificationEventType.CREATED)

        is AppointmentStatusChangedPayload ->
            payload.takeIf { it.toState == AppointmentState.CONFIRMED }
                ?.let { NotificationRoute(it.appointmentId.value, NotificationEventType.CONFIRMED) }

        is AppointmentCancelledPayload ->
            NotificationRoute(payload.appointmentId.value, NotificationEventType.CANCELLED)

        is AppointmentRescheduledPayload ->
            NotificationRoute(payload.originalAppointmentId.value, NotificationEventType.RESCHEDULED)
    }

    private data class NotificationRoute(
        val appointmentId: Long,
        val eventType: NotificationEventType,
    )
}
