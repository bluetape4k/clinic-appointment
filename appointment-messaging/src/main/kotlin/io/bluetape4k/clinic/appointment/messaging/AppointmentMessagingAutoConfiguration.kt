package io.bluetape4k.clinic.appointment.messaging

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
import io.micrometer.core.instrument.MeterRegistry
import javax.sql.DataSource
import org.jetbrains.exposed.v1.jdbc.Database
import java.net.URI

/** appointment-messaging 기본 contract와 DB store를 Spring Boot 4에 등록한다. */
@AutoConfiguration
@EnableConfigurationProperties(AppointmentMessagingBindingProperties::class)
@Import(
    AppointmentKafkaConsumerConfiguration::class,
    AppointmentConsumerRetentionSchedulingConfiguration::class,
)
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
        schemaRegistry = AppointmentSchemaRegistryProperties(
            enabled = binding.schemaRegistry.enabled,
            baseUri = binding.schemaRegistry.baseUri?.let(URI::create),
            subject = binding.schemaRegistry.subject,
            timeout = binding.schemaRegistry.timeout,
            credentialReference = binding.schemaRegistry.credentialReference,
        ),
        consumer = AppointmentConsumerProperties(
            enabled = binding.consumer.enabled,
            groupId = binding.consumer.groupId,
            logicalConsumerId = AppointmentLogicalConsumerId(binding.consumer.logicalConsumerId),
            logicalStreamId = AppointmentLogicalStreamId(binding.consumer.logicalStreamId),
            topic = AppointmentTopic(
                binding.consumer.topic.takeUnless { it == DefaultAppointmentOutboxWriter.DEFAULT_TOPIC }
                    ?: binding.topic,
            ),
            maxAttempts = binding.consumer.maxAttempts,
            processingLease = binding.consumer.processingLease,
            maxPollInterval = binding.consumer.maxPollInterval,
            maxPollRecords = binding.consumer.maxPollRecords,
            shutdownTimeout = binding.consumer.shutdownTimeout,
        ),
        retention = binding.retention,
    )

    @Bean
    @ConditionalOnMissingBean
    fun appointmentEventEnvelopeCodec(): AppointmentEventEnvelopeCodec = AppointmentEventEnvelopeCodec()

    @Bean
    @ConditionalOnMissingBean
    fun appointmentSchemaRegistry(
        properties: AppointmentMessagingProperties,
        credentialResolver: ObjectProvider<AppointmentSchemaRegistryCredentialResolver>,
    ): AppointmentSchemaRegistry {
        val registry = properties.schemaRegistry
        if (!registry.enabled) return StaticAppointmentSchemaRegistry(subject = registry.subject)

        val credentials = registry.credentialReference
            ?.let { reference ->
                requireNotNull(credentialResolver.getIfAvailable()) {
                    "AppointmentSchemaRegistryCredentialResolver is required for credentialReference"
                }.resolve(reference)
            }
        return HttpAppointmentSchemaRegistry(
            subject = registry.subject,
            compatibilityReader = JdkSchemaRegistryCompatibilityReader(
                baseUri = requireNotNull(registry.baseUri),
                subject = registry.subject,
                timeout = registry.timeout,
                credentials = credentials,
            ),
        )
    }

    @Bean
    @ConditionalOnBean(Database::class)
    @ConditionalOnMissingBean
    @DependsOnDatabaseInitialization
    fun appointmentConsumerInboxStore(
        database: Database,
        properties: AppointmentMessagingProperties,
        metrics: AppointmentConsumerMetrics,
    ): AppointmentConsumerInboxStore = JdbcAppointmentConsumerInboxStore(
        database = database,
        maxAttempts = properties.consumer.maxAttempts,
        processingLease = properties.consumer.processingLease,
        metrics = metrics,
    )

    @Bean
    @ConditionalOnProperty(prefix = "appointment.messaging.consumer", name = ["enabled"], havingValue = "true")
    @ConditionalOnBean(Database::class)
    @ConditionalOnMissingBean
    @DependsOnDatabaseInitialization
    fun appointmentConsumerScopeAuthority(
        database: Database,
    ): AppointmentConsumerScopeAuthority = DatabaseAppointmentConsumerScopeAuthority(database)

    @Bean
    @ConditionalOnProperty(prefix = "appointment.messaging.consumer", name = ["enabled"], havingValue = "true")
    @ConditionalOnBean(AppointmentConsumerInboxStore::class, AppointmentConsumerScopeAuthority::class)
    @ConditionalOnMissingBean
    fun appointmentConsumerRuntime(
        properties: AppointmentMessagingProperties,
        codec: AppointmentEventEnvelopeCodec,
        schemaRegistry: AppointmentSchemaRegistry,
        inboxStore: AppointmentConsumerInboxStore,
        scopeAuthority: AppointmentConsumerScopeAuthority,
        metrics: AppointmentConsumerMetrics,
    ): AppointmentConsumerRuntime {
        schemaRegistry.validate(AppointmentEventEnvelope.CURRENT_SCHEMA_VERSION)
        return AppointmentConsumerRuntime(
            codec = codec,
            inboxStore = inboxStore,
            allowedTopics = properties.allowedTopics,
            scopeAuthority = scopeAuthority,
            schemaRegistry = schemaRegistry,
            metrics = metrics,
        )
    }

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
        properties: AppointmentMessagingProperties,
        dataSource: ObjectProvider<DataSource>,
        schemaRegistry: AppointmentSchemaRegistry,
    ): AppointmentMessagingReadinessValidator = AppointmentMessagingReadinessValidator(
        codec = codec,
        dataSource = dataSource.getIfAvailable(),
        requireConsumerSchema = properties.consumer.enabled,
        schemaRegistry = schemaRegistry,
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
        // prerequisite를 writer 생성 전에 확인합니다. 따라서 schema 또는 serializer 계약이
        // 실패하면 writer bean을 노출하지 않고 startup을 중단합니다.
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
    @ConditionalOnMissingBean(value = [MeterRegistry::class, AppointmentConsumerMetrics::class])
    fun appointmentConsumerMetrics(): AppointmentConsumerMetrics = NoopAppointmentConsumerMetrics

    @Bean
    @ConditionalOnBean(MeterRegistry::class)
    @ConditionalOnMissingBean(AppointmentConsumerMetrics::class)
    fun micrometerAppointmentConsumerMetrics(
        registry: MeterRegistry,
    ): AppointmentConsumerMetrics = MicrometerAppointmentConsumerMetrics(registry)

    @Bean
    @ConditionalOnProperty(prefix = "appointment.messaging.retention", name = ["enabled"], havingValue = "true")
    @ConditionalOnBean(value = [Database::class, AppointmentConsumerInboxStore::class])
    @ConditionalOnMissingBean
    fun appointmentConsumerRetentionService(
        database: Database,
        inboxStore: AppointmentConsumerInboxStore,
        properties: AppointmentMessagingProperties,
        metrics: AppointmentConsumerMetrics,
    ): AppointmentConsumerRetentionService = AppointmentConsumerRetentionService(
        database = database,
        inboxStore = inboxStore,
        properties = properties.retention,
        metrics = metrics,
    )

    @Bean
    @ConditionalOnProperty(
        prefix = "appointment.messaging.retention",
        name = ["scheduler-enabled"],
        havingValue = "true",
    )
    @ConditionalOnBean(AppointmentConsumerRetentionService::class)
    @ConditionalOnMissingBean
    fun appointmentConsumerRetentionScheduler(
        service: AppointmentConsumerRetentionService,
    ): AppointmentConsumerRetentionScheduler = AppointmentConsumerRetentionScheduler(service)

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
