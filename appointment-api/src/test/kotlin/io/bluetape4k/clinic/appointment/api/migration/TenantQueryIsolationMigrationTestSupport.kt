package io.bluetape4k.clinic.appointment.api.migration

import org.flywaydb.core.Flyway
import java.sql.Connection
import javax.sql.DataSource

/** V21 tenant event-log backfill, FK, direct-lookup 인덱스 계약. */
internal object TenantQueryIsolationMigrationTestSupport {

    fun verifyV21Migration(
        dataSource: DataSource,
        location: String,
    ) {
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .cleanDisabled(false)
            .load()
        flyway.clean()
        Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .target("20")
            .load()
            .migrate()

        dataSource.connection.use { connection ->
            connection.autoCommit = false
            insertFixtures(connection)
            connection.commit()
        }

        val result = Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .target("21")
            .load()
            .migrate()
        check(result.success) { "V21 migration failed: ${result.warnings.joinToString()}" }
        check(result.migrationsExecuted == 1) {
            "Expected only V21 after target 20, executed=${result.migrationsExecuted}"
        }

        dataSource.connection.use(::verifySchema)
    }

    private fun insertFixtures(connection: Connection) {
        connection.prepareStatement(
            """
            INSERT INTO scheduling_tenant_groups (id, tenant_code, display_name, active)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, FIXTURE_TENANT_ID)
            statement.setString(2, "tenant-v21-fixture")
            statement.setString(3, "V21 Fixture Tenant")
            statement.setBoolean(4, true)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            "INSERT INTO scheduling_clinics (id, name, tenant_group_id) VALUES (?, ?, ?)",
        ).use { statement ->
            statement.setLong(1, FIXTURE_CLINIC_ID)
            statement.setString(2, "V21 Fixture Clinic")
            statement.setLong(3, FIXTURE_TENANT_ID)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            INSERT INTO scheduling_appointment_event_logs
                (id, event_type, entity_type, entity_id, clinic_id, payload_json)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            insertEventLog(statement, BACKFILL_EVENT_ID, FIXTURE_CLINIC_ID)
            insertEventLog(statement, ORPHAN_EVENT_ID, ORPHAN_CLINIC_ID)
            statement.executeBatch()
        }
    }

    private fun insertEventLog(statement: java.sql.PreparedStatement, id: Long, clinicId: Long) {
        statement.setLong(1, id)
        statement.setString(2, "APPOINTMENT_CREATED")
        statement.setString(3, "Appointment")
        statement.setLong(4, id)
        statement.setLong(5, clinicId)
        statement.setString(6, "{\"fixture\":true}")
        statement.addBatch()
    }

    private fun verifySchema(connection: Connection) {
        check(columnIsNullable(connection, "scheduling_appointment_event_logs", "tenant_group_id")) {
            "V21 event-log tenant_group_id must remain nullable during rolling deployment"
        }
        check(readTenant(connection, BACKFILL_EVENT_ID) == FIXTURE_TENANT_ID) {
            "V21 clinic join backfill did not resolve the fixture tenant"
        }
        check(readTenant(connection, ORPHAN_EVENT_ID) == null) {
            "V21 orphan fixture must remain null and hold dispatch preflight"
        }
        check(readTenantClinicMismatchCount(connection) == 0L) {
            "V21 backfill produced a tenant-clinic mismatch"
        }
        val eventLogIndex = indexColumns(connection, "scheduling_appointment_event_logs", "idx_appointment_event_logs_tenant_scope")
        check(eventLogIndex == listOf("tenant_group_id", "clinic_id", "created_at", "id")) {
            "Unexpected event-log tenant index columns: $eventLogIndex"
        }
        check(indexColumns(connection, "clinic_notification_outbox", "idx_notification_outbox_direct_lookup").isNotEmpty()) {
            "V21 must retain the legacy direct lookup index"
        }
        check(indexColumns(connection, "clinic_notification_outbox", "idx_notification_outbox_tenant_direct_lookup") ==
            listOf(
                "tenant_group_id",
                "clinic_id",
                "appointment_id",
                "event_type",
                "row_kind",
                "status",
                "available_at",
                "next_retry_at",
                "id",
            ))
        check(hasTenantForeignKey(connection)) {
            "V21 tenant event-log foreign key is missing"
        }
    }

    private fun readTenant(connection: Connection, eventId: Long): Long? =
        connection.prepareStatement(
            "SELECT tenant_group_id FROM scheduling_appointment_event_logs WHERE id = ?",
        ).use { statement ->
            statement.setLong(1, eventId)
            statement.executeQuery().use { result ->
                check(result.next()) { "Missing V21 fixture event log $eventId" }
                result.getLong(1).let { value -> if (result.wasNull()) null else value }
            }
        }

    private fun readTenantClinicMismatchCount(connection: Connection): Long =
        connection.prepareStatement(
            """
            SELECT COUNT(*)
            FROM scheduling_appointment_event_logs event_log
            LEFT JOIN scheduling_clinics clinic ON clinic.id = event_log.clinic_id
            WHERE event_log.tenant_group_id IS NOT NULL
              AND (clinic.id IS NULL OR clinic.tenant_group_id <> event_log.tenant_group_id)
            """.trimIndent(),
        ).use { statement ->
            statement.executeQuery().use { result ->
                check(result.next()) { "V21 mismatch preflight returned no row" }
                result.getLong(1)
            }
        }

    private fun columnIsNullable(connection: Connection, table: String, column: String): Boolean =
        connection.metaData.getColumns(null, null, null, null).use { columns ->
            generateSequence {
                if (columns.next()) columns else null
            }.any {
                it.getString("TABLE_NAME").equals(table, ignoreCase = true) &&
                    it.getString("COLUMN_NAME").equals(column, ignoreCase = true) &&
                    it.getString("IS_NULLABLE").equals("YES", ignoreCase = true)
            }
        }

    private fun indexColumns(connection: Connection, table: String, index: String): List<String> {
        val rows = mutableListOf<Pair<Short, String>>()
        listOf(table, table.uppercase(), table.lowercase()).distinct().forEach { tableName ->
            if (rows.isNotEmpty()) return@forEach
            connection.metaData.getIndexInfo(null, null, tableName, false, false).use { indexes ->
                while (indexes.next()) {
                    if (indexes.getString("INDEX_NAME").equals(index, ignoreCase = true)) {
                        val column = indexes.getString("COLUMN_NAME") ?: continue
                        rows += indexes.getShort("ORDINAL_POSITION") to column.lowercase()
                    }
                }
            }
        }
        return rows.sortedBy { it.first }.map { it.second }
    }

    private fun hasTenantForeignKey(connection: Connection): Boolean =
        listOf(
            "scheduling_appointment_event_logs",
            "SCHEDULING_APPOINTMENT_EVENT_LOGS",
            "scheduling_appointment_event_logs".uppercase(),
        ).distinct().any { table ->
            connection.metaData.getImportedKeys(null, null, table).use { keys ->
                generateSequence {
                    if (keys.next()) keys else null
                }.any {
                    it.getString("FKTABLE_NAME").equals("scheduling_appointment_event_logs", ignoreCase = true) &&
                        it.getString("FKCOLUMN_NAME").equals("tenant_group_id", ignoreCase = true) &&
                        it.getString("PKTABLE_NAME").equals("scheduling_tenant_groups", ignoreCase = true)
                }
            }
        }

    private const val FIXTURE_TENANT_ID = 91_001L
    private const val FIXTURE_CLINIC_ID = 91_002L
    private const val BACKFILL_EVENT_ID = 91_003L
    private const val ORPHAN_EVENT_ID = 91_004L
    private const val ORPHAN_CLINIC_ID = 99_999_999L
}
