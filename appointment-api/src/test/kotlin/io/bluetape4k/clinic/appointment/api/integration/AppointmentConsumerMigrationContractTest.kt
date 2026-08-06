package io.bluetape4k.clinic.appointment.api.integration

import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldBeFalse
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import java.sql.Driver

/** H2 migration proof for the metadata-only consumer inbox and quarantine tables. */
class AppointmentConsumerMigrationContractTest {
    @Test
    fun `V23 creates bounded tenant scoped consumer metadata tables`() {
        val driver = Class.forName("org.h2.Driver").getDeclaredConstructor().newInstance() as Driver
        val dataSource = SimpleDriverDataSource(
            driver,
            "jdbc:h2:mem:appointment_consumer_migration_${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        )
        dataSource.connection.use { connection ->
            val migration = checkNotNull(
                javaClass.getResourceAsStream("/db/migration/h2/V23__add_appointment_consumer_inbox.sql"),
            ).bufferedReader().readText()
            migration.split(';')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .forEach { statement -> connection.createStatement().use { it.execute(statement) } }

            val tableNames = mutableSetOf<String>()
            connection.metaData.getTables(null, null, null, arrayOf("TABLE")).use { rows ->
                while (rows.next()) rows.getString("TABLE_NAME")?.let(tableNames::add)
            }
            tableNames.contains("SCHEDULING_APPOINTMENT_CONSUMER_INBOX").shouldBeTrue()
            tableNames.contains("SCHEDULING_APPOINTMENT_CONSUMER_QUARANTINE").shouldBeTrue()
            tableNames.contains("SCHEDULING_APPOINTMENT_STATS_PROJECTION").shouldBeTrue()
            tableNames.contains("SCHEDULING_APPOINTMENT_CONSUMER_REPLAY_AUDIT").shouldBeTrue()

            val columns = mutableSetOf<String>()
            connection.metaData.getColumns(null, null, "SCHEDULING_APPOINTMENT_CONSUMER_INBOX", null).use { rows ->
                while (rows.next()) rows.getString("COLUMN_NAME")?.let(columns::add)
            }
            columns.containsAll(
                setOf(
                    "LOGICAL_CONSUMER_ID",
                    "LOGICAL_STREAM_ID",
                    "EVENT_ID",
                    "PARTITION_NUMBER",
                    "OFFSET_VALUE",
                    "TENANT_GROUP_ID",
                    "CLINIC_ID",
                    "PAYLOAD_SHA256",
                    "STATUS",
                ),
            ).shouldBeTrue()
            columns.contains("PAYLOAD_JSON").shouldBeFalse()

            val projectionColumns = mutableSetOf<String>()
            connection.metaData.getColumns(null, null, "SCHEDULING_APPOINTMENT_STATS_PROJECTION", null).use { rows ->
                while (rows.next()) rows.getString("COLUMN_NAME")?.let(projectionColumns::add)
            }
            projectionColumns.containsAll(
                setOf("TENANT_GROUP_ID", "CLINIC_ID", "EVENT_DATE", "STATUS", "APPOINTMENT_COUNT", "LAST_EVENT_VERSION"),
            ).shouldBeTrue()
            projectionColumns.contains("PAYLOAD_JSON").shouldBeFalse()
        }
    }
}
