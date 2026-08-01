package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.assertions.shouldBeEqualTo
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
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.util.function.Supplier

/** 실제 API 조립에서 reminder recovery port와 주기 runner가 함께 활성화되는지 검증합니다. */
internal class NotificationReminderRecoveryWiringTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(NotificationAutoConfiguration::class.java))
        .withUserConfiguration(ServiceConfig::class.java)
        .withBean("meterRegistry", MeterRegistry::class.java, Supplier { SimpleMeterRegistry() })
        .withBean("database", Database::class.java, Supplier {
            Database.connect(
                url = "jdbc:h2:mem:reminder_wiring_${System.nanoTime()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
                driver = "org.h2.Driver",
            )
        })
        .withBean("notificationOutboxHasher", NotificationOutboxHasher::class.java, Supplier {
            DefaultNotificationOutboxHasher(
                StaticNotificationOutboxKeyRing(
                    active = NotificationHmacKey("wiring-test", ByteArray(32) { 3 }),
                    previous = null,
                )
            )
        })

    @Test
    fun `HMAC과 DB가 준비되면 복구 저장소와 주기 runner를 모두 구성한다`() {
        contextRunner.run { context ->
            context.startupFailure shouldBeEqualTo null
            context.getBeansOfType(JdbcAppointmentReminderRecoveryStore::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(ReminderRecoverySource::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(ReminderRecoveryMaterializer::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(NotificationReminderSchedulingRunner::class.java).size shouldBeEqualTo 1
        }
    }
}
