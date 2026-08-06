package io.bluetape4k.clinic.appointment.api.stats

import io.bluetape4k.clinic.appointment.messaging.AppointmentConsumerIdentity
import io.bluetape4k.clinic.appointment.messaging.AppointmentConsumerRuntime
import io.bluetape4k.clinic.appointment.messaging.AppointmentLogicalConsumerId
import io.bluetape4k.clinic.appointment.messaging.AppointmentLogicalStreamId
import io.bluetape4k.clinic.appointment.messaging.AppointmentMessagingProperties
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment

/** 통계 projection consumer의 고정 group과 logical identity를 Kafka에 연결합니다. */
class AppointmentStatsProjectionKafkaListener(
    private val runtime: AppointmentConsumerRuntime,
    private val handler: AppointmentStatsProjectionConsumer,
    properties: AppointmentMessagingProperties,
) {
    private val topic = properties.consumer.topic.value
    private val identity = AppointmentConsumerIdentity(
        consumerId = AppointmentLogicalConsumerId(LOGICAL_CONSUMER_ID),
        streamId = AppointmentLogicalStreamId(LOGICAL_STREAM_ID),
    )

    @KafkaListener(
        topics = ["\${appointment.messaging.consumer.topic:clinic.appointment.events}"],
        groupId = GROUP_ID,
        containerFactory = "appointmentKafkaConsumerContainerFactory",
    )
    fun onMessage(record: ConsumerRecord<String, String>, acknowledgment: Acknowledgment) {
        runtime.consume(record, acknowledgment, identity, handler)
    }

    init {
        require(topic.isNotBlank()) { "statistics consumer topic must not be blank" }
    }

    companion object {
        const val GROUP_ID = "appointment-statistics-v1"
        const val LOGICAL_CONSUMER_ID = "statistics"
        const val LOGICAL_STREAM_ID = "appointment-events"
    }
}
