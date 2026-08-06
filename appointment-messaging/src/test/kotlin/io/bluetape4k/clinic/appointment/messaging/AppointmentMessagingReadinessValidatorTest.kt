package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.assertFailsWith
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Test

class AppointmentMessagingReadinessValidatorTest {
    @Test
    fun `V22 metadata and serializer self check make readiness eligible`() {
        val dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:appointment_readiness_${System.nanoTime()};DB_CLOSE_DELAY=-1")
        }
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

        val probe = AppointmentMessagingReadinessProbe(brokerAvailable = true)
        AppointmentMessagingReadinessValidator(AppointmentEventEnvelopeCodec(), dataSource).validate(probe)

        probe.snapshot().schemaValid.shouldBeTrue()
        probe.snapshot().serializerValid.shouldBeTrue()
        probe.snapshot().ready.shouldBeTrue()
    }

    @Test
    fun `missing V22 metadata fails readiness closed`() {
        val dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:appointment_readiness_missing_${System.nanoTime()};DB_CLOSE_DELAY=-1")
        }
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE scheduling_outbox_events (id BIGINT)")
            }
        }

        val probe = AppointmentMessagingReadinessProbe(brokerAvailable = true)
        AppointmentMessagingReadinessValidator(AppointmentEventEnvelopeCodec(), dataSource).validate(probe)

        probe.snapshot().schemaValid.shouldBeFalse()
        probe.snapshot().ready.shouldBeFalse()
    }

    @Test
    fun `consumer readiness fails closed when V23 contracts are unavailable`() {
        val dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:appointment_readiness_consumer_missing_${System.nanoTime()};DB_CLOSE_DELAY=-1")
        }
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
                statement.execute("CREATE INDEX idx_outbox_appointment_ready ON scheduling_outbox_events(id)")
                statement.execute("CREATE INDEX idx_outbox_appointment_lease_recovery ON scheduling_outbox_events(id)")
            }
        }

        val probe = AppointmentMessagingReadinessProbe(brokerAvailable = true)
        AppointmentMessagingReadinessValidator(
            codec = AppointmentEventEnvelopeCodec(),
            dataSource = dataSource,
            requireConsumerSchema = true,
        ).validate(probe)

        probe.snapshot().schemaValid.shouldBeFalse()
        probe.snapshot().ready.shouldBeFalse()
    }

    @Test
    fun `startup validator fails fast when V22 metadata is unavailable`() {
        val dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:appointment_readiness_startup_missing_${System.nanoTime()};DB_CLOSE_DELAY=-1")
        }
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE scheduling_outbox_events (id BIGINT)")
            }
        }

        val probe = AppointmentMessagingReadinessProbe(brokerAvailable = true)
        val validator = AppointmentMessagingStartupValidator(
            properties = AppointmentMessagingProperties(),
            readiness = probe,
            validator = AppointmentMessagingReadinessValidator(AppointmentEventEnvelopeCodec(), dataSource),
        )

        assertFailsWith<IllegalStateException> { validator.afterSingletonsInstantiated() }
    }

    @Test
    fun `readiness does not borrow V22 metadata from another schema`() {
        val dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:appointment_readiness_schema_scope_${System.nanoTime()};DB_CLOSE_DELAY=-1")
        }
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE scheduling_outbox_events (id BIGINT)")
                statement.execute("CREATE SCHEMA other")
                statement.execute(
                    """
                    CREATE TABLE other.scheduling_outbox_events (
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
                    "CREATE INDEX other.idx_outbox_appointment_ready ON other.scheduling_outbox_events(id)",
                )
                statement.execute(
                    "CREATE INDEX other.idx_outbox_appointment_lease_recovery ON other.scheduling_outbox_events(id)",
                )
            }
        }

        val probe = AppointmentMessagingReadinessProbe(brokerAvailable = true)
        AppointmentMessagingReadinessValidator(AppointmentEventEnvelopeCodec(), dataSource).validate(probe)

        probe.snapshot().schemaValid.shouldBeFalse()
    }
}
