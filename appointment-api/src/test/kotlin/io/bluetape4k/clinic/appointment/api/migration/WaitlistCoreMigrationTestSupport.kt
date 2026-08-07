package io.bluetape4k.clinic.appointment.api.migration

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.Date
import java.sql.SQLException
import java.sql.Time
import java.sql.Timestamp
import javax.sql.DataSource

/** V18 waitlist core additive schema를 clean install과 V17 upgrade 양쪽에서 검증합니다. */
internal object WaitlistCoreMigrationTestSupport {

    enum class Dialect {
        H2,
        POSTGRESQL,
        MYSQL,
    }

    private val expectedTables = setOf(
        "scheduling_waitlist_entries",
        "scheduling_waitlist_offers",
        "scheduling_waitlist_capacity_holds",
        "scheduling_waitlist_offer_events",
    )

    private val expectedColumns = mapOf(
        "scheduling_waitlist_entries" to setOf(
            "id",
            "tenant_group_id",
            "clinic_id",
            "member_id",
            "treatment_type_id",
            "doctor_id",
            "preferred_date_from",
            "preferred_date_to",
            "preferred_start_time",
            "preferred_end_time",
            "priority_rank",
            "status",
            "waiting_since",
            "version",
            "created_at",
            "updated_at",
        ),
        "scheduling_waitlist_offers" to setOf(
            "id",
            "tenant_group_id",
            "clinic_id",
            "member_id",
            "waitlist_entry_id",
            "vacancy_key",
            "active_entry_key",
            "active_vacancy_key",
            "resource_type",
            "resource_id",
            "capacity_units",
            "maximum_capacity",
            "doctor_id",
            "treatment_type_id",
            "starts_at",
            "ends_at",
            "expires_at",
            "status",
            "booking_reliability_decision_id",
            "booking_reliability_policy_version_id",
            "booking_reliability_policy_hash",
            "booking_reliability_evaluation_digest",
            "booking_reliability_expires_at",
            "candidate_rank",
            "selection_reason_code",
            "version",
            "created_at",
            "updated_at",
        ),
        "scheduling_waitlist_capacity_holds" to setOf(
            "id",
            "tenant_group_id",
            "clinic_id",
            "member_id",
            "offer_id",
            "vacancy_key",
            "active_vacancy_key",
            "resource_type",
            "resource_id",
            "starts_at",
            "ends_at",
            "capacity_units",
            "maximum_capacity",
            "status",
            "hold_expires_at",
            "version",
            "created_at",
            "updated_at",
            "released_at",
            "consumed_at",
        ),
        "scheduling_waitlist_offer_events" to setOf(
            "id",
            "waitlist_entry_id",
            "offer_id",
            "hold_id",
            "from_state",
            "to_state",
            "reason_code",
            "actor_ref",
            "correlation_id",
            "occurred_at",
            "event_version",
        ),
    )

    fun verifyV18Migration(
        dataSource: DataSource,
        location: String,
        dialect: Dialect,
    ) {
        verifyUpgradeMigration(dataSource, location, dialect)
        verifyCleanMigration(dataSource, location, dialect)
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
            .target("17")
            .load()
            .migrate()

        val result = Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .target("18")
            .load()
            .migrate()

        result.success shouldBeEqualTo true
        result.migrationsExecuted shouldBeEqualTo 1
        verifyAppliedChecksum(dataSource, location)
        dataSource.connection.use { verifyWaitlistContract(it, dialect) }
    }

    private fun verifyCleanMigration(
        dataSource: DataSource,
        location: String,
        dialect: Dialect,
    ) {
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .target("18")
            .cleanDisabled(false)
            .load()
        flyway.clean()

        val result = flyway.migrate()

        result.success shouldBeEqualTo true
        result.migrationsExecuted shouldBeEqualTo 18
        dataSource.connection.use { verifyWaitlistContract(it, dialect) }
    }

    private fun verifyAppliedChecksum(dataSource: DataSource, location: String) {
        val applied = Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .load()
            .info()
            .applied()
            .single { it.version?.version == "18" }
        check(applied.checksum != null) { "Applied V18 checksum must be recorded" }
    }

    private fun verifyWaitlistContract(connection: Connection, dialect: Dialect) {
        val tables = tableNames(connection)
        check(expectedTables.all(tables::contains)) {
            "Missing V18 waitlist tables: ${expectedTables - tables}"
        }
        verifyColumns(connection)
        verifyForeignKeys(connection)
        verifyIndexes(connection)
        verifyNullableActiveKeyReuse(connection)
        verifyNoPiiColumns(connection)
        verifyExplainUsesCandidateIndex(connection, dialect)
    }

    private fun verifyColumns(connection: Connection) {
        expectedColumns.forEach { (table, expected) ->
            columnNames(connection, table) shouldBeEqualTo expected
        }
    }

    private fun verifyForeignKeys(connection: Connection) {
        importedKeys(connection, "scheduling_waitlist_entries").containsAll(
            setOf(
                "tenant_group_id->scheduling_tenant_groups",
                "clinic_id->scheduling_clinics",
                "treatment_type_id->scheduling_treatment_types",
                "doctor_id->scheduling_doctors",
            ),
        ).shouldBeTrue()

        importedKeys(connection, "scheduling_waitlist_offers").contains(
            "waitlist_entry_id->scheduling_waitlist_entries",
        ).shouldBeTrue()
        importedKeys(connection, "scheduling_waitlist_capacity_holds").contains(
            "offer_id->scheduling_waitlist_offers",
        ).shouldBeTrue()
        importedKeys(connection, "scheduling_waitlist_offer_events").containsAll(
            setOf(
                "waitlist_entry_id->scheduling_waitlist_entries",
                "offer_id->scheduling_waitlist_offers",
                "hold_id->scheduling_waitlist_capacity_holds",
            ),
        ).shouldBeTrue()
    }

    private fun verifyIndexes(connection: Connection) {
        indexDefinition(
            connection,
            "scheduling_waitlist_entries",
            "idx_waitlist_entry_candidate",
        ) shouldBeEqualTo listOf(
            "tenant_group_id:A",
            "clinic_id:A",
            "treatment_type_id:A",
            "status:A",
            "preferred_date_from:A",
            "preferred_date_to:A",
            "priority_rank:D",
            "waiting_since:A",
            "id:A",
        )
        indexDefinition(
            connection,
            "scheduling_waitlist_entries",
            "idx_waitlist_entry_doctor_candidate",
        ) shouldBeEqualTo listOf(
            "tenant_group_id:A",
            "clinic_id:A",
            "doctor_id:A",
            "treatment_type_id:A",
            "status:A",
            "preferred_date_from:A",
            "preferred_date_to:A",
            "priority_rank:D",
            "waiting_since:A",
            "id:A",
        )
        indexDefinition(
            connection,
            "scheduling_waitlist_capacity_holds",
            "idx_waitlist_capacity_hold_overlap",
        ) shouldBeEqualTo listOf(
            "tenant_group_id:A",
            "clinic_id:A",
            "resource_type:A",
            "resource_id:A",
            "status:A",
            "starts_at:A",
            "ends_at:A",
            "id:A",
        )
        uniqueIndexColumns(
            connection,
            "scheduling_waitlist_offers",
            "uq_waitlist_offer_active_entry",
        ) shouldBeEqualTo listOf("tenant_group_id", "clinic_id", "active_entry_key")
        uniqueIndexColumns(
            connection,
            "scheduling_waitlist_offers",
            "uq_waitlist_offer_active_vacancy",
        ) shouldBeEqualTo listOf("tenant_group_id", "clinic_id", "active_vacancy_key")
        uniqueIndexColumns(
            connection,
            "scheduling_waitlist_capacity_holds",
            "uq_waitlist_capacity_hold_offer",
        ) shouldBeEqualTo listOf("offer_id")
        uniqueIndexColumns(
            connection,
            "scheduling_waitlist_capacity_holds",
            "uq_waitlist_capacity_hold_active_vacancy",
        ) shouldBeEqualTo listOf("tenant_group_id", "clinic_id", "active_vacancy_key")
    }

    private fun verifyNullableActiveKeyReuse(connection: Connection) {
        seedScope(connection)
        insertEntry(connection, id = 181001L, memberId = "member-181001")
        insertEntry(connection, id = 181002L, memberId = "member-181002")

        insertOffer(
            connection = connection,
            id = 182001L,
            entryId = 181001L,
            memberId = "member-181001",
            vacancyKey = "vacancy-reuse",
            activeEntryKey = null,
            activeVacancyKey = null,
            status = "DECLINED",
        )
        insertOffer(
            connection = connection,
            id = 182002L,
            entryId = 181002L,
            memberId = "member-181002",
            vacancyKey = "vacancy-reuse",
            activeEntryKey = "entry-181002",
            activeVacancyKey = "vacancy-reuse",
            status = "OFFERED",
        )

        expectConstraintViolation {
            insertOffer(
                connection = connection,
                id = 182003L,
                entryId = 181001L,
                memberId = "member-181001",
                vacancyKey = "vacancy-reuse",
                activeEntryKey = "entry-181003",
                activeVacancyKey = "vacancy-reuse",
                status = "OFFERED",
            )
        }
        insertOffer(
            connection = connection,
            id = 182004L,
            entryId = 181001L,
            memberId = "member-181001",
            vacancyKey = "vacancy-other",
            activeEntryKey = "entry-181004",
            activeVacancyKey = "vacancy-other",
            status = "OFFERED",
        )

        insertHold(
            connection = connection,
            id = 183001L,
            offerId = 182001L,
            memberId = "member-181001",
            activeVacancyKey = null,
            status = "RELEASED",
        )
        insertHold(
            connection = connection,
            id = 183002L,
            offerId = 182002L,
            memberId = "member-181002",
            activeVacancyKey = "vacancy-reuse",
            status = "OFFERED",
        )

        expectConstraintViolation {
            insertHold(
                connection = connection,
                id = 183003L,
                offerId = 182004L,
                memberId = "member-181001",
                activeVacancyKey = "vacancy-reuse",
                status = "OFFERED",
            )
        }
    }

    private fun verifyNoPiiColumns(connection: Connection) {
        val forbidden = setOf(
            "patient_name",
            "patient_phone",
            "profile",
            "raw_member_profile",
            "raw_payload",
            "payload_json",
        )
        expectedTables.forEach { table ->
            forbidden.intersect(columnNames(connection, table)).isEmpty().shouldBeTrue()
        }
    }

    private fun verifyExplainUsesCandidateIndex(connection: Connection, dialect: Dialect) {
        if (dialect == Dialect.H2) return

        val sql = when (dialect) {
            Dialect.POSTGRESQL ->
                """
                EXPLAIN SELECT id
                FROM scheduling_waitlist_entries
                WHERE tenant_group_id = 180001
                  AND clinic_id = 180010
                  AND treatment_type_id = 180030
                  AND status = 'WAITING'
                  AND preferred_date_from = DATE '2026-08-01'
                  AND preferred_date_to = DATE '2026-08-01'
                ORDER BY priority_rank DESC, waiting_since ASC, id ASC
                LIMIT 1
                """.trimIndent()

            Dialect.MYSQL ->
                """
                EXPLAIN SELECT id
                FROM scheduling_waitlist_entries
                WHERE tenant_group_id = 180001
                  AND clinic_id = 180010
                  AND treatment_type_id = 180030
                  AND status = 'WAITING'
                  AND preferred_date_from = DATE('2026-08-01')
                  AND preferred_date_to = DATE('2026-08-01')
                ORDER BY priority_rank DESC, waiting_since ASC, id ASC
                LIMIT 1
                """.trimIndent()

            Dialect.H2 -> error("H2 explain is not inspected")
        }

        val plan = buildString {
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    val metadata = rows.metaData
                    while (rows.next()) {
                        for (index in 1..metadata.columnCount) {
                            append(rows.getString(index)).append(' ')
                        }
                    }
                }
            }
        }.lowercase()

        check(
            "idx_waitlist_entry_candidate" in plan ||
                "idx_waitlist_entry_doctor_candidate" in plan ||
                "waitlist_entry_candidate" in plan,
        ) {
            "candidate query does not use waitlist candidate index: $plan"
        }
        check("filesort" !in plan) { "candidate query uses filesort: $plan" }
        check("seq scan" !in plan) { "candidate query uses sequential scan: $plan" }
    }

    private fun seedScope(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                INSERT INTO scheduling_tenant_groups(id, tenant_code, display_name, active)
                VALUES (180001, 'waitlist-migration', 'Waitlist Migration', TRUE)
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                INSERT INTO scheduling_clinics(id, tenant_group_id, name)
                VALUES (180010, 180001, 'Waitlist Clinic')
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                INSERT INTO scheduling_doctors(id, clinic_id, name)
                VALUES (180020, 180010, 'Waitlist Doctor')
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                INSERT INTO scheduling_treatment_types(id, clinic_id, name, default_duration_minutes)
                VALUES (180030, 180010, 'Waitlist Treatment', 30)
                """.trimIndent(),
            )
        }
    }

    private fun insertEntry(connection: Connection, id: Long, memberId: String) {
        connection.prepareStatement(
            """
            INSERT INTO scheduling_waitlist_entries(
                id, tenant_group_id, clinic_id, member_id, treatment_type_id, doctor_id,
                preferred_date_from, preferred_date_to, preferred_start_time, preferred_end_time,
                priority_rank, status, waiting_since, version, created_at, updated_at
            ) VALUES (?, 180001, 180010, ?, 180030, 180020,
                ?, ?, ?, ?,
                10, 'WAITING', ?, 0, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            val now = Timestamp.valueOf("2026-08-01 08:00:00")
            statement.setLong(1, id)
            statement.setString(2, memberId)
            statement.setDate(3, Date.valueOf("2026-08-01"))
            statement.setDate(4, Date.valueOf("2026-08-01"))
            statement.setTime(5, Time.valueOf("09:00:00"))
            statement.setTime(6, Time.valueOf("12:00:00"))
            statement.setTimestamp(7, now)
            statement.setTimestamp(8, now)
            statement.setTimestamp(9, now)
            statement.executeUpdate()
        }
    }

    private fun insertOffer(
        connection: Connection,
        id: Long,
        entryId: Long,
        memberId: String,
        vacancyKey: String,
        activeEntryKey: String?,
        activeVacancyKey: String?,
        status: String,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO scheduling_waitlist_offers(
                id, tenant_group_id, clinic_id, member_id, waitlist_entry_id,
                vacancy_key, active_entry_key, active_vacancy_key, resource_type, resource_id,
                capacity_units, maximum_capacity, doctor_id, treatment_type_id,
                starts_at, ends_at, expires_at, status,
                booking_reliability_decision_id, booking_reliability_policy_version_id,
                booking_reliability_policy_hash, booking_reliability_evaluation_digest,
                candidate_rank, selection_reason_code, version, created_at, updated_at
            ) VALUES (?, 180001, 180010, ?, ?, ?, ?, ?, 'PRACTITIONER', 'doctor-180020', 1, 1,
                180020, 180030, ?, ?, ?, ?, ?, ?, ?, ?, 1, 'MIGRATION_CHECK', 0, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            val startsAt = Timestamp.valueOf("2026-08-01 09:00:00")
            val endsAt = Timestamp.valueOf("2026-08-01 09:30:00")
            val expiresAt = Timestamp.valueOf("2026-08-01 08:45:00")
            val now = Timestamp.valueOf("2026-08-01 08:00:00")
            statement.setLong(1, id)
            statement.setString(2, memberId)
            statement.setLong(3, entryId)
            statement.setString(4, vacancyKey)
            statement.setString(5, activeEntryKey)
            statement.setString(6, activeVacancyKey)
            statement.setTimestamp(7, startsAt)
            statement.setTimestamp(8, endsAt)
            statement.setTimestamp(9, expiresAt)
            statement.setString(10, status)
            statement.setLong(11, 182000L)
            statement.setLong(12, 7L)
            statement.setString(13, "a".repeat(64))
            statement.setString(14, "b".repeat(64))
            statement.setTimestamp(15, now)
            statement.setTimestamp(16, now)
            statement.executeUpdate()
        }
    }

    private fun insertHold(
        connection: Connection,
        id: Long,
        offerId: Long,
        memberId: String,
        activeVacancyKey: String?,
        status: String,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO scheduling_waitlist_capacity_holds(
                id, tenant_group_id, clinic_id, member_id, offer_id,
                vacancy_key, active_vacancy_key, resource_type, resource_id,
                starts_at, ends_at, capacity_units, maximum_capacity, status,
                hold_expires_at, version, created_at, updated_at
            ) VALUES (?, 180001, 180010, ?, ?, 'vacancy-reuse', ?, 'PRACTITIONER', 'doctor-180020',
                ?, ?, 1, 1, ?, ?, 0, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            val startsAt = Timestamp.valueOf("2026-08-01 09:00:00")
            val endsAt = Timestamp.valueOf("2026-08-01 09:30:00")
            val expiresAt = Timestamp.valueOf("2026-08-01 08:45:00")
            val now = Timestamp.valueOf("2026-08-01 08:00:00")
            statement.setLong(1, id)
            statement.setString(2, memberId)
            statement.setLong(3, offerId)
            statement.setString(4, activeVacancyKey)
            statement.setTimestamp(5, startsAt)
            statement.setTimestamp(6, endsAt)
            statement.setString(7, status)
            statement.setTimestamp(8, expiresAt)
            statement.setTimestamp(9, now)
            statement.setTimestamp(10, now)
            statement.executeUpdate()
        }
    }

    private fun expectConstraintViolation(block: () -> Unit) {
        try {
            block()
            error("Expected SQL constraint violation")
        } catch (_: SQLException) {
// 예상 결과
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
