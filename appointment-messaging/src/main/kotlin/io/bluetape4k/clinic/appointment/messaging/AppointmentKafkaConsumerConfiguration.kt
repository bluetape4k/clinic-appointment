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
        metrics: ObjectProvider<AppointmentConsumerMetrics>,
    ): ConcurrentKafkaListenerContainerFactory<String, String> =
        createFactory(consumerFactory, properties, inboxStore.getIfAvailable(), metrics.getIfAvailable())

    fun appointmentKafkaConsumerContainerFactory(
        consumerFactory: ConsumerFactory<String, String>,
    ): ConcurrentKafkaListenerContainerFactory<String, String> =
        createFactory(consumerFactory, AppointmentMessagingProperties(), null, null)

    private fun createFactory(
        consumerFactory: ConsumerFactory<String, String>,
        properties: AppointmentMessagingProperties,
        inboxStore: AppointmentConsumerInboxStore?,
        metrics: AppointmentConsumerMetrics?,
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
            val recoverer = appointmentConsumerRecoverer(
                properties = properties,
                inboxStore = inboxStore,
                metrics = metrics,
            )
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

/** Spring Kafka가 감싼 listener 예외에서도 원래 consumer 실패 계약을 복원합니다. */
internal fun appointmentConsumerRecoverer(
    properties: AppointmentMessagingProperties,
    inboxStore: AppointmentConsumerInboxStore?,
    metrics: AppointmentConsumerMetrics?,
): ConsumerRecordRecoverer = ConsumerRecordRecoverer { record, exception ->
    val failureCode = appointmentConsumerRecoveryFailureCode(exception)
    inboxStore?.quarantineRejected(
        AppointmentConsumerIdentity(
            properties.consumer.logicalConsumerId,
            properties.consumer.logicalStreamId,
        ),
        record,
        failureCode,
    )
    metrics?.quarantined(failureCode)
}

/** listener wrapper의 cause chain을 따라 terminal recovery 분류를 결정합니다. */
internal fun appointmentConsumerRecoveryFailureCode(exception: Throwable): AppointmentConsumerFailureCode {
    var current: Throwable? = exception
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    while (current != null && seen.add(current)) {
        when (current) {
            is AppointmentConsumerInvalidEnvelopeException ->
                return AppointmentConsumerFailureCode.INVALID_ENVELOPE
            is AppointmentConsumerRetryableException ->
                return AppointmentConsumerFailureCode.HANDLER_RETRYABLE
        }
        current = current.cause
    }
    return AppointmentConsumerFailureCode.HANDLER_FAILED
}
