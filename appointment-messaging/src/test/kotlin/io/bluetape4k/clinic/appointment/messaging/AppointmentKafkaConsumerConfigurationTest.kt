package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.assertions.shouldBeEqualTo
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.junit.jupiter.api.Test

class AppointmentKafkaConsumerConfigurationTest {
    @Test
    fun `consumer factory uses manual immediate acknowledgement and disables topic auto creation`() {
        val consumerFactory = DefaultKafkaConsumerFactory<String, String>(
            mapOf(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to "localhost:9092",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ),
        )

        val factory: ConcurrentKafkaListenerContainerFactory<String, String> =
            AppointmentKafkaConsumerConfiguration().appointmentKafkaConsumerContainerFactory(consumerFactory)

        factory.containerProperties.ackMode shouldBeEqualTo
            org.springframework.kafka.listener.ContainerProperties.AckMode.MANUAL_IMMEDIATE
        consumerFactory.configurationProperties[ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG]
            .shouldBeEqualTo(false)
    }
}
