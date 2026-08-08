package io.bluetape4k.clinic.appointment.api.migration

import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxEvents
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.Timestamp
import java.sql.Types
import javax.sql.DataSource

/** V22 appointment envelope/lease 컬럼과 dialect별 인덱스 계약. */
internal object AppointmentMessagingMigrationTestSupport {

    /**
     * V23 consumer metadata 계약을 실제 JDBC 메타데이터와 대조한다.
     *
     * dialect별 마이그레이션에서 테이블, 키, retention 인덱스가 조용히 누락되지 않도록
     * H2, MySQL, PostgreSQL 테스트가 의도적으로 같은 helper를 사용한다.
     */
    fun verifyV23Migration(
        dataSource: DataSource,
        location: String,
    ) {
        Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .cleanDisabled(false)
            .load()
            .clean()

        Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .target("22")
            .load()
            .migrate()

        val result = Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .target("23")
            .load()
            .migrate()
        check(result.success) { "V23 migration failed: ${result.warnings.joinToString()}" }
        check(result.migrationsExecuted == 1) {
            "Expected only V23 after target 22, executed=${result.migrationsExecuted}"
        }

        verifyV23Metadata(dataSource)
    }

    /**
     * Flyway를 실행하거나 행을 변경하지 않고 이미 적용된 V23 메타데이터만 검증한다.
     *
     * 운영/스테이징 endpoint smoke test에서 사용할 수 있는 안전한 진입점이다. 운영
     * 데이터베이스에 마이그레이션을 적용하는 작업은 여전히 운영자가 통제하는 변경 창에서 수행한다.
     */
    fun verifyV23Metadata(dataSource: DataSource) {
        dataSource.connection.use(::assertV23Metadata)
    }

    /** V24 aggregate lock table을 V23 이후 additive migration으로 검증합니다. */
    fun verifyV24Migration(
        dataSource: DataSource,
        location: String,
    ) {
        Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .cleanDisabled(false)
            .load()
            .clean()

        Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .target("23")
            .load()
            .migrate()

        val result = Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .target("24")
            .load()
            .migrate()
        check(result.success) { "V24 migration failed: ${result.warnings.joinToString()}" }
        check(result.migrationsExecuted == 1) {
            "Expected only V24 after target 23, executed=${result.migrationsExecuted}"
        }

        verifyV23Metadata(dataSource)
        verifyV24Metadata(dataSource)
    }

    /** Flyway를 실행하지 않고 V24 aggregate lock table metadata만 검증합니다. */
    fun verifyV24Metadata(dataSource: DataSource) {
        dataSource.connection.use(::assertV24Metadata)
    }

    /** V25 replay audit hash 계약을 V24 이후 additive migration으로 검증합니다. */
    fun verifyV25Migration(
        dataSource: DataSource,
        location: String,
    ) {
        Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .cleanDisabled(false)
            .load()
            .clean()

        Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .target("24")
            .load()
            .migrate()

        val result = Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .target("25")
            .load()
            .migrate()
        check(result.success) { "V25 migration failed: ${result.warnings.joinToString()}" }
        check(result.migrationsExecuted == 1) {
            "Expected only V25 after target 24, executed=${result.migrationsExecuted}"
        }

        dataSource.connection.use { connection ->
            assertV23Metadata(connection, V25_REPLAY_AUDIT_COLUMNS)
        }
        verifyV24Metadata(dataSource)
        verifyV25Metadata(dataSource)
    }

    /** Flyway를 실행하지 않고 V25 replay audit metadata만 검증합니다. */
    fun verifyV25Metadata(dataSource: DataSource) {
        dataSource.connection.use(::assertV25Metadata)
    }

    private fun assertV23Metadata(
        connection: Connection,
        replayAuditAdditiveColumns: Set<String> = emptySet(),
    ) {
        V23_TABLES.forEach { (table, expectedColumns) ->
            check(tableExists(connection, table)) { "Missing V23 table $table" }
            val actualColumns = columns(connection, table)
            val expected = if (table == "scheduling_appointment_consumer_replay_audit") {
                expectedColumns + replayAuditAdditiveColumns
            } else {
                expectedColumns
            }
            check(actualColumns == expected) {
                "Unexpected V23 columns for $table: expected=$expected actual=$actualColumns"
            }
        }
        V23_PRIMARY_KEYS.forEach { (table, expected) ->
            check(primaryKeyColumns(connection, table) == expected) {
                "Unexpected V23 primary key for $table: ${primaryKeyColumns(connection, table)}"
            }
        }
        V23_INDEXES.forEach { (table, indexes) ->
            indexes.forEach { (name, expectedColumns) ->
                check(indexColumns(connection, table, name) == expectedColumns) {
                    "Unexpected V23 index $name on $table: " +
                        indexColumns(connection, table, name)
                }
            }
        }
        if (connection.metaData.databaseProductName.contains("MySQL", ignoreCase = true)) {
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT DATABASE()").use { rows ->
                    check(rows.next() && !rows.getString(1).isNullOrBlank()) {
                        "MySQL V23 verification must run against a selected catalog"
                    }
                }
            }
        }
    }

    private fun assertV24Metadata(connection: Connection) {
        V24_TABLES.forEach { (table, expectedColumns) ->
            check(tableExists(connection, table)) { "Missing V24 table $table" }
            val actualColumns = columns(connection, table)
            check(actualColumns == expectedColumns) {
                "Unexpected V24 columns for $table: expected=$expectedColumns actual=$actualColumns"
            }
        }
        V24_PRIMARY_KEYS.forEach { (table, expected) ->
            check(primaryKeyColumns(connection, table) == expected) {
                "Unexpected V24 primary key for $table: ${primaryKeyColumns(connection, table)}"
            }
        }
    }

    private fun assertV25Metadata(connection: Connection) {
        val table = "scheduling_appointment_consumer_replay_audit"
        check(tableExists(connection, table)) { "Missing V25 table $table" }
        val expectedColumns = requireNotNull(V23_TABLES[table]) + setOf("hash_version", "partition_number")
        val actualColumns = columns(connection, table)
        check(actualColumns == expectedColumns) {
            "Unexpected V25 columns for $table: expected=$expectedColumns actual=$actualColumns"
        }
        val hashVersion = requireNotNull(findColumn(connection, table, "hash_version")) {
            "Missing V25 hash_version column"
        }
        check(!hashVersion.nullable) {
            "V25 hash_version must be NOT NULL"
        }
        val partitionNumber = requireNotNull(findColumn(connection, table, "partition_number")) {
            "Missing V25 partition_number column"
        }
        check(partitionNumber.nullable) {
            "V25 partition_number must remain nullable for legacy audit rows"
        }
        check(hashVersion.defaultValue?.contains("1") == true) {
            "V25 hash_version must default legacy rows to 1: ${hashVersion.defaultValue}"
        }
    }

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

    private fun tableExists(connection: Connection, table: String): Boolean =
        tableCandidates(table).any { candidate ->
            connection.metaData.getTables(null, null, candidate, arrayOf("TABLE")).use { rows ->
                rows.next()
            }
        }

    private fun columns(connection: Connection, table: String): Set<String> {
        val result = linkedSetOf<String>()
        tableCandidates(table).forEach { candidate ->
            connection.metaData.getColumns(null, null, candidate, "%").use { rows ->
                while (rows.next()) rows.getString("COLUMN_NAME")?.lowercase()?.let(result::add)
            }
            if (result.isNotEmpty()) return@forEach
        }
        return result
    }

    private fun primaryKeyColumns(connection: Connection, table: String): List<String> {
        val rows = mutableListOf<Pair<Short, String>>()
        tableCandidates(table).forEach { candidate ->
            if (rows.isNotEmpty()) return@forEach
            connection.metaData.getPrimaryKeys(null, null, candidate).use { keys ->
                while (keys.next()) {
                    rows += keys.getShort("KEY_SEQ") to keys.getString("COLUMN_NAME").lowercase()
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
                            defaultValue = columns.getString("COLUMN_DEF"),
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
        val defaultValue: String?,
    )

    private const val OUTBOX_TABLE = "scheduling_outbox_events"
    private const val READY_INDEX = "idx_outbox_appointment_ready"
    private const val LEASE_RECOVERY_INDEX = "idx_outbox_appointment_lease_recovery"
    private const val FIXTURE_TENANT_ID = 91_001L
    private const val FIXTURE_CLINIC_ID = 91_002L
    private const val LEGACY_OUTBOX_ID = 91_003L
    private const val APPOINTMENT_OUTBOX_ID = 91_004L
    private val V25_REPLAY_AUDIT_COLUMNS = setOf("hash_version", "partition_number")

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

    private val V23_TABLES = linkedMapOf(
        "scheduling_appointment_consumer_inbox" to setOf(
            "logical_consumer_id", "logical_stream_id", "event_id", "topic",
            "partition_number", "offset_value", "schema_version", "tenant_group_id",
            "clinic_id", "payload_sha256", "status", "attempt_count", "failure_code",
            "received_at", "processed_at", "processing_lease_until",
        ),
        "scheduling_appointment_consumer_rejected" to setOf(
            "id", "logical_consumer_id", "logical_stream_id", "failure_code", "topic",
            "partition_number", "offset_value", "payload_sha256", "created_at",
        ),
        "scheduling_appointment_consumer_quarantine" to setOf(
            "id", "logical_consumer_id", "logical_stream_id", "event_id", "failure_code",
            "topic", "partition_number", "offset_value", "schema_version", "tenant_group_id",
            "clinic_id", "payload_sha256", "created_at",
        ),
        "scheduling_appointment_stats_projection" to setOf(
            "tenant_group_id", "clinic_id", "event_date", "status", "appointment_count",
            "last_event_version", "last_event_id", "updated_at",
        ),
        "scheduling_appointment_stats_projection_events" to setOf(
            "tenant_group_id", "clinic_id", "aggregate_id", "event_id", "event_version",
            "event_date", "status", "created_at",
        ),
        "scheduling_appointment_consumer_replay_audit" to setOf(
            "id", "request_id", "logical_consumer_id", "logical_stream_id", "tenant_group_id",
            "clinic_id", "from_offset", "to_offset", "request_hash", "dry_run", "approved_by",
            "status", "created_at", "completed_at",
        ),
    )

    private val V23_PRIMARY_KEYS = mapOf(
        "scheduling_appointment_consumer_inbox" to listOf(
            "logical_consumer_id", "logical_stream_id", "event_id",
        ),
        "scheduling_appointment_consumer_rejected" to listOf("id"),
        "scheduling_appointment_consumer_quarantine" to listOf("id"),
        "scheduling_appointment_stats_projection" to listOf(
            "tenant_group_id", "clinic_id", "event_date", "status",
        ),
        "scheduling_appointment_stats_projection_events" to listOf(
            "tenant_group_id", "clinic_id", "aggregate_id", "event_id",
        ),
        "scheduling_appointment_consumer_replay_audit" to listOf("id"),
    )

    private val V23_INDEXES = mapOf(
        "scheduling_appointment_consumer_inbox" to mapOf(
            "idx_appointment_consumer_inbox_status_received" to
                listOf("logical_consumer_id", "status", "received_at"),
            "idx_appointment_consumer_inbox_scope" to
                listOf("logical_consumer_id", "tenant_group_id", "clinic_id", "received_at"),
            "idx_appointment_consumer_inbox_status_processed" to listOf(
                "logical_consumer_id", "status", "processed_at", "logical_stream_id", "event_id",
            ),
        ),
        "scheduling_appointment_consumer_rejected" to mapOf(
            "idx_appointment_consumer_rejected_created" to listOf("created_at"),
        ),
        "scheduling_appointment_consumer_quarantine" to mapOf(
            "idx_appointment_consumer_quarantine_created" to listOf("created_at"),
        ),
        "scheduling_appointment_stats_projection" to mapOf(
            "idx_appointment_stats_projection_scope_date" to
                listOf("tenant_group_id", "clinic_id", "event_date"),
            "idx_appointment_stats_projection_scope_status_date" to
                listOf("tenant_group_id", "clinic_id", "status", "event_date"),
        ),
        "scheduling_appointment_stats_projection_events" to mapOf(
            "idx_appointment_stats_projection_events_scope_date" to
                listOf("tenant_group_id", "clinic_id", "event_date"),
        ),
        "scheduling_appointment_consumer_replay_audit" to mapOf(
            "idx_appointment_consumer_replay_audit_scope_created" to
                listOf("tenant_group_id", "clinic_id", "created_at"),
        ),
    )

    private val V24_TABLES = linkedMapOf(
        "scheduling_appointment_stats_projection_aggregate_locks" to setOf(
            "tenant_group_id", "clinic_id", "aggregate_id",
        ),
    )

    private val V24_PRIMARY_KEYS = mapOf(
        "scheduling_appointment_stats_projection_aggregate_locks" to listOf(
            "tenant_group_id", "clinic_id", "aggregate_id",
        ),
    )
}
