package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.messaging.AppointmentMessagingAutoConfiguration
import io.bluetape4k.clinic.appointment.messaging.AppointmentOutboxWriter
import io.bluetape4k.clinic.appointment.messaging.DefaultAppointmentOutboxWriter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.util.function.Supplier

/** API 연결은 메시징 자동 구성 writer를 사용하고 데이터베이스 초기화 순서를 유지해야 한다. */
internal class AppointmentMessagingAutoConfigurationWiringTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AppointmentMessagingAutoConfiguration::class.java))
        .withUserConfiguration(ServiceConfig::class.java)
        .withBean("meterRegistry", MeterRegistry::class.java, Supplier { SimpleMeterRegistry() })

    @Test
    fun `messaging auto configuration owns the writer when ServiceConfig is present`() {
        contextRunner.run { context ->
            context.startupFailure shouldBeEqualTo null
            context.getBeansOfType(AppointmentOutboxWriter::class.java).size shouldBeEqualTo 1
            context.getBean(AppointmentOutboxWriter::class.java)::class shouldBeEqualTo
                DefaultAppointmentOutboxWriter::class
        }
    }

    @Test
    fun `auto configured writer waits for database initialization`() {
        val writerMethod = AppointmentMessagingAutoConfiguration::class.java.declaredMethods
            .single { it.name == "appointmentOutboxWriter" }

        writerMethod.isAnnotationPresent(DependsOnDatabaseInitialization::class.java).shouldBeTrue()
    }

    @Test
    fun `startup validator waits for database initialization`() {
        val validatorMethod = AppointmentMessagingAutoConfiguration::class.java.declaredMethods
            .single { it.name == "appointmentMessagingStartupValidator" }

        validatorMethod.isAnnotationPresent(DependsOnDatabaseInitialization::class.java).shouldBeTrue()
    }
}
