package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.event.AppointmentEventLogs
import io.bluetape4k.clinic.appointment.event.notification.NotificationChannelType
import io.bluetape4k.clinic.appointment.event.notification.NotificationDeliveryAttempts
import io.bluetape4k.clinic.appointment.event.notification.NotificationFailureCode
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxEvents
import io.bluetape4k.clinic.appointment.event.notification.NotificationTemplateKey
import io.bluetape4k.clinic.appointment.event.notification.NotificationTemplateVersion
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.repository.AppointmentRepository
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.lettuce.LettuceLeaderGroupElector
import io.bluetape4k.leader.micrometer.InstrumentedLeaderGroupElector
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import kotlin.system.measureTimeMillis

internal class NotificationAutoConfigurationTest {

    @Test
    fun `resilient channel bean은 실제 channel 유형의 provider timeout override를 적용한다`() {
        val database = database("auto_channel_timeout", version = "21")
        context(database, withKey = true)
            .withPropertyValues(
                "clinic.notification.worker.provider-timeout=2s",
                "clinic.notification.worker.channels.sms.provider-timeout=25ms",
            )
            .withBean(
                "smsNotificationChannel",
                NotificationChannel::class.java,
                {
                    object : NotificationChannel {
                        override val channelType: NotificationChannelType = NotificationChannelType.SMS

                        override fun send(request: NotificationProviderRequest): NotificationProviderResult {
                            CountDownLatch(1).await()
                            return NotificationProviderResult.accepted()
                        }
                    }
                },
            )
            .run { applicationContext ->
                applicationContext.startupFailure shouldBeEqualTo null
                val channel = applicationContext.getBean(ResilientNotificationChannel::class.java)

                val elapsed = measureTimeMillis {
                    val failure = assertFailsWith<NotificationProviderException> {
                        channel.send(providerRequest())
                    }
                    failure.failureCode shouldBeEqualTo NotificationFailureCode.PROVIDER_UNAVAILABLE
                }

                check(elapsed < 500L) { "channel timeout override was not applied: elapsed=${elapsed}ms" }
            }
    }

    @Test
    fun `old schema와 key 부재에서는 새 worker traffic을 받지 않는다`() {
        val oldSchema = database("auto_old_schema", version = "13")
        context(oldSchema, withKey = true).run { applicationContext ->
            applicationContext.startupFailure shouldBeEqualTo null
            val worker = applicationContext.getBean(NotificationOutboxWorker::class.java)
            runBlocking { worker.recoverExpiredOnce(10) } shouldBeEqualTo emptyList()
            val retention = applicationContext.getBean(NotificationRetentionRunner::class.java)
            runBlocking { retention.runOnce().deletedByStatus } shouldBeEqualTo emptyMap()
        }

        val missingKey = database("auto_missing_key", version = "21")
        context(missingKey, withKey = false).run { applicationContext ->
            applicationContext.startupFailure shouldBeEqualTo null
            val worker = applicationContext.getBean(NotificationOutboxWorker::class.java)
            runBlocking { worker.recoverExpiredOnce(10) } shouldBeEqualTo emptyList()
        }
    }

    @Test
    fun `V21 schema와 active key가 있으면 worker readiness가 UP이다`() {
        val database = database("auto_ready", version = "21")
        context(database, withKey = true).run { applicationContext ->
            applicationContext.startupFailure shouldBeEqualTo null
            applicationContext.getBean(NotificationSchemaReadiness::class.java)
                .check()
                .available shouldBeEqualTo true
            applicationContext.getBeansOfType(NotificationOutboxWorker::class.java).size shouldBeEqualTo 1
            applicationContext.getBeansOfType(NotificationOutboxDispatcher::class.java).size shouldBeEqualTo 0
            applicationContext.getBeansOfType(NotificationRetentionRunner::class.java).size shouldBeEqualTo 1
            applicationContext.getBeansOfType(NotificationRetentionSchedulingRunner::class.java).size shouldBeEqualTo 1
        }
    }

    @Test
    fun `v2 producer는 schema와 cancellation template readiness가 없으면 v1로 fail closed 한다`() {
        val database = database("auto_v2_gate", version = "21")
        context(database, withKey = true)
            .withPropertyValues("clinic.notification.v2-producer=true")
            .run { applicationContext ->
                applicationContext.startupFailure shouldBeEqualTo null
                applicationContext.getBean(NotificationProducerSchemaReadiness::class.java)
                    .allowsV2() shouldBeEqualTo true
                applicationContext.getBean(NotificationOutboxReadinessSource::class.java)
                    .snapshot()
                    .schema
                    .available shouldBeEqualTo true
            }
    }

    @Test
    fun `v2 producer readiness는 cancellation template의 key version channel과 detail field를 함께 검증한다`() {
        val database = database("auto_v2_malformed_template", version = "21")
        context(database, withKey = true)
            .withPropertyValues("clinic.notification.v2-producer=true")
            .withBean(
                "malformedNotificationTemplateCatalog",
                NotificationTemplateCatalog::class.java,
                {
                    NotificationTemplateCatalog { key, version, channel ->
                        if (key != APPOINTMENT_CANCELLED_TEMPLATE_KEY) return@NotificationTemplateCatalog null
                        NotificationTemplate(
                            key = key,
                            version = version,
                            channel = channel,
                            fields = setOf("clinicDisplayName"),
                            textTemplate = "{{clinicDisplayName}}",
                        )
                    }
                },
            )
            .run { applicationContext ->
                applicationContext.startupFailure shouldBeEqualTo null
                applicationContext.getBean(NotificationProducerSchemaReadiness::class.java)
                    .allowsV2() shouldBeEqualTo false
            }
    }

    @Test
    fun `v2 producer readiness는 기존 cancellation v1 backlog를 렌더링하지 못하면 거부한다`() {
        val database = database("auto_v2_missing_legacy_template", version = "21")
        context(database, withKey = true)
            .withPropertyValues("clinic.notification.v2-producer=true")
            .withBean(
                "v2OnlyNotificationTemplateCatalog",
                NotificationTemplateCatalog::class.java,
                {
                    NotificationTemplateCatalog { key, version, channel ->
                        if (version != APPOINTMENT_CANCELLED_TEMPLATE_VERSION) {
                            null
                        } else {
                            BuiltInWaitlistNotificationTemplateCatalog.find(key, version, channel)
                        }
                    }
                },
            )
            .run { applicationContext ->
                applicationContext.startupFailure shouldBeEqualTo null
                applicationContext.getBean(NotificationProducerSchemaReadiness::class.java)
                    .allowsV2() shouldBeEqualTo false
            }
    }

    @Test
    fun `v2 producer 설정이 boolean이 아니면 binding 단계에서 시작을 거절한다`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NotificationAutoConfiguration::class.java))
            .withPropertyValues("clinic.notification.v2-producer=not-a-boolean")
            .run { applicationContext ->
                check(applicationContext.startupFailure != null) {
                    "invalid v2 producer property must fail startup"
                }
            }
    }

    @Test
    fun `ACTIVE에서 실제 delivery worker가 제공된 경우에만 dispatcher를 구성한다`() {
        val database = database("auto_dispatcher", version = "21")
        context(database, withKey = true)
            .withPropertyValues("clinic.notification.rollout.mode=ACTIVE")
            .withBean(
                "deliveryAction",
                NotificationDeliveryAction::class.java,
                { NotificationDeliveryAction { NotificationDeliveryResult.sent() } },
            )
            .run { applicationContext ->
                applicationContext.startupFailure shouldBeEqualTo null
                applicationContext.getBeansOfType(NotificationOutboxDispatcher::class.java).size shouldBeEqualTo 1
                applicationContext.getBeansOfType(NotificationOutboxSchedulingRunner::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `REDIS 모드는 전용 connection이 없으면 dispatcher 시작을 거절한다`() {
        val database = database("auto_dispatcher_redis_required", version = "21")
        context(database, withKey = true)
            .withPropertyValues(
                "clinic.notification.rollout.mode=ACTIVE",
                "clinic.notification.worker.concurrency-mode=REDIS",
            )
            .withBean(
                "deliveryAction",
                NotificationDeliveryAction::class.java,
                { NotificationDeliveryAction { NotificationDeliveryResult.sent() } },
            )
            .run { applicationContext ->
                check(applicationContext.startupFailure != null) {
                    "REDIS mode must fail startup without a dedicated connection"
                }
            }
    }

    @Test
    fun `REDIS 모드는 명시된 전용 connection으로 dispatcher를 구성한다`() {
        val database = database("auto_dispatcher_redis_connection", version = "21")
        val connection = mockk<StatefulRedisConnection<String, String>>(relaxed = true)
        context(database, withKey = true)
            .withPropertyValues(
                "clinic.notification.rollout.mode=ACTIVE",
                "clinic.notification.worker.concurrency-mode=REDIS",
            )
            .withBean(
                "notificationConcurrencyRedisConnection",
                NotificationConcurrencyRedisConnection::class.java,
                { NotificationConcurrencyRedisConnection { connection } },
            )
            .withBean(
                "deliveryAction",
                NotificationDeliveryAction::class.java,
                { NotificationDeliveryAction { NotificationDeliveryResult.sent() } },
            )
            .run { applicationContext ->
                applicationContext.startupFailure shouldBeEqualTo null
                applicationContext.getBeansOfType(NotificationOutboxDispatcher::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `auto-created dedicated Redis connection은 context destroy에서 한 번 닫힌다`() {
        val database = database("auto_dispatcher_owned_redis_connection", version = "21")
        val redisClient = mockk<RedisClient>(relaxed = true)
        val connection = mockk<StatefulRedisConnection<String, String>>(relaxed = true)
        every { redisClient.connect() } returns connection

        context(database, withKey = true)
            .withPropertyValues(
                "clinic.notification.rollout.mode=ACTIVE",
                "clinic.notification.worker.concurrency-mode=REDIS",
            )
            .withBean("redisClient", RedisClient::class.java, { redisClient })
            .withBean(
                "deliveryAction",
                NotificationDeliveryAction::class.java,
                { NotificationDeliveryAction { NotificationDeliveryResult.sent() } },
            )
            .run { applicationContext ->
                applicationContext.startupFailure shouldBeEqualTo null
                applicationContext.getBeansOfType(OwnedNotificationConcurrencyRedisConnection::class.java)
                    .size shouldBeEqualTo 1
            }

        verify(exactly = 1) { redisClient.connect() }
        verify(exactly = 1) { connection.close() }
    }

    @Test
    fun `기본 SHADOW는 runtime dependency가 있어도 background dispatcher를 구성하지 않는다`() {
        val database = database("auto_runtime_dispatcher", version = "21")
        context(database, withKey = true)
            .withBean(
                "memberNotificationProfileResolver",
                MemberNotificationProfileResolver::class.java,
                { MemberNotificationProfileResolver { MemberNotificationProfileResult.NotFound } },
            )
            .withBean(
                "notificationTemplateRenderer",
                NotificationTemplateRenderer::class.java,
                {
                    NotificationTemplateRenderer(
                        NotificationTemplateCatalog { key, version, channel ->
                            NotificationTemplate(
                                key = key,
                                version = version,
                                channel = channel,
                                fields = setOf("clinicDisplayName"),
                                textTemplate = "{{clinicDisplayName}}",
                            )
                        }
                    )
                },
            )
            .withBean(
                "notificationProviderIdempotencyKeyFactory",
                NotificationProviderIdempotencyKeyFactory::class.java,
                { NotificationProviderIdempotencyKeyFactory(ByteArray(32) { 1 }) },
            )
            .run { applicationContext ->
                applicationContext.startupFailure shouldBeEqualTo null
                applicationContext.getBeansOfType(NotificationOutboxDispatcher::class.java).size shouldBeEqualTo 0
                applicationContext.getBeansOfType(NotificationOutboxSchedulingRunner::class.java).size shouldBeEqualTo 1
                applicationContext.getBeansOfType(NotificationEventListener::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `waitlist delivery feature flag가 worker bean을 명시적으로 제어한다`() {
        fun runner(enabled: Boolean): ApplicationContextRunner =
            ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(NotificationAutoConfiguration::class.java))
                .withPropertyValues("appointment.waitlist.delivery.enabled=$enabled")
                .withBean(
                    "waitlistStore",
                    WaitlistOfferNotificationStore::class.java,
                    {
                        object : WaitlistOfferNotificationStore {
                            override suspend fun claim(
                                now: java.time.Instant,
                                owner: String,
                            ): WaitlistOfferNotificationClaim? = null

                            override suspend fun authorizeSend(
                                claim: WaitlistOfferNotificationClaim,
                                now: java.time.Instant,
                            ): Boolean = false

                            override suspend fun recordResult(
                                claim: WaitlistOfferNotificationClaim,
                                result: WaitlistNotificationDeliveryResult,
                                now: java.time.Instant,
                            ): Boolean = true
                        }
                    },
                )
                .withBean(
                    "memberResolver",
                    MemberNotificationProfileResolver::class.java,
                    { MemberNotificationProfileResolver { MemberNotificationProfileResult.NotFound } },
                )
                .withBean(
                    "providerKeyFactory",
                    NotificationProviderIdempotencyKeyFactory::class.java,
                    { NotificationProviderIdempotencyKeyFactory(ByteArray(32) { 7 }) },
                )

        runner(false).run { applicationContext ->
            applicationContext.startupFailure shouldBeEqualTo null
            applicationContext.getBeansOfType(WaitlistOfferNotificationWorker::class.java).size shouldBeEqualTo 0
        }
        runner(true).run { applicationContext ->
            applicationContext.startupFailure shouldBeEqualTo null
            applicationContext.getBeansOfType(WaitlistOfferNotificationWorker::class.java).size shouldBeEqualTo 1
        }
    }

    @Test
    fun `notification auto configuration은 host scheduling opt-in 없이 global scheduler를 활성화하지 않는다`() {
        val database = database("auto_scheduler_opt_in", version = "21")

        context(database, withKey = true).run { applicationContext ->
            applicationContext.startupFailure shouldBeEqualTo null
            applicationContext.containsBean("org.springframework.context.annotation.internalScheduledAnnotationProcessor") shouldBeEqualTo false
        }
    }

    @Test
    fun `ACTIVE는 runtime delivery dispatcher를 구성한다`() {
        val database = database("auto_runtime_active", version = "21")
        context(database, withKey = true)
            .withPropertyValues("clinic.notification.rollout.mode=ACTIVE")
            .withBean(
                "memberNotificationProfileResolver",
                MemberNotificationProfileResolver::class.java,
                { MemberNotificationProfileResolver { MemberNotificationProfileResult.NotFound } },
            )
            .withBean(
                "notificationTemplateRenderer",
                NotificationTemplateRenderer::class.java,
                {
                    NotificationTemplateRenderer(
                        NotificationTemplateCatalog { key, version, channel ->
                            NotificationTemplate(
                                key = key,
                                version = version,
                                channel = channel,
                                fields = setOf("clinicDisplayName"),
                                textTemplate = "{{clinicDisplayName}}",
                            )
                        }
                    )
                },
            )
            .withBean(
                "notificationProviderIdempotencyKeyFactory",
                NotificationProviderIdempotencyKeyFactory::class.java,
                { NotificationProviderIdempotencyKeyFactory(ByteArray(32) { 1 }) },
            )
            .run { applicationContext ->
                applicationContext.startupFailure shouldBeEqualTo null
                applicationContext.getBeansOfType(NotificationOutboxDispatcher::class.java).size shouldBeEqualTo 1
                applicationContext.getBeansOfType(NotificationOutboxSchedulingRunner::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `runtime delivery dependency 일부만 설정하면 시작 단계에서 거절한다`() {
        val database = database("auto_partial_runtime", version = "21")
        context(database, withKey = true)
            .withBean(
                "memberNotificationProfileResolver",
                MemberNotificationProfileResolver::class.java,
                { MemberNotificationProfileResolver { MemberNotificationProfileResult.NotFound } },
            )
            .run { applicationContext ->
                check(applicationContext.startupFailure != null) {
                    "partial runtime delivery configuration must fail startup"
                }
            }
    }

    @Test
    fun `recovery port가 모두 준비되면 DB 시각을 사용하는 scheduler를 구성한다`() {
        val database = database("auto_reminder_recovery", version = "21")
        context(database, withKey = true)
            .withBean(
                "reminderRecoverySource",
                ReminderRecoverySource::class.java,
                { ReminderRecoverySource { _, _ -> emptyList() } },
            )
            .withBean(
                "reminderRecoveryMaterializer",
                ReminderRecoveryMaterializer::class.java,
                {
                    object : ReminderRecoveryMaterializer {
                        override suspend fun enqueue(
                            candidate: ReminderRecoveryCandidate,
                        ): ReminderRecoveryMaterializationResult =
                            ReminderRecoveryMaterializationResult.ENQUEUED

                        override suspend fun suppressMissed(
                            candidate: ReminderRecoveryCandidate,
                        ): ReminderRecoveryMaterializationResult =
                            ReminderRecoveryMaterializationResult.SUPPRESSED
                    }
                },
            )
            .run { applicationContext ->
                applicationContext.startupFailure shouldBeEqualTo null
                applicationContext.getBeansOfType(NotificationReminderRecoveryScanner::class.java).size shouldBeEqualTo 1
                applicationContext.getBeansOfType(AppointmentReminderScheduler::class.java).size shouldBeEqualTo 1
                applicationContext.containsBean("notificationReminderSchedulingRunner") shouldBeEqualTo true
                runBlocking {
                    applicationContext.getBean(AppointmentReminderScheduler::class.java).triggerOnce()
                } shouldBeEqualTo ReminderRecoveryScanResult(0, 0, 0)
        }
    }

    @Test
    fun `Redis connection과 meter registry가 있으면 reminder leader는 instrumented elector로 구성된다`() {
        val database = database("auto_reminder_leader", version = "21")
        val connection = mockk<StatefulRedisConnection<String, String>>(relaxed = true)
        context(database, withKey = true)
            .withBean("statefulRedisConnection", StatefulRedisConnection::class.java, { connection })
            .withBean("meterRegistry", SimpleMeterRegistry::class.java, ::SimpleMeterRegistry)
            .withBean(
                "reminderRecoverySource",
                ReminderRecoverySource::class.java,
                { ReminderRecoverySource { _, _ -> emptyList() } },
            )
            .withBean(
                "reminderRecoveryMaterializer",
                ReminderRecoveryMaterializer::class.java,
                {
                    object : ReminderRecoveryMaterializer {
                        override suspend fun enqueue(candidate: ReminderRecoveryCandidate) =
                            ReminderRecoveryMaterializationResult.ENQUEUED

                        override suspend fun suppressMissed(candidate: ReminderRecoveryCandidate) =
                            ReminderRecoveryMaterializationResult.SUPPRESSED
                    }
                },
            )
            .run { applicationContext ->
                applicationContext.startupFailure shouldBeEqualTo null
                applicationContext.getBeansOfType(LeaderGroupElector::class.java).size shouldBeEqualTo 1
                applicationContext.getBean(LeaderGroupElector::class.java)::class shouldBeEqualTo InstrumentedLeaderGroupElector::class
            }
    }

    @Test
    fun `Redis connection만 있고 meter registry가 없으면 raw leader elector를 유지한다`() {
        val database = database("auto_reminder_leader_without_meter", version = "21")
        val connection = mockk<StatefulRedisConnection<String, String>>(relaxed = true)
        context(database, withKey = true)
            .withBean("statefulRedisConnection", StatefulRedisConnection::class.java, { connection })
            .run { applicationContext ->
                applicationContext.startupFailure shouldBeEqualTo null
                applicationContext.getBean(LeaderGroupElector::class.java)::class shouldBeEqualTo LettuceLeaderGroupElector::class
            }
    }

    @Test
    fun `host가 LeaderGroupElector를 제공하면 notification auto configuration은 자체 elector를 만들지 않는다`() {
        val database = database("auto_reminder_host_leader", version = "21")
        val connection = mockk<StatefulRedisConnection<String, String>>(relaxed = true)
        val hostElector = mockk<LeaderGroupElector>(relaxed = true)
        context(database, withKey = true)
            .withBean("statefulRedisConnection", StatefulRedisConnection::class.java, { connection })
            .withBean("hostLeaderGroupElector", LeaderGroupElector::class.java, { hostElector })
            .run { applicationContext ->
                applicationContext.startupFailure shouldBeEqualTo null
                applicationContext.getBeansOfType(LeaderGroupElector::class.java).size shouldBeEqualTo 1
                applicationContext.getBean(LeaderGroupElector::class.java) shouldBeEqualTo hostElector
            }
    }

    @Test
    fun `Redis와 Lettuce classpath가 없으면 leader auto configuration을 건너뛴다`() {
        ApplicationContextRunner()
            .withClassLoader(FilteredClassLoader(RedisClient::class.java, StatefulRedisConnection::class.java))
            .withConfiguration(AutoConfigurations.of(NotificationAutoConfiguration::class.java))
            .run { applicationContext ->
                applicationContext.startupFailure shouldBeEqualTo null
                applicationContext.getBeansOfType(LeaderGroupElector::class.java).isEmpty() shouldBeEqualTo true
            }
    }

    @Test
    fun `worker가 비활성화되면 reminder recovery background path를 구성하지 않는다`() {
        val database = database("auto_reminder_recovery_disabled", version = "21")
        context(database, withKey = true)
            .withPropertyValues("clinic.notification.worker.enabled=false")
            .withBean(
                "reminderRecoverySource",
                ReminderRecoverySource::class.java,
                { ReminderRecoverySource { _, _ -> emptyList() } },
            )
            .withBean(
                "reminderRecoveryMaterializer",
                ReminderRecoveryMaterializer::class.java,
                {
                    object : ReminderRecoveryMaterializer {
                        override suspend fun enqueue(candidate: ReminderRecoveryCandidate) =
                            ReminderRecoveryMaterializationResult.ENQUEUED

                        override suspend fun suppressMissed(candidate: ReminderRecoveryCandidate) =
                            ReminderRecoveryMaterializationResult.SUPPRESSED
                    }
                },
            )
            .run { applicationContext ->
                applicationContext.startupFailure shouldBeEqualTo null
                applicationContext.getBeansOfType(NotificationReminderRecoveryScanner::class.java).size shouldBeEqualTo 0
                applicationContext.getBeansOfType(AppointmentReminderScheduler::class.java).size shouldBeEqualTo 0
                applicationContext.getBeansOfType(NotificationReminderSchedulingRunner::class.java).size shouldBeEqualTo 0
            }
    }

    @Test
    fun `운영 관측 port가 준비되면 metric health alert status 서비스를 구성한다`() {
        val database = database("auto_observability", version = "21")
        context(database, withKey = true)
            .withBean("meterRegistry", SimpleMeterRegistry::class.java, ::SimpleMeterRegistry)
            .withBean(
                "notificationOutboxObservationStore",
                NotificationOutboxObservationStore::class.java,
                {
                    NotificationOutboxObservationStore {
                        NotificationOutboxObservationSnapshot(0, null)
                    }
                },
            )
            .withBean(
                "notificationOutboxReadinessSource",
                NotificationOutboxReadinessSource::class.java,
                {
                    NotificationOutboxReadinessSource {
                        NotificationOutboxReadinessSnapshot.up()
                    }
                },
            )
            .withBean(
                "notificationOutboxLivenessSource",
                NotificationOutboxLivenessSource::class.java,
                {
                    NotificationOutboxLivenessSource {
                        NotificationOutboxLivenessSnapshot()
                    }
                },
            )
            .withBean(
                "notificationStatusQueryStore",
                NotificationStatusQueryStore::class.java,
                { NotificationStatusQueryStore { null } },
            )
            .run { applicationContext ->
                applicationContext.startupFailure shouldBeEqualTo null
                applicationContext.getBeansOfType(NotificationOutboxMetrics::class.java).size shouldBeEqualTo 1
                applicationContext.getBeansOfType(NotificationObservationSchedulingRunner::class.java).size shouldBeEqualTo 1
                applicationContext.getBeansOfType(NotificationOutboxHealthIndicator::class.java).size shouldBeEqualTo 1
                applicationContext.getBeansOfType(NotificationOutboxAlertPolicy::class.java).size shouldBeEqualTo 1
                applicationContext.getBeansOfType(NotificationStatusQueryService::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `leader health는 기본 비활성이고 명시적으로 켠 경우에만 monitor를 구성한다`() {
        val disabledDatabase = database("auto_leader_health_disabled", version = "21")
        context(disabledDatabase, withKey = true)
            .withBean("leaderElector", LeaderGroupElector::class.java, { mockk(relaxed = true) })
            .run { applicationContext ->
                applicationContext.startupFailure shouldBeEqualTo null
                applicationContext.getBeansOfType(NotificationLeaderHealthMonitor::class.java).size shouldBeEqualTo 0
            }

        val enabledDatabase = database("auto_leader_health_enabled", version = "21")
        context(enabledDatabase, withKey = true)
            .withPropertyValues("clinic.notification.leader-health.enabled=true")
            .withBean("leaderElector", LeaderGroupElector::class.java, { mockk(relaxed = true) })
            .run { applicationContext ->
                applicationContext.startupFailure shouldBeEqualTo null
                applicationContext.getBeansOfType(NotificationLeaderHealthMonitor::class.java).size shouldBeEqualTo 1
                applicationContext.getBeansOfType(NotificationLeaderHealthSource::class.java).size shouldBeEqualTo 1
            }
    }

    private fun context(
        database: Database,
        withKey: Boolean,
    ): ApplicationContextRunner {
        var runner = ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NotificationAutoConfiguration::class.java))
            .withPropertyValues("clinic.notification.worker.concurrency-mode=LOCAL")
            .withBean("database", Database::class.java, { database })
            .withBean("appointmentRepository", AppointmentRepository::class.java, { AppointmentRepository() })
        if (withKey) {
            runner = runner.withPropertyValues(
                "clinic.notification.crypto.active.key-id=active-2099",
                "clinic.notification.crypto.active.secret-reference=vault:/clinic/notification/active",
                "clinic.notification.crypto.active.activated-at=2026-01-01T00:00:00Z",
                "clinic.notification.crypto.active.expires-at=2099-01-01T00:00:00Z",
            )
        }
        return runner
    }

    private fun database(
        name: String,
        version: String,
    ): Database =
        Database.connect(
            "jdbc:h2:mem:${name}_${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        ).also { database ->
            transaction(database) {
                SchemaUtils.createMissingTablesAndColumns(
                    TenantGroups,
                    Clinics,
                    AppointmentEventLogs,
                    NotificationOutboxEvents,
                    NotificationDeliveryAttempts,
                    FlywaySchemaHistory,
                )
                FlywaySchemaHistory.insert {
                    it[installedRank] = 1
                    it[FlywaySchemaHistory.version] = version
                    it[success] = true
                }
            }
        }

    private fun providerRequest(): NotificationProviderRequest =
        NotificationProviderRequest(
            channel = NotificationChannelType.SMS,
            destination = "+821012345678",
            idempotencyKey = NotificationProviderIdempotencyKey("hmac-v1.${"A".repeat(43)}"),
            templateKey = NotificationTemplateKey("appointment.confirmed"),
            templateVersion = NotificationTemplateVersion(1),
            rendered = RenderedNotificationTemplate(
                title = null,
                textBody = "confirmed",
                htmlBody = null,
            ),
        )
}
