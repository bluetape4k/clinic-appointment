package io.bluetape4k.clinic.appointment.api.migration

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.SQLException
import java.sql.Timestamp
import javax.sql.DataSource

/** V19 waitlist delivery additive schema를 clean 설치와 V18 업그레이드 양쪽에서 검증합니다. */
internal object WaitlistDeliveryMigrationTestSupport {

    enum class Dialect {
        H2,
        POSTGRESQL,
        MYSQL,
    }

    private val expectedTables = setOf(
        "scheduling_waitlist_policy_versions",
        "scheduling_waitlist_policy_events",
        "scheduling_booking_restrictions",
        "scheduling_disruption_recovery_credits",
        "scheduling_booking_benefit_grants",
        "scheduling_waitlist_vacancy_jobs",
        "scheduling_waitlist_command_records",
        "clinic_waitlist_notification_outbox",
    )

    private val expectedColumns = mapOf(
        "scheduling_waitlist_policy_versions" to setOf(
            "id", "tenant_group_id", "clinic_id", "generation", "policy_version", "policy_digest",
            "urgency_weight", "recovery_weight", "benefit_weight", "reliability_weight",
            "waiting_age_weight", "slot_fit_weight", "status", "effective_from", "effective_until",
            "canonical_policy_json", "created_by", "created_at", "retired_by", "retired_at",
        ),
        "scheduling_waitlist_policy_events" to setOf(
            "id", "tenant_group_id", "clinic_id", "policy_version", "event_type", "actor_ref",
            "correlation_id", "from_generation", "to_generation", "reason_code", "event_digest",
            "payload_json", "occurred_at", "created_at",
        ),
        "scheduling_booking_restrictions" to setOf(
            "id", "tenant_group_id", "clinic_id", "member_id", "evidence_digest", "reason_code",
            "policy_version", "restriction_mode", "actor_ref", "starts_at", "expires_at",
            "released_by", "released_at", "reversal_version", "created_at",
        ),
        "scheduling_disruption_recovery_credits" to setOf(
            "id", "tenant_group_id", "clinic_id", "member_id", "source_appointment_id", "credit_digest",
            "priority_boost", "reason_code", "granted_by", "expires_at", "consumed_at", "reversed_by",
            "reversed_at", "reversal_version", "created_at",
        ),
        "scheduling_booking_benefit_grants" to setOf(
            "id", "tenant_group_id", "clinic_id", "member_id", "approval_reference", "benefit_type",
            "benefit_cap", "grant_digest", "policy_version", "starts_at", "expires_at", "consumed_at",
            "revoked_by", "revoked_at", "revoke_version", "created_at",
        ),
        "scheduling_waitlist_vacancy_jobs" to setOf(
            "id", "tenant_group_id", "clinic_id", "vacancy_key", "vacancy_generation",
            "active_vacancy_key", "source_appointment_id", "source_transition_id", "resource_type",
            "resource_id", "capacity_units", "maximum_capacity", "treatment_type_id", "doctor_id",
            "policy_version", "status", "attempt", "lease_owner", "lease_version", "lease_expires_at",
            "next_attempt_at", "vacancy_starts_at", "vacancy_ends_at", "offered_waitlist_entry_id",
            "last_error_code", "version", "created_at", "updated_at",
        ),
        "scheduling_waitlist_command_records" to setOf(
            "id", "tenant_group_id", "clinic_id", "command_type", "key_digest", "request_digest",
            "status", "result_type", "result_id", "response_digest", "failure_code", "expires_at",
            "created_at", "updated_at",
        ),
        "clinic_waitlist_notification_outbox" to setOf(
            "id", "status", "idempotency_key", "event_id", "tenant_group_id", "clinic_id", "offer_id",
            "hold_id", "waitlist_entry_id", "reason_code", "correlation_id", "payload_json", "occurred_at",
            "available_at", "lease_owner", "lease_token", "lease_until", "attempt_number", "created_at",
            "updated_at", "terminal_at",
        ),
    )

    fun verifyV19Migration(
        dataSource: DataSource,
        location: String,
        dialect: Dialect,
    ) {
        verifyUpgradeMigration(dataSource, location, dialect)
        verifyCleanMigration(dataSource, location, dialect)
    }

    fun verifyV19DialectContracts(
        dataSource: DataSource,
        location: String,
        dialect: Dialect,
    ) {
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .cleanDisabled(false)
            .load()
        flyway.clean()
        flyway.migrate()

        dataSource.connection.use { connection ->
            verifyWaitlistDeliveryContract(connection, dialect)
            verifyPolicyPersistenceGenerationAuthority(connection)
            verifyExpiredLeaseOneWorkerClaim(connection)
            verifyRankedCandidateSkeleton(connection, dialect)
        }
    }

    private fun verifyUpgradeMigration(
        dataSource: DataSource,
        location: String,
        dialect: Dialect,
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
            .target("18")
            .load()
            .migrate()

        val result = Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .target("19")
            .load()
            .migrate()

        result.success shouldBeEqualTo true
        dataSource.connection.use { verifyWaitlistDeliveryContract(it, dialect) }
        result.migrationsExecuted shouldBeEqualTo 1
    }

    private fun verifyCleanMigration(
        dataSource: DataSource,
        location: String,
        dialect: Dialect,
    ) {
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .target("19")
            .cleanDisabled(false)
            .load()
        flyway.clean()

        val result = flyway.migrate()

        result.success shouldBeEqualTo true
        dataSource.connection.use { verifyWaitlistDeliveryContract(it, dialect) }
        result.migrationsExecuted shouldBeEqualTo 19
    }

    internal fun verifyWaitlistDeliveryContract(connection: Connection, dialect: Dialect) {
        check(dialect in Dialect.entries) { "Unsupported waitlist delivery dialect: $dialect" }
        val tables = tableNames(connection)
        check(expectedTables.all(tables::contains)) {
            "Missing V19 waitlist delivery tables: ${expectedTables - tables}"
        }
        verifyColumns(connection)
        verifyForeignKeys(connection)
        verifyIndexes(connection)
        verifyCommandIdempotencyKey(connection)
    }

    private fun verifyColumns(connection: Connection) {
        expectedColumns.forEach { (table, expected) ->
            columnNames(connection, table) shouldBeEqualTo expected
        }
    }

    private fun verifyForeignKeys(connection: Connection) {
        expectedTables.forEach { table ->
            val imported = importedKeys(connection, table)
            imported.contains("tenant_group_id->scheduling_tenant_groups").shouldBeTrue()
            imported.contains("clinic_id->scheduling_clinics").shouldBeTrue()
        }
    }

    private fun verifyIndexes(connection: Connection) {
        uniqueIndexColumns(
            connection,
            "scheduling_waitlist_policy_versions",
            "uq_waitlist_policy_generation",
        ) shouldBeEqualTo listOf("tenant_group_id", "clinic_id", "generation")
        uniqueIndexColumns(
            connection,
            "scheduling_waitlist_policy_versions",
            "uq_waitlist_policy_version",
        ) shouldBeEqualTo listOf("tenant_group_id", "clinic_id", "policy_version")
        indexDefinition(
            connection,
            "scheduling_waitlist_policy_versions",
            "idx_waitlist_policy_active",
        ) shouldBeEqualTo listOf("tenant_group_id:A", "clinic_id:A", "status:A", "effective_from:A")
        uniqueIndexColumns(
            connection,
            "scheduling_waitlist_vacancy_jobs",
            "uq_waitlist_vacancy_generation",
        ) shouldBeEqualTo listOf("tenant_group_id", "clinic_id", "vacancy_key", "vacancy_generation")
        uniqueIndexColumns(
            connection,
            "scheduling_waitlist_vacancy_jobs",
            "uq_waitlist_vacancy_source_transition",
        ) shouldBeEqualTo listOf("tenant_group_id", "clinic_id", "source_appointment_id", "source_transition_id")
        uniqueIndexColumns(
            connection,
            "scheduling_waitlist_vacancy_jobs",
            "uq_waitlist_vacancy_active",
        ) shouldBeEqualTo listOf("tenant_group_id", "clinic_id", "active_vacancy_key")
        indexDefinition(
            connection,
            "scheduling_waitlist_vacancy_jobs",
            "idx_waitlist_vacancy_due",
        ) shouldBeEqualTo listOf("status:A", "next_attempt_at:A", "lease_expires_at:A")
        indexDefinition(
            connection,
            "scheduling_waitlist_entries",
            "idx_waitlist_delivery_candidate_scope_order",
        ) shouldBeEqualTo listOf("tenant_group_id:A", "clinic_id:A", "status:A", "updated_at:A", "id:A")
        indexDefinition(
            connection,
            "scheduling_waitlist_offers",
            "idx_waitlist_delivery_offer_active_entry",
        ) shouldBeEqualTo listOf("tenant_group_id:A", "clinic_id:A", "active_entry_key:A", "status:A", "id:A")
        uniqueIndexColumns(
            connection,
            "clinic_waitlist_notification_outbox",
            "uk_waitlist_notification_outbox_idempotency",
        ) shouldBeEqualTo listOf("tenant_group_id", "clinic_id", "idempotency_key")
        indexDefinition(
            connection,
            "clinic_waitlist_notification_outbox",
            "idx_waitlist_notification_outbox_ready",
        ) shouldBeEqualTo listOf("tenant_group_id:A", "clinic_id:A", "status:A", "available_at:A", "id:A")
        indexDefinition(
            connection,
            "clinic_waitlist_notification_outbox",
            "idx_waitlist_notification_outbox_lease",
        ) shouldBeEqualTo listOf("status:A", "lease_until:A", "id:A")
    }

    private fun verifyCommandIdempotencyKey(connection: Connection) {
        uniqueIndexColumns(
            connection,
            "scheduling_waitlist_command_records",
            "uq_waitlist_command_idempotency",
        ) shouldBeEqualTo listOf("tenant_group_id", "clinic_id", "command_type", "key_digest")
    }

    private fun verifyPolicyPersistenceGenerationAuthority(connection: Connection) {
        seedScope(connection)
        insertPolicyVersion(connection, id = 190101L, generation = 1L, policyVersion = 1001L)
        expectConstraintViolation {
            insertPolicyVersion(connection, id = 190102L, generation = 2L, policyVersion = 1002L, urgencyWeight = 10001)
        }
        // ACTIVE window overlap과 first-activation race는 Task 2 repository의 scope lock + transaction algorithm이 검증한다.
        expectConstraintViolation {
            insertPolicyVersion(connection, id = 190103L, generation = 1L, policyVersion = 1003L)
        }
        insertPolicyVersion(connection, id = 190104L, generation = 2L, policyVersion = 1004L)
        insertCommandRecord(connection, id = 190301L, keyDigest = "wl-v1:${"a".repeat(64)}")
        expectConstraintViolation {
            insertCommandRecord(connection, id = 190302L, keyDigest = "raw-key")
        }
    }

    private fun verifyExpiredLeaseOneWorkerClaim(connection: Connection) {
        insertVacancyJob(connection)

        claimExpiredVacancyJob(connection, owner = "worker-a", expectedLeaseVersion = 0L) shouldBeEqualTo 1
        claimExpiredVacancyJob(connection, owner = "worker-b", expectedLeaseVersion = 0L) shouldBeEqualTo 0

        connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT lease_owner, lease_version, attempt
                FROM scheduling_waitlist_vacancy_jobs
                WHERE id = 190201
                """.trimIndent(),
            ).use { rows ->
                rows.next().shouldBeTrue()
                rows.getString("lease_owner") shouldBeEqualTo "worker-a"
                rows.getLong("lease_version") shouldBeEqualTo 1L
                rows.getInt("attempt") shouldBeEqualTo 1
            }
        }
    }

    private fun verifyRankedCandidateSkeleton(connection: Connection, dialect: Dialect) {
        val sql = rankedCandidateSql()
        sql.lowercase().split("least(100").size - 1 shouldBeEqualTo 4
        sql.lowercase().contains("limit 400").shouldBeTrue()
        indexDefinition(
            connection,
            "scheduling_waitlist_entries",
            "idx_waitlist_delivery_candidate_scope_order",
        ) shouldBeEqualTo listOf("tenant_group_id:A", "clinic_id:A", "status:A", "updated_at:A", "id:A")
        indexDefinition(
            connection,
            "scheduling_waitlist_offers",
            "idx_waitlist_delivery_offer_active_entry",
        ) shouldBeEqualTo listOf("tenant_group_id:A", "clinic_id:A", "active_entry_key:A", "status:A", "id:A")

        connection.createStatement().use { statement ->
            statement.executeQuery("EXPLAIN $sql").use { rows ->
                rows.next().shouldBeTrue()
            }
        }
        check(dialect in Dialect.entries) { "Unsupported waitlist delivery dialect: $dialect" }
    }

    private fun rankedCandidateSql(): String =
        """
        SELECT e.id
        FROM scheduling_waitlist_entries e
        LEFT JOIN scheduling_waitlist_offers active_offer
          ON active_offer.tenant_group_id = e.tenant_group_id
         AND active_offer.clinic_id = e.clinic_id
         AND active_offer.active_entry_key = e.member_id
         AND active_offer.status = 'OFFERED'
        WHERE e.tenant_group_id = 190001
          AND e.clinic_id = 190010
          AND e.status = 'WAITING'
          AND active_offer.id IS NULL
        ORDER BY (
            LEAST(100, 100)
            + LEAST(100, 100)
            + LEAST(100, 100)
            + LEAST(100, 100)
        ) DESC, e.updated_at ASC, e.id ASC
        LIMIT 400
        """.trimIndent()

    private fun seedScope(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                INSERT INTO scheduling_tenant_groups(id, tenant_code, display_name, active)
                VALUES (190001, 'waitlist-delivery-migration', 'Waitlist Delivery Migration', TRUE)
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                INSERT INTO scheduling_clinics(id, tenant_group_id, name)
                VALUES (190010, 190001, 'Waitlist Delivery Clinic')
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                INSERT INTO scheduling_doctors(id, clinic_id, name)
                VALUES (190020, 190010, 'Waitlist Delivery Doctor')
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                INSERT INTO scheduling_treatment_types(id, clinic_id, name, default_duration_minutes)
                VALUES (190030, 190010, 'Waitlist Delivery Treatment', 30)
                """.trimIndent(),
            )
        }
    }

    private fun insertPolicyVersion(
        connection: Connection,
        id: Long,
        generation: Long,
        policyVersion: Long,
        urgencyWeight: Int = 100,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO scheduling_waitlist_policy_versions(
                id, tenant_group_id, clinic_id, generation, policy_version, policy_digest,
                urgency_weight, recovery_weight, benefit_weight, reliability_weight,
                waiting_age_weight, slot_fit_weight, status, effective_from,
                effective_until, canonical_policy_json, created_by
            ) VALUES (?, 190001, 190010, ?, ?, ?, ?, 100, 100, 100, 100, 100,
                'ACTIVE', ?, NULL, '{"weights":{"urgency":100}}', 'migration-test')
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, id)
            statement.setLong(2, generation)
            statement.setLong(3, policyVersion)
            statement.setString(4, "b".repeat(64))
            statement.setInt(5, urgencyWeight)
            statement.setTimestamp(6, Timestamp.valueOf("2026-08-03 08:00:00"))
            statement.executeUpdate()
        }
    }

    private fun insertCommandRecord(connection: Connection, id: Long, keyDigest: String) {
        connection.prepareStatement(
            """
            INSERT INTO scheduling_waitlist_command_records(
                id, tenant_group_id, clinic_id, command_type, key_digest,
                request_digest, status, expires_at
            ) VALUES (?, 190001, 190010, 'ACTIVATE_POLICY', ?, ?, 'PROCESSING', ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, id)
            statement.setString(2, keyDigest)
            statement.setString(3, "c".repeat(64))
            statement.setTimestamp(4, Timestamp.valueOf("2026-08-04 08:00:00"))
            statement.executeUpdate()
        }
    }

    private fun insertVacancyJob(connection: Connection) {
        connection.prepareStatement(
            """
            INSERT INTO scheduling_waitlist_vacancy_jobs(
                id, tenant_group_id, clinic_id, vacancy_key, vacancy_generation,
                active_vacancy_key, source_appointment_id, source_transition_id,
                resource_type, resource_id, capacity_units, maximum_capacity,
                treatment_type_id, doctor_id, policy_version, status, attempt,
                lease_owner, lease_version, lease_expires_at, next_attempt_at,
                vacancy_starts_at, vacancy_ends_at
            ) VALUES (190201, 190001, 190010, 'vacancy-190201', 1,
                'vacancy-190201:1', 190900, 'transition-190201',
                'PRACTITIONER', 'doctor-190020', 1, 1,
                190030, 190020, 1001, 'PROCESSING', 0,
                'stale-worker', 0, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.valueOf("2026-08-03 07:59:00"))
            statement.setTimestamp(2, Timestamp.valueOf("2026-08-03 08:00:00"))
            statement.setTimestamp(3, Timestamp.valueOf("2026-08-03 09:00:00"))
            statement.setTimestamp(4, Timestamp.valueOf("2026-08-03 09:30:00"))
            statement.executeUpdate()
        }
    }

    private fun claimExpiredVacancyJob(
        connection: Connection,
        owner: String,
        expectedLeaseVersion: Long,
    ): Int =
        connection.prepareStatement(
            """
            UPDATE scheduling_waitlist_vacancy_jobs
            SET status = 'PROCESSING',
                lease_owner = ?,
                lease_version = lease_version + 1,
                lease_expires_at = ?,
                attempt = attempt + 1,
                updated_at = ?
            WHERE id = 190201
              AND status IN ('READY', 'PROCESSING')
              AND lease_expires_at < ?
              AND lease_version = ?
            """.trimIndent(),
        ).use { statement ->
            val now = Timestamp.valueOf("2026-08-03 08:00:00")
            statement.setString(1, owner)
            statement.setTimestamp(2, Timestamp.valueOf("2026-08-03 08:05:00"))
            statement.setTimestamp(3, now)
            statement.setTimestamp(4, now)
            statement.setLong(5, expectedLeaseVersion)
            statement.executeUpdate()
        }

    private fun expectConstraintViolation(block: () -> Unit) {
        try {
            block()
            error("Expected SQL constraint violation")
        } catch (_: SQLException) {
            // expected
        }
    }

    private fun tableNames(connection: Connection): Set<String> {
        val names = mutableSetOf<String>()
        connection.metaData.getTables(null, null, null, arrayOf("TABLE")).use { rows ->
            while (rows.next()) names += rows.getString("TABLE_NAME").lowercase()
        }
        return names
    }

    private fun columnNames(connection: Connection, table: String): Set<String> {
        val names = mutableSetOf<String>()
        readColumns(connection, table).forEach { names += it.lowercase() }
        if (names.isEmpty()) readColumns(connection, table.uppercase()).forEach { names += it.lowercase() }
        return names
    }

    private fun readColumns(connection: Connection, table: String): List<String> {
        val names = mutableListOf<String>()
        connection.metaData.getColumns(null, null, table, null).use { rows ->
            while (rows.next()) names += rows.getString("COLUMN_NAME")
        }
        return names
    }

    private fun importedKeys(connection: Connection, table: String): Set<String> {
        val keys = mutableSetOf<String>()
        readImportedKeys(connection, table, keys)
        readImportedKeys(connection, table.uppercase(), keys)
        return keys
    }

    private fun readImportedKeys(connection: Connection, table: String, keys: MutableSet<String>) {
        connection.metaData.getImportedKeys(null, null, table).use { rows ->
            while (rows.next()) {
                keys += "${rows.getString("FKCOLUMN_NAME").lowercase()}->${rows.getString("PKTABLE_NAME").lowercase()}"
            }
        }
    }

    private fun indexDefinition(
        connection: Connection,
        table: String,
        indexName: String,
    ): List<String> =
        indexRows(connection, table, indexName).ifEmpty {
            indexRows(connection, table.uppercase(), indexName.uppercase())
        }

    private fun uniqueIndexColumns(
        connection: Connection,
        table: String,
        indexName: String,
    ): List<String> =
        indexRows(connection, table, indexName, unique = true).ifEmpty {
            indexRows(connection, table.uppercase(), indexName.uppercase(), unique = true)
        }.map { it.substringBefore(":").lowercase() }

    private fun indexRows(
        connection: Connection,
        table: String,
        indexName: String,
        unique: Boolean = false,
    ): List<String> {
        val rows = mutableListOf<Pair<Short, String>>()
        connection.metaData.getIndexInfo(null, null, table, unique, false).use { resultSet ->
            while (resultSet.next()) {
                val actualName = resultSet.getString("INDEX_NAME") ?: continue
                if (!actualName.equals(indexName, ignoreCase = true)) continue
                val columnName = resultSet.getString("COLUMN_NAME") ?: continue
                val order = when (resultSet.getString("ASC_OR_DESC")?.uppercase()) {
                    "D" -> "D"
                    else -> "A"
                }
                rows += resultSet.getShort("ORDINAL_POSITION") to "${columnName.lowercase()}:$order"
            }
        }
        return rows.sortedBy { it.first }.map { it.second }
    }
}
