package io.bluetape4k.clinic.appointment.api.migration

import io.bluetape4k.assertions.shouldBeEqualTo
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.Date
import java.sql.Time
import javax.sql.DataSource

internal object AppointmentPlanMigrationTestSupport {

    fun verifyV8Migration(
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
            .target("7")
            .load()
            .migrate()
        dataSource.connection.use(::seedLegacyAppointment)
        val before = dataSource.connection.use(::readLegacyAppointment)

        val result = Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .load()
            .migrate()

        result.success shouldBeEqualTo true
        result.migrationsExecuted shouldBeEqualTo 1
        dataSource.connection.use { connection ->
            foundationTables(connection) shouldBeEqualTo EXPECTED_TABLES

            val missingIndexes = EXPECTED_INDEXES - indexes(connection)
            check(missingIndexes.isEmpty()) { "Missing V8 indexes: $missingIndexes" }

            val missingUniqueConstraints = EXPECTED_UNIQUE_CONSTRAINTS - uniqueConstraints(connection)
            check(missingUniqueConstraints.isEmpty()) {
                "Missing V8 unique constraints: $missingUniqueConstraints"
            }

            val missingForeignKeys = EXPECTED_FOREIGN_KEYS - foreignKeys(connection)
            check(missingForeignKeys.isEmpty()) { "Missing V8 foreign keys: $missingForeignKeys" }

            EXPECTED_CRITICAL_COLUMNS.forEach { (table, expectedColumns) ->
                val missingColumns = expectedColumns - columns(connection, table)
                check(missingColumns.isEmpty()) {
                    "Missing V8 columns on $table: $missingColumns"
                }
            }

            EXPECTED_UNIQUE_IDENTITIES.forEach { (identity, expectedColumns) ->
                val (table, constraint) = identity
                uniqueIndexColumns(connection, table, constraint) shouldBeEqualTo expectedColumns
            }

            readLegacyAppointment(connection) shouldBeEqualTo before
        }
    }

    private fun seedLegacyAppointment(connection: Connection) {
        connection.prepareStatement(
            "INSERT INTO scheduling_clinics(id, tenant_group_id, name) VALUES (?, ?, ?)"
        ).use { statement ->
            statement.setLong(1, 101)
            statement.setLong(2, 1)
            statement.setString(3, "Legacy Clinic")
            statement.executeUpdate()
        }
        connection.prepareStatement(
            "INSERT INTO scheduling_doctors(id, clinic_id, name) VALUES (?, ?, ?)"
        ).use { statement ->
            statement.setLong(1, 201)
            statement.setLong(2, 101)
            statement.setString(3, "Legacy Doctor")
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            INSERT INTO scheduling_treatment_types(id, clinic_id, name, default_duration_minutes)
            VALUES (?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, 301)
            statement.setLong(2, 101)
            statement.setString(3, "Legacy Treatment")
            statement.setInt(4, 30)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            INSERT INTO scheduling_appointments(
                id, clinic_id, doctor_id, treatment_type_id, patient_name,
                appointment_date, start_time, end_time, status
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, 401)
            statement.setLong(2, 101)
            statement.setLong(3, 201)
            statement.setLong(4, 301)
            statement.setString(5, "Legacy Patient")
            statement.setDate(6, Date.valueOf("2026-08-01"))
            statement.setTime(7, Time.valueOf("10:00:00"))
            statement.setTime(8, Time.valueOf("10:30:00"))
            statement.setString(9, "CONFIRMED")
            statement.executeUpdate()
        }
    }

    private fun readLegacyAppointment(connection: Connection): LegacyAppointment =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT id, clinic_id, doctor_id, treatment_type_id, patient_name,
                       appointment_date, start_time, end_time, status
                  FROM scheduling_appointments
                 WHERE id = 401
                """.trimIndent()
            ).use { rows ->
                check(rows.next())
                LegacyAppointment(
                    id = rows.getLong("id"),
                    clinicId = rows.getLong("clinic_id"),
                    doctorId = rows.getLong("doctor_id"),
                    treatmentTypeId = rows.getLong("treatment_type_id"),
                    patientName = rows.getString("patient_name"),
                    appointmentDate = rows.getDate("appointment_date").toLocalDate(),
                    startTime = rows.getTime("start_time").toLocalTime(),
                    endTime = rows.getTime("end_time").toLocalTime(),
                    status = rows.getString("status"),
                )
            }
        }

    private fun foundationTables(connection: Connection): Set<String> =
        buildSet {
            connection.metaData.getTables(null, null, null, arrayOf("TABLE")).use { rows ->
                while (rows.next()) {
                    rows.getString("TABLE_NAME")
                        .lowercase()
                        .takeIf(EXPECTED_TABLES::contains)
                        ?.let(::add)
                }
            }
        }

    private fun indexes(connection: Connection): Set<String> =
        EXPECTED_TABLES.flatMapTo(mutableSetOf()) { table ->
            metadataTableNames(table).flatMap { metadataTableName ->
                connection.metaData.getIndexInfo(null, null, metadataTableName, false, false).use { rows ->
                    buildList {
                        while (rows.next()) {
                            rows.getString("INDEX_NAME")?.lowercase()?.let(::add)
                        }
                    }
                }
            }
        }

    private fun uniqueConstraints(connection: Connection): Set<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT constraint_name
                  FROM information_schema.table_constraints
                 WHERE constraint_type = 'UNIQUE'
                """.trimIndent()
            ).use { rows ->
                buildSet {
                    while (rows.next()) add(rows.getString("constraint_name").lowercase())
                }
            }
        }

    private fun foreignKeys(connection: Connection): Set<String> =
        EXPECTED_TABLES.flatMapTo(mutableSetOf()) { table ->
            metadataTableNames(table).flatMap { metadataTableName ->
                connection.metaData.getImportedKeys(null, null, metadataTableName).use { rows ->
                    buildList {
                        while (rows.next()) {
                            rows.getString("FK_NAME")?.lowercase()?.let(::add)
                        }
                    }
                }
            }
        }

    private fun columns(connection: Connection, table: String): Set<String> =
        metadataTableNames(table).flatMapTo(mutableSetOf()) { metadataTableName ->
            connection.metaData.getColumns(null, null, metadataTableName, null).use { rows ->
                buildList {
                    while (rows.next()) {
                        rows.getString("COLUMN_NAME")?.lowercase()?.let(::add)
                    }
                }
            }
        }

    private fun uniqueIndexColumns(
        connection: Connection,
        table: String,
        indexName: String,
    ): List<String> =
        connection.prepareStatement(
            """
            SELECT column_name
              FROM information_schema.key_column_usage
             WHERE LOWER(table_name) = ?
               AND LOWER(constraint_name) = ?
             ORDER BY ordinal_position
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, table.lowercase())
            statement.setString(2, indexName.lowercase())
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(rows.getString("column_name").lowercase())
                    }
                }
            }
        }

    private fun metadataTableNames(table: String): Set<String> = setOf(table, table.uppercase())

    private data class LegacyAppointment(
        val id: Long,
        val clinicId: Long,
        val doctorId: Long,
        val treatmentTypeId: Long,
        val patientName: String,
        val appointmentDate: java.time.LocalDate,
        val startTime: java.time.LocalTime,
        val endTime: java.time.LocalTime,
        val status: String,
    )

    private val EXPECTED_TABLES = setOf(
        "scheduling_product_catalog_projections",
        "scheduling_product_catalog_bom_items",
        "scheduling_product_catalog_bom_dependencies",
        "scheduling_appointment_plans",
        "scheduling_planned_treatments",
        "scheduling_treatment_dependencies",
        "scheduling_inbox_events",
        "scheduling_outbox_events",
        "scheduling_quarantine_events",
        "scheduling_quarantine_audit_events",
    )
    private val EXPECTED_INDEXES = setOf(
        "idx_catalog_scope_product",
        "idx_treatment_dependency_plan",
        "idx_plan_tenant_clinic_status",
        "idx_plan_scope_purchase",
        "idx_treatment_plan_status_window",
        "idx_inbox_status_replay_after_received",
        "idx_inbox_source_version",
        "idx_outbox_status_created_at",
        "idx_outbox_status_next_attempt",
        "idx_quarantine_status_expiry",
        "idx_quarantine_scope_reason",
        "idx_quarantine_audit_quarantine_created",
    )
    private val EXPECTED_UNIQUE_CONSTRAINTS = setOf(
        "uq_catalog_scope_version",
        "uq_catalog_bom_item",
        "uq_catalog_bom_dependency",
        "uq_plan_source_purchase",
        "uq_planned_treatment_sequence",
        "uq_treatment_dependency",
        "uq_inbox_event_id",
        "uq_outbox_event_id",
        "uq_quarantine_event_id",
    )
    private val EXPECTED_FOREIGN_KEYS = setOf(
        "fk_catalog_projection_tenant",
        "fk_catalog_projection_clinic",
        "fk_catalog_bom_item_projection",
        "fk_catalog_bom_dependency_projection",
        "fk_appointment_plan_tenant",
        "fk_appointment_plan_clinic",
        "fk_appointment_plan_catalog",
        "fk_planned_treatment_plan",
        "fk_treatment_dependency_plan",
        "fk_treatment_dependency_predecessor",
        "fk_treatment_dependency_successor",
        "fk_inbox_tenant",
        "fk_inbox_clinic",
        "fk_outbox_tenant",
        "fk_outbox_clinic",
        "fk_outbox_plan",
        "fk_quarantine_tenant",
        "fk_quarantine_clinic",
        "fk_quarantine_audit_quarantine",
    )
    private val EXPECTED_CRITICAL_COLUMNS = mapOf(
        "scheduling_product_catalog_projections" to setOf(
            "source_authority",
            "catalog_status",
        ),
        "scheduling_appointment_plans" to setOf(
            "tenant_group_id",
            "clinic_id",
            "source_purchase_authority",
            "source_purchase_id",
            "catalog_source_authority",
        ),
        "scheduling_quarantine_events" to setOf(
            "envelope_hash",
            "encrypted_original_envelope",
            "encryption_key_id",
            "retention_class",
            "payload_expires_at",
            "legal_hold",
        ),
        "scheduling_quarantine_audit_events" to setOf(
            "quarantine_id",
            "action",
            "approval_references",
            "created_at",
        ),
    )
    private val EXPECTED_UNIQUE_IDENTITIES = mapOf(
        ("scheduling_product_catalog_projections" to "uq_catalog_scope_version") to listOf(
            "tenant_group_id",
            "clinic_id",
            "source_authority",
            "product_id",
            "catalog_version",
        ),
        ("scheduling_appointment_plans" to "uq_plan_source_purchase") to listOf(
            "tenant_group_id",
            "clinic_id",
            "source_purchase_authority",
            "source_purchase_id",
        ),
    )
}
