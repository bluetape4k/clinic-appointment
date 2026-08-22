package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.lettuce.LettuceLeaderGroupElector
import io.bluetape4k.leader.lettuce.leaderGroupElection
import io.bluetape4k.leader.micrometer.InstrumentedLeaderGroupElector
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxCodec
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxRepository
import io.bluetape4k.clinic.appointment.messaging.AppointmentConsumerRuntime
import io.bluetape4k.clinic.appointment.messaging.AppointmentMessagingProperties
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

/**
 * 알림 모듈 Auto-Configuration.
 *
 * `clinic.notification.enabled=true` (기본값)일 때 활성화됩니다.
 * [NotificationChannel] 빈이 없으면 [DummyNotificationChannel]을 등록합니다.
 * 데이터베이스가 있으면 내구성 outbox worker·dispatcher·retention runner를 구성하고,
 * Redis가 있으면 리마인더 복구 한 tick 전체를 감싸는 리더 선출 빈을 등록합니다.
 * 스케줄러는 호스트 애플리케이션이 [org.springframework.scheduling.annotation.EnableScheduling]을
 * 명시적으로 선택한 경우에만 동작합니다.
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

    /**
     * cache/leader connection과 분리된 notification semaphore 전용 연결입니다.
     * RedisClient가 있으면 모드와 관계없이 준비하지만 `LOCAL` 모드에서는 사용하지 않습니다.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnClass(RedisClient::class)
    @ConditionalOnBean(RedisClient::class)
    @ConditionalOnMissingBean(NotificationConcurrencyRedisConnection::class)
    fun notificationConcurrencyRedisConnection(
        redisClient: RedisClient,
    ): OwnedNotificationConcurrencyRedisConnection =
        OwnedNotificationConcurrencyRedisConnection(redisClient.connect())

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
    @ConditionalOnMissingBean
    fun notificationProducerSchemaReadiness(
        properties: NotificationProperties,
        schemaReadinessProvider: ObjectProvider<NotificationSchemaReadiness>,
        templateCatalogProvider: ObjectProvider<NotificationTemplateCatalog>,
    ): NotificationProducerSchemaReadiness =
        NotificationProducerSchemaReadiness(
            properties = properties,
            schemaReadiness = schemaReadinessProvider.ifAvailable,
            templateCatalog = templateCatalogProvider.ifAvailable ?: BuiltInWaitlistNotificationTemplateCatalog,
        )

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
        producerReadiness: NotificationProducerSchemaReadiness,
    ): NotificationOutboxReadinessSource =
        NotificationOutboxReadinessSource {
            val schemaReady = readiness.check().available
            val producerReady = producerReadiness.check().available
            if (schemaReady && producerReady) {
                NotificationOutboxReadinessSnapshot.up()
            } else {
                NotificationOutboxReadinessSnapshot(
                    schema = NotificationComponentState.down(if (schemaReady) "PRODUCER_NOT_READY" else "SCHEMA_NOT_READY"),
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
        redisConnectionProvider: ObjectProvider<NotificationConcurrencyRedisConnection>,
    ): NotificationOutboxDispatcher? {
        if (!routeGate.hasWorkerRoute) return null
        val runtimeConfigured =
            profileResolverProvider.ifAvailable != null &&
                templateRendererProvider.ifAvailable != null &&
                providerIdempotencyKeyFactoryProvider.ifAvailable != null
        if (deliveryActionProvider.ifAvailable == null && !runtimeConfigured) return null
        val workerProperties = properties.worker.validate()
        val metrics = metricsProvider.ifAvailable
        val coordinator: NotificationOutboxConcurrencyCoordinator = when (workerProperties.concurrencyMode) {
            NotificationConcurrencyMode.LOCAL -> LocalNotificationOutboxConcurrencyCoordinator(
                globalConcurrency = workerProperties.globalConcurrency,
                perClinicConcurrency = workerProperties.perClinicConcurrency,
            )
            NotificationConcurrencyMode.REDIS -> {
                val redisConnection = checkNotNull(redisConnectionProvider.ifAvailable) {
                    "clinic.notification.worker.concurrency-mode=REDIS requires a dedicated notification Redis connection"
                }
                val factory = LettuceNotificationPermitSemaphoreFactory(
                    connection = redisConnection.connection(),
                    leaseTime = workerProperties.leaseDuration,
                    pollInterval = workerProperties.pollInterval,
                )
                RedisNotificationOutboxConcurrencyCoordinator(
                    properties = workerProperties,
                    global = factory.create("global", workerProperties.globalConcurrency),
                    clinicFactory = factory,
                    onFailure = { reason ->
                        if (reason == NotificationPermitFailureReason.RELEASE_FAILURE) {
                            metrics?.recordConcurrencyAdmission(NotificationConcurrencyMode.REDIS, reason)
                        }
                    },
                )
            }
        }
        return NotificationOutboxDispatcher.withCoordinator(
            store = workStore,
            worker = worker,
            leaseOwner = "notification-outbox-worker",
            globalConcurrency = workerProperties.globalConcurrency,
            perClinicConcurrency = workerProperties.perClinicConcurrency,
            readiness = readiness,
            routeGate = routeGate,
            metrics = metrics,
            concurrencyCoordinator = coordinator,
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
    @ConditionalOnProperty(prefix = "appointment.messaging.consumer", name = ["enabled"], havingValue = "true")
    @ConditionalOnBean(AppointmentConsumerRuntime::class, NotificationDirectDeliveryPort::class)
    @ConditionalOnMissingBean(NotificationAppointmentEventConsumer::class)
    fun notificationAppointmentEventConsumer(
        delivery: NotificationDirectDeliveryPort,
        properties: NotificationProperties,
    ): NotificationAppointmentEventConsumer =
        NotificationAppointmentEventConsumer(
            delivery = delivery,
            suspendBridgeTimeout = properties.worker.validate().suspendBridgeTimeout,
        )

    @Bean
    @ConditionalOnProperty(prefix = "appointment.messaging.consumer", name = ["enabled"], havingValue = "true")
    @ConditionalOnBean(AppointmentConsumerRuntime::class, NotificationAppointmentEventConsumer::class)
    @ConditionalOnMissingBean(NotificationAppointmentEventKafkaListener::class)
    fun notificationAppointmentEventKafkaListener(
        runtime: AppointmentConsumerRuntime,
        consumer: NotificationAppointmentEventConsumer,
        properties: AppointmentMessagingProperties,
    ): NotificationAppointmentEventKafkaListener =
        NotificationAppointmentEventKafkaListener(runtime, consumer, properties)

    @Bean
    @ConditionalOnBean(NotificationOutboxWorkStore::class)
    @ConditionalOnMissingBean(NotificationOutboxSchedulingRunner::class)
    fun notificationOutboxSchedulingRunner(
        dispatcherProvider: ObjectProvider<NotificationOutboxDispatcher>,
        properties: NotificationProperties,
    ): NotificationOutboxSchedulingRunner =
        NotificationOutboxSchedulingRunner(
            dispatcher = dispatcherProvider.ifAvailable,
            suspendBridgeTimeout = properties.worker.validate().suspendBridgeTimeout,
        )

    @Bean
    @ConditionalOnBean(NotificationOutboxMetrics::class)
    @ConditionalOnMissingBean(NotificationObservationSchedulingRunner::class)
    fun notificationObservationSchedulingRunner(
        metrics: NotificationOutboxMetrics,
        properties: NotificationProperties,
    ): NotificationObservationSchedulingRunner =
        NotificationObservationSchedulingRunner(
            metrics = metrics,
            suspendBridgeTimeout = properties.worker.validate().suspendBridgeTimeout,
        )

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
        leaderElectorProvider: ObjectProvider<LeaderGroupElector>,
        leaderHealthMonitorProvider: ObjectProvider<NotificationLeaderHealthMonitor>,
        properties: NotificationProperties,
    ): NotificationReminderSchedulingRunner =
        NotificationReminderSchedulingRunner(
            scheduler = scheduler,
            metrics = metricsProvider.ifAvailable,
            leaderElector = leaderElectorProvider.ifAvailable,
            suspendBridgeTimeout = properties.worker.validate().suspendBridgeTimeout,
            leaderHealthMonitor = leaderHealthMonitorProvider.ifAvailable,
        )

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
        properties: NotificationProperties,
    ): NotificationRetentionSchedulingRunner =
        NotificationRetentionSchedulingRunner(
            runner = runner,
            healthSignals = healthSignals,
            suspendBridgeTimeout = properties.worker.validate().suspendBridgeTimeout,
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
     * 리마인더 복구 한 tick 전체를 단일 리더 action 안에서 실행할 때 사용합니다.
     * outbox 발송 정합성은 데이터베이스 lease와 fencing이 보장합니다.
     */
    @Bean
    @ConditionalOnClass(
        value = [RedisClient::class],
        name = ["io.bluetape4k.leader.micrometer.InstrumentedLeaderGroupElector"],
    )
    @ConditionalOnBean(StatefulRedisConnection::class)
    @ConditionalOnMissingBean(LeaderGroupElector::class)
    fun notificationLeaderElection(
        connection: StatefulRedisConnection<String, String>,
        meterRegistryProvider: ObjectProvider<MeterRegistry>,
    ): LeaderGroupElector {
        val delegate: LettuceLeaderGroupElector = connection.leaderGroupElection()
        val registry = meterRegistryProvider.ifAvailable
        if (registry == null) {
            log.info { "HA 리더 선출 활성화: LettuceLeaderGroupElector" }
            return delegate
        }
        log.info { "HA 리더 선출 활성화: InstrumentedLeaderGroupElector" }
        return InstrumentedLeaderGroupElector(
            delegate = delegate,
            registry = registry,
            lockName = REMINDER_RECOVERY_LOCK_NAME,
        )
    }

    /**
     * 기본 동작을 바꾸지 않고 reminder leader 상태를 선택적으로 관측합니다.
     * health 결과는 scheduler 실행이나 DB claim/fence의 허용 여부를 결정하지 않습니다.
     */
    @Bean
    @ConditionalOnProperty(
        prefix = "clinic.notification.leader-health",
        name = ["enabled"],
        havingValue = "true",
    )
    @ConditionalOnBean(LeaderGroupElector::class)
    @ConditionalOnMissingBean(NotificationLeaderHealthMonitor::class)
    fun notificationLeaderHealthMonitor(
        leaderElector: LeaderGroupElector,
        properties: NotificationProperties,
    ): NotificationLeaderHealthMonitor {
        val healthProperties = properties.leaderHealth.validate()
        return NotificationLeaderHealthMonitor(
            elector = leaderElector,
            failureWindow = healthProperties.failureWindow,
            leaseRiskWindow = healthProperties.leaseRiskWindow,
        )
    }

}

private const val RUNTIME_CONFIGURATION_DEPENDENCY_COUNT = 3
