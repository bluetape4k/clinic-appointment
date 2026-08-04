package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.leader.lettuce.LettuceLeaderGroupElector
import io.bluetape4k.leader.lettuce.leaderGroupElection
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxCodec
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxRepository
import io.bluetape4k.clinic.appointment.repository.waitlist.WaitlistRepository
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
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * 알림 모듈 Auto-Configuration.
 *
 * `clinic.notification.enabled=true` (기본값)일 때 활성화됩니다.
 * [NotificationChannel] 빈이 없으면 [DummyNotificationChannel]을 등록합니다.
 * 데이터베이스가 있으면 내구성 outbox worker·dispatcher·retention runner를 구성하고,
 * Redis가 있으면 향후 리마인더 복구 trigger용 리더 선출 빈을 등록합니다.
 */
@AutoConfiguration
@EnableScheduling
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
    fun notificationRuntimeHealthSignals(): NotificationRuntimeHealthSignals = NotificationRuntimeHealthSignals()

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
        metricsProvider: ObjectProvider<NotificationOutboxMetrics>,
    ): NotificationSchemaReadiness =
        NotificationSchemaReadiness(database, cryptoProperties, metricsProvider.ifAvailable)

    @Bean
    @ConditionalOnBean(Database::class)
    @ConditionalOnMissingBean(NotificationOutboxWorkStore::class)
    fun notificationOutboxWorkStore(
        database: Database,
        repository: NotificationOutboxRepository,
    ): JdbcNotificationOutboxWorkStore =
        JdbcNotificationOutboxWorkStore(database, repository)

    @Bean
    @ConditionalOnBean(Database::class)
    @ConditionalOnMissingBean(NotificationOutboxObservationStore::class)
    fun notificationOutboxObservationStore(
        database: Database,
        repository: NotificationOutboxRepository,
        properties: NotificationProperties,
    ): NotificationOutboxObservationStore =
        JdbcNotificationOutboxObservationStore(
            database = database,
            repository = repository,
            observationLimit = properties.observation.validate().limit,
        )

    @Bean
    @ConditionalOnMissingBean
    fun notificationDeliveryRouteGate(
        properties: NotificationProperties,
    ): NotificationDeliveryRouteGate =
        NotificationDeliveryRouteGate(properties.rollout)

    @Bean
    @ConditionalOnBean(MeterRegistry::class, NotificationOutboxObservationStore::class)
    @ConditionalOnMissingBean
    fun notificationOutboxMetrics(
        meterRegistry: MeterRegistry,
        observationStore: NotificationOutboxObservationStore,
    ): NotificationOutboxMetrics =
        NotificationOutboxMetrics(meterRegistry, observationStore)

    @Bean
    @ConditionalOnBean(NotificationSchemaReadiness::class)
    @ConditionalOnMissingBean(NotificationOutboxReadinessSource::class)
    fun notificationOutboxReadinessSource(
        readiness: NotificationSchemaReadiness,
    ): NotificationOutboxReadinessSource =
        NotificationOutboxReadinessSource {
            if (readiness.check().available) {
                NotificationOutboxReadinessSnapshot.up()
            } else {
                NotificationOutboxReadinessSnapshot(
                    schema = NotificationComponentState.down("SCHEMA_NOT_READY"),
                    claim = NotificationComponentState.down("CLAIM_NOT_READY"),
                    keyRing = NotificationComponentState.down("KEY_RING_NOT_READY"),
                )
            }
        }

    @Bean
    @ConditionalOnBean(NotificationOutboxMetrics::class)
    @ConditionalOnMissingBean(NotificationOutboxLivenessSource::class)
    fun notificationOutboxLivenessSource(
        metrics: NotificationOutboxMetrics,
        healthSignals: NotificationRuntimeHealthSignals,
    ): NotificationOutboxLivenessSource =
        NotificationOutboxLivenessSource {
            val observation = metrics.currentSnapshot()
            healthSignals.snapshot(observation.oldestActiveAge, observation.capped)
        }

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
        metricsProvider: ObjectProvider<NotificationOutboxMetrics>,
        healthSignals: NotificationRuntimeHealthSignals,
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
        val workerProperties = properties.worker.validate()
        val boundedProfileResolver = profileResolver
            ?.takeIf { runtimeConfigured }
            ?.let {
                BoundedMemberNotificationProfileResolver(
                    delegate = it,
                    timeout = workerProperties.memberResolverTimeout,
                    maxConcurrency = workerProperties.memberResolverMaxConcurrency,
                    rateLimitPerSecond = workerProperties.memberResolverRateLimitPerSecond,
                    circuitBreakerFailureRateThreshold =
                        workerProperties.memberResolverCircuitBreakerFailureRateThreshold,
                    healthSignals = healthSignals,
                )
            }
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
            profileResolver = boundedProfileResolver,
            templateRenderer = templateRenderer.takeIf { runtimeConfigured },
            providerChannel = resilientNotificationChannel.takeIf { runtimeConfigured },
            providerIdempotencyKeyFactory = providerIdempotencyKeyFactory.takeIf { runtimeConfigured },
            metrics = metricsProvider.ifAvailable,
        )
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "appointment.waitlist.delivery",
        name = ["enabled"],
        havingValue = "true",
    )
    @ConditionalOnBean(Database::class, WaitlistRepository::class)
    @ConditionalOnMissingBean(WaitlistOfferNotificationStore::class)
    fun waitlistOfferNotificationStore(
        database: Database,
        waitlistRepository: WaitlistRepository,
        properties: NotificationProperties,
    ): WaitlistOfferNotificationStore {
        val worker = properties.worker.validate()
        return JdbcWaitlistOfferNotificationStore(
            database = database,
            waitlistRepository = waitlistRepository,
            leaseDuration = worker.leaseDuration,
            maxAttempts = worker.maxAttempts,
            retryDelay = worker.pollInterval,
        )
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "appointment.waitlist.delivery",
        name = ["enabled"],
        havingValue = "true",
    )
    @ConditionalOnBean(
        WaitlistOfferNotificationStore::class,
        MemberNotificationProfileResolver::class,
        NotificationProviderIdempotencyKeyFactory::class,
    )
    @ConditionalOnMissingBean(WaitlistOfferNotificationWorker::class)
    fun waitlistOfferNotificationWorker(
        store: WaitlistOfferNotificationStore,
        profileResolver: MemberNotificationProfileResolver,
        resilientNotificationChannel: ResilientNotificationChannel,
        providerIdempotencyKeyFactory: NotificationProviderIdempotencyKeyFactory,
        templateCatalogProvider: ObjectProvider<NotificationTemplateCatalog>,
    ): WaitlistOfferNotificationWorker =
        WaitlistOfferNotificationWorker(
            store = store,
            profileResolver = profileResolver,
            channel = resilientNotificationChannel,
            requestRenderer = DefaultWaitlistOfferNotificationRequestRenderer(
                providerIdempotencyKeyFactory = providerIdempotencyKeyFactory,
                templateCatalog = templateCatalogProvider.ifAvailable ?: BuiltInWaitlistNotificationTemplateCatalog,
                channel = resilientNotificationChannel.channelType,
            ),
        )

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
        routeGate: NotificationDeliveryRouteGate,
        metricsProvider: ObjectProvider<NotificationOutboxMetrics>,
    ): NotificationOutboxDispatcher? {
        if (!routeGate.hasWorkerRoute) return null
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
            routeGate = routeGate,
            metrics = metricsProvider.ifAvailable,
        )
    }

    @Bean
    @ConditionalOnBean(NotificationDirectOutboxStore::class, NotificationOutboxJobWorker::class)
    @ConditionalOnMissingBean(NotificationDirectDeliveryExecutor::class)
    fun notificationDirectDeliveryExecutor(
        properties: NotificationProperties,
    ): NotificationDirectDeliveryExecutor {
        val worker = properties.worker.validate()
        return NotificationDirectDeliveryExecutor(
            concurrency = worker.globalConcurrency,
            queueCapacity = worker.batchSize,
        )
    }

    @Bean
    @ConditionalOnBean(NotificationDirectOutboxStore::class, NotificationOutboxJobWorker::class)
    @ConditionalOnMissingBean(NotificationDirectDeliveryPort::class)
    fun notificationDirectDelivery(
        store: NotificationDirectOutboxStore,
        worker: NotificationOutboxJobWorker,
        routeGate: NotificationDeliveryRouteGate,
        properties: NotificationProperties,
        metricsProvider: ObjectProvider<NotificationOutboxMetrics>,
    ): NotificationDirectDeliveryPort =
        properties.worker.validate().let { workerProperties ->
            NotificationDirectOutboxDelivery(
                store = store,
                worker = worker,
                routeGate = routeGate,
                globalConcurrency = workerProperties.globalConcurrency,
                perClinicConcurrency = workerProperties.perClinicConcurrency,
                metrics = metricsProvider.ifAvailable,
            )
        }

    @Bean
    @ConditionalOnBean(NotificationDirectDeliveryPort::class)
    @ConditionalOnMissingBean(NotificationEventListener::class)
    fun notificationEventListener(
        delivery: NotificationDirectDeliveryPort,
        properties: NotificationProperties,
        notificationDirectDeliveryExecutor: NotificationDirectDeliveryExecutor,
        routeGate: NotificationDeliveryRouteGate,
        metricsProvider: ObjectProvider<NotificationOutboxMetrics>,
    ): NotificationEventListener =
        NotificationEventListener(
            delivery = delivery,
            properties = properties,
            executor = notificationDirectDeliveryExecutor,
            routeGate = routeGate,
            metrics = metricsProvider.ifAvailable,
        )

    @Bean
    @ConditionalOnBean(NotificationOutboxWorkStore::class)
    @ConditionalOnMissingBean(NotificationOutboxSchedulingRunner::class)
    fun notificationOutboxSchedulingRunner(
        dispatcherProvider: ObjectProvider<NotificationOutboxDispatcher>,
    ): NotificationOutboxSchedulingRunner =
        NotificationOutboxSchedulingRunner(dispatcherProvider.ifAvailable)

    @Bean
    @ConditionalOnBean(NotificationOutboxMetrics::class)
    @ConditionalOnMissingBean(NotificationObservationSchedulingRunner::class)
    fun notificationObservationSchedulingRunner(
        metrics: NotificationOutboxMetrics,
    ): NotificationObservationSchedulingRunner =
        NotificationObservationSchedulingRunner(metrics)

    @Bean
    @ConditionalOnBean(
        NotificationOutboxWorkStore::class,
        ReminderRecoverySource::class,
        ReminderRecoveryMaterializer::class,
    )
    @ConditionalOnProperty(prefix = "clinic.notification.worker", name = ["enabled"], havingValue = "true", matchIfMissing = true)
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
    @ConditionalOnProperty(prefix = "clinic.notification.worker", name = ["enabled"], havingValue = "true", matchIfMissing = true)
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
            maxCandidatesPerRun = properties.worker.validate().reminderRecoveryMaxCandidatesPerRun,
        )

    @Bean
    @ConditionalOnBean(AppointmentReminderScheduler::class)
    @ConditionalOnProperty(prefix = "clinic.notification.worker", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(NotificationReminderSchedulingRunner::class)
    fun notificationReminderSchedulingRunner(
        scheduler: AppointmentReminderScheduler,
        metricsProvider: ObjectProvider<NotificationOutboxMetrics>,
    ): NotificationReminderSchedulingRunner =
        NotificationReminderSchedulingRunner(scheduler, metricsProvider.ifAvailable)

    @Bean
    @ConditionalOnBean(NotificationOutboxWorkStore::class)
    @ConditionalOnMissingBean(NotificationRetentionRunner::class)
    fun notificationRetentionRunner(
        workStore: NotificationOutboxWorkStore,
        readiness: NotificationSchemaReadiness?,
        properties: NotificationProperties,
    ): NotificationRetentionRunner {
        val retention = properties.retention.validate()
        return NotificationRetentionRunner(
            workStore = workStore,
            sentRetention = retention.sent,
            suppressedRetention = retention.suppressed,
            exhaustedRetention = retention.exhausted,
            pageSize = retention.pageSize,
            maxPagesPerStatus = retention.maxPagesPerStatus,
            backpressure = retention.backpressure,
            readiness = readiness,
        )
    }

    @Bean
    @ConditionalOnBean(NotificationRetentionRunner::class)
    @ConditionalOnMissingBean(NotificationRetentionSchedulingRunner::class)
    fun notificationRetentionSchedulingRunner(
        runner: NotificationRetentionRunner,
        healthSignals: NotificationRuntimeHealthSignals,
    ): NotificationRetentionSchedulingRunner =
        NotificationRetentionSchedulingRunner(runner, healthSignals)

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
        properties: NotificationProperties,
        healthSignals: NotificationRuntimeHealthSignals,
    ): ResilientNotificationChannel {
        log.info { "Resilience4j 적용: CircuitBreaker + Retry + Bulkhead" }
        val worker = properties.worker.validate()
        return ResilientNotificationChannel.create(
            delegate = notificationChannel,
            properties = resilienceProperties,
            providerAttemptsPerLease = worker.providerAttemptsPerLease,
            providerTimeout = worker.providerTimeoutFor(notificationChannel.channelType),
            healthSignals = healthSignals,
        )
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
