package io.bluetape4k.clinic.appointment.consumer

import io.bluetape4k.clinic.appointment.messaging.AppointmentConsumerContext
import io.bluetape4k.clinic.appointment.messaging.AppointmentConsumerRuntime
import io.bluetape4k.clinic.appointment.messaging.AppointmentMessagingProperties
import io.bluetape4k.clinic.appointment.notification.JdbcNotificationOutboxObservationStore
import io.bluetape4k.clinic.appointment.notification.JdbcNotificationOutboxWorkStore
import io.bluetape4k.clinic.appointment.notification.NotificationAppointmentEventConsumer
import io.bluetape4k.clinic.appointment.notification.NotificationAppointmentEventKafkaListener
import io.bluetape4k.clinic.appointment.notification.NotificationAutoConfiguration
import io.bluetape4k.clinic.appointment.notification.NotificationObservationSchedulingRunner
import io.bluetape4k.clinic.appointment.notification.NotificationOutboxMetrics
import io.bluetape4k.clinic.appointment.notification.NotificationOutboxSchedulingRunner
import io.bluetape4k.clinic.appointment.notification.NotificationReminderSchedulingRunner
import io.bluetape4k.clinic.appointment.notification.NotificationRetentionSchedulingRunner
import io.bluetape4k.clinic.appointment.notification.NotificationSchemaReadiness
import io.bluetape4k.clinic.appointment.notification.NotificationRuntimeHealthSignals
import io.bluetape4k.clinic.appointment.notification.ResilientNotificationChannel
import io.bluetape4k.clinic.appointment.notification.NotificationDirectDeliveryPort
import io.bluetape4k.clinic.appointment.notification.NotificationRetentionRunner
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxRepository
import io.bluetape4k.clinic.appointment.notification.NotificationOutboxObservationStore
import io.bluetape4k.clinic.appointment.notification.NotificationOutboxWorkStore
import io.bluetape4k.clinic.appointment.notification.AppointmentReminderScheduler
import io.bluetape4k.leader.LeaderGroupElector
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.github.resilience4j.bulkhead.Bulkhead
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.retry.Retry
import io.micrometer.core.instrument.MeterRegistry
import javax.sql.DataSource
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.jetbrains.exposed.v1.jdbc.Database
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.kafka.support.Acknowledgment
import kotlin.reflect.KClass

// appointment-notification/.../NotificationAppointmentEventConsumer.kt: messaging handler supertype.
private val eventConsumerType: KClass<out io.bluetape4k.clinic.appointment.messaging.AppointmentConsumerHandler> =
    NotificationAppointmentEventConsumer::class

// appointment-notification/.../NotificationAppointmentEventKafkaListener.kt: ConsumerRecord/Acknowledgment listener method.
private fun listenerMethodType(
    record: ConsumerRecord<String, String>,
    acknowledgment: Acknowledgment,
): Pair<ConsumerRecord<String, String>, Acknowledgment> = TODO("compile-only fixture")

// appointment-notification/.../NotificationSchemaReadiness.kt: public Database constructor.
private fun schemaReadinessType(database: Database): NotificationSchemaReadiness =
    NotificationSchemaReadiness(database, io.bluetape4k.clinic.appointment.notification.NotificationCryptoProperties())

// appointment-notification/.../NotificationOutboxWorkStore.kt: public JDBC store constructors.
private fun workStoreType(database: Database, repository: NotificationOutboxRepository): JdbcNotificationOutboxWorkStore =
    JdbcNotificationOutboxWorkStore(database, repository)
private fun observationStoreType(database: Database, repository: NotificationOutboxRepository): JdbcNotificationOutboxObservationStore =
    JdbcNotificationOutboxObservationStore(database, repository)

// appointment-notification/.../NotificationOutboxMetrics.kt: public MeterRegistry constructor.
private fun metricsType(
    registry: MeterRegistry,
    observationStore: NotificationOutboxObservationStore,
): NotificationOutboxMetrics =
    io.bluetape4k.clinic.appointment.notification.NotificationOutboxMetrics(registry, observationStore)

// appointment-notification/.../NotificationSchedulingRunners.kt: all four public scheduling runner classes.
private val runnerTypes: List<KClass<*>> = listOf(
    NotificationOutboxSchedulingRunner::class,
    NotificationObservationSchedulingRunner::class,
    NotificationRetentionSchedulingRunner::class,
    NotificationReminderSchedulingRunner::class,
)

// appointment-notification/.../ResilientNotificationChannel.kt: Resilience4j public factory type-use.
private fun resilientChannelType(
    delegate: io.bluetape4k.clinic.appointment.notification.NotificationChannel,
    circuitBreaker: CircuitBreaker,
    retry: Retry,
    bulkhead: Bulkhead,
): ResilientNotificationChannel? = null

// appointment-notification/.../NotificationAutoConfiguration.kt: Redis/Lettuce/leader annotation and bean type-use.
@ConditionalOnClass(RedisClient::class)
private fun leaderType(
    redisClient: RedisClient,
    connection: StatefulRedisConnection<String, String>,
    leader: LeaderGroupElector,
    registry: MeterRegistry,
    dataSource: DataSource?,
    database: Database?,
    provider: ObjectProvider<NotificationOutboxMetrics>,
): List<KClass<*>> = listOf(
    NotificationAutoConfiguration::class,
    NotificationAppointmentEventConsumer::class,
    NotificationAppointmentEventKafkaListener::class,
    NotificationSchemaReadiness::class,
    NotificationRuntimeHealthSignals::class,
    JdbcNotificationOutboxWorkStore::class,
    JdbcNotificationOutboxObservationStore::class,
    NotificationOutboxWorkStore::class,
    NotificationOutboxObservationStore::class,
    NotificationOutboxMetrics::class,
    *runnerTypes.toTypedArray(),
    NotificationRetentionRunner::class,
    AppointmentReminderScheduler::class,
    ResilientNotificationChannel::class,
    NotificationDirectDeliveryPort::class,
    NotificationOutboxRepository::class,
    eventConsumerType,
)
