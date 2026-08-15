package io.bluetape4k.clinic.appointment.api.migration

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.model.tables.AppointmentCommitments
import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.tables.ProfileReevaluationHeads
import io.bluetape4k.clinic.appointment.model.tables.ProfileReevaluationJobs
import io.bluetape4k.clinic.appointment.model.tables.ProfileReevaluationOutcomes
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.flywaydb.core.Flyway
import java.sql.Connection
import javax.sql.DataSource

/**
 * 프로필 재평가 V13 스키마가 세 데이터베이스에서 같은 계약을 제공하는지 검증합니다.
 */
internal object ProfileReevaluationMigrationTestSupport {

    fun verifyV13Migration(
        dataSource: DataSource,
        location: String,
    ) {
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .target("13")
            .cleanDisabled(false)
            .load()
        flyway.clean()
        val result = flyway.migrate()

        result.success shouldBeEqualTo true
        val applied = flyway.info().applied().single { it.version?.version == "13" }
        check(applied.checksum != null) { "Applied V13 checksum must be recorded" }

        dataSource.connection.use { connection ->
            val tables = tableNames(connection)
            check(EXPECTED_TABLES.all(tables::contains)) {
                "Missing V13 tables: ${EXPECTED_TABLES - tables}"
            }
            verifyColumns(connection)
            verifyIndexes(connection)
            verifyForeignKeys(connection)
            verifyCheckConstraints(connection)
        }
        verifyExposedModelHasNoAdditiveDrift(dataSource)
    }

    private fun verifyColumns(connection: Connection) {
        EXPECTED_COLUMNS.forEach { (table, expected) ->
            val actual = columnNames(connection, table)
            check(actual == expected) {
                "$table columns differ. expected=$expected actual=$actual"
            }
            check(actual.none { column -> FORBIDDEN_COLUMN_TOKENS.any(column::contains) }) {
                "$table contains a forbidden sensitive payload column: $actual"
            }
        }
    }

    private fun verifyIndexes(connection: Connection) {
        EXPECTED_INDEXES.forEach { (table, expectedIndexes) ->
            val actual = indexDefinitions(connection, table)
            expectedIndexes.forEach { (index, columns) ->
                val actualColumns = actual[index]
                    ?: actual.entries.singleOrNull { (name, _) -> name.startsWith("${index}_index_") }?.value
                check(actualColumns == columns) {
                    "$table.$index differs. expected=$columns actual=$actualColumns indexes=$actual"
                }
            }
        }
    }

    private fun verifyForeignKeys(connection: Connection) {
        EXPECTED_FOREIGN_KEYS.forEach { (table, expected) ->
            val actual = importedKeys(connection, table)
            check(actual.containsAll(expected)) {
                "$table foreign keys differ. missing=${expected - actual}"
            }
        }
    }

    private fun verifyCheckConstraints(connection: Connection) {
        val constraints = checkConstraintNames(connection)
        check(constraints.containsAll(EXPECTED_CHECKS)) {
            "V13 check constraints differ. missing=${EXPECTED_CHECKS - constraints}"
        }
    }

    /**
     * dev/test Exposed 초기화 표면이 V13보다 추가 table·column·index를 요구하지 않는지 확인합니다.
     */
    private fun verifyExposedModelHasNoAdditiveDrift(dataSource: DataSource) {
        val database = Database.connect(dataSource)
        val additiveDrift = transaction(database) {
            MigrationUtils.statementsRequiredForDatabaseMigration(
                Appointments,
                AppointmentCommitments,
                ProfileReevaluationHeads,
                ProfileReevaluationJobs,
                ProfileReevaluationOutcomes,
                withLogs = false,
            ).filter(::isAdditiveSchemaChange)
        }
        check(additiveDrift.isEmpty()) {
            "Flyway V13 is missing additive DDL required by Exposed:\n" +
                additiveDrift.joinToString(separator = "\n")
        }
    }

    private fun isAdditiveSchemaChange(statement: String): Boolean {
        val normalized = statement
            .trim()
            .replace(Regex("\\s+"), " ")
            .uppercase()
        return normalized.startsWith("CREATE TABLE ") ||
            normalized.startsWith("CREATE INDEX ") ||
            normalized.startsWith("CREATE UNIQUE INDEX ") ||
            (normalized.startsWith("ALTER TABLE ") && normalized.contains(" ADD COLUMN "))
    }

    private fun tableNames(connection: Connection): Set<String> =
        connection.metaData.getTables(null, null, "%", arrayOf("TABLE")).use { rows ->
            buildSet {
                while (rows.next()) add(rows.getString("TABLE_NAME").lowercase())
            }
        }

    private fun columnNames(connection: Connection, table: String): Set<String> =
        metadataTableCandidates(table).firstNotNullOfOrNull { candidate ->
            connection.metaData.getColumns(null, null, candidate, "%").use { rows ->
                buildSet {
                    while (rows.next()) add(rows.getString("COLUMN_NAME").lowercase())
                }.takeIf(Set<String>::isNotEmpty)
            }
        }.orEmpty()

    private fun indexDefinitions(connection: Connection, table: String): Map<String, List<String>> =
        metadataTableCandidates(table).firstNotNullOfOrNull { candidate ->
            connection.metaData.getIndexInfo(null, null, candidate, false, false).use { rows ->
                buildMap<String, MutableList<Pair<Int, String>>> {
                    while (rows.next()) {
                        val name = rows.getString("INDEX_NAME")?.lowercase() ?: continue
                        val column = rows.getString("COLUMN_NAME")?.lowercase() ?: continue
                        getOrPut(name) { mutableListOf() }
                            .add(rows.getInt("ORDINAL_POSITION") to column)
                    }
                }
                    .mapValues { (_, columns) ->
                        columns.sortedBy(Pair<Int, String>::first).map(Pair<Int, String>::second)
                    }
                    .takeIf(Map<String, List<String>>::isNotEmpty)
            }
        }.orEmpty()

    private fun importedKeys(connection: Connection, table: String): Set<String> =
        metadataTableCandidates(table).firstNotNullOfOrNull { candidate ->
            connection.metaData.getImportedKeys(null, null, candidate).use { rows ->
                buildSet {
                    while (rows.next()) {
                        add(
                            rows.getString("FKCOLUMN_NAME").lowercase() +
                                "->" +
                                rows.getString("PKTABLE_NAME").lowercase()
                        )
                    }
                }.takeIf(Set<String>::isNotEmpty)
            }
        }.orEmpty()

    private fun checkConstraintNames(connection: Connection): Set<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE constraint_type = 'CHECK'
                """.trimIndent()
            ).use { rows ->
                buildSet {
                    while (rows.next()) add(rows.getString(1).lowercase())
                }
            }
        }

    private fun metadataTableCandidates(table: String) = listOf(table, table.uppercase())

    private val EXPECTED_TABLES = setOf(
        "scheduling_profile_reevaluation_heads",
        "scheduling_profile_reevaluation_jobs",
        "scheduling_profile_reevaluation_outcomes",
    )

    private val EXPECTED_COLUMNS = mapOf(
        "scheduling_profile_reevaluation_heads" to setOf(
            "id",
            "tenant_group_id",
            "clinic_id",
            "patient_reference_fingerprint",
            "latest_revision",
            "latest_event_id",
            "assessment_ref",
            "assessment_hash",
            "occurred_at",
            "created_at",
            "updated_at",
        ),
        "scheduling_profile_reevaluation_jobs" to setOf(
            "id",
            "head_id",
            "tenant_group_id",
            "clinic_id",
            "patient_reference_fingerprint",
            "target_revision",
            "event_id",
            "assessment_ref",
            "assessment_hash",
            "status",
            "occurred_at",
            "due_at",
            "target_duration_seconds",
            "held_target_seconds",
            "proposed_target_seconds",
            "target_policy_ref",
            "target_policy_generation",
            "next_attempt_at",
            "lease_owner",
            "lease_expires_at",
            "attempt_count",
            "first_attempt_at",
            "redrive_count",
            "root_job_id",
            "redrive_of_job_id",
            "redrive_generation",
            "priority_class",
            "held_cursor_appointment_id",
            "proposed_cursor_appointment_id",
            "scanned_count",
            "proposal_superseded_count",
            "hold_kept_count",
            "hold_replaced_count",
            "fallback_to_proposed_count",
            "skipped_ineligible_count",
            "skipped_unchanged_count",
            "last_failure_code",
            "created_at",
            "updated_at",
        ),
        "scheduling_profile_reevaluation_outcomes" to setOf(
            "id",
            "job_id",
            "target_revision",
            "appointment_id",
            "outcome_type",
            "created_at",
        ),
    )

    private val EXPECTED_INDEXES = mapOf(
        "scheduling_profile_reevaluation_heads" to mapOf(
            "uq_profile_reevaluation_head_scope" to
                listOf("tenant_group_id", "clinic_id", "patient_reference_fingerprint"),
        ),
        "scheduling_profile_reevaluation_jobs" to mapOf(
            "idx_profile_reevaluation_due" to
                listOf("status", "next_attempt_at", "clinic_id", "id"),
            "idx_profile_reevaluation_lease" to
                listOf("status", "lease_expires_at", "clinic_id", "id"),
            "uq_profile_reevaluation_job_lineage" to
                listOf("root_job_id", "target_revision", "redrive_generation"),
        ),
        "scheduling_profile_reevaluation_outcomes" to mapOf(
            "uq_profile_reevaluation_outcome" to listOf("job_id", "appointment_id"),
        ),
        "scheduling_appointments" to mapOf(
            "idx_appointment_profile_reevaluation" to
                listOf("clinic_id", "patient_reference_fingerprint", "id"),
        ),
        "scheduling_appointment_commitments" to mapOf(
            "idx_commitment_profile_reevaluation" to listOf("commitment_status", "appointment_id"),
        ),
    )

    private val EXPECTED_FOREIGN_KEYS = mapOf(
        "scheduling_profile_reevaluation_jobs" to
            setOf("head_id->scheduling_profile_reevaluation_heads"),
        "scheduling_profile_reevaluation_outcomes" to
            setOf("job_id->scheduling_profile_reevaluation_jobs"),
    )

    private val EXPECTED_CHECKS = setOf(
        "ck_profile_reevaluation_job_status",
        "ck_profile_reevaluation_priority_class",
        "ck_profile_reevaluation_outcome_type",
    )

    private val FORBIDDEN_COLUMN_TOKENS = setOf(
        "json",
        "payload",
        "reason",
        "profile",
        "feature",
        "score",
        "explanation",
        "correction",
    )
}
