package io.bluetape4k.clinic.appointment.messaging

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
import io.micrometer.core.instrument.MeterRegistry
import javax.sql.DataSource

/** appointment-messaging 기본 contract와 DB store를 Spring Boot 4에 등록한다. */
@AutoConfiguration
@EnableConfigurationProperties(AppointmentMessagingBindingProperties::class)
class AppointmentMessagingAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun appointmentMessagingProperties(
        binding: AppointmentMessagingBindingProperties,
    ): AppointmentMessagingProperties = AppointmentMessagingProperties(
        topic = AppointmentTopic(binding.topic),
        allowedTopics = binding.allowedTopics.map(::AppointmentTopic).toSet(),
        leaseDuration = binding.leaseDuration,
        sendTimeout = binding.sendTimeout,
        retryBaseDelay = binding.retryBaseDelay,
        maxRetryDelay = binding.maxRetryDelay,
        kafkaClientRetryBudget = binding.kafkaClientRetryBudget,
        terminalDbUpdateBudget = binding.terminalDbUpdateBudget,
        safetyMargin = binding.safetyMargin,
        pollInterval = binding.pollInterval,
        shutdownTimeout = binding.shutdownTimeout,
        claimSize = binding.claimSize,
        maxInFlight = binding.maxInFlight,
        maxClinicBatch = binding.maxClinicBatch,
        maxAttempts = binding.maxAttempts,
        enabled = binding.enabled,
        producerAcks = binding.producerAcks,
        producerEnableIdempotence = binding.producerEnableIdempotence,
        producerAllowAutoCreateTopics = binding.producerAllowAutoCreateTopics,
        producerRequestTimeout = binding.producerRequestTimeout,
        producerDeliveryTimeout = binding.producerDeliveryTimeout,
        producerMetadataTimeout = binding.producerMetadataTimeout,
        producerSecurityProtocol = binding.producerSecurityProtocol,
        producerCredentialReference = binding.producerCredentialReference,
    )

    @Bean
    @ConditionalOnMissingBean
    fun appointmentEventEnvelopeCodec(): AppointmentEventEnvelopeCodec = AppointmentEventEnvelopeCodec()

    @Bean
    @ConditionalOnMissingBean
    fun appointmentMessagingReadinessProbe(
        properties: AppointmentMessagingProperties,
    ): AppointmentMessagingReadinessProbe = AppointmentMessagingReadinessProbe(
        enabled = properties.enabled,
    )

    @Bean
    @ConditionalOnClass(name = ["org.springframework.boot.health.contributor.HealthIndicator"])
    @ConditionalOnMissingBean(name = ["appointmentMessagingHealthIndicator"])
    fun appointmentMessagingHealthIndicator(
        readiness: AppointmentMessagingReadinessProbe,
    ): Any = AppointmentMessagingHealthIndicator(readiness)

    @Bean
    @ConditionalOnMissingBean
    fun appointmentMessagingReadinessValidator(
        codec: AppointmentEventEnvelopeCodec,
        dataSource: ObjectProvider<DataSource>,
    ): AppointmentMessagingReadinessValidator = AppointmentMessagingReadinessValidator(
        codec = codec,
        dataSource = dataSource.getIfAvailable(),
    )

    @Bean
    @ConditionalOnBean(DataSource::class)
    @ConditionalOnMissingBean
    @DependsOnDatabaseInitialization
    fun appointmentMessagingStartupValidator(
        properties: AppointmentMessagingProperties,
        readiness: AppointmentMessagingReadinessProbe,
        validator: AppointmentMessagingReadinessValidator,
    ): AppointmentMessagingStartupValidator = AppointmentMessagingStartupValidator(
        properties = properties,
        readiness = readiness,
        validator = validator,
    )

    @Bean
    @ConditionalOnBean(ProducerFactory::class)
    @ConditionalOnMissingBean
    fun appointmentKafkaProducerConfiguration(
        properties: AppointmentMessagingProperties,
        producerFactory: ProducerFactory<*, *>,
        credentialResolver: ObjectProvider<AppointmentKafkaCredentialResolver>,
    ): AppointmentKafkaProducerConfiguration =
        AppointmentKafkaProducerConfiguration.apply(
            properties = properties,
            producerFactory = producerFactory,
            credentialResolver = credentialResolver.getIfAvailable(),
        )

    @Bean
    @ConditionalOnMissingBean
    @DependsOnDatabaseInitialization
    fun appointmentOutboxWriter(
        properties: AppointmentMessagingProperties,
        codec: AppointmentEventEnvelopeCodec,
        startupValidator: ObjectProvider<AppointmentMessagingStartupValidator>,
    ): AppointmentOutboxWriter {
        // The prerequisite is resolved before constructing the writer. A schema or
        // serializer contract failure therefore aborts startup without exposing a writer bean.
        startupValidator.getIfAvailable()?.afterSingletonsInstantiated()
        return DefaultAppointmentOutboxWriter(
            codec = codec,
            eventTopic = properties.topic,
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun appointmentOutboxStore(
        properties: AppointmentMessagingProperties,
        metrics: AppointmentOutboxMetrics,
    ): AppointmentOutboxStore =
        JdbcAppointmentOutboxStore(
            maxAttempts = properties.maxAttempts,
            allowedTopics = properties.allowedTopics,
            maxClinicBatch = properties.maxClinicBatch,
            metrics = metrics,
        )

    @Bean
    @ConditionalOnBean(KafkaTemplate::class)
    @ConditionalOnMissingBean
    fun appointmentKafkaPublisher(
        properties: AppointmentMessagingProperties,
        kafkaTemplate: KafkaTemplate<String, String>,
        producerConfiguration: ObjectProvider<AppointmentKafkaProducerConfiguration>,
        kafkaAdmin: ObjectProvider<KafkaAdmin>,
    ): AppointmentKafkaPublisher {
        producerConfiguration.getIfAvailable()
        val admin = requireNotNull(kafkaAdmin.getIfAvailable()) {
            "KafkaAdmin is required for non-creating appointment topic readiness"
        }
        return SpringKafkaAppointmentPublisher(
            kafkaTemplate = kafkaTemplate,
            kafkaAdmin = admin,
            metadataTimeout = properties.producerMetadataTimeout,
        )
    }

    @Bean
    @ConditionalOnBean(AppointmentKafkaPublisher::class)
    @ConditionalOnMissingBean
    fun appointmentOutboxRelay(
        properties: AppointmentMessagingProperties,
        store: AppointmentOutboxStore,
        publisher: AppointmentKafkaPublisher,
        codec: AppointmentEventEnvelopeCodec,
        readiness: AppointmentMessagingReadinessProbe,
        readinessValidator: AppointmentMessagingReadinessValidator,
        metrics: AppointmentOutboxMetrics,
    ): AppointmentOutboxRelay = AppointmentOutboxRelay(
        store = store,
        publisher = publisher,
        codec = codec,
        allowedTopics = properties.allowedTopics,
        claimSize = properties.claimSize,
        leaseDuration = properties.leaseDuration,
        sendTimeout = properties.sendTimeout,
        retryBaseDelay = properties.retryBaseDelay,
        maxRetryDelay = properties.maxRetryDelay,
        kafkaClientRetryBudget = properties.kafkaClientRetryBudget,
        terminalDbUpdateBudget = properties.terminalDbUpdateBudget,
        safetyMargin = properties.safetyMargin,
        maxInFlight = properties.maxInFlight,
        maxClinicBatch = properties.maxClinicBatch,
        maxAttempts = properties.maxAttempts,
        readiness = readiness,
        readinessValidator = readinessValidator,
        metrics = metrics,
    )

    @Bean
    @ConditionalOnMissingBean(value = [MeterRegistry::class, AppointmentOutboxMetrics::class])
    fun appointmentOutboxMetrics(): AppointmentOutboxMetrics = NoopAppointmentOutboxMetrics

    @Bean
    @ConditionalOnBean(MeterRegistry::class)
    @ConditionalOnMissingBean(AppointmentOutboxMetrics::class)
    fun micrometerAppointmentOutboxMetrics(
        registry: MeterRegistry,
    ): AppointmentOutboxMetrics = MicrometerAppointmentOutboxMetrics(registry)

    @Bean
    @ConditionalOnBean(AppointmentOutboxRelay::class)
    @ConditionalOnMissingBean
    fun appointmentOutboxRelayLifecycle(
        relay: AppointmentOutboxRelay,
        properties: AppointmentMessagingProperties,
    ): AppointmentOutboxRelayLifecycle = AppointmentOutboxRelayLifecycle(
        relay = relay,
        properties = properties,
    )
}
