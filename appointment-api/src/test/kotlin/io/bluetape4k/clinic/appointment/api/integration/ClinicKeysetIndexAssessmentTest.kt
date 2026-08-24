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
import kotlin.math.ceil
import kotlin.math.round

/** PostgreSQL 실제 schema에서 keyset 후보 인덱스의 읽기·쓰기 비용을 반복 비교합니다. */
@ResourceLock(value = API_INTEGRATION_RESOURCE, mode = ResourceAccessMode.READ_WRITE)
@Execution(ExecutionMode.SAME_THREAD)
class ClinicKeysetIndexAssessmentTest {

    @Test
    fun `clinic id와 id 복합 인덱스의 반복 계획과 쓰기 비용을 비교하고 롤백한다`() {
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

            val environment = readEnvironment(connection)
            val existingIndexes = TABLES.associate { it.table to readIndexes(connection, it.table) }
            val tableEvidence = TABLES.map { spec ->
                dropCandidateIndex(connection, spec)
                analyze(connection)
                val baseline = measureState(connection, spec, IndexState.BASELINE)

                createCandidateIndex(connection, spec)
                analyze(connection)
                val candidate = measureState(connection, spec, IndexState.COMPOSITE)

                dropCandidateIndex(connection, spec)
                analyze(connection)
                candidate.indexPresentAfterRollback = !candidateIndexExists(connection, spec)
                TableEvidence(spec, baseline, candidate)
            }

            tableEvidence.forEach { evidence ->
                assertRowsAndShape(evidence.baseline)
                assertRowsAndShape(evidence.candidate)
                evidence.candidate.indexPresentAfterRollback.shouldBeTrue()
            }
            writeReport(environment, existingIndexes, tableEvidence)
            printSummary(tableEvidence)
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
                    VALUES ($TENANT_ID, 'issue-386-index', 'Issue 386 Index Assessment', true)
                    """.trimIndent(),
                )
                connection.prepareStatement(
                    "INSERT INTO scheduling_clinics(id, tenant_group_id, name) VALUES (?, ?, ?)",
                ).use { statement ->
                    repeat(CLINIC_COUNT) { clinicOrdinal ->
                        statement.setLong(1, clinicId(clinicOrdinal))
                        statement.setLong(2, TENANT_ID)
                        statement.setString(3, "Issue 386 Clinic $clinicOrdinal")
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
            }
            TABLES.forEach { insertRows(connection, it) }
            connection.commit()
        } catch (failure: Exception) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    private fun insertRows(connection: Connection, spec: TableSpec) {
        connection.prepareStatement(spec.insertSql).use { statement ->
            repeat(FIXTURE_ROWS) { rowOrdinal ->
                val clinicOrdinal = rowOrdinal % CLINIC_COUNT
                val id = ID_BASE + rowOrdinal
                statement.setLong(1, id)
                statement.setLong(2, clinicId(clinicOrdinal))
                statement.setString(3, "Issue 386 ${spec.table} $id")
                spec.bindRemaining(statement)
                statement.addBatch()
                if ((rowOrdinal + 1) % BATCH_SIZE == 0) statement.executeBatch()
            }
            statement.executeBatch()
        }
    }

    private fun analyze(connection: Connection) {
        connection.createStatement().use { statement ->
            TABLES.forEach { statement.execute("ANALYZE ${it.table}") }
            statement.execute("ANALYZE scheduling_clinics")
        }
    }

    private fun measureState(
        connection: Connection,
        spec: TableSpec,
        state: IndexState,
    ): StateEvidence {
        connection.createStatement().use { statement ->
            statement.execute("DISCARD PLANS")
        }
        val warmupPlans = repeatPlans(connection, spec, WARMUP_SAMPLES)
        check(warmupPlans.all { it.rowCount == LIMIT }) {
            "warmup returned an unexpected row count for ${spec.table}: $warmupPlans"
        }
        val plans = repeatPlans(connection, spec, MEASURED_SAMPLES)
        val writeSamples = repeatWrites(connection, spec, WRITE_SAMPLES)
        return StateEvidence(
            state = state,
            plans = plans,
            writeSamplesMillis = writeSamples,
            indexSizeBytes = readIndexSize(connection, spec),
            indexPresentAfterRollback = false,
        )
    }

    private fun repeatPlans(
        connection: Connection,
        spec: TableSpec,
        count: Int,
    ): List<PlanEvidence> = (1..count).map { sample ->
        explain(connection, spec, sample)
    }

    private fun explain(
        connection: Connection,
        spec: TableSpec,
        sample: Int,
    ): PlanEvidence {
        val sql = """
            SELECT catalog.id, catalog.clinic_id, catalog.name
              FROM ${spec.table} catalog
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
        val planSql = "EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) $sql"
        connection.prepareStatement(planSql).use { statement ->
            listOf(TARGET_CLINIC_ID, TENANT_ID, TARGET_CLINIC_ID, TARGET_CLINIC_ID, cursorId(), LIMIT)
                .forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { rows ->
                val lines = buildList {
                    while (rows.next()) add(rows.getString(1))
                }
                check(lines.isNotEmpty()) { "PostgreSQL EXPLAIN returned no rows" }
                val plan = lines.joinToString("\n")
                return PlanEvidence(
                    sample = sample,
                    rowCount = Regex("actual time=[^)]*rows=(\\d+(?:\\.\\d+)?)")
                        .find(plan)?.groupValues?.get(1)?.toDouble()?.toInt()
                        ?: error("EXPLAIN plan has no row count: $plan"),
                    executionTimeMillis = Regex("Execution Time: ([0-9.]+) ms")
                        .find(plan)?.groupValues?.get(1)?.toDouble()
                        ?: error("EXPLAIN plan has no execution time: $plan"),
                    planningTimeMillis = Regex("Planning Time: ([0-9.]+) ms")
                        .find(plan)?.groupValues?.get(1)?.toDouble()
                        ?: error("EXPLAIN plan has no planning time: $plan"),
                    sharedHitBuffers = topLevelBufferMetric(plan, "hit"),
                    sharedReadBuffers = topLevelBufferMetric(plan, "read"),
                    rowsRemovedByFilter = Regex("Rows Removed by Filter: (\\d+)")
                        .findAll(plan)
                        .sumOf { it.groupValues[1].toInt() },
                    indexNames = Regex("(?:Index Scan|Index Only Scan) using ([^ ]+)")
                        .findAll(plan)
                        .map { it.groupValues[1] }
                        .distinct()
                        .toList(),
                    plan = plan,
                )
            }
        }
    }

    private fun repeatWrites(
        connection: Connection,
        spec: TableSpec,
        count: Int,
    ): List<Double> {
        repeat(WRITE_WARMUP_SAMPLES) { insertAndRollback(connection, spec) }
        return (1..count).map {
            val started = System.nanoTime()
            insertAndRollback(connection, spec)
            (System.nanoTime() - started) / NANOS_PER_MILLISECOND
        }
    }

    private fun insertAndRollback(connection: Connection, spec: TableSpec) {
        check(connection.autoCommit) { "write measurement requires auto-commit connection" }
        connection.autoCommit = false
        try {
            connection.prepareStatement(spec.insertSql).use { statement ->
                repeat(WRITE_ROWS) { rowOrdinal ->
                    val id = WRITE_ID_BASE + rowOrdinal
                    statement.setLong(1, id)
                    statement.setLong(2, TARGET_CLINIC_ID)
                    statement.setString(3, "Issue 386 write $id")
                    spec.bindRemaining(statement)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            connection.rollback()
        } finally {
            connection.autoCommit = true
        }
    }

    private fun createCandidateIndex(connection: Connection, spec: TableSpec) {
        connection.createStatement().use { statement ->
            statement.execute("CREATE INDEX ${spec.indexName} ON ${spec.table} (clinic_id, id)")
        }
    }

    private fun dropCandidateIndex(connection: Connection, spec: TableSpec) {
        connection.createStatement().use { statement ->
            statement.execute("DROP INDEX IF EXISTS ${spec.indexName}")
        }
    }

    private fun candidateIndexExists(connection: Connection, spec: TableSpec): Boolean =
        connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL").use { statement ->
            statement.setString(1, spec.indexName)
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getBoolean(1)
            }
        }

    private fun readIndexSize(connection: Connection, spec: TableSpec): Long? =
        if (!candidateIndexExists(connection, spec)) {
            null
        } else {
            connection.prepareStatement("SELECT pg_relation_size(to_regclass(?))").use { statement ->
                statement.setString(1, spec.indexName)
                statement.executeQuery().use { rows ->
                    check(rows.next())
                    rows.getLong(1)
                }
            }
        }

    private fun readIndexes(connection: Connection, table: String): List<String> =
        connection.prepareStatement(
            "SELECT indexname FROM pg_indexes WHERE schemaname = current_schema() AND tablename = ? ORDER BY indexname",
        ).use { statement ->
            statement.setString(1, table)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(rows.getString(1))
                }
            }
        }

    private fun readEnvironment(connection: Connection): EnvironmentEvidence =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT version(), current_setting('server_version'), current_setting('shared_buffers'), current_setting('random_page_cost')",
            ).use { rows ->
                check(rows.next())
                EnvironmentEvidence(
                    serverVersion = rows.getString(1).replace('\n', ' '),
                    serverVersionSetting = rows.getString(2),
                    sharedBuffers = rows.getString(3),
                    randomPageCost = rows.getString(4),
                )
            }
        }

    private fun assertRowsAndShape(evidence: StateEvidence) {
        evidence.plans.forEach { plan ->
            plan.rowCount shouldBeEqualTo LIMIT
            (!plan.plan.lowercase().contains("offset")).shouldBeTrue()
        }
        check(evidence.plans.map { it.executionTimeMillis }.all { it >= 0.0 })
        check(evidence.writeSamplesMillis.all { it >= 0.0 })
    }

    private fun writeReport(
        environment: EnvironmentEvidence,
        existingIndexes: Map<String, List<String>>,
        tableEvidence: List<TableEvidence>,
    ) {
        val report = Path.of("build/reports/performance/issue-386-keyset-index-assessment.txt")
        Files.createDirectories(report.parent)
        Files.writeString(
            report,
            buildString {
                appendLine("# Issue #386 PostgreSQL keyset composite-index assessment")
                appendLine("postgresqlImage=${io.bluetape4k.testcontainers.database.PostgreSQLServer.IMAGE}:${io.bluetape4k.testcontainers.database.PostgreSQLServer.TAG}")
                appendLine("serverVersion=${environment.serverVersion}")
                appendLine("serverVersionSetting=${environment.serverVersionSetting}")
                appendLine("sharedBuffers=${environment.sharedBuffers}")
                appendLine("randomPageCost=${environment.randomPageCost}")
                appendLine("clinicCount=$CLINIC_COUNT")
                appendLine("rowsPerClinic=$ROWS_PER_CLINIC")
                appendLine("rowsPerTable=$FIXTURE_ROWS")
                appendLine("targetClinicId=$TARGET_CLINIC_ID")
                appendLine("cursorId=${cursorId()}")
                appendLine("limit=$LIMIT")
                appendLine("warmupSamples=$WARMUP_SAMPLES")
                appendLine("measuredSamples=$MEASURED_SAMPLES")
                appendLine("writeRowsPerSample=$WRITE_ROWS")
                appendLine("writeSamples=$WRITE_SAMPLES")
                tableEvidence.forEach { evidence ->
                    appendLine()
                    appendLine("## ${evidence.spec.table}")
                    appendLine("existingIndexes=${existingIndexes[evidence.spec.table].orEmpty().joinToString(",")}")
                    appendStateReport(this, evidence.baseline)
                    appendStateReport(this, evidence.candidate)
                    appendLine("candidateIndexPresentAfterRollback=${evidence.candidate.indexPresentAfterRollback}")
                }
            },
        )
        println("ISSUE386_REPORT=${report.toAbsolutePath()}")
    }

    private fun appendStateReport(builder: StringBuilder, evidence: StateEvidence) {
        builder.appendLine("state=${evidence.state.name.lowercase()}")
        builder.appendLine("candidateIndexSizeBytes=${evidence.indexSizeBytes ?: "absent"}")
        builder.appendLine("readMedianMs=${format(median(evidence.plans.map { it.executionTimeMillis }))}")
        builder.appendLine("readP95Ms=${format(percentile(evidence.plans.map { it.executionTimeMillis }, 0.95))}")
        builder.appendLine("writeMedianMs=${format(median(evidence.writeSamplesMillis))}")
        builder.appendLine("writeP95Ms=${format(percentile(evidence.writeSamplesMillis, 0.95))}")
        evidence.plans.forEach { plan ->
            builder.appendLine(
                "readSample=${plan.sample} executionMs=${format(plan.executionTimeMillis)} " +
                    "planningMs=${format(plan.planningTimeMillis)} hit=${plan.sharedHitBuffers} " +
                    "read=${plan.sharedReadBuffers} rowsRemoved=${plan.rowsRemovedByFilter} " +
                    "indexes=${plan.indexNames.joinToString(",")}",
            )
            builder.appendLine(plan.plan)
        }
        builder.appendLine("writeSamplesMs=${evidence.writeSamplesMillis.joinToString(",") { format(it) }}")
    }

    private fun printSummary(tableEvidence: List<TableEvidence>) {
        tableEvidence.forEach { evidence ->
            println(
                "ISSUE386_SUMMARY table=${evidence.spec.table} " +
                    "baselineReadP95Ms=${format(percentile(evidence.baseline.plans.map { it.executionTimeMillis }, 0.95))} " +
                    "candidateReadP95Ms=${format(percentile(evidence.candidate.plans.map { it.executionTimeMillis }, 0.95))} " +
                    "baselineWriteP95Ms=${format(percentile(evidence.baseline.writeSamplesMillis, 0.95))} " +
                    "candidateWriteP95Ms=${format(percentile(evidence.candidate.writeSamplesMillis, 0.95))} " +
                    "baselineRowsRemoved=${evidence.baseline.plans.sumOf { it.rowsRemovedByFilter }} " +
                    "candidateRowsRemoved=${evidence.candidate.plans.sumOf { it.rowsRemovedByFilter }}",
            )
        }
    }

    private fun topLevelBufferMetric(plan: String, metric: String): Int =
        Regex("(?m)^\\s*Buffers: shared [^\\n]*\\b$metric=(\\d+)")
            .find(plan)
            ?.groupValues
            ?.get(1)
            ?.toInt()
            ?: 0

    private fun median(values: List<Double>): Double = percentile(values, 0.5)

    private fun percentile(values: List<Double>, percentile: Double): Double {
        val sorted = values.sorted()
        val index = ceil(sorted.size * percentile).toInt().coerceAtLeast(1) - 1
        return sorted[index.coerceAtMost(sorted.lastIndex)]
    }

    private fun format(value: Double): String = "%.3f".format(java.util.Locale.ROOT, round(value * 1000.0) / 1000.0)

    private fun cursorId(): Long = ID_BASE + TARGET_CLINIC_ORDINAL + ((ROWS_PER_CLINIC / 2) * CLINIC_COUNT)

    private fun clinicId(ordinal: Int): Long = CLINIC_ID_BASE + ordinal

    private fun driver(className: String): Driver =
        Class.forName(className).getDeclaredConstructor().newInstance() as Driver

    private enum class IndexState {
        BASELINE,
        COMPOSITE,
    }

    private data class TableSpec(
        val table: String,
        val indexName: String,
        val insertSql: String,
        val bindRemaining: (java.sql.PreparedStatement) -> Unit,
    )

    private data class PlanEvidence(
        val sample: Int,
        val rowCount: Int,
        val executionTimeMillis: Double,
        val planningTimeMillis: Double,
        val sharedHitBuffers: Int,
        val sharedReadBuffers: Int,
        val rowsRemovedByFilter: Int,
        val indexNames: List<String>,
        val plan: String,
    )

    private data class StateEvidence(
        val state: IndexState,
        val plans: List<PlanEvidence>,
        val writeSamplesMillis: List<Double>,
        val indexSizeBytes: Long?,
        var indexPresentAfterRollback: Boolean,
    )

    private data class TableEvidence(
        val spec: TableSpec,
        val baseline: StateEvidence,
        val candidate: StateEvidence,
    )

    private data class EnvironmentEvidence(
        val serverVersion: String,
        val serverVersionSetting: String,
        val sharedBuffers: String,
        val randomPageCost: String,
    )

    private companion object {
        const val CLINIC_COUNT = 16
        const val ROWS_PER_CLINIC = 2_000
        const val FIXTURE_ROWS = CLINIC_COUNT * ROWS_PER_CLINIC
        const val BATCH_SIZE = 500
        const val TENANT_ID = 3_860_001L
        const val CLINIC_ID_BASE = 3_860_100L
        const val ID_BASE = 3_860_100_000L
        const val WRITE_ID_BASE = 3_860_200_000L
        const val TARGET_CLINIC_ORDINAL = 8
        const val TARGET_CLINIC_ID = CLINIC_ID_BASE + TARGET_CLINIC_ORDINAL
        const val LIMIT = 50
        const val WARMUP_SAMPLES = 2
        const val MEASURED_SAMPLES = 7
        const val WRITE_WARMUP_SAMPLES = 1
        const val WRITE_SAMPLES = 5
        const val WRITE_ROWS = 500
        const val NANOS_PER_MILLISECOND = 1_000_000.0

        val TABLES = listOf(
            TableSpec(
                table = "scheduling_doctors",
                indexName = "idx_issue386_doctors_clinic_id",
                insertSql = "INSERT INTO scheduling_doctors(id, clinic_id, name, provider_type) VALUES (?, ?, ?, 'DOCTOR')",
                bindRemaining = {},
            ),
            TableSpec(
                table = "scheduling_equipments",
                indexName = "idx_issue386_equipments_clinic_id",
                insertSql = "INSERT INTO scheduling_equipments(id, clinic_id, name, usage_duration_minutes, quantity) VALUES (?, ?, ?, 30, 1)",
                bindRemaining = {},
            ),
            TableSpec(
                table = "scheduling_treatment_types",
                indexName = "idx_issue386_treatment_types_clinic_id",
                insertSql = "INSERT INTO scheduling_treatment_types(id, clinic_id, name, category, default_duration_minutes, required_provider_type, requires_equipment) VALUES (?, ?, ?, 'TREATMENT', 30, 'DOCTOR', false)",
                bindRemaining = {},
            ),
        )
    }
}
