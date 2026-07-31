package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.leader.lettuce.LettuceLeaderGroupElector
import io.bluetape4k.leader.lettuce.leaderGroupElection
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxCodec
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxRepository
import io.micrometer.core.instrument.MeterRegistry
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import org.jetbrains.exposed.v1.jdbc.Database
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * 알림 모듈 Auto-Configuration.
 *
 * `clinic.notification.enabled=true` (기본값)일 때 활성화됩니다.
 * [NotificationChannel] 빈이 없으면 [DummyNotificationChannel]을 등록합니다.
 * 데이터베이스가 있으면 내구성 outbox worker·dispatcher·retention runner를 구성하고,
 * Redis가 있으면 향후 리마인더 복구 trigger용 리더 선출 빈을 등록합니다.
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
class NotificationAutoConfiguration {
    companion object : KLogging()

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
    @ConditionalOnBean(MeterRegistry::class, NotificationOutboxObservationStore::class)
    @ConditionalOnMissingBean
    fun notificationOutboxMetrics(
        meterRegistry: MeterRegistry,
        observationStore: NotificationOutboxObservationStore,
    ): NotificationOutboxMetrics =
        NotificationOutboxMetrics(meterRegistry, observationStore)

    @Bean
    @ConditionalOnBean(NotificationOutboxReadinessSource::class, NotificationOutboxLivenessSource::class)
    @ConditionalOnMissingBean
    fun notificationOutboxHealthIndicator(
        readinessSource: NotificationOutboxReadinessSource,
        livenessSource: NotificationOutboxLivenessSource,
    ): NotificationOutboxHealthIndicator =
        NotificationOutboxHealthIndicator(readinessSource, livenessSource)

    @Bean
    @ConditionalOnMissingBean
    fun notificationOutboxAlertPolicy(): NotificationOutboxAlertPolicy = NotificationOutboxAlertPolicy()

    @Bean
    @ConditionalOnBean(NotificationStatusQueryStore::class)
    @ConditionalOnMissingBean
    fun notificationStatusQueryService(
        store: NotificationStatusQueryStore,
    ): NotificationStatusQueryService =
        NotificationStatusQueryService(store)

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
        deliveryActionProvider: ObjectProvider<NotificationDeliveryAction>,
        profileResolverProvider: ObjectProvider<MemberNotificationProfileResolver>,
        templateRendererProvider: ObjectProvider<NotificationTemplateRenderer>,
        providerIdempotencyKeyFactoryProvider: ObjectProvider<NotificationProviderIdempotencyKeyFactory>,
        resilientNotificationChannel: ResilientNotificationChannel,
    ): NotificationOutboxWorker {
        val profileResolver = profileResolverProvider.ifAvailable
        val templateRenderer = templateRendererProvider.ifAvailable
        val providerIdempotencyKeyFactory = providerIdempotencyKeyFactoryProvider.ifAvailable
        val runtimeDependencyCount = listOf(
            profileResolver,
            templateRenderer,
            providerIdempotencyKeyFactory,
        ).count { it != null }
        check(runtimeDependencyCount == 0 || runtimeDependencyCount == RUNTIME_CONFIGURATION_DEPENDENCY_COUNT) {
            "member profile resolver, template renderer, and provider idempotency key factory must be configured together"
        }
        val runtimeConfigured = runtimeDependencyCount == RUNTIME_CONFIGURATION_DEPENDENCY_COUNT
        return NotificationOutboxWorker(
            workStore = workStore,
            leaseOwner = "notification-outbox-worker",
            readiness = readiness,
            deliveryAction = deliveryActionProvider.ifAvailable
                ?: NotificationDeliveryAction {
                    NotificationDeliveryResult.retry(
                        io.bluetape4k.clinic.appointment.event.notification.NotificationFailureCode.DELIVERY_RESULT_UNKNOWN,
                    )
                },
            profileResolver = profileResolver.takeIf { runtimeConfigured },
            templateRenderer = templateRenderer.takeIf { runtimeConfigured },
            providerChannel = resilientNotificationChannel.takeIf { runtimeConfigured },
            providerIdempotencyKeyFactory = providerIdempotencyKeyFactory.takeIf { runtimeConfigured },
        ).also {
            properties.worker.validate()
        }
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
        deliveryActionProvider: ObjectProvider<NotificationDeliveryAction>,
        profileResolverProvider: ObjectProvider<MemberNotificationProfileResolver>,
        templateRendererProvider: ObjectProvider<NotificationTemplateRenderer>,
        providerIdempotencyKeyFactoryProvider: ObjectProvider<NotificationProviderIdempotencyKeyFactory>,
    ): NotificationOutboxDispatcher? {
        val runtimeConfigured =
            profileResolverProvider.ifAvailable != null &&
                templateRendererProvider.ifAvailable != null &&
                providerIdempotencyKeyFactoryProvider.ifAvailable != null
        if (deliveryActionProvider.ifAvailable == null && !runtimeConfigured) return null
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
    @ConditionalOnBean(
        NotificationOutboxWorkStore::class,
        ReminderRecoverySource::class,
        ReminderRecoveryMaterializer::class,
    )
    @ConditionalOnMissingBean(NotificationReminderRecoveryScanner::class)
    fun notificationReminderRecoveryScanner(
        source: ReminderRecoverySource,
        materializer: ReminderRecoveryMaterializer,
        workStore: NotificationOutboxWorkStore,
        properties: NotificationProperties,
    ): NotificationReminderRecoveryScanner {
        val workerProperties = properties.worker.validate()
        return NotificationReminderRecoveryScanner(
            source = source,
            materializer = materializer,
            catchUpWindow = workerProperties.catchUpWindow,
            clock = ReminderRecoveryClock(workStore::currentDatabaseTime),
        )
    }

    @Bean
    @ConditionalOnBean(NotificationReminderRecoveryScanner::class)
    @ConditionalOnMissingBean(AppointmentReminderScheduler::class)
    fun appointmentReminderScheduler(
        scanner: NotificationReminderRecoveryScanner,
        properties: NotificationProperties,
        triggerGuardProvider: ObjectProvider<ReminderRecoveryTriggerGuard>,
    ): AppointmentReminderScheduler =
        AppointmentReminderScheduler(
            scanner = scanner,
            triggerGuard = triggerGuardProvider.ifAvailable ?: ReminderRecoveryTriggerGuard { true },
            batchSize = properties.worker.validate().batchSize,
        )

    @Bean
    @ConditionalOnBean(NotificationOutboxWorkStore::class)
    @ConditionalOnMissingBean(NotificationRetentionRunner::class)
    fun notificationRetentionRunner(
        workStore: NotificationOutboxWorkStore,
        readiness: NotificationSchemaReadiness?,
    ): NotificationRetentionRunner =
        NotificationRetentionRunner(
            workStore = workStore,
            readiness = readiness,
        )

    @Bean
    @ConditionalOnMissingBean(NotificationChannel::class)
    fun dummyNotificationChannel(): NotificationChannel = DummyNotificationChannel()

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
     * 향후 리마인더 복구 trigger를 단일 인스턴스에서 실행할 때 사용합니다.
     * outbox 발송 정합성은 데이터베이스 lease와 fencing이 보장합니다.
     */
    @Bean
    @ConditionalOnClass(RedisClient::class)
    @ConditionalOnBean(StatefulRedisConnection::class)
    fun notificationLeaderElection(connection: StatefulRedisConnection<String, String>): LettuceLeaderGroupElector {
        log.info { "HA 리더 선출 활성화: LettuceLeaderGroupElector" }
        return connection.leaderGroupElection()
    }

}

private const val RUNTIME_CONFIGURATION_DEPENDENCY_COUNT = 3
