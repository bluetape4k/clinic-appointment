package io.bluetape4k.clinic.appointment.api.integration

import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.api.integration.NotificationOutboxPerformanceTestSupport.Dialect
import io.bluetape4k.clinic.appointment.api.test.API_INTEGRATION_RESOURCE
import io.bluetape4k.clinic.appointment.api.test.Containers
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.Driver
import java.sql.Timestamp
import javax.sql.DataSource

/** 20,000행 backlog에서 PostgreSQL과 MySQL의 알림 hot query index 사용을 검증합니다. */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@ResourceLock(value = API_INTEGRATION_RESOURCE, mode = ResourceAccessMode.READ_WRITE)
class NotificationOutboxQueryPlanTest {

    @Test
    @Order(1)
    fun `PostgreSQL 알림 hot query는 bounded index plan을 사용한다`() {
        val postgres = Containers.Postgres
        verify(
            Dialect.POSTGRESQL,
            SimpleDriverDataSource(
                driver("org.postgresql.Driver"),
                postgres.jdbcUrl,
                postgres.username ?: "test",
                postgres.password ?: "",
            ),
            "classpath:db/migration/postgresql",
        )
    }

    @Test
    @Order(2)
    fun `MySQL 알림 hot query는 bounded index plan을 사용한다`() {
        val mysql = Containers.MySql8
        verify(
            Dialect.MYSQL,
            SimpleDriverDataSource(
                driver("com.mysql.cj.jdbc.Driver"),
                mysql.jdbcUrl,
                mysql.username ?: "test",
                mysql.password ?: "",
            ),
            "classpath:db/migration/mysql",
        )
    }

    private fun verify(
        dialect: Dialect,
        dataSource: DataSource,
        migrationLocation: String,
    ) {
        NotificationOutboxPerformanceTestSupport.migrate(dataSource, migrationLocation)
        dataSource.connection.use { connection ->
            NotificationOutboxPerformanceTestSupport.seedBacklog(connection)
            NotificationOutboxPerformanceTestSupport.analyze(connection, dialect)
            val plans = linkedMapOf(
                "direct lookup" to explain(
                    connection,
                    dialect,
                    """
                    SELECT id FROM clinic_notification_outbox
                    WHERE clinic_id = ? AND appointment_id = ? AND event_type = 'CONFIRMED'
                      AND row_kind = 'SENDABLE' AND status IN ('PENDING', 'RETRY_WAIT')
                      AND available_at <= ? AND (next_retry_at IS NULL OR next_retry_at <= ?)
                    ORDER BY available_at, id LIMIT 1
                    """.trimIndent(),
                    listOf(2L, 100_001L, OLD_TIME, OLD_TIME),
                ),
                "reminder suppression" to explain(
                    connection,
                    dialect,
                    """
                    SELECT id FROM clinic_notification_outbox
                    WHERE tenant_group_id = 1 AND clinic_id = ? AND appointment_id = ?
                      AND row_kind = 'SENDABLE'
                      AND notification_slot IN ('REMINDER_24H', 'REMINDER_SAME_DAY')
                      AND status IN ('PENDING', 'RETRY_WAIT', 'PROCESSING')
                    ORDER BY id LIMIT 100
                    """.trimIndent(),
                    listOf(2L, 100_001L),
                ),
                "ready clinic cursor" to explain(
                    connection,
                    dialect,
                    """
                    SELECT DISTINCT tenant_group_id, clinic_id FROM clinic_notification_outbox
                    WHERE row_kind = 'SENDABLE' AND status IN ('PENDING', 'RETRY_WAIT')
                      AND available_at <= ? AND (next_retry_at IS NULL OR next_retry_at <= ?)
                    ORDER BY tenant_group_id, clinic_id LIMIT 100
                    """.trimIndent(),
                    listOf(OLD_TIME, OLD_TIME),
                ),
                "ready within clinic" to explain(
                    connection,
                    dialect,
                    """
                    SELECT id FROM clinic_notification_outbox
                    WHERE tenant_group_id = 1 AND clinic_id = ? AND row_kind = 'SENDABLE'
                      AND status IN ('PENDING', 'RETRY_WAIT') AND available_at <= ?
                      AND (next_retry_at IS NULL OR next_retry_at <= ?)
                    ORDER BY available_at, id LIMIT 100
                    """.trimIndent(),
                    listOf(2L, OLD_TIME, OLD_TIME),
                ),
                "lease recovery" to explain(
                    connection,
                    dialect,
                    """
                    SELECT id FROM clinic_notification_outbox
                    WHERE row_kind = 'SENDABLE' AND status = 'PROCESSING' AND lease_until < ?
                    ORDER BY lease_until, id LIMIT 100
                    """.trimIndent(),
                    listOf(OLD_TIME),
                ),
                "terminal retention" to explain(
                    connection,
                    dialect,
                    """
                    SELECT id FROM clinic_notification_outbox
                    WHERE row_kind = 'SENDABLE' AND status = 'SENT' AND terminal_at <= ?
                    ORDER BY terminal_at, id LIMIT 100
                    """.trimIndent(),
                    listOf(OLD_TIME),
                ),
                "pending oldest" to explain(
                    connection,
                    dialect,
                    """
                    SELECT available_at FROM clinic_notification_outbox
                    WHERE row_kind = 'SENDABLE' AND status IN ('PENDING', 'RETRY_WAIT')
                    ORDER BY available_at, created_at LIMIT 1
                    """.trimIndent(),
                    emptyList(),
                ),
            )

            val expectedIndexes = expectedIndexes(dialect)
            writeReport(dialect, plans)
            plans.forEach { (name, plan) ->
                expectedIndexes.getValue(name)
                    .any { chosenIndex(plan, dialect, it) }
                    .shouldBeTrue()
                (!fullTableScan(plan, dialect)).shouldBeTrue()
            }
        }
    }

    private fun expectedIndexes(dialect: Dialect): Map<String, Set<String>> {
        val common = mapOf(
            "direct lookup" to setOf("idx_notification_outbox_direct_lookup"),
            "reminder suppression" to setOf(
                "idx_notification_outbox_reminder_suppression",
                "idx_notification_outbox_direct_lookup",
            ),
            "lease recovery" to setOf("idx_notification_outbox_lease_recovery"),
            "terminal retention" to setOf("idx_notification_outbox_terminal_retention"),
            "pending oldest" to setOf("idx_notification_outbox_pending_oldest"),
        )
        val fairScheduling = when (dialect) {
            Dialect.POSTGRESQL -> mapOf(
                "ready clinic cursor" to setOf("idx_notification_outbox_ready_clinic_cursor", "idx_notification_outbox_pending_oldest"),
                "ready within clinic" to setOf("idx_notification_outbox_ready_within_clinic", "idx_notification_outbox_ready_clinic_cursor"),
            )
            Dialect.MYSQL,
            Dialect.H2,
            -> mapOf(
                "ready clinic cursor" to setOf("idx_notification_outbox_ready_clinic_cursor"),
                "ready within clinic" to setOf(
                    "idx_notification_outbox_ready_within_clinic",
                    "idx_notification_outbox_ready_clinic_cursor",
                ),
                "pending oldest" to setOf(
                    "idx_notification_outbox_pending_oldest",
                    "idx_notification_outbox_ready_clinic_cursor",
                ),
            )
        }
        return common + fairScheduling
    }

    private fun explain(
        connection: Connection,
        dialect: Dialect,
        sql: String,
        parameters: List<Any>,
    ): String {
        val prefix = if (dialect == Dialect.POSTGRESQL) "EXPLAIN (FORMAT TEXT) " else "EXPLAIN FORMAT=JSON "
        return connection.prepareStatement(prefix + sql).use { statement ->
            parameters.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { rows ->
                buildString {
                    while (rows.next()) appendLine(rows.getString(1))
                }
            }
        }
    }

    private fun fullTableScan(plan: String, dialect: Dialect): Boolean =
        when (dialect) {
            Dialect.POSTGRESQL -> plan.contains("Seq Scan", ignoreCase = true)
            Dialect.MYSQL -> plan.contains("\"access_type\": \"ALL\"", ignoreCase = true)
            Dialect.H2 -> error("H2 query plan is not used here")
        }

    private fun chosenIndex(
        plan: String,
        dialect: Dialect,
        indexName: String,
    ): Boolean =
        when (dialect) {
            Dialect.POSTGRESQL -> plan.contains(indexName, ignoreCase = true)
            Dialect.MYSQL -> Regex("\\\"key\\\"\\s*:\\s*\\\"${Regex.escape(indexName)}\\\"", RegexOption.IGNORE_CASE)
                .containsMatchIn(plan)
            Dialect.H2 -> error("H2 query plan is not used here")
        }

    private fun writeReport(
        dialect: Dialect,
        plans: Map<String, String>,
    ) {
        val target = Path.of("build/reports/performance/notification-outbox-${dialect.name.lowercase()}-explain.txt")
        Files.createDirectories(target.parent)
        Files.writeString(
            target,
            plans.entries.joinToString("\n\n") { (name, plan) -> "# $name\n$plan" },
        )
    }

    private fun driver(className: String): Driver =
        Class.forName(className).getDeclaredConstructor().newInstance() as Driver

    private companion object {
        val OLD_TIME: Timestamp = Timestamp.valueOf("2021-01-01 00:00:00")
    }
}
