package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.event.notification.NotificationDeliveryAttempts
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxEvents
import io.bluetape4k.clinic.appointment.repository.AppointmentRepository
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

internal class NotificationAutoConfigurationTest {

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

        val missingKey = database("auto_missing_key", version = "14")
        context(missingKey, withKey = false).run { applicationContext ->
            applicationContext.startupFailure shouldBeEqualTo null
            val worker = applicationContext.getBean(NotificationOutboxWorker::class.java)
            runBlocking { worker.recoverExpiredOnce(10) } shouldBeEqualTo emptyList()
        }
    }

    @Test
    fun `V14 schema와 active key가 있으면 worker readiness가 UP이다`() {
        val database = database("auto_ready", version = "14")
        context(database, withKey = true).run { applicationContext ->
            applicationContext.startupFailure shouldBeEqualTo null
            applicationContext.getBean(NotificationSchemaReadiness::class.java)
                .check()
                .available shouldBeEqualTo true
            applicationContext.getBeansOfType(NotificationOutboxWorker::class.java).size shouldBeEqualTo 1
            applicationContext.getBeansOfType(NotificationOutboxDispatcher::class.java).size shouldBeEqualTo 0
            applicationContext.getBeansOfType(NotificationRetentionRunner::class.java).size shouldBeEqualTo 1
        }
    }

    @Test
    fun `실제 delivery worker가 제공된 경우에만 dispatcher를 구성한다`() {
        val database = database("auto_dispatcher", version = "14")
        context(database, withKey = true)
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
    fun `profile template provider key가 모두 준비되면 runtime delivery dispatcher를 구성한다`() {
        val database = database("auto_runtime_dispatcher", version = "14")
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
                applicationContext.getBeansOfType(NotificationOutboxDispatcher::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `runtime delivery dependency 일부만 설정하면 시작 단계에서 거절한다`() {
        val database = database("auto_partial_runtime", version = "14")
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
        val database = database("auto_reminder_recovery", version = "14")
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
                runBlocking {
                    applicationContext.getBean(AppointmentReminderScheduler::class.java).triggerOnce()
                } shouldBeEqualTo ReminderRecoveryScanResult(0, 0, 0)
            }
    }

    private fun context(
        database: Database,
        withKey: Boolean,
    ): ApplicationContextRunner {
        var runner = ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NotificationAutoConfiguration::class.java))
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
                SchemaUtils.create(NotificationOutboxEvents, NotificationDeliveryAttempts, FlywaySchemaHistory)
                FlywaySchemaHistory.insert {
                    it[installedRank] = 1
                    it[FlywaySchemaHistory.version] = version
                    it[success] = true
                }
            }
        }
}
