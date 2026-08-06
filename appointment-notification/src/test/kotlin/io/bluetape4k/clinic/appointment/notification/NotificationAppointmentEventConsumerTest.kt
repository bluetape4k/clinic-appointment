package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.event.notification.NotificationEventType
import io.bluetape4k.clinic.appointment.messaging.AppointmentAggregateId
import io.bluetape4k.clinic.appointment.messaging.AppointmentCancelledPayload
import io.bluetape4k.clinic.appointment.messaging.AppointmentConsumerContext
import io.bluetape4k.clinic.appointment.messaging.AppointmentConsumerIdentity
import io.bluetape4k.clinic.appointment.messaging.AppointmentConsumerProvenance
import io.bluetape4k.clinic.appointment.messaging.AppointmentCreatedPayload
import io.bluetape4k.clinic.appointment.messaging.AppointmentEventEnvelope
import io.bluetape4k.clinic.appointment.messaging.AppointmentEventId
import io.bluetape4k.clinic.appointment.messaging.AppointmentEventType
import io.bluetape4k.clinic.appointment.messaging.AppointmentLogicalConsumerId
import io.bluetape4k.clinic.appointment.messaging.AppointmentLogicalStreamId
import io.bluetape4k.clinic.appointment.messaging.AppointmentRescheduledPayload
import io.bluetape4k.clinic.appointment.messaging.AppointmentStatusChangedPayload
import io.bluetape4k.clinic.appointment.messaging.AppointmentTopic
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import io.bluetape4k.clinic.appointment.service.AppointmentCausationId
import io.bluetape4k.clinic.appointment.service.AppointmentCorrelationId
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import org.junit.jupiter.api.Test
import java.time.Instant

class NotificationAppointmentEventConsumerTest {
    @Test
    fun `appointment events are mapped to the durable notification delivery port`() {
        val calls = mutableListOf<DeliveryCall>()
        val consumer = NotificationAppointmentEventConsumer(
            delivery = NotificationDirectDeliveryPort { scope, appointmentId, eventType ->
                calls += DeliveryCall(scope, appointmentId, eventType)
                NotificationDirectDeliveryResult.NotFound
            },
        )

        listOf(
            envelope(AppointmentEventType.CREATED, AppointmentCreatedPayload(AppointmentAggregateId(42), 1, AppointmentState.CONFIRMED)),
            envelope(
                AppointmentEventType.STATUS_CHANGED,
                AppointmentStatusChangedPayload(
                    appointmentId = AppointmentAggregateId(43),
                    version = 2,
                    fromState = AppointmentState.REQUESTED,
                    toState = AppointmentState.CONFIRMED,
                ),
            ),
            envelope(AppointmentEventType.CANCELLED, AppointmentCancelledPayload(AppointmentAggregateId(44), 3)),
            envelope(
                AppointmentEventType.RESCHEDULED,
                AppointmentRescheduledPayload(AppointmentAggregateId(45), AppointmentAggregateId(46), 4, 1),
            ),
        ).forEach { event -> consumer.handle(event, context()) }

        calls shouldBeEqualTo listOf(
            DeliveryCall(TenantClinicScope(7, 31), 42, NotificationEventType.CREATED),
            DeliveryCall(TenantClinicScope(7, 31), 43, NotificationEventType.CONFIRMED),
            DeliveryCall(TenantClinicScope(7, 31), 44, NotificationEventType.CANCELLED),
            DeliveryCall(TenantClinicScope(7, 31), 45, NotificationEventType.RESCHEDULED),
        )
    }

    @Test
    fun `non confirmed status event is ignored`() {
        var calls = 0
        val consumer = NotificationAppointmentEventConsumer(
            delivery = NotificationDirectDeliveryPort { _, _, _ ->
                calls += 1
                NotificationDirectDeliveryResult.NotFound
            },
        )

        consumer.handle(
            envelope(
                AppointmentEventType.STATUS_CHANGED,
                AppointmentStatusChangedPayload(
                    appointmentId = AppointmentAggregateId(42),
                    version = 2,
                    fromState = AppointmentState.REQUESTED,
                    toState = AppointmentState.CANCELLED,
                ),
            ),
            context(),
        )

        calls shouldBeEqualTo 0
    }

    private fun context() = AppointmentConsumerContext(
        identity = AppointmentConsumerIdentity(
            AppointmentLogicalConsumerId("notification"),
            AppointmentLogicalStreamId("appointment-events"),
        ),
        provenance = AppointmentConsumerProvenance(
            topic = AppointmentTopic("clinic.appointment.events"),
            partition = 1,
            offset = 12,
            schemaVersion = AppointmentEventEnvelope.CURRENT_SCHEMA_VERSION,
            tenantGroupId = 7,
            clinicId = 31,
            payloadSha256 = "a".repeat(64),
        ),
    )

    private fun envelope(type: AppointmentEventType, payload: io.bluetape4k.clinic.appointment.messaging.AppointmentEventPayload) =
        AppointmentEventEnvelope(
            eventId = AppointmentEventId("event-notification-${type.name.lowercase()}"),
            eventType = type,
            schemaVersion = AppointmentEventEnvelope.CURRENT_SCHEMA_VERSION,
            occurredAt = Instant.parse("2026-08-06T00:00:00Z"),
            tenantGroupId = 7,
            clinicId = 31,
            aggregateType = AppointmentEventEnvelope.AGGREGATE_TYPE,
            aggregateId = when (payload) {
                is AppointmentCreatedPayload -> payload.appointmentId
                is AppointmentStatusChangedPayload -> payload.appointmentId
                is AppointmentCancelledPayload -> payload.appointmentId
                is AppointmentRescheduledPayload -> payload.originalAppointmentId
            },
            correlationId = AppointmentCorrelationId("correlation-notification-42"),
            causationId = AppointmentCausationId("causation-notification-42"),
            payload = payload,
        )

    private data class DeliveryCall(
        val scope: TenantClinicScope,
        val appointmentId: Long,
        val eventType: NotificationEventType,
    )
}
