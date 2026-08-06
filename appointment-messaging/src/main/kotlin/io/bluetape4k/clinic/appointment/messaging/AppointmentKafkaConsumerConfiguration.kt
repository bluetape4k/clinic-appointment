package io.bluetape4k.clinic.appointment.messaging

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.ConsumerRecordRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.FixedBackOff

/** Kafka 4 consumer의 manual-ack, polling budget, bounded recovery 경계를 고정합니다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(ConcurrentKafkaListenerContainerFactory::class)
@ConditionalOnBean(ConsumerFactory::class)
class AppointmentKafkaConsumerConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = ["appointmentKafkaConsumerContainerFactory"])
    fun appointmentKafkaConsumerContainerFactory(
        consumerFactory: ConsumerFactory<String, String>,
        properties: AppointmentMessagingProperties,
        inboxStore: ObjectProvider<AppointmentConsumerInboxStore>,
    ): ConcurrentKafkaListenerContainerFactory<String, String> =
        createFactory(consumerFactory, properties, inboxStore.getIfAvailable())

    fun appointmentKafkaConsumerContainerFactory(
        consumerFactory: ConsumerFactory<String, String>,
    ): ConcurrentKafkaListenerContainerFactory<String, String> =
        createFactory(consumerFactory, AppointmentMessagingProperties(), null)

    private fun createFactory(
        consumerFactory: ConsumerFactory<String, String>,
        properties: AppointmentMessagingProperties,
        inboxStore: AppointmentConsumerInboxStore?,
    ): ConcurrentKafkaListenerContainerFactory<String, String> {
        consumerFactory.updateConfigs(
            mapOf(
                ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG to false,
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG to properties.consumer.maxPollRecords,
                ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG to properties.consumer.maxPollInterval
                    .toMillis()
                    .coerceIn(1_000, Int.MAX_VALUE.toLong())
                    .toInt(),
            ),
        )
        return ConcurrentKafkaListenerContainerFactory<String, String>().also { factory ->
            factory.setConsumerFactory(consumerFactory)
            factory.containerProperties.ackMode = ContainerProperties.AckMode.MANUAL_IMMEDIATE
            factory.containerProperties.setShutdownTimeout(properties.consumer.shutdownTimeout.toMillis())
            factory.containerProperties.setMissingTopicsFatal(true)
            val recoverer = ConsumerRecordRecoverer { record, exception ->
                inboxStore?.quarantineRejected(
                    AppointmentConsumerIdentity(
                        properties.consumer.logicalConsumerId,
                        properties.consumer.logicalStreamId,
                    ),
                    record,
                    if (exception is AppointmentConsumerInvalidEnvelopeException) {
                        AppointmentConsumerFailureCode.INVALID_ENVELOPE
                    } else {
                        AppointmentConsumerFailureCode.HANDLER_FAILED
                    },
                )
            }
            factory.setCommonErrorHandler(
                DefaultErrorHandler(
                    recoverer,
                    FixedBackOff(
                        properties.retryBaseDelay.toMillis(),
                        (properties.consumer.maxAttempts - 1).toLong(),
                    ),
                ).also { errorHandler ->
                    errorHandler.addNotRetryableExceptions(AppointmentConsumerInvalidEnvelopeException::class.java)
                    errorHandler.setCommitRecovered(true)
                },
            )
        }
    }
}
