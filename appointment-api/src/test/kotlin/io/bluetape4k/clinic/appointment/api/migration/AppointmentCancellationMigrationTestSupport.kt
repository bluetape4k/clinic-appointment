package io.bluetape4k.clinic.appointment.api.migration

import org.flywaydb.core.Flyway
import java.sql.Connection
import javax.sql.DataSource

/** V27 취소 detail snapshot table의 additive schema·unique/index 계약을 검증합니다. */
internal object AppointmentCancellationMigrationTestSupport {

    fun verifyV27Migration(dataSource: DataSource, location: String) {
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .cleanDisabled(false)
            .load()
        flyway.clean()
        Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .target("26")
            .load()
            .migrate()
        val result = Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .target("27")
            .load()
            .migrate()
        check(result.success) { "V27 migration failed: ${result.warnings.joinToString()}" }
        check(result.migrationsExecuted == 1) {
            "Expected only V27 after target 26, executed=${result.migrationsExecuted}"
        }
        dataSource.connection.use(::assertMetadata)
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

    private val EXPECTED_FOREIGN_KEYS = mapOf(
        "tenant_group_id" to "scheduling_tenant_groups",
        "clinic_id" to "scheduling_clinics",
        "appointment_id" to "scheduling_appointments",
        "commitment_id" to "scheduling_appointment_commitments",
        "proposal_id" to "scheduling_appointment_proposals",
    )
}
