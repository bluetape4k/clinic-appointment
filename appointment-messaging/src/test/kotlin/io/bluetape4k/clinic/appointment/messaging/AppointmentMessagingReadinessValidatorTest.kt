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
    fun `consumer readiness fails closed when V24 aggregate lock is unavailable`() {
        val dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:appointment_readiness_v24_missing_${System.nanoTime()};DB_CLOSE_DELAY=-1")
        }
        dataSource.connection.use { connection ->
            createConsumerSchema(connection, includeV24AggregateLock = false)
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
    fun `consumer readiness accepts complete V24 aggregate lock schema`() {
        val dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:appointment_readiness_v24_complete_${System.nanoTime()};DB_CLOSE_DELAY=-1")
        }
        dataSource.connection.use { connection ->
            createConsumerSchema(connection, includeV24AggregateLock = true)
        }

        val probe = AppointmentMessagingReadinessProbe(brokerAvailable = true)
        AppointmentMessagingReadinessValidator(
            codec = AppointmentEventEnvelopeCodec(),
            dataSource = dataSource,
            requireConsumerSchema = true,
        ).validate(probe)

        probe.snapshot().schemaValid.shouldBeTrue()
        probe.snapshot().serializerValid.shouldBeTrue()
        probe.snapshot().ready.shouldBeTrue()
    }

    @Test
    fun `consumer readiness fails closed when schema registry compatibility is unavailable`() {
        val dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:appointment_readiness_registry_missing_${System.nanoTime()};DB_CLOSE_DELAY=-1")
        }
        val probe = AppointmentMessagingReadinessProbe(brokerAvailable = true)
        AppointmentMessagingReadinessValidator(
            codec = AppointmentEventEnvelopeCodec(),
            dataSource = dataSource,
            requireConsumerSchema = true,
            schemaRegistry = HttpAppointmentSchemaRegistry(
                compatibilityReader = { throw IllegalStateException("registry unavailable") },
            ),
        ).validate(probe)

        probe.snapshot().registryValid.shouldBeFalse()
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

    private fun createConsumerSchema(
        connection: java.sql.Connection,
        includeV24AggregateLock: Boolean,
    ) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE scheduling_outbox_events (
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
            statement.execute("CREATE INDEX idx_outbox_appointment_ready ON scheduling_outbox_events(occurred_at)")
            statement.execute("CREATE INDEX idx_outbox_appointment_lease_recovery ON scheduling_outbox_events(lease_until)")
            statement.execute(
                """
                CREATE TABLE scheduling_appointment_consumer_inbox (
                    logical_consumer_id VARCHAR(128),
                    logical_stream_id VARCHAR(128),
                    event_id VARCHAR(128),
                    status VARCHAR(32),
                    processed_at TIMESTAMP,
                    processing_lease_until TIMESTAMP
                )
                """.trimIndent(),
            )
            statement.execute(
                "CREATE INDEX idx_appointment_consumer_inbox_status_processed " +
                    "ON scheduling_appointment_consumer_inbox(status, processed_at)",
            )
            statement.execute(
                """
                CREATE TABLE scheduling_appointment_consumer_rejected (
                    logical_consumer_id VARCHAR(128),
                    topic VARCHAR(249),
                    partition_number INTEGER,
                    offset_value BIGINT,
                    payload_sha256 VARCHAR(64)
                )
                """.trimIndent(),
            )
            statement.execute(
                "CREATE INDEX idx_appointment_consumer_rejected_created " +
                    "ON scheduling_appointment_consumer_rejected(logical_consumer_id)",
            )
            statement.execute(
                """
                CREATE TABLE scheduling_appointment_consumer_quarantine (
                    logical_consumer_id VARCHAR(128),
                    event_id VARCHAR(128),
                    failure_code VARCHAR(64),
                    payload_sha256 VARCHAR(64)
                )
                """.trimIndent(),
            )
            statement.execute(
                "CREATE INDEX idx_appointment_consumer_quarantine_created " +
                    "ON scheduling_appointment_consumer_quarantine(logical_consumer_id)",
            )
            statement.execute(
                """
                CREATE TABLE scheduling_appointment_consumer_replay_audit (
                    request_id VARCHAR(128),
                    request_hash VARCHAR(64),
                    status VARCHAR(32),
                    completed_at TIMESTAMP
                )
                """.trimIndent(),
            )
            statement.execute(
                "CREATE INDEX idx_appointment_consumer_replay_audit_scope_created " +
                    "ON scheduling_appointment_consumer_replay_audit(request_id)",
            )
            statement.execute(
                """
                CREATE TABLE scheduling_appointment_stats_projection (
                    tenant_group_id BIGINT,
                    clinic_id BIGINT,
                    event_date DATE,
                    status VARCHAR(32),
                    appointment_count BIGINT,
                    last_event_version BIGINT,
                    last_event_id VARCHAR(128)
                )
                """.trimIndent(),
            )
            statement.execute(
                "CREATE INDEX idx_appointment_stats_projection_scope_date " +
                    "ON scheduling_appointment_stats_projection(tenant_group_id, clinic_id, event_date)",
            )
            statement.execute(
                "CREATE INDEX idx_appointment_stats_projection_scope_status_date " +
                    "ON scheduling_appointment_stats_projection(tenant_group_id, clinic_id, status, event_date)",
            )
            statement.execute(
                """
                CREATE TABLE scheduling_appointment_stats_projection_events (
                    tenant_group_id BIGINT,
                    clinic_id BIGINT,
                    aggregate_id VARCHAR(128),
                    event_id VARCHAR(128),
                    event_version BIGINT
                )
                """.trimIndent(),
            )
            statement.execute(
                "CREATE INDEX idx_appointment_stats_projection_events_scope_date " +
                    "ON scheduling_appointment_stats_projection_events(tenant_group_id, clinic_id)",
            )
            if (includeV24AggregateLock) {
                statement.execute(
                    """
                    CREATE TABLE scheduling_appointment_stats_projection_aggregate_locks (
                        tenant_group_id BIGINT,
                        clinic_id BIGINT,
                        aggregate_id VARCHAR(128)
                    )
                    """.trimIndent(),
                )
            }
        }
    }
}
