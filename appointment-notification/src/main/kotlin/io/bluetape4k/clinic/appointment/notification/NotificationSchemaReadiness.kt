package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.clinic.appointment.event.notification.NotificationDeliveryAttempts
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxEvents
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import java.io.Serializable

/**
 * notification worker가 시작해도 되는 schema와 key-ring 준비 상태입니다.
 */
class NotificationSchemaReadiness(
    private val database: Database,
    private val cryptoProperties: NotificationCryptoProperties,
) {
    fun check(): NotificationReadiness =
        try {
            transaction(database) {
                val missing = requiredTables().filterNot(::tableReadable)
                if (missing.isNotEmpty()) {
                    return@transaction NotificationReadiness.down("missing tables: ${missing.joinToString()}")
                }
                val flywayVersion = installedFlywayVersion()
                    ?: return@transaction NotificationReadiness.down("flyway schema version is unavailable")
                if (flywayVersion < REQUIRED_FLYWAY_VERSION) {
                    return@transaction NotificationReadiness.down("flyway schema version $flywayVersion is below $REQUIRED_FLYWAY_VERSION")
                }
                val missingIndexes = missingRequiredIndexes()
                if (missingIndexes.isNotEmpty()) {
                    return@transaction NotificationReadiness.down("missing indexes: ${missingIndexes.joinToString()}")
                }
                cryptoProperties.validate()
                NotificationReadiness.up()
            }
        } catch (e: Exception) {
            log.warn(e) { "Notification schema readiness is DOWN" }
            NotificationReadiness.down(e.message ?: e.javaClass.name)
        }

    private fun tableReadable(tableName: String): Boolean =
        try {
            TransactionManager.current().exec("SELECT 1 FROM $tableName WHERE 1 = 0") { true } == true
        } catch (e: Exception) {
            false
        }

    private fun installedFlywayVersion(): Int? =
        try {
            if (!tableReadable(FlywaySchemaHistory.tableName)) return null
            FlywaySchemaHistory
                .select(FlywaySchemaHistory.version)
                .where { FlywaySchemaHistory.success eq true }
                .orderBy(FlywaySchemaHistory.installedRank to SortOrder.DESC)
                .limit(1)
                .singleOrNull()
                ?.get(FlywaySchemaHistory.version)
                ?.toIntOrNull()
        } catch (e: Exception) {
            null
        }

    private fun requiredTables(): List<String> =
        listOf(NotificationOutboxEvents.tableName, NotificationDeliveryAttempts.tableName)

    private fun missingRequiredIndexes(): List<String> {
        val migrationStatements = MigrationUtils.statementsRequiredForDatabaseMigration(
            NotificationOutboxEvents,
            NotificationDeliveryAttempts,
        )
        return REQUIRED_INDEXES.filter { required ->
            migrationStatements.any { statement -> statement.contains(required, ignoreCase = true) }
        }
    }

    private companion object : KLogging() {
        const val REQUIRED_FLYWAY_VERSION = 14
        val REQUIRED_INDEXES = listOf(
            "idx_notification_outbox_ready_clinic_cursor",
            "idx_notification_outbox_lease_recovery",
            "idx_notification_outbox_terminal_retention",
        )
    }
}

data class NotificationReadiness(
    val available: Boolean,
    val reason: String?,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L

        fun up(): NotificationReadiness = NotificationReadiness(true, null)

        fun down(reason: String): NotificationReadiness = NotificationReadiness(false, reason)
    }
}

internal object FlywaySchemaHistory : Table("flyway_schema_history") {
    val installedRank = integer("installed_rank")
    val version = varchar("version", 50).nullable()
    val success = bool("success")

    override val primaryKey: PrimaryKey = PrimaryKey(installedRank)
}
