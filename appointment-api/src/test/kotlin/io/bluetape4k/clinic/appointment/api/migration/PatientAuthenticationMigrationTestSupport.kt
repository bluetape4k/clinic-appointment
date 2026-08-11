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

    private fun columns(connection: Connection, table: String): Set<String> =
        connection.metaData.getColumns(null, null, table, null).use { rows ->
            buildSet {
                while (rows.next()) add(rows.getString("COLUMN_NAME").lowercase())
            }
        }

    private fun indexColumns(connection: Connection, table: String, index: String): List<String> =
        connection.metaData.getIndexInfo(null, null, table, true, false).use { rows ->
            buildList {
                while (rows.next()) {
                    if (rows.getString("INDEX_NAME")?.equals(index, ignoreCase = true) == true) {
                        add(rows.getString("COLUMN_NAME").lowercase())
                    }
                }
            }
        }
}
