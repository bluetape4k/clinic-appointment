package io.bluetape4k.clinic.appointment.messaging

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.listener.ContainerProperties

/** Kafka 4 consumer의 manual-ack와 topic auto-create 경계를 고정합니다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(ConcurrentKafkaListenerContainerFactory::class)
@ConditionalOnBean(ConsumerFactory::class)
class AppointmentKafkaConsumerConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = ["appointmentKafkaConsumerContainerFactory"])
    fun appointmentKafkaConsumerContainerFactory(
        consumerFactory: ConsumerFactory<String, String>,
    ): ConcurrentKafkaListenerContainerFactory<String, String> {
        consumerFactory.updateConfigs(
            mapOf(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG to false),
        )
        return ConcurrentKafkaListenerContainerFactory<String, String>().also { factory ->
            factory.setConsumerFactory(consumerFactory)
            factory.containerProperties.ackMode = ContainerProperties.AckMode.MANUAL_IMMEDIATE
        }
    }
}
