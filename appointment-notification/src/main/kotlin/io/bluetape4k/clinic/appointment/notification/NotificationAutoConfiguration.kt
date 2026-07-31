package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.leader.lettuce.LettuceLeaderGroupElector
import io.bluetape4k.leader.lettuce.leaderGroupElection
import io.bluetape4k.clinic.appointment.repository.AppointmentRepository
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxCodec
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxRepository
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * 알림 모듈 Auto-Configuration.
 *
 * `clinic.notification.enabled=true` (기본값)일 때 활성화됩니다.
 * [NotificationChannel] 빈이 없으면 [DummyNotificationChannel]을 등록합니다.
 * Redis가 있으면 리더 선출 + Resilience4j 장애 격리를 적용합니다.
 */
@AutoConfiguration
@ConditionalOnProperty(
    prefix = "clinic.notification",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true
)
@EnableConfigurationProperties(
    NotificationProperties::class,
    NotificationResilienceProperties::class,
    NotificationCryptoProperties::class,
)
@EnableScheduling
class NotificationAutoConfiguration {
    companion object : KLogging()

    /**
     * Flyway 비활성 시 Exposed SchemaUtils로 알림 테이블 생성.
     */
    @Bean
    @ConditionalOnProperty(name = ["spring.flyway.enabled"], havingValue = "false", matchIfMissing = true)
    fun notificationSchemaInitializer(): ApplicationRunner =
        ApplicationRunner {
            transaction {
                MigrationUtils.statementsRequiredForDatabaseMigration(NotificationHistoryTable).forEach { exec(it) }
            }
        }

    @Bean
    fun notificationHistoryRepository(): NotificationHistoryRepository = NotificationHistoryRepository()

    @Bean
    @ConditionalOnMissingBean
    fun notificationOutboxCodec(): NotificationOutboxCodec = NotificationOutboxCodec()

    @Bean
    @ConditionalOnMissingBean
    fun notificationOutboxRepository(
        codec: NotificationOutboxCodec,
        properties: NotificationProperties,
    ): NotificationOutboxRepository =
        NotificationOutboxRepository(
            codec = codec,
            leaseDuration = properties.worker.validate().leaseDuration,
        )

    @Bean
    @ConditionalOnBean(Database::class)
    @ConditionalOnMissingBean
    fun notificationSchemaReadiness(
        database: Database,
        cryptoProperties: NotificationCryptoProperties,
    ): NotificationSchemaReadiness =
        NotificationSchemaReadiness(database, cryptoProperties)

    @Bean
    @ConditionalOnBean(Database::class)
    @ConditionalOnMissingBean(NotificationOutboxWorkStore::class)
    fun notificationOutboxWorkStore(
        database: Database,
        repository: NotificationOutboxRepository,
    ): NotificationOutboxWorkStore =
        JdbcNotificationOutboxWorkStore(database, repository)

    @Bean
    @ConditionalOnProperty(
        prefix = "clinic.notification.worker",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    @ConditionalOnBean(NotificationOutboxWorkStore::class)
    @ConditionalOnMissingBean(NotificationOutboxWorker::class)
    fun notificationOutboxWorker(
        workStore: NotificationOutboxWorkStore,
        properties: NotificationProperties,
        readiness: NotificationSchemaReadiness?,
    ): NotificationOutboxWorker =
        NotificationOutboxWorker(
            workStore = workStore,
            leaseOwner = "notification-outbox-worker",
            readiness = readiness,
        ).also {
            properties.worker.validate()
        }

    @Bean
    @ConditionalOnProperty(
        prefix = "clinic.notification.worker",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    @ConditionalOnBean(
        NotificationOutboxWorkStore::class,
        NotificationOutboxJobWorker::class,
    )
    @ConditionalOnMissingBean(NotificationOutboxDispatcher::class)
    fun notificationOutboxDispatcher(
        workStore: NotificationOutboxWorkStore,
        worker: NotificationOutboxJobWorker,
        properties: NotificationProperties,
        readiness: NotificationSchemaReadiness?,
    ): NotificationOutboxDispatcher {
        val workerProperties = properties.worker.validate()
        return NotificationOutboxDispatcher(
            store = workStore,
            worker = worker,
            leaseOwner = "notification-outbox-worker",
            globalConcurrency = workerProperties.globalConcurrency,
            perClinicConcurrency = workerProperties.perClinicConcurrency,
            readiness = readiness,
        )
    }

    @Bean
    @ConditionalOnMissingBean(NotificationChannel::class)
    fun dummyNotificationChannel(historyRepository: NotificationHistoryRepository): NotificationChannel =
        DummyNotificationChannel(historyRepository)

    /**
     * Resilience4j 데코레이터.
     * 외부 알림 서비스 호출 시 CircuitBreaker + Retry + Bulkhead 적용.
     */
    @Bean
    fun resilientNotificationChannel(
        notificationChannel: NotificationChannel,
        resilienceProperties: NotificationResilienceProperties,
    ): ResilientNotificationChannel {
        log.info { "Resilience4j 적용: CircuitBreaker + Retry + Bulkhead" }
        return ResilientNotificationChannel.create(notificationChannel, resilienceProperties)
    }

    /**
     * Redis가 있을 때 리더 선출 빈 등록.
     * HA 환경에서 스케줄러가 1개 인스턴스에서만 실행되도록 합니다.
     */
    @Bean
    @ConditionalOnClass(RedisClient::class)
    @ConditionalOnBean(StatefulRedisConnection::class)
    fun notificationLeaderElection(connection: StatefulRedisConnection<String, String>): LettuceLeaderGroupElector {
        log.info { "HA 리더 선출 활성화: LettuceLeaderGroupElector" }
        return connection.leaderGroupElection()
    }

    @Bean
    fun notificationEventListener(
        resilientNotificationChannel: ResilientNotificationChannel,
        appointmentRepository: AppointmentRepository,
        properties: NotificationProperties,
    ): NotificationEventListener =
        NotificationEventListener(resilientNotificationChannel, appointmentRepository, properties)

    @Bean
    fun appointmentReminderScheduler(
        resilientNotificationChannel: ResilientNotificationChannel,
        appointmentRepository: AppointmentRepository,
        historyRepository: NotificationHistoryRepository,
        properties: NotificationProperties,
        leaderElection: LettuceLeaderGroupElector?,
    ): AppointmentReminderScheduler =
        AppointmentReminderScheduler(
            resilientNotificationChannel,
            appointmentRepository,
            historyRepository,
            properties,
            leaderElection
        )
}
