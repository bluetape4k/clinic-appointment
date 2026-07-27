package io.bluetape4k.clinic.appointment.api.migration

import io.bluetape4k.assertions.shouldBeEqualTo
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.Date
import java.sql.SQLException
import java.sql.Time
import javax.sql.DataSource

internal object AppointmentPlanMigrationTestSupport {

    fun verifyV9Migration(
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

        Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .target("8")
            .load()
            .migrate()
        dataSource.connection.use(::seedV8PlanOutbox)

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
            check(missingIndexes.isEmpty()) { "Missing V9 indexes: $missingIndexes" }

            val missingUniqueConstraints = EXPECTED_UNIQUE_CONSTRAINTS - uniqueConstraints(connection)
            check(missingUniqueConstraints.isEmpty()) {
                "Missing V9 unique constraints: $missingUniqueConstraints"
            }

            val missingForeignKeys = EXPECTED_FOREIGN_KEYS - foreignKeys(connection)
            check(missingForeignKeys.isEmpty()) { "Missing V9 foreign keys: $missingForeignKeys" }

            val missingChecks = EXPECTED_CHECK_CONSTRAINTS - checkConstraints(connection)
            check(missingChecks.isEmpty()) { "Missing V9 check constraints: $missingChecks" }

            EXPECTED_CRITICAL_COLUMNS.forEach { (table, expectedColumns) ->
                val missingColumns = expectedColumns - columns(connection, table)
                check(missingColumns.isEmpty()) {
                    "Missing V9 columns on $table: $missingColumns"
                }
            }

            EXPECTED_UNIQUE_IDENTITIES.forEach { (identity, expectedColumns) ->
                val (table, constraint) = identity
                uniqueIndexColumns(connection, table, constraint) shouldBeEqualTo expectedColumns
            }

            verifyInvalidQuarantineStatusRejected(connection)
            verifyV8OutboxBackfill(connection)
            readLegacyAppointment(connection) shouldBeEqualTo before
        }
    }

    private fun verifyInvalidQuarantineStatusRejected(connection: Connection) {
        if (connection.metaData.databaseProductName.contains("MySQL", ignoreCase = true)) {
            connection.createStatement().use {
                it.execute("SET SESSION sql_mode = 'STRICT_ALL_TABLES'")
            }
        }
        val failure = try {
            connection.prepareStatement(
                """
                INSERT INTO scheduling_quarantine_events(
                    event_id, event_type, envelope_hash, encrypted_original_envelope,
                    encryption_key_id, producer, source_authority, schema_version,
                    source_aggregate_id, source_aggregate_version, tenant_group_id,
                    clinic_id, reason_code, detected_at, correlation_id,
                    retention_class, payload_expires_at, legal_hold, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?,
                          ?, CURRENT_TIMESTAMP, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, "invalid-quarantine-status")
                statement.setString(2, "PurchaseCompleted")
                statement.setString(3, "a".repeat(64))
                statement.setString(4, "encrypted")
                statement.setString(5, "quarantine-key")
                statement.setString(6, "commerce-service")
                statement.setString(7, "commerce")
                statement.setInt(8, 2)
                statement.setString(9, "purchase-aggregate")
                statement.setLong(10, 1)
                statement.setLong(11, 1)
                statement.setLong(12, 101)
                statement.setString(13, "TRUST_FAILED")
                statement.setString(14, "migration-check")
                statement.setString(15, "STANDARD")
                statement.setBoolean(16, false)
                statement.setString(17, "INVALID_STATUS")
                statement.executeUpdate()
            }
            null
        } catch (caught: SQLException) {
            caught
        }
        val persisted = connection.prepareStatement(
            "SELECT COUNT(*) FROM scheduling_quarantine_events WHERE event_id = ?"
        ).use { statement ->
            statement.setString(1, "invalid-quarantine-status")
            statement.executeQuery().use { result ->
                result.next()
                result.getLong(1)
            }
        }
        check(failure != null && persisted == 0L) {
            "V8 must reject invalid quarantine status without persisting it; " +
                "sqlState=${failure?.sqlState}, persisted=$persisted"
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

    private fun seedV8PlanOutbox(connection: Connection) {
        connection.prepareStatement(
            """
            INSERT INTO scheduling_product_catalog_projections(
                id, tenant_group_id, clinic_id, source_authority, product_id,
                catalog_version, catalog_status, product_name, schema_version,
                source_updated_at, payload_hash
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, 501)
            statement.setLong(2, 1)
            statement.setLong(3, 101)
            statement.setString(4, "product-catalog")
            statement.setString(5, "legacy-product")
            statement.setLong(6, 1)
            statement.setString(7, "ACTIVE")
            statement.setString(8, "Legacy Product")
            statement.setInt(9, 1)
            statement.setString(10, "a".repeat(64))
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            INSERT INTO scheduling_appointment_plans(
                id, tenant_group_id, clinic_id, catalog_projection_id,
                source_purchase_authority, source_purchase_id,
                patient_reference_ciphertext, patient_reference_key_id,
                patient_reference_fingerprint, catalog_source_authority,
                product_id, catalog_version, catalog_payload_hash, product_name,
                booking_preference_type, booking_preference_payload, status
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, 601)
            statement.setLong(2, 1)
            statement.setLong(3, 101)
            statement.setLong(4, 501)
            statement.setString(5, "commerce")
            statement.setString(6, "legacy-purchase")
            statement.setString(7, "ciphertext")
            statement.setString(8, "key-1")
            statement.setString(9, "b".repeat(64))
            statement.setString(10, "product-catalog")
            statement.setString(11, "legacy-product")
            statement.setLong(12, 1)
            statement.setString(13, "a".repeat(64))
            statement.setString(14, "Legacy Product")
            statement.setString(15, "NOT_PROVIDED")
            statement.setString(16, "")
            statement.setString(17, "ACTIVE")
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            INSERT INTO scheduling_outbox_events(
                event_id, causation_event_id, correlation_id, event_type,
                tenant_group_id, clinic_id, plan_id, schema_version,
                payload_json, status, attempt_count
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, "legacy-plan-outbox")
            statement.setString(2, "legacy-purchase-event")
            statement.setString(3, "legacy-correlation")
            statement.setString(4, "AppointmentPlanCreated")
            statement.setLong(5, 1)
            statement.setLong(6, 101)
            statement.setLong(7, 601)
            statement.setInt(8, 1)
            statement.setString(9, "{}")
            statement.setString(10, "PENDING")
            statement.setInt(11, 0)
            statement.executeUpdate()
        }
    }

    private fun verifyV8OutboxBackfill(connection: Connection) {
        connection.prepareStatement(
            """
            SELECT aggregate_type, aggregate_id, plan_id
              FROM scheduling_outbox_events
             WHERE event_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, "legacy-plan-outbox")
            statement.executeQuery().use { rows ->
                check(rows.next()) { "Legacy V8 outbox row must be preserved" }
                rows.getString("aggregate_type") shouldBeEqualTo "APPOINTMENT_PLAN"
                rows.getString("aggregate_id") shouldBeEqualTo "601"
                rows.getLong("plan_id") shouldBeEqualTo 601L
            }
        }
        check(columnIsNullable(connection, "scheduling_outbox_events", "plan_id")) {
            "V9 plan_id must remain available but become nullable for non-plan aggregates"
        }
        check(columnIsNullable(connection, "scheduling_outbox_events", "clinic_id")) {
            "V9 clinic_id must become nullable for tenant-scope policy aggregates"
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

    private fun checkConstraints(connection: Connection): Set<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT constraint_name
                  FROM information_schema.table_constraints
                 WHERE constraint_type = 'CHECK'
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

    private fun columnIsNullable(
        connection: Connection,
        table: String,
        column: String,
    ): Boolean =
        metadataTableNames(table).any { metadataTableName ->
            setOf(column, column.uppercase()).any { metadataColumnName ->
                connection.metaData.getColumns(null, null, metadataTableName, metadataColumnName).use { rows ->
                    rows.next() && rows.getInt("NULLABLE") == java.sql.DatabaseMetaData.columnNullable
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
        "scheduling_untrusted_event_rejections",
        "scheduling_quarantine_events",
        "scheduling_quarantine_audit_events",
        "scheduling_policy_definitions",
        "scheduling_policy_approvals",
        "scheduling_policy_scope_heads",
        "effective_scheduling_policy_snapshots",
        "scheduling_policy_activation_commands",
        "scheduling_policy_preview_jobs",
    )
    private val EXPECTED_INDEXES = setOf(
        "idx_catalog_scope_product",
        "idx_treatment_dependency_plan",
        "idx_treatment_dependency_successor",
        "idx_plan_tenant_clinic_status",
        "idx_treatment_plan_status_window",
        "idx_inbox_status_replay_after_received",
        "idx_inbox_source_version",
        "idx_outbox_plan_id",
        "idx_outbox_status_created_at",
        "idx_outbox_status_next_attempt",
        "idx_untrusted_rejection_detected",
        "idx_untrusted_rejection_claimed_scope",
        "idx_quarantine_status_expiry",
        "idx_quarantine_scope_reason",
        "idx_quarantine_audit_quarantine_created",
        "idx_policy_definition_effective",
        "idx_effective_policy_generation",
        "idx_policy_activation_due",
        "idx_policy_preview_due",
        "idx_policy_preview_scope",
        "idx_outbox_aggregate",
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
        "uq_untrusted_rejection_event_id",
        "uq_quarantine_event_id",
        "uq_policy_definition",
        "uq_policy_approval",
        "uq_policy_scope_head",
        "uq_effective_policy_hash",
        "uq_policy_activation_idempotency",
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
        "fk_policy_approval_definition",
    )
    private val EXPECTED_CHECK_CONSTRAINTS = setOf(
        "ck_policy_definition_scope",
        "ck_policy_definition_interval",
        "ck_policy_definition_versions",
        "ck_policy_definition_lifecycle",
        "ck_policy_approval_revision",
        "ck_policy_scope_head_scope",
        "ck_policy_scope_head_counters",
        "ck_effective_policy_generation",
        "ck_policy_activation_scope",
        "ck_policy_activation_state",
        "ck_policy_preview_state",
        "ck_policy_preview_progress",
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
        "scheduling_inbox_events" to setOf(
            "tenant_group_id",
            "clinic_id",
            "source_authority",
            "source_aggregate_id",
            "source_aggregate_version",
        ),
        "scheduling_quarantine_events" to setOf(
            "envelope_hash",
            "encrypted_original_envelope",
            "encryption_key_id",
            "retention_class",
            "payload_expires_at",
            "legal_hold",
        ),
        "scheduling_untrusted_event_rejections" to setOf(
            "claimed_tenant_group_id",
            "claimed_clinic_id",
            "envelope_hash",
            "reason_code",
        ),
        "scheduling_quarantine_audit_events" to setOf(
            "quarantine_id",
            "action",
            "approval_references",
            "created_at",
        ),
        "scheduling_outbox_events" to setOf(
            "aggregate_type",
            "aggregate_id",
            "plan_id",
        ),
        "scheduling_policy_definitions" to setOf(
            "tenant_group_id",
            "scope",
            "clinic_id",
            "clinic_scope_key",
            "policy_kind",
            "version",
            "schema_version",
            "revision",
            "payload_hash",
            "payload_json",
        ),
        "scheduling_policy_activation_commands" to setOf(
            "idempotency_key_hash",
            "request_fingerprint",
            "lease_owner",
            "lease_until",
            "result_tenant_generation",
            "result_clinic_generation",
        ),
        "scheduling_policy_preview_jobs" to setOf(
            "draft_revision",
            "tenant_generation",
            "clinic_generation",
            "cursor_partition",
            "cursor_last_appointment_id",
            "scanned_count",
            "affected_count",
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
        ("scheduling_policy_definitions" to "uq_policy_definition") to listOf(
            "tenant_group_id",
            "scope",
            "clinic_scope_key",
            "policy_kind",
            "version",
        ),
        ("scheduling_policy_scope_heads" to "uq_policy_scope_head") to listOf(
            "tenant_group_id",
            "scope",
            "clinic_scope_key",
        ),
        ("scheduling_policy_activation_commands" to "uq_policy_activation_idempotency") to listOf(
            "tenant_group_id",
            "scope",
            "clinic_scope_key",
            "idempotency_key_hash",
        ),
    )
}
