package io.bluetape4k.clinic.appointment.api.migration

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.configuration.FluentConfiguration
import java.sql.Connection
import javax.sql.DataSource

/** V27→V30 취소 detail snapshot의 expand·index·checkpoint 계약을 검증합니다. */
internal object AppointmentCancellationMigrationTestSupport {

    fun verifyV27Migration(dataSource: DataSource, location: String) {
        val flyway = flyway(dataSource, location)
            .cleanDisabled(false)
            .load()
        flyway.clean()
        flyway(dataSource, location)
            .target("26")
            .load()
            .migrate()
        val result = flyway(dataSource, location)
            .target("27")
            .load()
            .migrate()
        check(result.success) { "V27 migration failed: ${result.warnings.joinToString()}" }
        check(result.migrationsExecuted == 1) {
            "Expected only V27 after target 26, executed=${result.migrationsExecuted}"
        }
        dataSource.connection.use(::assertMetadata)
    }

    fun verifyV28Migration(dataSource: DataSource, location: String) {
        val flyway = flyway(dataSource, location)
            .cleanDisabled(false)
            .load()
        flyway.clean()
        flyway(dataSource, location)
            .target("27")
            .load()
            .migrate()
        dataSource.connection.use(::insertV28BackfillFixture)
        val result = flyway(dataSource, location)
            .target("28")
            .load()
            .migrate()
        check(result.success) { "V28 migration failed: ${result.warnings.joinToString()}" }
        check(result.migrationsExecuted == 1) {
            "Expected only V28 after target 27, executed=${result.migrationsExecuted}"
        }
        dataSource.connection.use { connection ->
            assertV28Metadata(connection)
            assertV28Residual(connection)
        }

        val indexResult = flyway(dataSource, location)
            .target("29")
            .load()
            .migrate()
        check(indexResult.success) { "V29 migration failed: ${indexResult.warnings.joinToString()}" }
        check(indexResult.migrationsExecuted == 1) {
            "Expected only V29 after target 28, executed=${indexResult.migrationsExecuted}"
        }
        dataSource.connection.use { connection ->
            assertV29Index(connection)
            assertV28Residual(connection)
        }

        val checkpointResult = flyway(dataSource, location)
            .target("30")
            .load()
            .migrate()
        check(checkpointResult.success) { "V30 migration failed: ${checkpointResult.warnings.joinToString()}" }
        check(checkpointResult.migrationsExecuted == 1) {
            "Expected only V30 after target 29, executed=${checkpointResult.migrationsExecuted}"
        }
        dataSource.connection.use(::assertV30CheckpointMetadata)
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

    private fun assertMetadata(connection: Connection) {
        val expected = setOf(
            "id", "tenant_group_id", "clinic_id", "appointment_id", "commitment_id", "proposal_id",
            "reason_code", "reason_detail", "actor_role", "actor_scope_hash", "detail_hash", "occurred_at",
        )
        check(columns(connection, TABLE) == expected) {
            "Unexpected V27 cancellation detail columns: ${columns(connection, TABLE)}"
        }
        check(hasIndexColumns(connection, TABLE, listOf("commitment_id"), unique = true)) {
            "V27 commitment uniqueness is missing"
        }
        check(hasIndexColumns(connection, TABLE, listOf("tenant_group_id", "clinic_id", "occurred_at"))) {
            "V27 scope/time index is missing"
        }
        check(foreignKeys(connection, TABLE) == EXPECTED_FOREIGN_KEYS) {
            "V27 foreign-key contract is missing: ${foreignKeys(connection, TABLE)}"
        }
    }

    private fun assertV28Metadata(connection: Connection) {
        val expected = setOf(
            "id", "tenant_group_id", "clinic_id", "appointment_id", "commitment_id", "proposal_id",
            "reason_code", "reason_detail", "from_commitment_status", "patient_scope_fingerprint",
            "actor_role", "actor_scope_hash", "detail_hash", "occurred_at",
        )
        check(columns(connection, TABLE) == expected) {
            "Unexpected V28 cancellation detail columns: ${columns(connection, TABLE)}"
        }
    }

    private fun assertV29Index(connection: Connection) {
        check(hasIndexColumns(connection, TABLE, listOf("tenant_group_id", "patient_scope_fingerprint", "occurred_at", "id"))) {
            "V29 patient scope/time index is missing"
        }
    }

    private fun assertV30CheckpointMetadata(connection: Connection) {
        val expected = setOf("scope", "migration_version", "dialect", "last_detail_id", "updated_at")
        check(columns(connection, CHECKPOINT_TABLE) == expected) {
            "Unexpected V30 checkpoint columns: ${columns(connection, CHECKPOINT_TABLE)}"
        }
        check(hasIndexColumns(connection, CHECKPOINT_TABLE, listOf("scope"), unique = true)) {
            "V30 checkpoint primary key is missing"
        }
    }

    private fun insertV28BackfillFixture(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "INSERT INTO scheduling_tenant_groups(id, tenant_code, display_name, active) " +
                    "VALUES (9901, 'migration-v28', 'Migration V28', TRUE)",
            )
            statement.executeUpdate(
                "INSERT INTO scheduling_clinics(id, tenant_group_id, name) " +
                    "VALUES (9902, 9901, 'Migration clinic')",
            )
            statement.executeUpdate(
                "INSERT INTO scheduling_appointments(id, clinic_id, patient_name, patient_reference_fingerprint) " +
                    "VALUES (9903, 9902, 'Legacy patient', '" + "a".repeat(64) + "')",
            )
            statement.executeUpdate(
                "INSERT INTO scheduling_appointment_commitments(" +
                    "id, appointment_id, commitment_status, origin, effective_policy_snapshot_id, version" +
                    ") VALUES (9904, 9903, 'CANCELLED', 'PATIENT', 1, 1)",
            )
            statement.executeUpdate(
                "INSERT INTO scheduling_appointment_proposals(" +
                    "id, commitment_id, revision, proposed_start_at, proposed_end_at, expires_at, " +
                    "representative_treatment_name, proposal_hash, policy_snapshot_id, created_by_actor" +
                    ") VALUES (9905, 9904, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, " +
                    "'Treatment', '" + "b".repeat(64) + "', 1, 'migration-test')",
            )
            statement.executeUpdate(
                "INSERT INTO scheduling_appointment_cancellation_details(" +
                    "id, tenant_group_id, clinic_id, appointment_id, commitment_id, proposal_id, reason_code, " +
                    "actor_role, actor_scope_hash, detail_hash, occurred_at" +
                    ") VALUES (9906, 9901, 9902, 9903, 9904, 9905, 'CUSTOMER_REQUEST', 'PATIENT', " +
                    "'" + "c".repeat(64) + "', '" + "d".repeat(64) + "', CURRENT_TIMESTAMP)",
            )
        }
    }

    private fun assertV28Residual(connection: Connection) {
        connection.prepareStatement(
            "SELECT patient_scope_fingerprint FROM scheduling_appointment_cancellation_details WHERE id = 9906",
        ).use { statement ->
            statement.executeQuery().use { rows ->
                check(rows.next()) { "V28 fixture is missing" }
                check(rows.getString(1) == null) {
                    "V28 expand migration must not perform an unbounded backfill"
                }
            }
        }
    }

    private fun columns(connection: Connection, table: String): Set<String> {
        val result = linkedSetOf<String>()
        candidates(table).forEach { candidate ->
            connection.metaData.getColumns(null, null, candidate, "%").use { rows ->
                while (rows.next()) rows.getString("COLUMN_NAME")?.lowercase()?.let(result::add)
            }
        }
        return result
    }

    private fun hasIndexColumns(connection: Connection, table: String, expected: List<String>, unique: Boolean = false): Boolean {
        val indexMap = linkedMapOf<String, Pair<Boolean, MutableList<Pair<Short, String>>>>()
        candidates(table).forEach { candidate ->
            connection.metaData.getIndexInfo(null, null, candidate, false, false).use { rows ->
                while (rows.next()) {
                    val name = rows.getString("INDEX_NAME") ?: continue
                    val column = rows.getString("COLUMN_NAME") ?: continue
                    val entry = indexMap.getOrPut(name) {
                        rows.getBoolean("NON_UNIQUE") to mutableListOf()
                    }
                    entry.second += rows.getShort("ORDINAL_POSITION") to column.lowercase()
                }
            }
        }
        return indexMap.values.any { (nonUnique, columns) ->
            (!unique || !nonUnique) && columns.sortedBy { it.first }.map { it.second } == expected
        }
    }

    private fun foreignKeys(connection: Connection, table: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        candidates(table).forEach { candidate ->
            connection.metaData.getImportedKeys(null, null, candidate).use { rows ->
                while (rows.next()) {
                    val column = rows.getString("FKCOLUMN_NAME")?.lowercase() ?: continue
                    val referenced = rows.getString("PKTABLE_NAME")?.lowercase() ?: continue
                    result[column] = referenced
                }
            }
        }
        return result
    }

    private fun candidates(table: String): List<String> = listOf(table, table.uppercase(), table.lowercase()).distinct()

    private const val TABLE = "scheduling_appointment_cancellation_details"
    private const val CHECKPOINT_TABLE = "scheduling_patient_history_backfill_checkpoint"

    private val EXPECTED_FOREIGN_KEYS = mapOf(
        "tenant_group_id" to "scheduling_tenant_groups",
        "clinic_id" to "scheduling_clinics",
        "appointment_id" to "scheduling_appointments",
        "commitment_id" to "scheduling_appointment_commitments",
        "proposal_id" to "scheduling_appointment_proposals",
    )
}
