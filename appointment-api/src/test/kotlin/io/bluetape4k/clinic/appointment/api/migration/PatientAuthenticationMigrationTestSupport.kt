package io.bluetape4k.clinic.appointment.api.migration

import org.flywaydb.core.Flyway
import java.sql.Connection
import javax.sql.DataSource

/** V26 환자 인증 table과 uniqueness/FK 계약을 검증하는 migration support입니다. */
internal object PatientAuthenticationMigrationTestSupport {

    fun verifyV26Migration(dataSource: DataSource, location: String) {
        Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .cleanDisabled(false)
            .load()
            .clean()

        Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .target("25")
            .load()
            .migrate()

        val result = Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .target("26")
            .load()
            .migrate()
        check(result.success) { "V26 migration failed: ${result.warnings.joinToString()}" }
        check(result.migrationsExecuted == 1) {
            "Expected only V26 after target 25, executed=${result.migrationsExecuted}"
        }

        dataSource.connection.use(::assertMetadata)
    }

    private fun assertMetadata(connection: Connection) {
        val accounts = columns(connection, "scheduling_patient_accounts")
        check(accounts == setOf(
            "id", "tenant_group_id", "patient_subject", "display_name", "password_hash",
            "active", "created_at", "updated_at",
        )) { "Unexpected patient account columns: $accounts" }

        val identities = columns(connection, "scheduling_patient_login_identities")
        check(identities == setOf(
            "id", "patient_account_id", "tenant_group_id", "identifier_key",
            "normalized_value", "created_at",
        )) { "Unexpected patient identity columns: $identities" }
        check(indexColumns(connection, "scheduling_patient_accounts", "uq_patient_accounts_tenant_subject") ==
            listOf("tenant_group_id", "patient_subject"))
        check(indexColumns(connection, "scheduling_patient_login_identities", "uq_patient_login_identities_tenant_key_value") ==
            listOf("tenant_group_id", "identifier_key", "normalized_value"))
        check(indexColumns(connection, "scheduling_patient_login_identities", "uq_patient_login_identities_account_key") ==
            listOf("patient_account_id", "identifier_key"))
    }

    private fun columns(connection: Connection, table: String): Set<String> {
        val result = linkedSetOf<String>()
        tableCandidates(table).forEach { candidate ->
            connection.metaData.getColumns(null, null, candidate, "%").use { rows ->
                while (rows.next()) rows.getString("COLUMN_NAME")?.lowercase()?.let(result::add)
            }
        }
        return result
    }

    private fun indexColumns(connection: Connection, table: String, index: String): List<String> {
        val rows = mutableListOf<Pair<Short, String>>()
        tableCandidates(table).forEach { candidate ->
            if (rows.isNotEmpty()) return@forEach
            connection.metaData.getIndexInfo(null, null, candidate, false, false).use { indexes ->
                while (indexes.next()) {
                    if (!indexes.getString("INDEX_NAME").equals(index, ignoreCase = true)) continue
                    indexes.getString("COLUMN_NAME")?.let { column ->
                        rows += indexes.getShort("ORDINAL_POSITION") to column.lowercase()
                    }
                }
            }
        }
        return rows.sortedBy { it.first }.map { it.second }
    }

    private fun tableCandidates(table: String): List<String> =
        listOf(table, table.uppercase(), table.lowercase()).distinct()
}
