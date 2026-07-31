package io.bluetape4k.clinic.appointment.api.migration

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.event.notification.NotificationDeliveryAttempts
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxEvents
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Timestamp
import javax.sql.DataSource

/**
 * 알림 outbox V14 스키마가 clean 설치와 V13 업그레이드에서 같은 계약을 제공하는지 검증합니다.
 *
 * 이 검증은 민감한 수신자 정보가 outbox에 오래 남지 않도록 row-kind별 필수값과
 * terminal redaction을 실제 DML 실패로 확인합니다. metadata 검사는 worker query가
 * 의존하는 index 이름과 열 순서를 함께 잠급니다.
 */
internal object NotificationOutboxMigrationTestSupport {

    fun verifyV14Migration(
        dataSource: DataSource,
        location: String,
    ) {
        verifyUpgradeMigration(dataSource, location)
        verifyCleanMigration(dataSource, location)
        verifyExposedModelHasNoAdditiveDrift(dataSource)
    }

    private fun verifyUpgradeMigration(
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
            .target("13")
            .load()
            .migrate()

        val result = Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .target("14")
            .load()
            .migrate()

        result.success shouldBeEqualTo true
        result.migrationsExecuted shouldBeEqualTo 1
        val applied = Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .load()
            .info()
            .applied()
            .single { it.version?.version == "14" }
        check(applied.checksum != null) { "Applied V14 checksum must be recorded" }

        dataSource.connection.use(::verifyNotificationSchema)
    }

    private fun verifyCleanMigration(
        dataSource: DataSource,
        location: String,
    ) {
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .target("14")
            .cleanDisabled(false)
            .load()
        flyway.clean()

        val result = flyway.migrate()

        result.success shouldBeEqualTo true
        result.migrationsExecuted shouldBeEqualTo 14
        dataSource.connection.use(::verifyNotificationSchema)
    }

    private fun verifyNotificationSchema(connection: Connection) {
        val tables = tableNames(connection)
        check(EXPECTED_TABLES.all(tables::contains)) {
            "Missing V14 tables: ${EXPECTED_TABLES - tables}"
        }
        verifyColumns(connection)
        verifyIndexes(connection)
        verifyForeignKeys(connection)
        verifyUniqueConstraints(connection)
        verifyLifecycleConstraints(connection)
    }

    private fun verifyColumns(connection: Connection) {
        EXPECTED_COLUMNS.forEach { (table, expected) ->
            val actual = columnNames(connection, table)
            check(actual == expected) {
                "$table columns differ. expected=$expected actual=$actual"
            }
        }
    }

    private fun verifyIndexes(connection: Connection) {
        EXPECTED_INDEXES.forEach { (table, expectedIndexes) ->
            expectedIndexes.forEach { (index, columns) ->
                indexDefinition(connection, table, index) shouldBeEqualTo columns
            }
        }
    }

    private fun verifyForeignKeys(connection: Connection) {
        importedKeys(connection, "clinic_notification_delivery_attempts")
            .contains("outbox_id->clinic_notification_outbox") shouldBeEqualTo true
    }

    private fun verifyUniqueConstraints(connection: Connection) {
        uniqueIndexColumns(
            connection,
            "clinic_notification_outbox",
            "uk_notification_outbox_idempotency",
        ) shouldBeEqualTo listOf("idempotency_key_version", "idempotency_key")
        uniqueIndexColumns(
            connection,
            "clinic_notification_delivery_attempts",
            "uk_notification_delivery_attempt_number",
        ) shouldBeEqualTo listOf("outbox_id", "attempt_number")
    }

    private fun verifyLifecycleConstraints(connection: Connection) {
        insertSendable(connection, id = 1L)
        insertAttempt(connection, id = 1L, outboxId = 1L)

        expectConstraintViolation("active SENDABLE must include member_id") {
            insertSendable(connection, id = 2L, memberId = null)
        }
        expectConstraintViolation("terminal SENDABLE must redact member_id") {
            insertSendable(connection, id = 3L, status = "SENT", memberId = "member-3", parametersJson = null)
        }
        expectConstraintViolation("terminal SENDABLE must redact parameters_json") {
            insertSendable(connection, id = 4L, status = "EXHAUSTED", memberId = null, parametersJson = "{}")
        }
        expectConstraintViolation("legacy suppression must be SUPPRESSED") {
            insertLegacySuppression(connection, id = 5L, status = "PENDING")
        }
        expectConstraintViolation("legacy suppression must not keep template_key") {
            insertLegacySuppression(connection, id = 6L, templateKey = "appointment-confirmed")
        }
        expectConstraintViolation("idempotency digest must be unique") {
            insertSendable(connection, id = 7L, idempotencyKey = "digest-1")
        }
    }

    private fun insertSendable(
        connection: Connection,
        id: Long,
        status: String = "PENDING",
        memberId: String? = "member-$id",
        parametersJson: String? = "{}",
        idempotencyKey: String = "digest-$id",
    ) {
        connection.prepareStatement(SENDABLE_INSERT_SQL).use { statement ->
            bindCommon(statement, id, rowKind = "SENDABLE", status = status, idempotencyKey = idempotencyKey)
            statement.setLong(11, 1000L + id)
            statement.setString(12, memberId)
            statement.setString(13, "SMS")
            statement.setString(14, "CONFIRMED")
            statement.setString(15, "CONFIRMED")
            statement.setString(16, "provider")
            statement.setString(17, "appointment-confirmed")
            statement.setInt(18, 1)
            statement.setString(19, "APPOINTMENT_CONFIRMED")
            statement.setString(20, parametersJson)
            statement.setString(21, null)
            statement.executeUpdate() shouldBeEqualTo 1
        }
    }

    private fun insertLegacySuppression(
        connection: Connection,
        id: Long,
        status: String = "SUPPRESSED",
        templateKey: String? = null,
    ) {
        connection.prepareStatement(SENDABLE_INSERT_SQL).use { statement ->
            bindCommon(statement, id, rowKind = "LEGACY_SUPPRESSION", status = status)
            statement.setObject(11, null)
            statement.setString(12, null)
            statement.setString(13, null)
            statement.setString(14, null)
            statement.setString(15, null)
            statement.setString(16, null)
            statement.setString(17, templateKey)
            statement.setObject(18, null)
            statement.setString(19, null)
            statement.setString(20, null)
            statement.setString(21, "MEMBER_ID_MISSING_LEGACY")
            statement.executeUpdate() shouldBeEqualTo 1
        }
    }

    private fun bindCommon(
        statement: PreparedStatement,
        id: Long,
        rowKind: String,
        status: String,
        idempotencyKey: String = "digest-$id",
    ) {
        val now = Timestamp.valueOf("2026-07-31 00:00:00")
        statement.setLong(1, id)
        statement.setString(2, rowKind)
        statement.setString(3, status)
        statement.setInt(4, 1)
        statement.setString(5, idempotencyKey)
        statement.setString(6, "key-id")
        statement.setInt(7, 1)
        statement.setString(8, "fingerprint-$id")
        statement.setString(9, "audit-key-id")
        statement.setLong(10, 10L)
        statement.setLong(22, 20L)
        statement.setString(23, "event-$id")
        statement.setTimestamp(24, now)
        statement.setTimestamp(25, now)
        statement.setTimestamp(26, now)
    }

    private fun insertAttempt(
        connection: Connection,
        id: Long,
        outboxId: Long,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO clinic_notification_delivery_attempts(
                id, outbox_id, attempt_number, owner, token, channel, event_type,
                template_key, template_version, started_at
            ) VALUES (?, ?, 1, 'worker-a', 'token-a', 'SMS', 'CONFIRMED',
                      'appointment-confirmed', 1, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, id)
            statement.setLong(2, outboxId)
            statement.setTimestamp(3, Timestamp.valueOf("2026-07-31 00:00:00"))
            statement.executeUpdate() shouldBeEqualTo 1
        }
    }

    private fun verifyExposedModelHasNoAdditiveDrift(dataSource: DataSource) {
        val database = Database.connect(dataSource)
        val additiveDrift = transaction(database) {
            MigrationUtils.statementsRequiredForDatabaseMigration(
                NotificationOutboxEvents,
                NotificationDeliveryAttempts,
                withLogs = false,
            ).filter(::isAdditiveSchemaChange)
        }
        check(additiveDrift.isEmpty()) {
            "Flyway V14 is missing additive DDL required by Exposed:\n" +
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

    private fun expectConstraintViolation(
        description: String,
        block: () -> Unit,
    ) {
        val failure = try {
            block()
            null
        } catch (e: SQLException) {
            e
        }
        check(failure != null) { "Expected constraint violation: $description" }
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

    private fun indexDefinition(
        connection: Connection,
        table: String,
        index: String,
    ): List<String> =
        metadataTableCandidates(table).firstNotNullOfOrNull { candidate ->
            connection.metaData.getIndexInfo(null, null, candidate, false, false).use { rows ->
                buildList<Pair<Int, String>> {
                    while (rows.next()) {
                        val actualName = rows.getString("INDEX_NAME")?.lowercase() ?: continue
                        if (actualName != index.lowercase() && !actualName.startsWith("${index.lowercase()}_index_")) continue
                        val column = rows.getString("COLUMN_NAME")?.lowercase() ?: continue
                        val direction = if (rows.getString("ASC_OR_DESC") == "D") "D" else "A"
                        add(rows.getInt("ORDINAL_POSITION") to "$column:$direction")
                    }
                }
                    .sortedBy(Pair<Int, String>::first)
                    .map(Pair<Int, String>::second)
                    .takeIf(List<String>::isNotEmpty)
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
                                rows.getString("PKTABLE_NAME").lowercase(),
                        )
                    }
                }.takeIf(Set<String>::isNotEmpty)
            }
        }.orEmpty()

    private fun uniqueIndexColumns(
        connection: Connection,
        table: String,
        index: String,
    ): List<String> =
        metadataTableCandidates(table).firstNotNullOfOrNull { candidate ->
            connection.metaData.getIndexInfo(null, null, candidate, true, false).use { rows ->
                buildList<Pair<Int, String>> {
                    while (rows.next()) {
                        val actualName = rows.getString("INDEX_NAME")?.lowercase() ?: continue
                        if (actualName != index.lowercase() && !actualName.startsWith("${index.lowercase()}_index_")) continue
                        val column = rows.getString("COLUMN_NAME")?.lowercase() ?: continue
                        add(rows.getInt("ORDINAL_POSITION") to column)
                    }
                }
                    .sortedBy(Pair<Int, String>::first)
                    .map(Pair<Int, String>::second)
                    .takeIf(List<String>::isNotEmpty)
            }
        }.orEmpty()

    private fun metadataTableCandidates(table: String) = listOf(table, table.uppercase())

    private val EXPECTED_TABLES = setOf(
        "clinic_notification_outbox",
        "clinic_notification_delivery_attempts",
    )

    private val EXPECTED_COLUMNS = mapOf(
        "clinic_notification_outbox" to setOf(
            "id",
            "row_kind",
            "status",
            "idempotency_key_version",
            "idempotency_key",
            "idempotency_key_id",
            "audit_fingerprint_version",
            "audit_fingerprint",
            "audit_fingerprint_key_id",
            "tenant_group_id",
            "clinic_id",
            "event_id",
            "appointment_id",
            "member_id",
            "channel",
            "event_type",
            "notification_slot",
            "provider_key",
            "template_key",
            "template_version",
            "parameter_type",
            "parameters_json",
            "suppression_reason",
            "failure_code",
            "provider_message_reference",
            "destination_fingerprint",
            "correlation_id",
            "trace_id",
            "available_at",
            "next_retry_at",
            "lease_owner",
            "lease_token",
            "lease_until",
            "attempt_number",
            "created_at",
            "updated_at",
            "terminal_at",
        ),
        "clinic_notification_delivery_attempts" to setOf(
            "id",
            "outbox_id",
            "attempt_number",
            "owner",
            "token",
            "channel",
            "event_type",
            "template_key",
            "template_version",
            "started_at",
            "completed_at",
            "duration_millis",
            "outcome",
            "failure_code",
            "provider_message_reference",
            "destination_fingerprint",
            "correlation_id",
            "trace_id",
        ),
    )

    private val EXPECTED_INDEXES = mapOf(
        "clinic_notification_outbox" to mapOf(
            "idx_notification_outbox_ready_clinic_cursor" to listOf(
                "row_kind:A",
                "status:A",
                "available_at:A",
                "next_retry_at:A",
                "tenant_group_id:A",
                "clinic_id:A",
            ),
            "idx_notification_outbox_ready_within_clinic" to listOf(
                "tenant_group_id:A",
                "clinic_id:A",
                "row_kind:A",
                "status:A",
                "available_at:A",
                "id:A",
                "next_retry_at:A",
            ),
            "idx_notification_outbox_lease_recovery" to listOf(
                "row_kind:A",
                "status:A",
                "lease_until:A",
                "id:A",
            ),
            "idx_notification_outbox_terminal_retention" to listOf(
                "row_kind:A",
                "status:A",
                "terminal_at:A",
                "id:A",
            ),
            "idx_notification_outbox_pending_oldest" to listOf(
                "row_kind:A",
                "status:A",
                "available_at:A",
                "created_at:A",
            ),
        ),
        "clinic_notification_delivery_attempts" to mapOf(
            "idx_notification_delivery_attempt_completed_retention" to listOf(
                "completed_at:A",
                "id:A",
            ),
        ),
    )

    private const val SENDABLE_INSERT_SQL = """
        INSERT INTO clinic_notification_outbox(
            id, row_kind, status, idempotency_key_version, idempotency_key,
            idempotency_key_id, audit_fingerprint_version, audit_fingerprint,
            audit_fingerprint_key_id, tenant_group_id, appointment_id, member_id,
            channel, event_type, notification_slot, provider_key, template_key,
            template_version, parameter_type, parameters_json, suppression_reason,
            clinic_id, event_id, available_at, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """
}
