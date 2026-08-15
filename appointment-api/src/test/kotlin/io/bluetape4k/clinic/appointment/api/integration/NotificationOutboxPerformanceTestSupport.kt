package io.bluetape4k.clinic.appointment.api.integration

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.configuration.FluentConfiguration
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Timestamp
import javax.sql.DataSource

internal object NotificationOutboxPerformanceTestSupport {
    const val ACTIVE_ROWS = 10_000
    const val TERMINAL_ROWS = 10_000
// 동일 clinic/appointment 행을 충분히 유지해 legacy clinic-leading index가
// V21 tenant-leading lookup index보다 유의미하게 덜 선택적이 되도록 한다.
    const val CLINIC_COUNT = 10
    const val TARGET_ACTIVE_ID = 1L
    const val TARGET_CLINIC_ID = 2L
    const val TARGET_APPOINTMENT_ID = 100_001L

    fun migrate(
        dataSource: DataSource,
        location: String,
    ) {
        flyway(dataSource, location)
            .cleanDisabled(false)
            .load()
            .apply {
                clean()
                migrate()
            }
    }

    private fun flyway(dataSource: DataSource, location: String): FluentConfiguration =
        Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .apply {
                if (location.endsWith("/postgresql")) {
                    configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
                }
            }

    fun seedBacklog(connection: Connection) {
        connection.autoCommit = false
        try {
            connection.prepareStatement(INSERT_SQL).use { statement ->
                (1L..ACTIVE_ROWS.toLong()).forEach { id ->
                    bindActive(statement, id)
                    statement.addBatch()
                    if (id % BATCH_SIZE == 0L) statement.executeBatch()
                }
                statement.executeBatch()
                (1L..TERMINAL_ROWS.toLong()).forEach { offset ->
                    bindTerminal(statement, ACTIVE_ROWS + offset)
                    statement.addBatch()
                    if (offset % BATCH_SIZE == 0L) statement.executeBatch()
                }
                statement.executeBatch()
            }
            connection.commit()
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    fun analyze(
        connection: Connection,
        dialect: Dialect,
    ) {
        connection.createStatement().use { statement ->
            when (dialect) {
                Dialect.H2 -> statement.execute("ANALYZE")
                Dialect.POSTGRESQL -> statement.execute("ANALYZE clinic_notification_outbox")
                Dialect.MYSQL -> statement.execute("ANALYZE TABLE clinic_notification_outbox")
            }
        }
    }

    private fun bindActive(statement: PreparedStatement, id: Long) {
        val status = when {
            id % 25L == 0L -> "PROCESSING"
            id % 10L == 0L -> "RETRY_WAIT"
            else -> "PENDING"
        }
        val clinicId = (id % CLINIC_COUNT) + 1L
        val tenantGroupId = if (clinicId == TARGET_CLINIC_ID) (id / CLINIC_COUNT.toLong()) % 2L + 1L else 1L
        val eventType = if (clinicId == TARGET_CLINIC_ID && tenantGroupId == 1L && id != TARGET_ACTIVE_ID) {
            "CREATED"
        } else {
            "CONFIRMED"
        }
        bindCommon(statement, id, status, clinicId, tenantGroupId, eventType)
        statement.setLong(11, if (clinicId == TARGET_CLINIC_ID) TARGET_APPOINTMENT_ID else 100_000L + id)
        statement.setString(12, "member-$id")
        statement.setString(20, "{}")
        statement.setTimestamp(25, if (status == "RETRY_WAIT") OLD_TIME else null)
        statement.setString(26, if (status == "PROCESSING") "expired-worker" else null)
        statement.setString(27, if (status == "PROCESSING") "expired-token-$id" else null)
        statement.setTimestamp(28, if (status == "PROCESSING") OLD_TIME else null)
        statement.setInt(29, if (status == "PROCESSING") 1 else 0)
        statement.setTimestamp(31, null)
    }

    private fun bindTerminal(statement: PreparedStatement, id: Long) {
        val clinicId = (id % CLINIC_COUNT) + 1L
        bindCommon(statement, id, "SENT", clinicId, tenantGroupId = 1L, eventType = "CONFIRMED")
        statement.setObject(11, null)
        statement.setString(12, null)
        statement.setString(20, null)
        statement.setTimestamp(25, null)
        statement.setString(26, null)
        statement.setString(27, null)
        statement.setTimestamp(28, null)
        statement.setInt(29, 1)
        statement.setTimestamp(31, OLD_TIME)
    }

    private fun bindCommon(
        statement: PreparedStatement,
        id: Long,
        status: String,
        clinicId: Long,
        tenantGroupId: Long,
        eventType: String,
    ) {
        statement.setLong(1, id)
        statement.setString(2, "SENDABLE")
        statement.setString(3, status)
        statement.setInt(4, 1)
        statement.setString(5, "perf-idempotency-$id")
        statement.setString(6, "perf-key")
        statement.setInt(7, 1)
        statement.setString(8, "perf-audit-$id")
        statement.setString(9, "perf-audit-key")
        statement.setLong(10, tenantGroupId)
        statement.setString(13, "DUMMY")
        statement.setString(14, eventType)
        statement.setString(15, "CONFIRMED")
        statement.setString(16, "dummy")
        statement.setString(17, "appointment-confirmed")
        statement.setInt(18, 1)
        statement.setString(19, "APPOINTMENT_CONFIRMED")
        statement.setLong(21, clinicId)
        statement.setString(22, "perf-event-$id")
        statement.setTimestamp(23, OLD_TIME)
        statement.setTimestamp(24, OLD_TIME)
        statement.setTimestamp(30, OLD_TIME)
    }

    enum class Dialect {
        H2,
        POSTGRESQL,
        MYSQL,
    }

    private const val BATCH_SIZE = 1_000L
    private val OLD_TIME = Timestamp.valueOf("2020-01-01 00:00:00")
    private const val INSERT_SQL = """
        INSERT INTO clinic_notification_outbox(
            id, row_kind, status, idempotency_key_version, idempotency_key,
            idempotency_key_id, audit_fingerprint_version, audit_fingerprint,
            audit_fingerprint_key_id, tenant_group_id, appointment_id, member_id,
            channel, event_type, notification_slot, provider_key, template_key,
            template_version, parameter_type, parameters_json, clinic_id, event_id,
            available_at, created_at, next_retry_at, lease_owner, lease_token,
            lease_until, attempt_number, updated_at, terminal_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """
}
