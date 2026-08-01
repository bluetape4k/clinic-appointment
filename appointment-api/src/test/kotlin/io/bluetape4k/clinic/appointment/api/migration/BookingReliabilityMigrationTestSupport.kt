package io.bluetape4k.clinic.appointment.api.migration

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

/** V17의 네 원장과 개인정보 비저장 계약을 clean 설치 및 V16 업그레이드에서 검증합니다. */
internal object BookingReliabilityMigrationTestSupport {

    private val tables = listOf(
        "booking_reliability_events",
        "booking_reliability_decisions",
        "booking_reliability_overrides",
        "booking_reliability_reevaluation_jobs",
    )

    fun verifyV17Migration(dataSource: DataSource, location: String) {
        Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .cleanDisabled(false)
            .load()
            .clean()

        Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .target("16")
            .load()
            .migrate()
        val result = Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .target("17")
            .load()
            .migrate()

        result.success shouldBeEqualTo true
        result.migrationsExecuted shouldBeEqualTo 1
        dataSource.connection.use(::verifyReliabilityContract)
    }

    private fun verifyReliabilityContract(connection: Connection) {
        tables.forEach { table ->
            tableExists(connection, table).shouldBeTrue()
            verifyNoPiiColumns(connection, table)
        }
        verifyExpectedIndexes(connection)

        insertReliabilityEvent(connection, eventId = "reliability-event-1", sourceVersion = 1)

        tryUpdate(connection) {
            insertReliabilityEvent(connection, eventId = "reliability-event-1", sourceVersion = 1)
        }.shouldBeEqualTo(true)

        insertReliabilityEvent(connection, eventId = "reliability-event-1", sourceVersion = 2)

        tryUpdate(connection) {
            insertReliabilityEvent(
                connection = connection,
                eventId = "reliability-event-invalid-hash",
                sourceVersion = 3,
                eventHash = "not-a-sha256",
            )
        }.shouldBeEqualTo(true)

        if (!connection.metaData.databaseProductName.contains("H2", ignoreCase = true)) {
            tryUpdate(connection) {
                insertReliabilityEvent(
                    connection = connection,
                    eventId = "reliability-event-invalid-responsibility",
                    sourceVersion = 4,
                    responsibility = "SUBJECTIVE_LABEL",
                )
            }.shouldBeEqualTo(true)
        }
    }

    private fun tableExists(connection: Connection, table: String): Boolean =
        connection.metaData.getTables(null, null, table, arrayOf("TABLE")).use { rows ->
            rows.next()
        } || connection.metaData.getTables(null, null, table.uppercase(), arrayOf("TABLE")).use { rows ->
            rows.next()
        }

    private fun verifyNoPiiColumns(connection: Connection, table: String) {
        val columns = mutableSetOf<String>()
        connection.metaData.getColumns(null, null, table, null).use { rows ->
            while (rows.next()) columns += rows.getString("COLUMN_NAME").lowercase()
        }
        if (columns.isEmpty()) {
            connection.metaData.getColumns(null, null, table.uppercase(), null).use { rows ->
                while (rows.next()) columns += rows.getString("COLUMN_NAME").lowercase()
            }
        }
        listOf("patient_name", "patient_phone", "signature", "raw_payload", "payload_json", "patient_reference_fingerprint")
            .forEach { columns.contains(it).shouldBeFalse() }
    }

    private fun verifyExpectedIndexes(connection: Connection) {
        val names = mutableSetOf<String>()
        tables.forEach { table ->
            connection.metaData.getIndexInfo(null, null, table, false, false).use { rows ->
                while (rows.next()) rows.getString("INDEX_NAME")?.lowercase()?.let(names::add)
            }
            connection.metaData.getIndexInfo(null, null, table.uppercase(), false, false).use { rows ->
                while (rows.next()) rows.getString("INDEX_NAME")?.lowercase()?.let(names::add)
            }
        }
        listOf(
            "ux_booking_reliability_event_identity",
            "ux_booking_reliability_decision_digest",
            "ux_booking_reliability_override_idempotency",
            "ux_booking_reliability_reevaluation_idempotency",
            "idx_booking_reliability_event_member_time",
            "idx_booking_reliability_decision_member_latest",
        ).forEach {
            check(names.contains(it)) { "Missing booking reliability index $it; actual=$names" }
        }
    }

    private fun insertReliabilityEvent(
        connection: Connection,
        eventId: String,
        sourceVersion: Long,
        eventHash: String = "b".repeat(64),
        responsibility: String = "PATIENT",
    ) {
        connection.prepareStatement(
            """
            INSERT INTO booking_reliability_events (
                tenant_group_id, clinic_id, member_id, event_id, appointment_id,
                event_type, responsibility, scheduled_start_at, occurred_at,
                source_version, event_hash, source, correlation_id, retention_class
            ) VALUES (1, 1, 'member-opaque-1', ?, 100, 'NO_SHOW', ?,
                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?, 'APPOINTMENT',
                'migration-test', 'STANDARD')
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, eventId)
            statement.setString(2, responsibility)
            statement.setLong(3, sourceVersion)
            statement.setString(4, eventHash)
            statement.executeUpdate()
        }
    }

    private fun tryUpdate(connection: Connection, block: () -> Unit): Boolean =
        try {
            block()
            false
        } catch (_: SQLException) {
            true
        }
}
