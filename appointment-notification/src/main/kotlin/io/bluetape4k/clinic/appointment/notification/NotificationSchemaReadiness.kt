package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.clinic.appointment.event.notification.NotificationDeliveryAttempts
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxEvents
import io.bluetape4k.clinic.appointment.event.waitlist.WaitlistNotificationOutboxEvents
import io.bluetape4k.clinic.appointment.event.AppointmentEventLogs
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import java.io.Serializable
import java.sql.SQLException
import java.sql.SQLInvalidAuthorizationSpecException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLTimeoutException

private val NOTIFICATION_READINESS_OPERATION_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
private val NOTIFICATION_READINESS_TARGET_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
private val NOTIFICATION_READINESS_CODE_PATTERN = Regex("^[A-Z][A-Z0-9_]{0,63}$")
private val NOTIFICATION_READINESS_ERROR_CLASS_PATTERN = Regex("^[A-Za-z0-9.\\x24_]{1,128}\\z")

/**
 * notification worker가 시작해도 되는 schema와 key-ring 준비 상태입니다.
 */
class NotificationSchemaReadiness(
    private val database: Database,
    private val cryptoProperties: NotificationCryptoProperties,
    private val metrics: NotificationOutboxMetrics? = null,
) {
    fun check(): NotificationReadiness = try {
        transaction(database) {
            val collector = DiagnosticCollector()
            val missing = requiredTables().filterNot { tableReadable(it, collector) }
            if (missing.isNotEmpty()) {
                return@transaction NotificationReadiness.down(
                    reason = "missing tables: ${missing.joinToString()}",
                    diagnostic = collector.firstOrNull()
                        ?: missingDiagnostic(OPERATION_SCHEMA_TABLE, missing.first(), CODE_TABLE_MISSING),
                )
            }
            if (!eventLogTenantColumnReadable(collector)) {
                return@transaction NotificationReadiness.down(
                    reason = "missing column: ${AppointmentEventLogs.tableName}.tenant_group_id",
                    diagnostic = collector.firstOrNull()
                        ?: missingDiagnostic(OPERATION_SCHEMA_COLUMN, TARGET_EVENT_TENANT_COLUMN, CODE_COLUMN_MISSING),
                )
            }
            val flywayVersion = installedFlywayVersion(collector)
                ?: return@transaction NotificationReadiness.down(
                    reason = "flyway schema version is unavailable",
                    diagnostic = collector.firstOrNull()
                        ?: missingDiagnostic(OPERATION_SCHEMA_FLYWAY, TARGET_FLYWAY, CODE_FLYWAY_UNAVAILABLE),
                )
            if (flywayVersion < REQUIRED_FLYWAY_VERSION) {
                return@transaction NotificationReadiness.down(
                    reason = "flyway schema version $flywayVersion is below $REQUIRED_FLYWAY_VERSION",
                    diagnostic = missingDiagnostic(OPERATION_SCHEMA_FLYWAY, TARGET_FLYWAY, CODE_VERSION_TOO_OLD),
                )
            }
            val unresolvedTenantRows = try {
                unresolvedEventLogTenantRows()
            } catch (failure: Exception) {
                collector.record(OPERATION_SCHEMA_TENANT_PREFLIGHT, TARGET_EVENT_TENANT_ROWS, failure)
                return@transaction NotificationReadiness.down(
                    reason = "event-log tenant preflight is unavailable",
                    diagnostic = collector.firstOrNull()
                        ?: missingDiagnostic(
                            OPERATION_SCHEMA_TENANT_PREFLIGHT,
                            TARGET_EVENT_TENANT_ROWS,
                            CODE_METADATA_UNAVAILABLE,
                        ),
                )
            }
            metrics?.recordEventLogNullTenantRows(unresolvedTenantRows)
            if (unresolvedTenantRows > 0L) {
                return@transaction NotificationReadiness.down(
                    reason = "event-log tenant preflight has $unresolvedTenantRows unresolved rows",
                    diagnostic = missingDiagnostic(
                        OPERATION_SCHEMA_TENANT_PREFLIGHT,
                        TARGET_EVENT_TENANT_ROWS,
                        CODE_TENANT_DATA_INCONSISTENT,
                    ),
                )
            }
            val missingIndexes = missingRequiredIndexes()
            if (missingIndexes.isNotEmpty()) {
                return@transaction NotificationReadiness.down(
                    reason = "missing indexes: ${missingIndexes.joinToString()}",
                    diagnostic = missingDiagnostic(
                        OPERATION_SCHEMA_INDEX,
                        missingIndexes.first(),
                        CODE_INDEX_MISSING,
                    ),
                )
            }
            try {
                cryptoProperties.validate()
            } catch (failure: Exception) {
                return@transaction NotificationReadiness.down(
                    reason = "notification crypto key-ring is invalid",
                    diagnostic = NotificationReadinessDiagnostic(
                        operation = OPERATION_KEY_RING,
                        target = TARGET_KEY_RING,
                        code = CODE_KEY_RING_INVALID,
                        errorClass = safeErrorClass(failure),
                        retryable = false,
                    ),
                )
            }
            NotificationReadiness.up()
        }
    } catch (failure: Exception) {
        log.warn(failure) {
            "Notification schema readiness is DOWN: " +
                readinessDiagnosticFor(OPERATION_SCHEMA_CHECK, TARGET_DATABASE, failure).safeSummary()
        }
        NotificationReadiness.down(
            reason = "notification schema readiness check failed",
            diagnostic = readinessDiagnosticFor(OPERATION_SCHEMA_CHECK, TARGET_DATABASE, failure),
        )
    }

    private fun tableReadable(tableName: String, collector: DiagnosticCollector): Boolean =
        try {
            TransactionManager.current().exec("SELECT 1 FROM $tableName WHERE 1 = 0") { true } == true
        } catch (failure: Exception) {
            collector.record(OPERATION_SCHEMA_TABLE, tableName, failure)
            false
        }

    private fun installedFlywayVersion(collector: DiagnosticCollector): Int? =
        try {
            if (!tableReadable(FlywaySchemaHistory.tableName, collector)) return null
            FlywaySchemaHistory
                .select(FlywaySchemaHistory.version)
                .where { FlywaySchemaHistory.success eq true }
                .orderBy(FlywaySchemaHistory.installedRank to SortOrder.DESC)
                .limit(1)
                .singleOrNull()
                ?.get(FlywaySchemaHistory.version)
                ?.toIntOrNull()
        } catch (failure: Exception) {
            collector.record(OPERATION_SCHEMA_FLYWAY, TARGET_FLYWAY, failure)
            null
        }

    private fun requiredTables(): List<String> =
        listOf(
            NotificationOutboxEvents.tableName,
            WaitlistNotificationOutboxEvents.tableName,
            NotificationDeliveryAttempts.tableName,
            AppointmentEventLogs.tableName,
            Clinics.tableName,
        )

    private fun eventLogTenantColumnReadable(collector: DiagnosticCollector): Boolean =
        try {
            TransactionManager.current().exec(
                "SELECT tenant_group_id FROM ${AppointmentEventLogs.tableName} WHERE 1 = 0",
            ) { true } == true
        } catch (failure: Exception) {
            collector.record(OPERATION_SCHEMA_COLUMN, TARGET_EVENT_TENANT_COLUMN, failure)
            false
        }

    private fun unresolvedEventLogTenantRows(): Long =
        TransactionManager.current().exec(
            """
            SELECT COUNT(*)
            FROM ${AppointmentEventLogs.tableName} event_log
            LEFT JOIN ${Clinics.tableName} clinic ON clinic.id = event_log.clinic_id
            WHERE event_log.tenant_group_id IS NULL
               OR clinic.id IS NULL
               OR clinic.tenant_group_id <> event_log.tenant_group_id
            """.trimIndent(),
        ) { resultSet ->
            if (!resultSet.next()) 0L else resultSet.getLong(1)
        } ?: 0L

    private fun missingRequiredIndexes(): List<String> {
        val migrationStatements = MigrationUtils.statementsRequiredForDatabaseMigration(
            NotificationOutboxEvents,
            WaitlistNotificationOutboxEvents,
            NotificationDeliveryAttempts,
        )
        return REQUIRED_INDEXES.filter { required ->
            migrationStatements.any { statement -> statement.contains(required, ignoreCase = true) }
        }
    }

    private class DiagnosticCollector {
        private val values = mutableListOf<NotificationReadinessDiagnostic>()

        fun record(operation: String, target: String, failure: Exception) {
            values += readinessDiagnosticFor(operation, target, failure)
        }

        fun firstOrNull(): NotificationReadinessDiagnostic? = values.firstOrNull()
    }

    internal companion object : KLogging() {
        const val REQUIRED_FLYWAY_VERSION = 21
        const val OPERATION_SCHEMA_CHECK = "schema.check"
        const val OPERATION_SCHEMA_TABLE = "schema.table"
        const val OPERATION_SCHEMA_COLUMN = "schema.column"
        const val OPERATION_SCHEMA_FLYWAY = "schema.flyway"
        const val OPERATION_SCHEMA_TENANT_PREFLIGHT = "schema.tenant-preflight"
        const val OPERATION_SCHEMA_INDEX = "schema.index"
        const val OPERATION_KEY_RING = "key-ring.validate"
        const val TARGET_DATABASE = "database"
        const val TARGET_FLYWAY = "flyway_schema_history"
        const val TARGET_EVENT_TENANT_COLUMN = "scheduling_appointment_event_logs.tenant_group_id"
        const val TARGET_EVENT_TENANT_ROWS = "scheduling_appointment_event_logs.tenant_scope"
        const val TARGET_KEY_RING = "notification.crypto.key-ring"
        const val CODE_TABLE_MISSING = "SCHEMA_TABLE_MISSING"
        const val CODE_COLUMN_MISSING = "SCHEMA_COLUMN_MISSING"
        const val CODE_FLYWAY_UNAVAILABLE = "SCHEMA_FLYWAY_UNAVAILABLE"
        const val CODE_VERSION_TOO_OLD = "SCHEMA_VERSION_TOO_OLD"
        const val CODE_TENANT_DATA_INCONSISTENT = "SCHEMA_TENANT_DATA_INCONSISTENT"
        const val CODE_INDEX_MISSING = "SCHEMA_INDEX_MISSING"
        const val CODE_KEY_RING_INVALID = "KEY_RING_INVALID"
        const val CODE_METADATA_TIMEOUT = "SCHEMA_METADATA_TIMEOUT"
        const val CODE_PERMISSION_DENIED = "SCHEMA_PERMISSION_DENIED"
        const val CODE_CONNECTION_FAILURE = "SCHEMA_CONNECTION_FAILURE"
        const val CODE_METADATA_UNAVAILABLE = "SCHEMA_METADATA_UNAVAILABLE"
        val REQUIRED_INDEXES = listOf(
            "idx_notification_outbox_ready_clinic_cursor",
            "idx_notification_outbox_ready_within_clinic",
            "idx_notification_outbox_direct_lookup",
            "idx_notification_outbox_tenant_direct_lookup",
            "idx_notification_outbox_reminder_suppression",
            "idx_notification_outbox_lease_recovery",
            "idx_notification_outbox_terminal_retention",
            "idx_notification_outbox_pending_oldest",
            "uk_waitlist_notification_outbox_idempotency",
            "idx_waitlist_notification_outbox_ready",
            "idx_waitlist_notification_outbox_lease",
        )

        internal fun readinessDiagnosticFor(
            operation: String,
            target: String,
            failure: Exception,
        ): NotificationReadinessDiagnostic {
            val root = rootCause(failure)
            val sqlState = generateSequence<Throwable>(failure) { it.cause }
                .filterIsInstance<SQLException>()
                .mapNotNull { it.sqlState }
                .firstOrNull()
            val code = when {
                root is SQLTimeoutException || sqlState == "HYT00" -> CODE_METADATA_TIMEOUT
                root is SQLInvalidAuthorizationSpecException || sqlState?.startsWith("28") == true ->
                    CODE_PERMISSION_DENIED
                root is SQLNonTransientConnectionException || sqlState?.startsWith("08") == true ->
                    CODE_CONNECTION_FAILURE
                sqlState?.startsWith("42") == true && operation == OPERATION_SCHEMA_COLUMN -> CODE_COLUMN_MISSING
                sqlState?.startsWith("42") == true && operation == OPERATION_SCHEMA_TABLE -> CODE_TABLE_MISSING
                else -> CODE_METADATA_UNAVAILABLE
            }
            return NotificationReadinessDiagnostic(
                operation = operation,
                target = target,
                code = code,
                errorClass = safeErrorClass(root),
                retryable = code == CODE_METADATA_TIMEOUT ||
                    code == CODE_CONNECTION_FAILURE ||
                    code == CODE_METADATA_UNAVAILABLE,
            )
        }

        fun missingDiagnostic(
            operation: String,
            target: String,
            code: String,
        ): NotificationReadinessDiagnostic = NotificationReadinessDiagnostic(
            operation = operation,
            target = target,
            code = code,
            retryable = false,
        )

        private fun safeErrorClass(failure: Throwable): String =
            (failure.javaClass.simpleName.ifBlank { failure.javaClass.name.substringAfterLast('.') })
                .take(128)

        private fun rootCause(failure: Throwable): Throwable {
            var current = failure
            while (true) {
                val next = current.cause ?: break
                if (next === current) break
                current = next
            }
            return current
        }
    }
}

data class NotificationReadiness(
    val available: Boolean,
    val reason: String?,
    val diagnostics: List<NotificationReadinessDiagnostic> = emptyList(),
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L

        fun up(): NotificationReadiness = NotificationReadiness(true, null)

        fun down(
            reason: String,
            diagnostic: NotificationReadinessDiagnostic? = null,
        ): NotificationReadiness = NotificationReadiness(false, reason, listOfNotNull(diagnostic))
    }
}

/**
 * 알림 readiness 실패를 운영자가 안전하게 분류할 수 있도록 보존하는 bounded 진단입니다.
 * 예외 메시지·SQL·secret 값은 포함하지 않고 operation, target, code, 예외 종류와 재시도
 * 가능성만 공개합니다.
 */
data class NotificationReadinessDiagnostic(
    val operation: String,
    val target: String,
    val code: String,
    val errorClass: String? = null,
    val retryable: Boolean,
) : Serializable {
    init {
        require(operation.matches(NOTIFICATION_READINESS_OPERATION_PATTERN)) {
            "readiness diagnostic operation must be canonical and bounded"
        }
        require(target.matches(NOTIFICATION_READINESS_TARGET_PATTERN)) {
            "readiness diagnostic target must be bounded"
        }
        require(code.matches(NOTIFICATION_READINESS_CODE_PATTERN)) {
            "readiness diagnostic code must be canonical and bounded"
        }
        require(errorClass == null || errorClass.matches(NOTIFICATION_READINESS_ERROR_CLASS_PATTERN)) {
            "readiness diagnostic error class must be bounded"
        }
    }

    fun toHealthDetail(): Map<String, Any> = buildMap {
        put("operation", operation)
        put("target", target)
        put("code", code)
        errorClass?.let { put("errorClass", it) }
        put("retryable", retryable)
    }

    fun safeSummary(): String = "${code}(operation=$operation, target=$target, retryable=$retryable)"
}

internal object FlywaySchemaHistory : Table("flyway_schema_history") {
    val installedRank = integer("installed_rank")
    val version = varchar("version", 50).nullable()
    val success = bool("success")

    override val primaryKey: PrimaryKey = PrimaryKey(installedRank)
}
