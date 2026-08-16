package io.bluetape4k.clinic.appointment.consumer

import io.bluetape4k.clinic.appointment.messaging.AppointmentConsumerHandler
import io.bluetape4k.clinic.appointment.messaging.AppointmentConsumerIdentity
import io.bluetape4k.clinic.appointment.messaging.AppointmentConsumerInboxStore
import io.bluetape4k.clinic.appointment.messaging.AppointmentConsumerMetrics
import io.bluetape4k.clinic.appointment.messaging.AppointmentConsumerRetentionProperties
import io.bluetape4k.clinic.appointment.messaging.AppointmentConsumerRetentionService
import io.bluetape4k.clinic.appointment.messaging.AppointmentConsumerRuntime
import io.bluetape4k.clinic.appointment.messaging.AppointmentConsumerInboxTable
import io.bluetape4k.clinic.appointment.messaging.AppointmentEventEnvelopeCodec
import io.bluetape4k.clinic.appointment.messaging.AppointmentKafkaConsumerConfiguration
import io.bluetape4k.clinic.appointment.messaging.AppointmentKafkaCredentialResolver
import io.bluetape4k.clinic.appointment.messaging.AppointmentKafkaProducerConfiguration
import io.bluetape4k.clinic.appointment.messaging.AppointmentKafkaConsumerListener
import io.bluetape4k.clinic.appointment.messaging.AppointmentMessagingAutoConfiguration
import io.bluetape4k.clinic.appointment.messaging.AppointmentMessagingHealthIndicator
import io.bluetape4k.clinic.appointment.messaging.AppointmentMessagingProperties
import io.bluetape4k.clinic.appointment.messaging.AppointmentMessagingReadinessProbe
import io.bluetape4k.clinic.appointment.messaging.AppointmentMessagingReadinessValidator
import io.bluetape4k.clinic.appointment.messaging.AppointmentMessagingStartupValidator
import io.bluetape4k.clinic.appointment.messaging.AppointmentOutboxRelayLifecycle
import io.bluetape4k.clinic.appointment.messaging.AppointmentReplayService
import io.bluetape4k.clinic.appointment.messaging.AppointmentSchemaRegistry
import io.bluetape4k.clinic.appointment.messaging.AppointmentTopic
import io.bluetape4k.clinic.appointment.messaging.KafkaAppointmentReplaySource
import io.bluetape4k.clinic.appointment.messaging.JdbcAppointmentConsumerInboxStore
import io.bluetape4k.clinic.appointment.messaging.MicrometerAppointmentConsumerMetrics
import io.bluetape4k.clinic.appointment.messaging.MicrometerAppointmentOutboxMetrics
import io.bluetape4k.clinic.appointment.messaging.NoopAppointmentConsumerMetrics
import io.bluetape4k.clinic.appointment.messaging.SpringKafkaAppointmentPublisher
import io.bluetape4k.clinic.appointment.messaging.AppointmentOutboxMetrics
import io.micrometer.core.instrument.MeterRegistry
import javax.sql.DataSource
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.context.SmartLifecycle
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.support.Acknowledgment
import kotlin.reflect.KClass

// appointment-messaging/.../AppointmentConsumerInboxStore.kt: public ConsumerRecord type-use.
private fun rejectedRecordType(record: ConsumerRecord<*, *>): ConsumerRecord<*, *> = record

// appointment-messaging/.../AppointmentConsumerRuntime.kt: all public consume argument types.
private fun runtimeConsumeTypes(
    runtime: AppointmentConsumerRuntime,
    record: ConsumerRecord<String, String>,
    acknowledgment: Acknowledgment?,
    identity: AppointmentConsumerIdentity,
    handler: AppointmentConsumerHandler,
): Any? = TODO("compile-only fixture")

// appointment-messaging/.../AppointmentConsumerRetentionService.kt: public Database constructor.
private fun retentionServiceType(
    database: Database,
    store: AppointmentConsumerInboxStore,
    properties: AppointmentConsumerRetentionProperties,
): AppointmentConsumerRetentionService = AppointmentConsumerRetentionService(database, store, properties)

// appointment-messaging/.../AppointmentReplayService.kt: public Database constructor.
private fun replayServiceType(database: Database, source: io.bluetape4k.clinic.appointment.messaging.AppointmentReplaySource): AppointmentReplayService =
    AppointmentReplayService(database, source)

// appointment-messaging/.../AppointmentConsumerInboxStore.kt: public JDBC store constructor.
private fun inboxStoreType(database: Database): JdbcAppointmentConsumerInboxStore = JdbcAppointmentConsumerInboxStore(database)

// appointment-messaging/.../AppointmentKafkaConsumerListener.kt: public ConsumerRecord/Acknowledgment listener method.
private val listenerType: KClass<out AppointmentKafkaConsumerListener> = AppointmentKafkaConsumerListener::class

// appointment-messaging/.../AppointmentKafkaConsumerListener.kt: Consumer-aware callback type.
private fun listenerConsumerType(consumer: Consumer<*, *>?): Consumer<*, *>? = consumer

// appointment-messaging/.../KafkaAppointmentReplaySource.kt: public ConsumerFactory constructor.
private fun replaySourceType(
    factory: ConsumerFactory<String, String>,
    topic: AppointmentTopic,
    runtime: AppointmentConsumerRuntime,
    handler: AppointmentConsumerHandler,
    identity: AppointmentConsumerIdentity,
): KafkaAppointmentReplaySource = KafkaAppointmentReplaySource(factory, topic, runtime, handler, identity)

// appointment-messaging/.../SpringKafkaAppointmentPublisher.kt: public KafkaTemplate/KafkaAdmin constructor.
private fun publisherType(template: KafkaTemplate<String, String>, admin: KafkaAdmin): SpringKafkaAppointmentPublisher =
    SpringKafkaAppointmentPublisher(template, admin)

// appointment-messaging/.../AppointmentKafkaConsumerConfiguration.kt: public ConsumerFactory factory method.
private fun consumerFactoryMethod(
    configuration: AppointmentKafkaConsumerConfiguration,
    consumerFactory: ConsumerFactory<String, String>,
): ConcurrentKafkaListenerContainerFactory<String, String> = TODO("compile-only fixture")

// appointment-messaging/.../AppointmentKafkaProducerConfiguration.kt: public ProducerFactory callable.
private fun producerConfigurationMethod(
    properties: AppointmentMessagingProperties,
    producerFactory: ProducerFactory<*, *>,
    credentialResolver: AppointmentKafkaCredentialResolver?,
): AppointmentKafkaProducerConfiguration = TODO("compile-only fixture")

// appointment-messaging/.../AppointmentMessagingAutoConfiguration.kt: public bean method type-use anchors.
private val autoConfigurationType: KClass<out AppointmentMessagingAutoConfiguration> = AppointmentMessagingAutoConfiguration::class
private val startupValidatorType: KClass<out SmartInitializingSingleton> = AppointmentMessagingStartupValidator::class
private val readinessValidatorType: KClass<out AppointmentMessagingReadinessValidator> = AppointmentMessagingReadinessValidator::class
private val healthIndicatorType: KClass<out HealthIndicator> = AppointmentMessagingHealthIndicator::class
private val lifecycleType: KClass<out SmartLifecycle> = AppointmentOutboxRelayLifecycle::class
private val inboxTableType: KClass<out Table> = AppointmentConsumerInboxTable::class
private val longIdTableType: KClass<out LongIdTable> = io.bluetape4k.clinic.appointment.messaging.AppointmentConsumerQuarantineTable::class
private val metricsType: KClass<out AppointmentConsumerMetrics> = MicrometerAppointmentConsumerMetrics::class
private val outboxMetricsType: KClass<out AppointmentOutboxMetrics> = MicrometerAppointmentOutboxMetrics::class

// Keep external public bean parameter types directly in the fixture signature.
@Suppress("UNUSED_PARAMETER")
fun verifyMessagingApiConsumerSurface(
    dataSource: DataSource?,
    database: Database?,
    meterRegistry: MeterRegistry?,
    provider: ObjectProvider<AppointmentConsumerInboxStore>?,
    producerFactory: ProducerFactory<*, *>?,
    kafkaTemplate: KafkaTemplate<String, String>?,
    kafkaAdmin: KafkaAdmin?,
    consumerFactory: ConsumerFactory<String, String>?,
    consumer: Consumer<*, *>?,
): List<KClass<*>> = listOf(
    AppointmentConsumerRuntime::class,
    AppointmentConsumerInboxStore::class,
    AppointmentConsumerRetentionService::class,
    AppointmentReplayService::class,
    AppointmentKafkaConsumerListener::class,
    KafkaAppointmentReplaySource::class,
    SpringKafkaAppointmentPublisher::class,
    AppointmentKafkaConsumerConfiguration::class,
    AppointmentKafkaProducerConfiguration::class,
    autoConfigurationType,
    healthIndicatorType,
    lifecycleType,
    startupValidatorType,
    readinessValidatorType,
    metricsType,
    outboxMetricsType,
    inboxTableType,
    longIdTableType,
)
