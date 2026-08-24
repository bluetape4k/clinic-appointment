package io.bluetape4k.clinic.appointment.api.integration

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.api.test.API_INTEGRATION_RESOURCE
import io.bluetape4k.clinic.appointment.api.test.Containers
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.Driver
import javax.sql.DataSource
import kotlin.math.round

/** PostgreSQL 실제 schema에서 catalog keyset과 offset query를 비교합니다. */
@ResourceLock(value = API_INTEGRATION_RESOURCE, mode = ResourceAccessMode.READ_WRITE)
@Execution(ExecutionMode.SAME_THREAD)
class ClinicKeysetPaginationQueryPlanTest {

    @Test
    fun `PostgreSQL catalog cursor query는 offset 없이 bounded plan을 사용한다`() {
        val postgres = Containers.Postgres
        val dataSource = SimpleDriverDataSource(
            driver("org.postgresql.Driver"),
            postgres.jdbcUrl,
            postgres.username ?: "test",
            postgres.password ?: "",
        )
        migrate(dataSource)
        dataSource.connection.use { connection ->
            seed(connection)
            analyze(connection)

            val reports = listOf(
                explainComparison(connection, "scheduling_doctors", "id, clinic_id, name"),
                explainComparison(connection, "scheduling_equipments", "id, clinic_id, name"),
                explainComparison(connection, "scheduling_treatment_types", "id, clinic_id, name"),
            )
            reports.forEach { report ->
                report.keyset.rowCount shouldBeEqualTo LIMIT
                report.offset.rowCount shouldBeEqualTo LIMIT
                (!report.keysetSql.lowercase().contains("offset")).shouldBeTrue()
                (!report.keyset.plan.lowercase().contains("offset")).shouldBeTrue()
                report.keyset.plan.lowercase().contains("limit").shouldBeTrue()
            }
            writeReport(reports)
        }
    }

    private fun migrate(dataSource: DataSource) {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration/postgresql")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
            .cleanDisabled(false)
            .load()
            .apply {
                clean()
                migrate()
            }
    }

    private fun seed(connection: Connection) {
        connection.autoCommit = false
        try {
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    INSERT INTO scheduling_tenant_groups(id, tenant_code, display_name, active)
                    VALUES ($TENANT_ID, 'issue-312-explain', 'Issue 312 Explain', true)
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO scheduling_clinics(id, tenant_group_id, name)
                    VALUES ($CLINIC_ID, $TENANT_ID, 'Issue 312 Explain Clinic')
                    """.trimIndent(),
                )
            }
            insertRows(connection, "scheduling_doctors") { statement, id ->
                statement.setLong(1, id)
                statement.setLong(2, CLINIC_ID)
                statement.setString(3, "Explain Doctor $id")
            }
            insertRows(connection, "scheduling_equipments") { statement, id ->
                statement.setLong(1, id)
                statement.setLong(2, CLINIC_ID)
                statement.setString(3, "Explain Equipment $id")
            }
            insertRows(connection, "scheduling_treatment_types") { statement, id ->
                statement.setLong(1, id)
                statement.setLong(2, CLINIC_ID)
                statement.setString(3, "Explain Treatment $id")
            }
            connection.commit()
        } catch (failure: Exception) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    private fun insertRows(
        connection: Connection,
        table: String,
        bind: (statement: java.sql.PreparedStatement, id: Long) -> Unit,
    ) {
        val sql = when (table) {
            "scheduling_doctors" ->
                "INSERT INTO scheduling_doctors(id, clinic_id, name, provider_type) VALUES (?, ?, ?, 'DOCTOR')"
            "scheduling_equipments" ->
                "INSERT INTO scheduling_equipments(id, clinic_id, name, usage_duration_minutes, quantity) VALUES (?, ?, ?, 30, 1)"
            "scheduling_treatment_types" ->
                "INSERT INTO scheduling_treatment_types(id, clinic_id, name, category, default_duration_minutes, required_provider_type, requires_equipment) VALUES (?, ?, ?, 'TREATMENT', 30, 'DOCTOR', false)"
            else -> error("unsupported fixture table: $table")
        }
        connection.prepareStatement(sql).use { statement ->
            for (offset in 1..FIXTURE_ROWS) {
                bind(statement, ID_BASE + offset)
                statement.addBatch()
                if (offset % BATCH_SIZE == 0) statement.executeBatch()
            }
            statement.executeBatch()
        }
    }

    private fun analyze(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("ANALYZE scheduling_doctors")
            statement.execute("ANALYZE scheduling_equipments")
            statement.execute("ANALYZE scheduling_treatment_types")
            statement.execute("ANALYZE scheduling_clinics")
        }
    }

    private fun explainComparison(
        connection: Connection,
        table: String,
        columns: String,
    ): QueryComparison {
        val keysetSql = """
            SELECT $columns
              FROM $table catalog
             WHERE catalog.clinic_id = ?
               AND catalog.clinic_id IN (
                   SELECT clinic.id FROM scheduling_clinics clinic WHERE clinic.tenant_group_id = ?
               )
               AND (
                   catalog.clinic_id > ?
                   OR (catalog.clinic_id = ? AND catalog.id > ?)
               )
             ORDER BY catalog.clinic_id ASC, catalog.id ASC
             LIMIT ?
        """.trimIndent()
        val offsetSql = """
            SELECT $columns
              FROM $table catalog
             WHERE catalog.clinic_id = ?
               AND catalog.clinic_id IN (
                   SELECT clinic.id FROM scheduling_clinics clinic WHERE clinic.tenant_group_id = ?
               )
             ORDER BY catalog.clinic_id ASC, catalog.id ASC
             LIMIT ? OFFSET ?
        """.trimIndent()

        val keyset = explain(connection, keysetSql, listOf(CLINIC_ID, TENANT_ID, CLINIC_ID, CLINIC_ID, CURSOR_ID, LIMIT))
        val offset = explain(connection, offsetSql, listOf(CLINIC_ID, TENANT_ID, LIMIT, OFFSET))
        return QueryComparison(table, keysetSql, keyset, offsetSql, offset)
    }

    private fun explain(connection: Connection, sql: String, parameters: List<Any>): PlanEvidence {
        val planSql = "EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) $sql"
        connection.prepareStatement(planSql).use { statement ->
            parameters.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { rows ->
                val lines = buildList {
                    while (rows.next()) add(rows.getString(1))
                }
                check(lines.isNotEmpty()) { "PostgreSQL EXPLAIN returned no rows" }
                val plan = lines.joinToString("\n")
                val rowCount = Regex("rows=(\\d+)").find(plan)?.groupValues?.get(1)?.toInt()
                    ?: error("EXPLAIN plan has no row count: $plan")
                val executionTimeMillis = Regex("Execution Time: ([0-9.]+) ms")
                    .find(plan)?.groupValues?.get(1)?.toDouble()
                    ?: error("EXPLAIN plan has no execution time: $plan")
                return PlanEvidence(plan, rowCount, executionTimeMillis)
            }
        }
    }

    private fun writeReport(reports: List<QueryComparison>) {
        val report = Path.of("build/reports/performance/issue-312-keyset-pagination-postgresql-explain.txt")
        Files.createDirectories(report.parent)
        Files.writeString(
            report,
            buildString {
                appendLine("# Issue #312 PostgreSQL keyset/offset EXPLAIN")
                appendLine("fixtureRows=$FIXTURE_ROWS")
                appendLine("limit=$LIMIT")
                appendLine("offset=$OFFSET")
                appendLine("cursorId=$CURSOR_ID")
                reports.forEach { comparison ->
                    appendLine()
                    appendLine("## ${comparison.table}")
                    appendLine("keysetExecutionTimeMs=${round(comparison.keyset.executionTimeMillis * 1000.0) / 1000.0}")
                    appendLine("offsetExecutionTimeMs=${round(comparison.offset.executionTimeMillis * 1000.0) / 1000.0}")
                    appendLine("keysetSqlHasOffset=${comparison.keysetSql.contains("OFFSET", ignoreCase = true)}")
                    appendLine("keysetPlan:")
                    appendLine(comparison.keyset.plan)
                    appendLine("offsetPlan:")
                    appendLine(comparison.offset.plan)
                }
            },
        )
    }

    private fun driver(className: String): Driver =
        Class.forName(className).getDeclaredConstructor().newInstance() as Driver

    private data class PlanEvidence(
        val plan: String,
        val rowCount: Int,
        val executionTimeMillis: Double,
    )

    private data class QueryComparison(
        val table: String,
        val keysetSql: String,
        val keyset: PlanEvidence,
        val offsetSql: String,
        val offset: PlanEvidence,
    )

    private companion object {
        const val FIXTURE_ROWS = 2_000
        const val BATCH_SIZE = 500
        const val TENANT_ID = 3_120_001L
        const val CLINIC_ID = 3_120_010L
        const val ID_BASE = 3_120_100L
        const val CURSOR_ID = ID_BASE + 1_500
        const val LIMIT = 50
        const val OFFSET = 1_500
    }
}
