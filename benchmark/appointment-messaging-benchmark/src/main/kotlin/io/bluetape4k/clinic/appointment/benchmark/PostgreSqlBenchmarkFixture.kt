package io.bluetape4k.clinic.appointment.benchmark

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.clinic.appointment.messaging.AppointmentConsumerInboxStore
import io.bluetape4k.clinic.appointment.messaging.AppointmentConsumerIdentity
import io.bluetape4k.clinic.appointment.messaging.AppointmentConsumerProvenance
import io.bluetape4k.clinic.appointment.messaging.AppointmentEventId
import io.bluetape4k.clinic.appointment.messaging.AppointmentLogicalConsumerId
import io.bluetape4k.clinic.appointment.messaging.AppointmentLogicalStreamId
import io.bluetape4k.clinic.appointment.messaging.AppointmentTopic
import io.bluetape4k.clinic.appointment.messaging.JdbcAppointmentConsumerInboxStore
import io.bluetape4k.clinic.appointment.messaging.JdbcAppointmentOutboxStore
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import java.sql.DriverManager
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * benchmark가 사용하는 격리 PostgreSQL schema와 실제 production outbox store를 소유합니다.
 */
class PostgreSqlBenchmarkFixture {

    private val contentionExecutor = Executors.newFixedThreadPool(2)
    private val contentionSequence = AtomicLong()

    lateinit var dataSource: HikariDataSource
        private set

    lateinit var store: JdbcAppointmentOutboxStore
        private set

    lateinit var consumerInboxStore: AppointmentConsumerInboxStore
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

        val database = Database.connect(dataSource)
        seedSchema(dataSource)
        store = JdbcAppointmentOutboxStore(maxClinicBatch = 4)
        consumerInboxStore = JdbcAppointmentConsumerInboxStore(database, maxAttempts = 8)
    }

    fun close() {
        contentionExecutor.shutdownNow()
        contentionExecutor.awaitTermination(5, TimeUnit.SECONDS)
        if (::dataSource.isInitialized) {
            dataSource.close()
        }
    }

    fun measureDuplicateInsertContention(): Long {
        val eventId = "benchmark-contention-${contentionSequence.incrementAndGet()}"
        val barrier = CyclicBarrier(2)
        val futures = (0 until 2).map {
            contentionExecutor.submit<Long> {
                barrier.await(5, TimeUnit.SECONDS)
                val startedAt = System.nanoTime()
                consumerInboxStore.begin(
                    identity = DUPLICATE_CONSUMER_IDENTITY,
                    eventId = AppointmentEventId(eventId),
                    provenance = DUPLICATE_PROVENANCE.copy(offset = contentionSequence.get()),
                )
                System.nanoTime() - startedAt
            }
        }
        val samples = futures.map { it.get(30, TimeUnit.SECONDS) }
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                DELETE FROM scheduling_appointment_consumer_inbox
                WHERE logical_consumer_id = ? AND logical_stream_id = ? AND event_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, DUPLICATE_CONSUMER_IDENTITY.consumerId.value)
                statement.setString(2, DUPLICATE_CONSUMER_IDENTITY.streamId.value)
                statement.setString(3, eventId)
                statement.executeUpdate()
            }
            connection.commit()
        }
        return samples.maxOrNull() ?: error("contention sample did not complete")
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

    fun seedProcessedConsumerRows(rowCount: Int) {
        require(rowCount in 1..100_000) { "rowCount must be bounded" }
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.prepareStatement(
                """
                INSERT INTO scheduling_appointment_consumer_inbox(
                    logical_consumer_id, logical_stream_id, event_id, topic,
                    partition_number, offset_value, schema_version, tenant_group_id, clinic_id,
                    payload_sha256, status, attempt_count, received_at, processed_at
                )
                SELECT 'statistics', 'appointment-events', 'benchmark-cleanup-' || series.position::TEXT,
                       'appointment.events.v1', 0, series.position, 1, ?, ?,
                       repeat('a', 64), 'PROCESSED', 1, CURRENT_TIMESTAMP - INTERVAL '1 hour',
                       CURRENT_TIMESTAMP - INTERVAL '1 hour'
                FROM generate_series(0, ? - 1) AS series(position)
                ON CONFLICT (logical_consumer_id, logical_stream_id, event_id) DO NOTHING
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, TENANT_ID)
                statement.setLong(2, CLINIC_ID)
                statement.setInt(3, rowCount)
                statement.executeUpdate()
            }
            connection.commit()
        }
    }

    fun seedDuplicateConsumerRow() {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.prepareStatement(
                """
                INSERT INTO scheduling_appointment_consumer_inbox(
                    logical_consumer_id, logical_stream_id, event_id, topic,
                    partition_number, offset_value, schema_version, tenant_group_id, clinic_id,
                    payload_sha256, status, attempt_count, received_at, processed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP - INTERVAL '1 hour', CURRENT_TIMESTAMP)
                ON CONFLICT (logical_consumer_id, logical_stream_id, event_id) DO NOTHING
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, DUPLICATE_CONSUMER_IDENTITY.consumerId.value)
                statement.setString(2, DUPLICATE_CONSUMER_IDENTITY.streamId.value)
                statement.setString(3, DUPLICATE_EVENT_ID.value)
                statement.setString(4, "appointment.events.v1")
                statement.setInt(5, 0)
                statement.setLong(6, 1)
                statement.setInt(7, 1)
                statement.setLong(8, TENANT_ID)
                statement.setLong(9, CLINIC_ID)
                statement.setString(10, "a".repeat(64))
                statement.setString(11, "PROCESSED")
                statement.setInt(12, 1)
                statement.executeUpdate()
            }
            connection.commit()
        }
    }

    companion object {
        const val TENANT_ID = 1L
        const val CLINIC_ID = 31L
        const val ROW_COUNT = 20_000
        const val FIRST_APPOINTMENT_ID = 1_000_000L
        const val SCHEMA = "appointment_messaging_benchmark"

        val DUPLICATE_CONSUMER_IDENTITY = AppointmentConsumerIdentity(
            AppointmentLogicalConsumerId("statistics"),
            AppointmentLogicalStreamId("appointment-events"),
        )
        val DUPLICATE_EVENT_ID = AppointmentEventId("benchmark-consumer-duplicate")
        val DUPLICATE_PROVENANCE = AppointmentConsumerProvenance(
            topic = AppointmentTopic("appointment.events.v1"),
            partition = 0,
            offset = 1,
            schemaVersion = 1,
            tenantGroupId = TENANT_ID,
            clinicId = CLINIC_ID,
            payloadSha256 = "a".repeat(64),
        )
    }

    private fun String.withCurrentSchema(schema: String): String =
        "$this${if (contains('?')) '&' else '?'}currentSchema=$schema"
}
