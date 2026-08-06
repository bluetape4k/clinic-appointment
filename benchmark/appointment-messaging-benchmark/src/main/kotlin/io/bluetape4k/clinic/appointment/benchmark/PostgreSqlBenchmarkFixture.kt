package io.bluetape4k.clinic.appointment.benchmark

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.clinic.appointment.messaging.JdbcAppointmentOutboxStore
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import java.sql.DriverManager

/**
 * Owns the isolated PostgreSQL schema and the actual production outbox store used by the benchmark.
 */
class PostgreSqlBenchmarkFixture {

    lateinit var dataSource: HikariDataSource
        private set

    lateinit var store: JdbcAppointmentOutboxStore
        private set

    fun start() {
        val postgres = PostgreSQLServer.Launcher.postgres
        val schema = SCHEMA
        createSchema(postgres, schema)

        val jdbcUrl = postgres.jdbcUrl.withCurrentSchema(schema)
        dataSource = HikariDataSource(
            HikariConfig().apply {
                this.jdbcUrl = jdbcUrl
                username = postgres.username ?: PostgreSQLServer.USERNAME
                password = postgres.password ?: PostgreSQLServer.PASSWORD
                driverClassName = PostgreSQLServer.DRIVER_CLASS_NAME
                maximumPoolSize = 4
                minimumIdle = 1
                poolName = "appointment-messaging-benchmark"
                isAutoCommit = false
            },
        )

        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration/postgresql")
            .schemas(schema)
            .defaultSchema(schema)
            .cleanDisabled(false)
            .load()
            .also { flyway ->
                flyway.clean()
                flyway.migrate()
            }

        Database.connect(dataSource)
        seedSchema(dataSource)
        store = JdbcAppointmentOutboxStore(maxClinicBatch = 4)
    }

    fun close() {
        if (::dataSource.isInitialized) {
            dataSource.close()
        }
    }

    private fun createSchema(postgres: PostgreSQLServer, schema: String) {
        DriverManager.getConnection(
            postgres.jdbcUrl,
            postgres.username ?: PostgreSQLServer.USERNAME,
            postgres.password ?: PostgreSQLServer.PASSWORD,
        ).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE SCHEMA IF NOT EXISTS $schema")
            }
        }
    }

    private fun seedSchema(dataSource: HikariDataSource) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.prepareStatement(
                """
                INSERT INTO scheduling_clinics (id, name, tenant_group_id)
                VALUES (?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET tenant_group_id = EXCLUDED.tenant_group_id
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, CLINIC_ID)
                statement.setString(2, "benchmark-clinic")
                statement.setLong(3, TENANT_ID)
                statement.executeUpdate()
            }

            connection.prepareStatement(
                """
                WITH params AS (
                    SELECT ?::BIGINT AS first_id, ?::BIGINT AS tenant_id,
                           ?::BIGINT AS clinic_id, ?::INTEGER AS row_count
                )
                INSERT INTO scheduling_outbox_events(
                    event_id, causation_event_id, correlation_id, event_type,
                    tenant_group_id, clinic_id, plan_id, aggregate_type, aggregate_id,
                    occurred_at, topic, partition_key, schema_version, payload_json,
                    status, attempt_count, next_attempt_at, published_at
                )
                SELECT
                    'benchmark-41-' || (params.first_id + series.position)::TEXT,
                    'benchmark-causation-' || (params.first_id + series.position)::TEXT,
                    'benchmark-correlation-41',
                    CASE series.position % 4
                        WHEN 0 THEN 'AppointmentCreated'
                        WHEN 1 THEN 'AppointmentStatusChanged'
                        WHEN 2 THEN 'AppointmentCancelled'
                        ELSE 'AppointmentRescheduled'
                    END,
                    params.tenant_id,
                    params.clinic_id,
                    NULL,
                    'APPOINTMENT',
                    (params.first_id + series.position)::TEXT,
                    CURRENT_TIMESTAMP - INTERVAL '60 seconds',
                    'appointment.events.v1',
                    'clinic-' || params.clinic_id::TEXT || '-appointment-' || (params.first_id + series.position)::TEXT,
                    1,
                    '{"appointmentId":' || (params.first_id + series.position)::TEXT || ',"version":1,"status":"CONFIRMED"}',
                    'PENDING',
                    0,
                    NULL,
                    NULL
                FROM params
                CROSS JOIN LATERAL generate_series(0, params.row_count - 1) AS series(position)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, FIRST_APPOINTMENT_ID)
                statement.setLong(2, TENANT_ID)
                statement.setLong(3, CLINIC_ID)
                statement.setInt(4, ROW_COUNT)
                statement.executeUpdate()
            }
            connection.commit()
        }
    }

    private fun String.withCurrentSchema(schema: String): String =
        "$this${if (contains('?')) '&' else '?'}currentSchema=$schema"

    companion object {
        const val TENANT_ID = 1L
        const val CLINIC_ID = 31L
        const val ROW_COUNT = 20_000
        const val FIRST_APPOINTMENT_ID = 1_000_000L
        const val SCHEMA = "appointment_messaging_benchmark"
    }
}
