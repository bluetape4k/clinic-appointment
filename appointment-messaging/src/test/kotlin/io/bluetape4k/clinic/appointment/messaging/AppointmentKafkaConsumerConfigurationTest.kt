package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.listener.DefaultErrorHandler
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
        consumerFactory.configurationProperties[ConsumerConfig.MAX_POLL_RECORDS_CONFIG]
            .toString()
            .shouldBeEqualTo("1")
        consumerFactory.configurationProperties[ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG]
            .toString()
            .shouldBeEqualTo("300000")
        factory.containerProperties.isMissingTopicsFatal.shouldBeTrue()
        factory.containerProperties.shutdownTimeout shouldBeEqualTo 10_000L
        factory.createContainer("appointment-test").getCommonErrorHandler()
            .shouldBeInstanceOf<DefaultErrorHandler>()
    }
}
