package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import org.h2.jdbcx.JdbcDataSource
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.health.contributor.Status
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Supplier
import javax.sql.DataSource

/** messaging prerequisite와 writer/relay readiness의 context wiring을 고정한다. */
internal class AppointmentMessagingAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AppointmentMessagingAutoConfiguration::class.java))

    @Test
    fun `missing V22 prerequisite fails before writer construction`() {
        val writerConstructed = AtomicBoolean(false)
        val dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:appointment_auto_config_prerequisite_${System.nanoTime()};DB_CLOSE_DELAY=-1")
        }

        contextRunner
            .withBean("dataSource", DataSource::class.java, Supplier { dataSource })
            .withBean(
                "writerConstructionTracker",
                BeanPostProcessor::class.java,
                Supplier {
                    object : BeanPostProcessor {
                        override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
                            if (bean is DefaultAppointmentOutboxWriter) writerConstructed.set(true)
                            return bean
                        }
                    }
                },
            )
            .run { context ->
                context.startupFailure.shouldNotBeNull()
                writerConstructed.get().shouldBeFalse()
            }
    }

    @Test
    fun `valid schema with broker outage keeps writer and marks readiness out of service`() {
        val dataSource = validDataSource()
        createV22Metadata(dataSource)

        contextRunner
            .withBean("dataSource", DataSource::class.java, Supplier { dataSource })
            .run { context ->
                context.startupFailure shouldBeEqualTo null
                context.getBean(AppointmentOutboxWriter::class.java)::class shouldBeEqualTo
                    DefaultAppointmentOutboxWriter::class
                context.getBean(AppointmentMessagingReadinessProbe::class.java)
                    .snapshot().brokerAvailable.shouldBeFalse()

                val health = (context.getBean("appointmentMessagingHealthIndicator") as AppointmentMessagingHealthIndicator)
                    .health()
                health.status shouldBeEqualTo Status.OUT_OF_SERVICE
                health.details["brokerAvailable"] shouldBeEqualTo false
                health.details["schemaValid"] shouldBeEqualTo true
                health.details["registryValid"] shouldBeEqualTo true
                health.details["serializerValid"] shouldBeEqualTo true
                (health.status != Status.DOWN).shouldBeTrue()
            }
    }

    @Test
    fun `schema registry binding creates authenticated http registry without contacting endpoint`() {
        contextRunner
            .withPropertyValues(
                "appointment.messaging.enabled=false",
                "appointment.messaging.schema-registry.enabled=true",
                "appointment.messaging.schema-registry.base-uri=https://registry.example.com",
                "appointment.messaging.schema-registry.subject=appointment-events-value",
                "appointment.messaging.schema-registry.credential-reference=secret/schema-registry",
            )
            .withBean(
                AppointmentSchemaRegistryCredentialResolver::class.java,
                Supplier {
                    AppointmentSchemaRegistryCredentialResolver {
                        AppointmentSchemaRegistryBasicCredentials("user", "password")
                    }
                },
            )
            .run { context ->
                context.startupFailure shouldBeEqualTo null
                context.getBean(AppointmentSchemaRegistry::class.java)::class shouldBeEqualTo
                    HttpAppointmentSchemaRegistry::class
            }
    }

    private fun validDataSource(): JdbcDataSource = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:appointment_auto_config_broker_outage_${System.nanoTime()};DB_CLOSE_DELAY=-1")
    }

    private fun createV22Metadata(dataSource: DataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE scheduling_outbox_events (
                        id BIGINT,
                        occurred_at TIMESTAMP,
                        topic VARCHAR(249),
                        partition_key VARCHAR(512),
                        lease_owner VARCHAR(160),
                        lease_token VARCHAR(128),
                        lease_until TIMESTAMP,
                        last_failure_code VARCHAR(64),
                        last_failure_at TIMESTAMP
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    "CREATE INDEX idx_outbox_appointment_ready ON scheduling_outbox_events(id)",
                )
                statement.execute(
                    "CREATE INDEX idx_outbox_appointment_lease_recovery ON scheduling_outbox_events(id)",
                )
            }
        }
    }
}
