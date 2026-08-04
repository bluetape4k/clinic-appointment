package io.bluetape4k.clinic.appointment.api.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.api.test.API_INTEGRATION_RESOURCE
import io.bluetape4k.clinic.appointment.api.notification.JdbcAppointmentReminderRecoveryStore
import io.bluetape4k.clinic.appointment.event.notification.DefaultNotificationOutboxHasher
import io.bluetape4k.clinic.appointment.event.notification.NotificationHmacKey
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxHasher
import io.bluetape4k.clinic.appointment.event.notification.StaticNotificationOutboxKeyRing
import io.bluetape4k.clinic.appointment.notification.NotificationAutoConfiguration
import io.bluetape4k.clinic.appointment.notification.NotificationReminderSchedulingRunner
import io.bluetape4k.clinic.appointment.notification.ReminderRecoveryMaterializer
import io.bluetape4k.clinic.appointment.notification.ReminderRecoverySource
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource
import java.util.function.Supplier

/** 실제 API 조립에서 reminder recovery port와 주기 runner가 함께 활성화되는지 검증합니다. */
@ResourceLock(value = API_INTEGRATION_RESOURCE, mode = ResourceAccessMode.READ_WRITE)
internal class NotificationReminderRecoveryWiringTest {

    private var lastDataSource: HikariDataSource? = null
    private var lastDatabase: Database? = null

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(NotificationAutoConfiguration::class.java))
        .withUserConfiguration(ServiceConfig::class.java, NotificationDatabaseTestConfiguration::class.java)
        .withBean("meterRegistry", MeterRegistry::class.java, Supplier { SimpleMeterRegistry() })
        .withBean("dataSource", DataSource::class.java, Supplier {
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = "jdbc:h2:mem:reminder_wiring_${System.nanoTime()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
                    driverClassName = "org.h2.Driver"
                    username = "sa"
                },
            ).also { dataSource ->
                seedMarker(dataSource)
                lastDataSource = dataSource
            }
        })
        .withBean("notificationOutboxHasher", NotificationOutboxHasher::class.java, Supplier {
            DefaultNotificationOutboxHasher(
                StaticNotificationOutboxKeyRing(
                    active = NotificationHmacKey("wiring-test", ByteArray(32) { 3 }),
                    previous = null,
                )
            )
        })

    @AfterEach
    fun dataSourceIsClosedBySpringContext() {
        lastDataSource?.isClosed?.shouldBeEqualTo(true)
        lastDatabase?.let { database ->
            val unregistered = try {
                TransactionManager.managerFor(database)
                false
            } catch (_: IllegalStateException) {
                true
            }
            unregistered.shouldBeTrue()
        }
    }

    @Test
    fun `HMAC과 DB가 준비되면 복구 저장소와 주기 runner를 모두 구성한다`() {
        contextRunner.run { context ->
            context.startupFailure shouldBeEqualTo null
            val database = context.getBean(Database::class.java)
            lastDatabase = database
            context.getBeansOfType(ExposedDatabaseLifecycle::class.java).size shouldBeEqualTo 1
            transaction(database) {
                exec("SELECT marker_value FROM datasource_marker") { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            } shouldBeEqualTo 223
            context.getBeansOfType(JdbcAppointmentReminderRecoveryStore::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(ReminderRecoverySource::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(ReminderRecoveryMaterializer::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(NotificationReminderSchedulingRunner::class.java).size shouldBeEqualTo 1
        }
    }

    private fun seedMarker(dataSource: HikariDataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE datasource_marker (marker_value INT NOT NULL)")
                statement.execute("INSERT INTO datasource_marker(marker_value) VALUES (223)")
            }
        }
    }
}

@Configuration(proxyBeanMethods = false)
private class NotificationDatabaseTestConfiguration {
    @Bean(name = ["notificationTestDatabase"])
    fun database(dataSource: DataSource): Database = ExposedDatabaseFactory.connect(dataSource)

    @Bean(name = ["notificationTestDatabaseLifecycle"])
    fun databaseLifecycle(database: Database): ExposedDatabaseLifecycle = ExposedDatabaseLifecycle(database)
}
