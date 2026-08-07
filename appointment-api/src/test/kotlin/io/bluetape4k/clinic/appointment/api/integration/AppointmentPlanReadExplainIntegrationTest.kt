package io.bluetape4k.clinic.appointment.api.integration

import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.api.test.AbstractApiIntegrationTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.system.measureTimeMillis

/**
 * 운영 PostgreSQL/MySQL 스키마가 이름이 지정된 appointment-plan 읽기 인덱스를
 * 구매, dependency, inbox, outbox의 주요 접근 경로에 사용할 수 있는지 검증한다.
 */
class AppointmentPlanReadExplainIntegrationTest @Autowired constructor(
    private val dataSource: DataSource,
) : AbstractApiIntegrationTest() {

    private val fixtureUuid = UUID.randomUUID()
    private val fixturePrefix = "explain-$fixtureUuid".take(48)
    private val fixtureHighId = -1_000_000_000L - (fixtureUuid.leastSignificantBits and 0x3fff_ffffL)
    private var nextFixtureId = fixtureHighId
    private var tenantGroupId: Long = 0
    private var clinicId: Long = 0
    private var targetPlanId: Long = 0
    private var targetPurchaseId: String = ""
    private val dependencyFixturePlanIds = ArrayList<Long>(DEPENDENCY_FIXTURE_PLAN_COUNT)
    private val dialect: Dialect?
        get() = when {
            activeProfiles.contains("test-postgresql") -> Dialect.POSTGRESQL
            activeProfiles.contains("test-mysql") -> Dialect.MYSQL
            else -> null
        }

    @BeforeEach
    fun seedPlannerFixture() {
        assumeSupportedDialect()
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                deleteFixture(connection)
                val seedMillis = measureTimeMillis { seedFixture(connection) }
                val analyzeMillis = measureTimeMillis { analyzePlannerTables(connection) }
                connection.commit()
                println(
                        "APPOINTMENT_PLAN_EXPLAIN_FIXTURE dialect=${requireNotNull(dialect)} " +
                        "plans=$FIXTURE_PLAN_COUNT partitions=$FIXTURE_PARTITION_COUNT " +
                        "dependencyPlans=$DEPENDENCY_FIXTURE_PLAN_COUNT " +
                        "seedMs=$seedMillis analyzeMs=$analyzeMillis"
                )
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
        }
    }

    @AfterEach
    fun deletePlannerFixture() {
        if (nextFixtureId == fixtureHighId) return
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val cleanupMillis = measureTimeMillis { deleteFixture(connection) }
                connection.commit()
                println(
                    "APPOINTMENT_PLAN_EXPLAIN_CLEANUP dialect=${requireNotNull(dialect)} " +
                        "cleanupMs=$cleanupMillis"
                )
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
        }
    }

    @Test
    fun `PostgreSQL and MySQL plans use appointment foundation read indexes`() {
        val currentDialect = assumeSupportedDialect()
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val purchase = explainPlan(
                    connection = connection,
                    dialect = currentDialect,
                    indexName = "uq_plan_source_purchase",
                    sql = """
                        SELECT id
                          FROM scheduling_appointment_plans
                         WHERE tenant_group_id = ?
                           AND clinic_id = ?
                           AND source_purchase_authority = ?
                           AND source_purchase_id = ?
                    """.trimIndent(),
                    parameters = listOf(tenantGroupId, clinicId, "commerce", targetPurchaseId),
                )
                purchase.assertUsesIndex(maxRows = 2)

                val dependencies = explainPlan(
                    connection = connection,
                    dialect = currentDialect,
                    indexName = "idx_treatment_dependency_plan",
                    sql = """
                        SELECT predecessor_treatment_id, successor_treatment_id
                          FROM scheduling_treatment_dependencies
                         WHERE plan_id = ?
                         ORDER BY predecessor_treatment_id, successor_treatment_id
                    """.trimIndent(),
                    parameters = listOf(targetPlanId),
                )
                dependencies.assertUsesIndex(maxRows = 1_200)

                val inbox = explainPlan(
                    connection = connection,
                    dialect = currentDialect,
                    indexName = "idx_inbox_status_replay_after_received",
                    sql = """
                        SELECT id
                          FROM scheduling_inbox_events
                         WHERE status = ?
                           AND replay_after <= ?
                         ORDER BY replay_after, received_at
                         LIMIT 25
                    """.trimIndent(),
                    parameters = listOf("WAITING_GAP", Timestamp.from(NOW.plusSeconds(60))),
                )
                inbox.assertUsesIndex(maxRows = 5_000)

                val outbox = explainPlan(
                    connection = connection,
                    dialect = currentDialect,
                    indexName = "idx_outbox_status_next_attempt",
                    sql = """
                        SELECT id
                          FROM scheduling_outbox_events
                         WHERE status = ?
                           AND next_attempt_at <= ?
                         ORDER BY next_attempt_at
                         LIMIT 25
                    """.trimIndent(),
                    parameters = listOf("PENDING", Timestamp.from(NOW.plusSeconds(60))),
                )
                outbox.assertUsesIndex(maxRows = 10)

                val outboxOldest = explainPlan(
                    connection = connection,
                    dialect = currentDialect,
                    indexName = "idx_outbox_status_created_at",
                    sql = """
                        SELECT id
                          FROM scheduling_outbox_events
                         WHERE status = ?
                         ORDER BY created_at
                         LIMIT 25
                    """.trimIndent(),
                    parameters = listOf("PENDING"),
                )
                outboxOldest.assertUsesIndex(maxRows = 5_000)

                connection.rollback()
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
        }
    }

    private fun assumeSupportedDialect(): Dialect =
        dialect.also {
            assumeTrue(it != null, "EXPLAIN index proof is PostgreSQL/MySQL-only")
        } ?: error("unreachable")

    private fun seedFixture(connection: Connection) {
        val scopes = (0 until FIXTURE_PARTITION_COUNT).map { partition ->
            val partitionTenantId = fixtureId()
            val partitionClinicId = fixtureId()
            val partitionCatalogId = fixtureId()
            connection.executeInsert(
                """
                INSERT INTO scheduling_tenant_groups(id, tenant_code, display_name, active)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
                partitionTenantId,
                "$fixturePrefix-$partition",
                "EXPLAIN Tenant $partition",
                true,
            )
            connection.executeInsert(
                """
                INSERT INTO scheduling_clinics(id, tenant_group_id, name)
                VALUES (?, ?, ?)
                """.trimIndent(),
                partitionClinicId,
                partitionTenantId,
                "EXPLAIN Clinic $partition",
            )
            connection.executeInsert(
                """
                INSERT INTO scheduling_product_catalog_projections(
                    id, tenant_group_id, clinic_id, source_authority, product_id, catalog_version,
                    catalog_status, product_name, schema_version, source_updated_at, payload_hash
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                partitionCatalogId,
                partitionTenantId,
                partitionClinicId,
                "product-catalog",
                "$fixturePrefix-product-$partition",
                1L,
                "ACTIVE",
                "EXPLAIN Product $partition",
                1,
                Timestamp.from(NOW),
                "a".repeat(64),
            )
            FixtureScope(partitionTenantId, partitionClinicId, partitionCatalogId)
        }
        seedPlanAndQueueRows(connection, scopes)
        seedMaximumPlanChildren(connection)
    }

    private fun seedPlanAndQueueRows(
        connection: Connection,
        scopes: List<FixtureScope>,
    ) {
        for (batchStart in 0 until FIXTURE_PLAN_COUNT step INSERT_BATCH_SIZE) {
            val batchEnd = minOf(batchStart + INSERT_BATCH_SIZE, FIXTURE_PLAN_COUNT)
            val planParameters = ArrayList<Any>((batchEnd - batchStart) * PLAN_COLUMN_COUNT)
            val inboxParameters = ArrayList<Any>((batchEnd - batchStart) * INBOX_COLUMN_COUNT)
            val outboxParameters = ArrayList<Any>((batchEnd - batchStart) * OUTBOX_COLUMN_COUNT)
            for (index in batchStart until batchEnd) {
                val scope = scopes[index % scopes.size]
                val planId = fixtureId()
                planParameters.addPlan(planId, scope, index)
                inboxParameters.addInbox(fixtureId(), scope, index)
                outboxParameters.addOutbox(fixtureId(), planId, scope, index)

                if (index == TARGET_INDEX) {
                    tenantGroupId = scope.tenantGroupId
                    clinicId = scope.clinicId
                    targetPlanId = planId
                    targetPurchaseId = purchaseId(index)
                    dependencyFixturePlanIds += planId
                } else if (index < DEPENDENCY_FIXTURE_PLAN_COUNT - 1) {
                    dependencyFixturePlanIds += planId
                }
            }
            connection.executeMultiRowInsert(PLAN_INSERT_PREFIX, PLAN_COLUMN_COUNT, planParameters)
            connection.executeMultiRowInsert(INBOX_INSERT_PREFIX, INBOX_COLUMN_COUNT, inboxParameters)
            connection.executeMultiRowInsert(OUTBOX_INSERT_PREFIX, OUTBOX_COLUMN_COUNT, outboxParameters)
        }
    }

    private fun seedMaximumPlanChildren(connection: Connection) {
        check(dependencyFixturePlanIds.size == DEPENDENCY_FIXTURE_PLAN_COUNT)
        dependencyFixturePlanIds.forEach { planId ->
            seedMaximumPlanChildren(connection, planId)
        }
    }

    private fun seedMaximumPlanChildren(
        connection: Connection,
        planId: Long,
    ) {
        val treatmentIds = LongArray(MAX_TREATMENT_COUNT)
        for (batchStart in 0 until MAX_TREATMENT_COUNT step INSERT_BATCH_SIZE) {
            val batchEnd = minOf(batchStart + INSERT_BATCH_SIZE, MAX_TREATMENT_COUNT)
            val parameters = ArrayList<Any>((batchEnd - batchStart) * TREATMENT_COLUMN_COUNT)
            for (index in batchStart until batchEnd) {
                val treatmentId = fixtureId()
                treatmentIds[index] = treatmentId
                val itemIndex = index / REPEATS_PER_ITEM
                val sequenceNo = index % REPEATS_PER_ITEM + 1
                parameters.addAll(
                    listOf(
                    treatmentId,
                    planId,
                    "item$itemIndex",
                    sequenceNo,
                    itemIndex,
                    "Treatment item$itemIndex",
                    """["CARE"]""",
                    30,
                    7,
                    14,
                    21,
                    """["DOCTOR"]""",
                    """["DEVICE"]""",
                    """["ROOM"]""",
                    "PLANNED",
                    )
                )
            }
            connection.executeMultiRowInsert(TREATMENT_INSERT_PREFIX, TREATMENT_COLUMN_COUNT, parameters)
        }
        val dependencyParameters = ArrayList<Any>(MAX_DEPENDENCY_COUNT * DEPENDENCY_COLUMN_COUNT)
        repeat(MAX_DEPENDENCY_COUNT) { index ->
            dependencyParameters.addAll(
                listOf(
                    fixtureId(),
                    planId,
                    treatmentIds[index],
                    treatmentIds[index + MAX_DEPENDENCY_COUNT],
                    7,
                    14,
                    21,
                )
            )
        }
        connection.executeMultiRowInsert(
            DEPENDENCY_INSERT_PREFIX,
            DEPENDENCY_COLUMN_COUNT,
            dependencyParameters,
        )
    }

    private fun MutableList<Any>.addPlan(
        planId: Long,
        scope: FixtureScope,
        index: Int,
    ) {
        addAll(
            listOf(
                planId,
                scope.tenantGroupId,
                scope.clinicId,
                scope.catalogId,
                "commerce",
                purchaseId(index),
                "encrypted-patient",
                "patient-key",
                "$fixturePrefix-patient-$index",
                "product-catalog",
                "$fixturePrefix-product-${index % FIXTURE_PARTITION_COUNT}",
                1L,
                "a".repeat(64),
                "EXPLAIN Product ${index % FIXTURE_PARTITION_COUNT}",
                "NOT_PROVIDED",
                "{}",
                "DRAFT",
            )
        )
    }

    private fun MutableList<Any>.addInbox(
        inboxId: Long,
        scope: FixtureScope,
        index: Int,
    ) {
        addAll(
            listOf(
                inboxId,
                "$fixturePrefix-inbox-$index",
                "PurchaseCompleted",
                "commerce-service",
                "commerce",
                "$fixturePrefix-purchase-$index",
                1L,
                scope.tenantGroupId,
                scope.clinicId,
                "b".repeat(64),
                if (index % 100 == 0) "WAITING_GAP" else "PROCESSED",
                Timestamp.from(NOW.minusSeconds(index.toLong())),
                index % 5,
                Timestamp.from(NOW.minusSeconds(120 + index.toLong())),
                Timestamp.from(NOW.minusSeconds(60 + index.toLong())),
            )
        )
    }

    private fun MutableList<Any>.addOutbox(
        outboxId: Long,
        planId: Long,
        scope: FixtureScope,
        index: Int,
    ) {
        addAll(
            listOf(
                outboxId,
                "$fixturePrefix-outbox-$index",
                "$fixturePrefix-cause-$index",
                "$fixturePrefix-correlation-$index",
                "AppointmentPlanCreated",
                scope.tenantGroupId,
                scope.clinicId,
                planId,
                1,
                """{"planId":$planId}""",
                if (index % 100 == 0) "PENDING" else "PUBLISHED",
                index % 3,
                Timestamp.from(
                    if (index == 0) {
                        NOW.minusSeconds(1)
                    } else {
                        NOW.plusSeconds(3_600 + index.toLong())
                    }
                ),
                Timestamp.from(NOW.minusSeconds(240 + index.toLong())),
            )
        )
    }

    private fun Connection.executeMultiRowInsert(
        insertPrefix: String,
        columnCount: Int,
        parameters: List<Any>,
    ) {
        require(parameters.isNotEmpty())
        require(parameters.size % columnCount == 0)
        val rowCount = parameters.size / columnCount
        val rowPlaceholder = List(columnCount) { "?" }.joinToString(prefix = "(", postfix = ")")
        val sql = "$insertPrefix VALUES ${List(rowCount) { rowPlaceholder }.joinToString()}"
        prepareStatement(sql).use { statement ->
            parameters.forEachIndexed { index, value ->
                statement.setObject(index + 1, value)
            }
            statement.executeUpdate()
        }
    }

    private fun analyzePlannerTables(connection: Connection) {
        val currentDialect = requireNotNull(dialect) { "planner fixture requires PostgreSQL or MySQL" }
        listOf(
            "scheduling_appointment_plans",
            "scheduling_treatment_dependencies",
            "scheduling_inbox_events",
            "scheduling_outbox_events",
        ).forEach { table ->
            val analyzeSql = when (currentDialect) {
                Dialect.POSTGRESQL -> "ANALYZE $table"
                Dialect.MYSQL -> "ANALYZE TABLE $table"
            }
            connection.createStatement().use { it.execute(analyzeSql) }
        }
    }

    private fun explainPlan(
        connection: Connection,
        dialect: Dialect,
        indexName: String,
        sql: String,
        parameters: List<Any>,
    ): PlanEvidence =
        when (dialect) {
            Dialect.POSTGRESQL -> explainPostgreSQL(connection, indexName, sql, parameters)
            Dialect.MYSQL -> explainMySQL(connection, indexName, sql, parameters)
        }

    private fun explainPostgreSQL(
        connection: Connection,
        indexName: String,
        sql: String,
        parameters: List<Any>,
    ): PlanEvidence =
        connection.prepareStatement("EXPLAIN $sql").use { statement ->
            statement.bind(*parameters.toTypedArray())
            statement.executeQuery().use { rows ->
                val planText = buildString {
                    while (rows.next()) {
                        appendLine(rows.getString(1))
                    }
                }.sanitizePlan()
                PlanEvidence(
                    indexName = indexName,
                    planText = planText,
                    estimatedRows = POSTGRESQL_ROWS.find(planText)
                        ?.groupValues
                        ?.get(1)
                        ?.toLong()
                        ?: Long.MAX_VALUE,
                )
            }
        }

    private fun explainMySQL(
        connection: Connection,
        indexName: String,
        sql: String,
        parameters: List<Any>,
    ): PlanEvidence =
        connection.prepareStatement("EXPLAIN $sql").use { statement ->
            statement.bind(*parameters.toTypedArray())
            statement.executeQuery().use { rows ->
                check(rows.next()) { "MySQL EXPLAIN returned no rows for $indexName" }
                val usedKey = rows.getString("key").orEmpty()
                val estimatedRows = rows.getLong("rows")
                val planText = buildString {
                    append("key=").append(usedKey)
                    append("; rows=").append(estimatedRows)
                    append("; type=").append(rows.getString("type"))
                    append("; extra=").append(rows.getString("Extra"))
                }.sanitizePlan()
                PlanEvidence(
                    indexName = indexName,
                    planText = planText,
                    estimatedRows = estimatedRows,
                )
            }
        }

    private fun deleteFixture(connection: Connection) {
        listOf(
            "scheduling_treatment_dependencies",
            "scheduling_planned_treatments",
            "scheduling_outbox_events",
            "scheduling_inbox_events",
            "scheduling_appointment_plans",
            "scheduling_product_catalog_bom_dependencies",
            "scheduling_product_catalog_bom_items",
            "scheduling_product_catalog_projections",
            "scheduling_clinics",
            "scheduling_tenant_groups",
        ).forEach { table ->
            connection.prepareStatement(
                "DELETE FROM $table WHERE id <= ? AND id > ?",
            ).use { statement ->
                statement.setLong(1, fixtureHighId)
                statement.setLong(2, nextFixtureId)
                statement.executeUpdate()
            }
        }
        tenantGroupId = 0
        clinicId = 0
        targetPlanId = 0
        targetPurchaseId = ""
        dependencyFixturePlanIds.clear()
    }

    private fun Connection.executeInsert(
        sql: String,
        vararg parameters: Any,
    ) {
        prepareStatement(sql).use { statement ->
            statement.bind(*parameters)
            statement.executeUpdate()
        }
    }

    private fun fixtureId(): Long = nextFixtureId--

    private fun PreparedStatement.bind(vararg parameters: Any) {
        parameters.forEachIndexed { index, value ->
            setObject(index + 1, value)
        }
    }

    private fun purchaseId(index: Int): String = "$fixturePrefix-purchase-$index"

    private fun PlanEvidence.assertUsesIndex(maxRows: Long) {
        println(
            "APPOINTMENT_PLAN_EXPLAIN expectedIndex=$indexName estimatedRows=$estimatedRows " +
                "plan=${planText.replace('\n', ' ')}"
        )
        val lowerPlan = planText.lowercase()
        lowerPlan.contains(indexName.lowercase()).shouldBeTrue()
        (estimatedRows in 0..maxRows).shouldBeTrue()
    }

    private fun String.sanitizePlan(): String =
        replace(fixturePrefix, "<fixture>")
            .lines()
            .joinToString("\n") { it.trim() }

    private data class PlanEvidence(
        val indexName: String,
        val planText: String,
        val estimatedRows: Long,
    )

    private data class FixtureScope(
        val tenantGroupId: Long,
        val clinicId: Long,
        val catalogId: Long,
    )

    private enum class Dialect {
        POSTGRESQL,
        MYSQL,
    }

    private companion object {
        private const val FIXTURE_PARTITION_COUNT = 20
        private const val FIXTURE_PLAN_COUNT = 100_000
        private const val TARGET_INDEX = 77_777
        private const val INSERT_BATCH_SIZE = 1_000
        private const val MAX_TREATMENT_COUNT = 2_000
        private const val MAX_DEPENDENCY_COUNT = 1_000
        private const val DEPENDENCY_FIXTURE_PLAN_COUNT = 20
        private const val REPEATS_PER_ITEM = 100
        private const val PLAN_COLUMN_COUNT = 17
        private const val INBOX_COLUMN_COUNT = 15
        private const val OUTBOX_COLUMN_COUNT = 14
        private const val TREATMENT_COLUMN_COUNT = 15
        private const val DEPENDENCY_COLUMN_COUNT = 7
        private val PLAN_INSERT_PREFIX = """
            INSERT INTO scheduling_appointment_plans(
                id, tenant_group_id, clinic_id, catalog_projection_id,
                source_purchase_authority, source_purchase_id,
                patient_reference_ciphertext, patient_reference_key_id,
                patient_reference_fingerprint, catalog_source_authority,
                product_id, catalog_version, catalog_payload_hash, product_name,
                booking_preference_type, booking_preference_payload, status
            )
        """.trimIndent()
        private val INBOX_INSERT_PREFIX = """
            INSERT INTO scheduling_inbox_events(
                id, event_id, event_type, producer, source_authority, source_aggregate_id,
                source_aggregate_version, tenant_group_id, clinic_id, payload_hash,
                status, occurred_at, attempt_count, replay_after, received_at
            )
        """.trimIndent()
        private val OUTBOX_INSERT_PREFIX = """
            INSERT INTO scheduling_outbox_events(
                id, event_id, causation_event_id, correlation_id, event_type,
                tenant_group_id, clinic_id, plan_id,
                schema_version, payload_json, status, attempt_count,
                next_attempt_at, created_at
            )
        """.trimIndent()
        private val TREATMENT_INSERT_PREFIX = """
            INSERT INTO scheduling_planned_treatments(
                id, plan_id, bom_item_id, sequence_no, bom_order,
                representative_treatment_name, detailed_treatment_codes_json,
                duration_minutes, minimum_interval_days, preferred_interval_days,
                maximum_interval_days, practitioner_qualifications_json,
                equipment_types_json, room_types_json, status
            )
        """.trimIndent()
        private val DEPENDENCY_INSERT_PREFIX = """
            INSERT INTO scheduling_treatment_dependencies(
                id, plan_id, predecessor_treatment_id, successor_treatment_id,
                minimum_interval_days, preferred_interval_days, maximum_interval_days
            )
        """.trimIndent()
        private val NOW = Instant.parse("2026-07-26T05:00:00Z")
        private val POSTGRESQL_ROWS = Regex("""rows=(\d+)""")
        private val activeProfiles: String
            get() = System.getProperty("spring.profiles.active", "test")
    }
}
