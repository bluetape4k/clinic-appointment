package io.bluetape4k.clinic.appointment.api.migration

import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxEvents
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.Timestamp
import java.sql.Types
import javax.sql.DataSource

/** V22 appointment envelope/lease columns and dialect-specific index contract. */
internal object AppointmentMessagingMigrationTestSupport {

    fun verifyV22Migration(
        dataSource: DataSource,
        location: String,
    ) {
        val baseline = Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .cleanDisabled(false)
            .load()
        baseline.clean()

        Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .target("21")
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
            .target("22")
            .load()
            .migrate()
        check(result.success) { "V22 migration failed: ${result.warnings.joinToString()}" }
        check(result.migrationsExecuted == 1) {
            "Expected only V22 after target 21, executed=${result.migrationsExecuted}"
        }

        dataSource.connection.use { connection ->
            verifyColumns(connection)
            verifyIndexes(connection)
            verifyLegacyRowsRemainUnchanged(connection)
            verifyAppointmentMetadataRoundTrip(connection)
            verifyExposedColumnMetadata()
        }
    }

    private fun insertFixtures(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                INSERT INTO scheduling_tenant_groups(id, tenant_code, display_name, active)
                VALUES (91001, 'tenant-v22-fixture', 'V22 Fixture Tenant', TRUE)
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                INSERT INTO scheduling_clinics(id, name, tenant_group_id)
                VALUES (91002, 'V22 Fixture Clinic', 91001)
                """.trimIndent(),
            )
        }

        connection.prepareStatement(
            """
            INSERT INTO scheduling_outbox_events(
                id, event_id, causation_event_id, correlation_id, event_type,
                tenant_group_id, clinic_id, plan_id, schema_version, payload_json,
                status, attempt_count, next_attempt_at, created_at, published_at,
                aggregate_type, aggregate_id
            ) VALUES (?, ?, NULL, ?, ?, ?, ?, NULL, ?, ?, ?, ?, NULL, CURRENT_TIMESTAMP, NULL, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            insertOutboxFixture(
                statement = statement,
                id = LEGACY_OUTBOX_ID,
                eventId = "v22-legacy-event",
                correlationId = "v22-legacy-correlation",
                eventType = "LegacyPolicy",
                clinicId = null,
                aggregateType = null,
                aggregateId = null,
            )
            insertOutboxFixture(
                statement = statement,
                id = APPOINTMENT_OUTBOX_ID,
                eventId = "v22-appointment-event",
                correlationId = "v22-appointment-correlation",
                eventType = "AppointmentCreated",
                clinicId = FIXTURE_CLINIC_ID,
                aggregateType = "APPOINTMENT",
                aggregateId = "appointment-41",
            )
        }
    }

    private fun insertOutboxFixture(
        statement: java.sql.PreparedStatement,
        id: Long,
        eventId: String,
        correlationId: String,
        eventType: String,
        clinicId: Long?,
        aggregateType: String?,
        aggregateId: String?,
    ) {
        statement.setLong(1, id)
        statement.setString(2, eventId)
        statement.setString(3, correlationId)
        statement.setString(4, eventType)
        statement.setLong(5, FIXTURE_TENANT_ID)
        if (clinicId == null) {
            statement.setNull(6, Types.BIGINT)
        } else {
            statement.setLong(6, clinicId)
        }
        statement.setInt(7, 1)
        statement.setString(8, "{}")
        statement.setString(9, "PENDING")
        statement.setInt(10, 0)
        if (aggregateType == null) {
            statement.setNull(11, Types.VARCHAR)
        } else {
            statement.setString(11, aggregateType)
        }
        if (aggregateId == null) {
            statement.setNull(12, Types.VARCHAR)
        } else {
            statement.setString(12, aggregateId)
        }
        statement.executeUpdate()
    }

    private fun verifyColumns(connection: Connection) {
        EXPECTED_COLUMNS.forEach { (column, expectedSize) ->
            val metadata = findColumn(connection, OUTBOX_TABLE, column)
            check(metadata != null) { "Missing V22 column $OUTBOX_TABLE.$column" }
            check(metadata.nullable) { "V22 column $column must remain nullable" }
            if (expectedSize != null) {
                check(metadata.size == expectedSize) {
                    "Unexpected V22 size for $column: expected=$expectedSize actual=${metadata.size}"
                }
            }
        }
    }

    private fun verifyIndexes(connection: Connection) {
        val postgresql = connection.metaData.databaseProductName.contains("PostgreSQL", ignoreCase = true)
        val readyColumns = if (postgresql) {
            listOf("next_attempt_at", "lease_until", "created_at", "id")
        } else {
            listOf(
                "status",
                "aggregate_type",
                "event_type",
                "next_attempt_at",
                "lease_until",
                "created_at",
                "id",
            )
        }
        check(indexColumns(connection, OUTBOX_TABLE, READY_INDEX) == readyColumns) {
            "Unexpected V22 ready index columns: ${indexColumns(connection, OUTBOX_TABLE, READY_INDEX)}"
        }
        check(indexColumns(connection, OUTBOX_TABLE, LEASE_RECOVERY_INDEX) ==
            listOf("status", "aggregate_type", "event_type", "lease_until", "id")) {
            "Unexpected V22 lease-recovery index columns: " +
                indexColumns(connection, OUTBOX_TABLE, LEASE_RECOVERY_INDEX)
        }

        if (postgresql) {
            verifyPostgreSqlReadyPredicate(connection)
        }
    }

    private fun verifyPostgreSqlReadyPredicate(connection: Connection) {
        connection.prepareStatement(
            """
            SELECT indexdef
            FROM pg_indexes
            WHERE schemaname = current_schema()
              AND tablename = ?
              AND indexname = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, OUTBOX_TABLE)
            statement.setString(2, READY_INDEX)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "Missing PostgreSQL V22 ready index definition" }
                val definition = rows.getString(1).uppercase()
                check("STATUS" in definition && "'PENDING'" in definition) {
                    "V22 PostgreSQL ready index must constrain PENDING status: $definition"
                }
                check("AGGREGATE_TYPE" in definition && "'APPOINTMENT'" in definition) {
                    "V22 PostgreSQL ready index must constrain APPOINTMENT aggregate: $definition"
                }
                listOf(
                    "APPOINTMENTCREATED",
                    "APPOINTMENTSTATUSCHANGED",
                    "APPOINTMENTCANCELLED",
                    "APPOINTMENTRESCHEDULED",
                ).forEach { eventType ->
                    check(eventType in definition.replace("'", "").replace("_", "")) {
                        "V22 PostgreSQL ready index is missing event allow-list value $eventType: $definition"
                    }
                }
            }
        }
    }

    private fun verifyLegacyRowsRemainUnchanged(connection: Connection) {
        check(readCount(connection, "SELECT COUNT(*) FROM $OUTBOX_TABLE") == 2L) {
            "V22 must preserve both legacy and appointment rows"
        }
        check(
            readCount(
                connection,
                """
                SELECT COUNT(*)
                FROM $OUTBOX_TABLE
                WHERE occurred_at IS NOT NULL
                   OR topic IS NOT NULL
                   OR partition_key IS NOT NULL
                   OR lease_owner IS NOT NULL
                   OR lease_token IS NOT NULL
                   OR lease_until IS NOT NULL
                   OR last_failure_code IS NOT NULL
                   OR last_failure_at IS NOT NULL
                """.trimIndent(),
            ) == 0L,
        ) {
            "V22 migration must not backfill new appointment metadata"
        }
        connection.prepareStatement(
            "SELECT clinic_id, aggregate_type, aggregate_id FROM $OUTBOX_TABLE WHERE id = ?",
        ).use { statement ->
            statement.setLong(1, LEGACY_OUTBOX_ID)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "Missing V22 legacy fixture" }
                check(rows.getObject(1) == null) { "Legacy clinic_id must remain null" }
                check(rows.getString(2) == null) { "Legacy aggregate_type must remain null" }
                check(rows.getString(3) == null) { "Legacy aggregate_id must remain null" }
            }
        }
    }

    private fun verifyAppointmentMetadataRoundTrip(connection: Connection) {
        val occurredAt = Timestamp.valueOf("2026-08-05 08:30:00")
        val leaseUntil = Timestamp.valueOf("2026-08-05 08:30:30")
        connection.prepareStatement(
            """
            UPDATE $OUTBOX_TABLE
               SET occurred_at = ?,
                   topic = ?,
                   partition_key = ?,
                   lease_owner = ?,
                   lease_token = ?,
                   lease_until = ?,
                   last_failure_code = ?,
                   last_failure_at = ?
             WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setTimestamp(1, occurredAt)
            statement.setString(2, "clinic.appointment.events")
            statement.setString(3, "tenant-91001:CLINIC:clinic-91002:APPOINTMENT:apt-41")
            statement.setString(4, "relay-v22")
            statement.setString(5, "token-v22")
            statement.setTimestamp(6, leaseUntil)
            statement.setString(7, "BROKER_TIMEOUT")
            statement.setTimestamp(8, occurredAt)
            statement.setLong(9, APPOINTMENT_OUTBOX_ID)
            check(statement.executeUpdate() == 1) { "V22 appointment metadata update affected no row" }
        }

        connection.prepareStatement(
            """
            SELECT occurred_at, topic, partition_key, lease_owner, lease_token,
                   lease_until, last_failure_code, last_failure_at
            FROM $OUTBOX_TABLE
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, APPOINTMENT_OUTBOX_ID)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "Missing V22 appointment fixture" }
                check(rows.getTimestamp(1).toLocalDateTime() == occurredAt.toLocalDateTime())
                check(rows.getString(2) == "clinic.appointment.events")
                check(rows.getString(3) == "tenant-91001:CLINIC:clinic-91002:APPOINTMENT:apt-41")
                check(rows.getString(4) == "relay-v22")
                check(rows.getString(5) == "token-v22")
                check(rows.getTimestamp(6).toLocalDateTime() == leaseUntil.toLocalDateTime())
                check(rows.getString(7) == "BROKER_TIMEOUT")
                check(rows.getTimestamp(8).toLocalDateTime() == occurredAt.toLocalDateTime())
            }
        }
    }

    private fun verifyExposedColumnMetadata() {
        val modelColumns = setOf(
            SchedulingOutboxEvents.occurredAt.name,
            SchedulingOutboxEvents.topic.name,
            SchedulingOutboxEvents.partitionKey.name,
            SchedulingOutboxEvents.leaseOwner.name,
            SchedulingOutboxEvents.leaseToken.name,
            SchedulingOutboxEvents.leaseUntil.name,
            SchedulingOutboxEvents.lastFailureCode.name,
            SchedulingOutboxEvents.lastFailureAt.name,
        )
        check(modelColumns == EXPECTED_COLUMNS.keys) {
            "V22 Exposed model metadata drifted: $modelColumns"
        }
        val modelIndexes = SchedulingOutboxEvents.indices
            .mapNotNull { index -> index.customName?.let { it to index.columns.map { column -> column.name } } }
            .toMap()
        check(
            modelIndexes[READY_INDEX] == listOf(
                "status",
                "aggregate_type",
                "event_type",
                "next_attempt_at",
                "lease_until",
                "created_at",
                "id",
            ),
        ) {
            "V22 Exposed ready index metadata drifted: ${modelIndexes[READY_INDEX]}"
        }
        check(
            modelIndexes[LEASE_RECOVERY_INDEX] ==
                listOf("status", "aggregate_type", "event_type", "lease_until", "id"),
        ) {
            "V22 Exposed lease-recovery index metadata drifted: ${modelIndexes[LEASE_RECOVERY_INDEX]}"
        }
    }

    private fun readCount(connection: Connection, sql: String): Long =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                check(rows.next()) { "Expected count query to return one row: $sql" }
                rows.getLong(1)
            }
        }

    private fun indexColumns(connection: Connection, table: String, index: String): List<String> {
        val rows = mutableListOf<Pair<Short, String>>()
        tableCandidates(table).forEach { tableName ->
            if (rows.isNotEmpty()) return@forEach
            connection.metaData.getIndexInfo(null, null, tableName, false, false).use { indexes ->
                while (indexes.next()) {
                    if (!indexes.getString("INDEX_NAME").equals(index, ignoreCase = true)) continue
                    val column = indexes.getString("COLUMN_NAME") ?: continue
                    rows += indexes.getShort("ORDINAL_POSITION") to column.lowercase()
                }
            }
        }
        return rows.sortedBy { it.first }.map { it.second }
    }

    private fun findColumn(connection: Connection, table: String, column: String): ColumnMetadata? {
        tableCandidates(table).forEach { tableName ->
            connection.metaData.getColumns(null, null, tableName, "%").use { columns ->
                while (columns.next()) {
                    if (columns.getString("COLUMN_NAME").equals(column, ignoreCase = true)) {
                        return ColumnMetadata(
                            nullable = columns.getInt("NULLABLE") == DatabaseMetaData.columnNullable,
                            size = columns.getLong("COLUMN_SIZE"),
                        )
                    }
                }
            }
        }
        return null
    }

    private fun tableCandidates(table: String): List<String> =
        listOf(table, table.uppercase(), table.lowercase()).distinct()

    private data class ColumnMetadata(
        val nullable: Boolean,
        val size: Long,
    )

    private const val OUTBOX_TABLE = "scheduling_outbox_events"
    private const val READY_INDEX = "idx_outbox_appointment_ready"
    private const val LEASE_RECOVERY_INDEX = "idx_outbox_appointment_lease_recovery"
    private const val FIXTURE_TENANT_ID = 91_001L
    private const val FIXTURE_CLINIC_ID = 91_002L
    private const val LEGACY_OUTBOX_ID = 91_003L
    private const val APPOINTMENT_OUTBOX_ID = 91_004L

    private val EXPECTED_COLUMNS = linkedMapOf(
        "occurred_at" to null,
        "topic" to 249L,
        "partition_key" to 512L,
        "lease_owner" to 160L,
        "lease_token" to 128L,
        "lease_until" to null,
        "last_failure_code" to 64L,
        "last_failure_at" to null,
    )
}
