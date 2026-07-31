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
