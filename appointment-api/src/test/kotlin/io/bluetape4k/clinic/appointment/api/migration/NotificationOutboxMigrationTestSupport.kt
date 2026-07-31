package io.bluetape4k.clinic.appointment.api.migration

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.event.notification.NotificationDeliveryAttempts
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxEvents
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import java.sql.Connection
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
        insertOutbox(connection, sendableRow(id = 1L))
        insertAttempt(connection, id = 1L, outboxId = 1L)
        insertOutbox(connection, sendableRow(id = 2L, status = "SENT").copy(appointmentId = null, memberId = null, parametersJson = null))
        insertOutbox(connection, legacySuppressionRow(id = 3L))

        VALID_PARAMETER_TYPES.forEachIndexed { offset, parameterType ->
            insertOutbox(
                connection,
                sendableRow(id = 10L + offset).copy(parameterType = parameterType),
            )
        }

        ACTIVE_REQUIRED_FIELDS.forEachIndexed { offset, testCase ->
            expectConstraintViolation("active SENDABLE must include ${testCase.field}") {
                insertOutbox(connection, testCase.mutate(sendableRow(id = 100L + offset)))
            }
        }

        TERMINAL_REDACTION_FIELDS.forEachIndexed { offset, testCase ->
            expectConstraintViolation("terminal SENDABLE must redact ${testCase.field}") {
                insertOutbox(
                    connection,
                    testCase.mutate(
                        sendableRow(id = 200L + offset, status = "SENT")
                            .copy(appointmentId = null, memberId = null, parametersJson = null),
                    ),
                )
            }
        }

        LEGACY_SUPPRESSION_FIELDS.forEachIndexed { offset, testCase ->
            expectConstraintViolation("LEGACY_SUPPRESSION must reject ${testCase.field}") {
                insertOutbox(connection, testCase.mutate(legacySuppressionRow(id = 300L + offset)))
            }
        }

        ALLOW_LIST_FIELDS.forEachIndexed { offset, testCase ->
            expectConstraintViolation("allow-list must reject invalid ${testCase.field}") {
                insertOutbox(connection, testCase.mutate(sendableRow(id = 400L + offset)))
            }
        }

        expectConstraintViolation("attempt outcome must use the exact allow-list value") {
            insertAttempt(connection, id = 2L, outboxId = 1L, outcome = "success")
        }

        expectConstraintViolation("idempotency digest must be unique") {
            insertOutbox(connection, sendableRow(id = 500L).copy(idempotencyKey = "digest-1"))
        }
    }

    private fun sendableRow(
        id: Long,
        status: String = "PENDING",
    ): OutboxRow =
        OutboxRow(
            id = id,
            rowKind = "SENDABLE",
            status = status,
            idempotencyKey = "digest-$id",
            auditFingerprint = "fingerprint-$id",
            appointmentId = 1000L + id,
            memberId = "member-$id",
            channel = "SMS",
            eventType = "CONFIRMED",
            notificationSlot = "CONFIRMED",
            providerKey = "provider",
            templateKey = "appointment-confirmed",
            templateVersion = 1,
            parameterType = "APPOINTMENT_CONFIRMED",
            parametersJson = "{}",
            suppressionReason = null,
            clinicId = 20L,
            eventId = "event-$id",
        )

    private fun legacySuppressionRow(id: Long): OutboxRow =
        OutboxRow(
            id = id,
            rowKind = "LEGACY_SUPPRESSION",
            status = "SUPPRESSED",
            idempotencyKey = "digest-$id",
            auditFingerprint = "fingerprint-$id",
            appointmentId = null,
            memberId = null,
            channel = null,
            eventType = null,
            notificationSlot = null,
            providerKey = null,
            templateKey = null,
            templateVersion = null,
            parameterType = null,
            parametersJson = null,
            suppressionReason = "MEMBER_ID_MISSING_LEGACY",
            clinicId = 20L,
            eventId = "event-$id",
        )

    private fun insertOutbox(
        connection: Connection,
        row: OutboxRow,
    ) {
        connection.prepareStatement(OUTBOX_INSERT_SQL).use { statement ->
            val now = Timestamp.valueOf("2026-07-31 00:00:00")
            statement.setLong(1, row.id)
            statement.setString(2, row.rowKind)
            statement.setString(3, row.status)
            statement.setInt(4, 1)
            statement.setString(5, row.idempotencyKey)
            statement.setString(6, "key-id")
            statement.setInt(7, 1)
            statement.setString(8, row.auditFingerprint)
            statement.setString(9, "audit-key-id")
            statement.setLong(10, 10L)
            statement.setObject(11, row.appointmentId)
            statement.setString(12, row.memberId)
            statement.setString(13, row.channel)
            statement.setString(14, row.eventType)
            statement.setString(15, row.notificationSlot)
            statement.setString(16, row.providerKey)
            statement.setString(17, row.templateKey)
            statement.setObject(18, row.templateVersion)
            statement.setString(19, row.parameterType)
            statement.setString(20, row.parametersJson)
            statement.setString(21, row.suppressionReason)
            statement.setLong(22, row.clinicId)
            statement.setString(23, row.eventId)
            statement.setTimestamp(24, now)
            statement.setTimestamp(25, now)
            statement.setTimestamp(26, now)
            statement.executeUpdate() shouldBeEqualTo 1
        }
    }

    private fun insertAttempt(
        connection: Connection,
        id: Long,
        outboxId: Long,
        outcome: String? = null,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO clinic_notification_delivery_attempts(
                id, outbox_id, attempt_number, owner, token, channel, event_type,
                template_key, template_version, started_at, completed_at, outcome
            ) VALUES (?, ?, ?, 'worker-a', 'token-a', 'SMS', 'CONFIRMED',
                      'appointment-confirmed', 1, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, id)
            statement.setLong(2, outboxId)
            statement.setInt(3, id.toInt())
            statement.setTimestamp(4, Timestamp.valueOf("2026-07-31 00:00:00"))
            statement.setTimestamp(
                5,
                outcome?.let { Timestamp.valueOf("2026-07-31 00:01:00") },
            )
            statement.setString(6, outcome)
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
                "tenant_group_id:A",
                "clinic_id:A",
                "status:A",
                "available_at:A",
                "next_retry_at:A",
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
            "idx_notification_outbox_direct_lookup" to listOf(
                "clinic_id:A",
                "appointment_id:A",
                "event_type:A",
                "row_kind:A",
                "status:A",
                "available_at:A",
                "next_retry_at:A",
                "id:A",
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

    private data class OutboxRow(
        val id: Long,
        val rowKind: String,
        val status: String,
        val idempotencyKey: String,
        val auditFingerprint: String,
        val appointmentId: Long?,
        val memberId: String?,
        val channel: String?,
        val eventType: String?,
        val notificationSlot: String?,
        val providerKey: String?,
        val templateKey: String?,
        val templateVersion: Int?,
        val parameterType: String?,
        val parametersJson: String?,
        val suppressionReason: String?,
        val clinicId: Long,
        val eventId: String,
    )

    private data class ConstraintCase(
        val field: String,
        val mutate: (OutboxRow) -> OutboxRow,
    )

    private val ACTIVE_REQUIRED_FIELDS = listOf(
        ConstraintCase("appointment_id") { it.copy(appointmentId = null) },
        ConstraintCase("member_id") { it.copy(memberId = null) },
        ConstraintCase("channel") { it.copy(channel = null) },
        ConstraintCase("event_type") { it.copy(eventType = null) },
        ConstraintCase("notification_slot") { it.copy(notificationSlot = null) },
        ConstraintCase("template_key") { it.copy(templateKey = null) },
        ConstraintCase("template_version") { it.copy(templateVersion = null) },
        ConstraintCase("parameter_type") { it.copy(parameterType = null) },
        ConstraintCase("parameters_json") { it.copy(parametersJson = null) },
    )

    private val TERMINAL_REDACTION_FIELDS = listOf(
        ConstraintCase("appointment_id") { it.copy(appointmentId = 9001L) },
        ConstraintCase("member_id") { it.copy(memberId = "member-terminal") },
        ConstraintCase("parameters_json") { it.copy(parametersJson = "{}") },
    )

    private val LEGACY_SUPPRESSION_FIELDS = listOf(
        ConstraintCase("status") { it.copy(status = "PENDING") },
        ConstraintCase("suppression_reason") { it.copy(suppressionReason = null) },
        ConstraintCase("appointment_id") { it.copy(appointmentId = 9002L) },
        ConstraintCase("member_id") { it.copy(memberId = "member-legacy") },
        ConstraintCase("channel") { it.copy(channel = "SMS") },
        ConstraintCase("event_type") { it.copy(eventType = "CONFIRMED") },
        ConstraintCase("notification_slot") { it.copy(notificationSlot = "CONFIRMED") },
        ConstraintCase("provider_key") { it.copy(providerKey = "provider") },
        ConstraintCase("template_key") { it.copy(templateKey = "appointment-confirmed") },
        ConstraintCase("template_version") { it.copy(templateVersion = 1) },
        ConstraintCase("parameter_type") { it.copy(parameterType = "APPOINTMENT_CONFIRMED") },
        ConstraintCase("parameters_json") { it.copy(parametersJson = "{}") },
    )

    private val ALLOW_LIST_FIELDS = listOf(
        ConstraintCase("row_kind") { it.copy(rowKind = "BROKEN") },
        ConstraintCase("status") { it.copy(status = "BROKEN") },
        ConstraintCase("channel") { it.copy(channel = "BROKEN") },
        ConstraintCase("event_type") { it.copy(eventType = "BROKEN") },
        ConstraintCase("notification_slot") { it.copy(notificationSlot = "BROKEN") },
        ConstraintCase("parameter_type") { it.copy(parameterType = "BROKEN") },
        ConstraintCase("row_kind lowercase") { it.copy(rowKind = "sendable") },
        ConstraintCase("status lowercase") { it.copy(status = "pending") },
        ConstraintCase("channel lowercase") { it.copy(channel = "sms") },
        ConstraintCase("event_type lowercase") { it.copy(eventType = "confirmed") },
        ConstraintCase("notification_slot lowercase") { it.copy(notificationSlot = "confirmed") },
        ConstraintCase("parameter_type lowercase") { it.copy(parameterType = "appointment_confirmed") },
    )

    private val VALID_PARAMETER_TYPES = listOf(
        "APPOINTMENT_CREATED",
        "APPOINTMENT_CONFIRMED",
        "APPOINTMENT_REMINDER",
        "APPOINTMENT_CANCELLED",
        "APPOINTMENT_RESCHEDULED",
    )

    private const val OUTBOX_INSERT_SQL = """
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
