package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.messaging.AppointmentConsumerHandler
import io.bluetape4k.clinic.appointment.messaging.AppointmentConsumerIdentity
import io.bluetape4k.clinic.appointment.messaging.AppointmentConsumerRuntime
import io.bluetape4k.clinic.appointment.messaging.AppointmentLogicalConsumerId
import io.bluetape4k.clinic.appointment.messaging.AppointmentLogicalStreamId
import io.bluetape4k.clinic.appointment.messaging.AppointmentMessagingProperties
import io.mockk.mockk
import io.mockk.verify
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Test
import org.springframework.kafka.support.Acknowledgment

class NotificationAppointmentEventKafkaListenerTest {
    @Test
    fun `listener uses the fixed notification identity and delegates manual acknowledgement`() {
        val runtime = mockk<AppointmentConsumerRuntime>(relaxed = true)
        val handler = mockk<NotificationAppointmentEventConsumer>(relaxed = true)
        val acknowledgment = mockk<Acknowledgment>(relaxed = true)
        val listener = NotificationAppointmentEventKafkaListener(runtime, handler, AppointmentMessagingProperties())
        val record = ConsumerRecord("clinic.appointment.events", 2, 17L, "key", "value")

        listener.onMessage(record, acknowledgment)

        verify(exactly = 1) {
            runtime.consume(
                record,
                acknowledgment,
                AppointmentConsumerIdentity(
                    AppointmentLogicalConsumerId("notification"),
                    AppointmentLogicalStreamId("appointment-events"),
                ),
                handler,
            )
        }
        NotificationAppointmentEventKafkaListener.GROUP_ID shouldBeEqualTo "appointment-notification-v1"
    }
}
