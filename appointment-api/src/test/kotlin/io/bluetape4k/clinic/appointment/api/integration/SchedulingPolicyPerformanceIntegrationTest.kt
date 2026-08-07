package io.bluetape4k.clinic.appointment.api.integration

import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.api.test.AbstractApiIntegrationTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.env.Environment
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.sql.Connection
import java.sql.Date
import java.sql.PreparedStatement
import java.sql.Time
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import javax.sql.DataSource
import kotlin.system.measureTimeMillis

/**
 * 정책 미리보기와 활성화 worker의 주요 read path가 대량 데이터에서도 bounded query를 유지하는지 검증한다.
 *
 * 각 hot table에 10,000건을 주입한 뒤 실제 Flyway schema의 named index가 선택되는지 확인한다.
 * PostgreSQL과 MySQL은 optimizer가 선택한 호환 named index node의 예상 탐색 행 수까지 제한하고,
 * H2는 호환 index와 실제 반환 행 수를 확인한다. optimizer는 통계와 index 폭에 따라 preview 전용
 * index 또는 기존 clinic/date/status index를 선택할 수 있지만 full table scan은 허용하지 않는다.
 * 실행 시간은 환경별 관측값으로 남기되 machine-specific microbenchmark
 * 임계값으로 사용하지 않는다. deadline과 최대 materialization은 monotonic clock 기반 단위
 * 테스트와 이 테스트의 query/result 상한으로 각각 검증한다.
 */
class SchedulingPolicyPerformanceIntegrationTest @Autowired constructor(
    private val dataSource: DataSource,
    private val environment: Environment,
) : AbstractApiIntegrationTest() {

    private val fixtureUuid = UUID.randomUUID()
    private val fixturePrefix = "policy-perf-${fixtureUuid.toString().replace("-", "")}".take(48)
    private val fixtureHighId = -3_000_000_000L - (fixtureUuid.leastSignificantBits and 0x1fff_ffffL)
    private var nextFixtureId = fixtureHighId
    private val scopes = ArrayList<FixtureScope>(PARTITION_COUNT)
    private val planIds = ArrayList<Long>(PARTITION_COUNT)

    @BeforeEach
    fun seedPerformanceFixture() {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                deleteFixture(connection)
                val seedMillis = measureTimeMillis {
                    seedScopes(connection)
                    seedAppointments(connection)
                    seedAppointmentPlans(connection)
                    seedPlannedTreatments(connection)
                    seedPolicyDefinitions(connection)
                    seedActivationCommands(connection)
                    seedOutboxEvents(connection)
                    analyzeTables(connection)
                }
                connection.commit()
                println(
                    "SCHEDULING_POLICY_PERFORMANCE_FIXTURE dialect=$dialect " +
                        "rowsPerHotTable=$HOT_TABLE_ROW_COUNT seedMs=$seedMillis"
                )
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
        }
    }

    @AfterEach
    fun deletePerformanceFixture() {
        if (nextFixtureId == fixtureHighId) return
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                deleteFixture(connection)
                connection.commit()
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
        }
    }

    @Test
    fun `ten thousand row policy hot paths use bounded indexed reads`() {
        val target = scopes[TARGET_PARTITION]
        dataSource.connection.use { connection ->
            val preview = explainAndRead(
                connection = connection,
                indexName = "idx_appointments_policy_preview",
                alternativeIndexNames = setOf("idx_appointments_clinic_date_status"),
                sql = """
                    SELECT id
                      FROM scheduling_appointments
                     WHERE clinic_id = ?
                       AND status = ?
                       AND appointment_date >= ?
                     ORDER BY appointment_date, start_time, id
                     LIMIT 5000
                """.trimIndent(),
                parameters = listOf(target.clinicId, "REQUESTED", Date.valueOf(BASE_DATE)),
                maxEstimatedRows = INDEX_PROOF_ROW_COUNT + INDEX_PROOF_NOISE_ROW_COUNT,
                maxActualRows = INDEX_PROOF_ROW_COUNT,
            )
            preview.assertBounded()

            val maximumPreviewPage = explainAndRead(
                connection = connection,
                indexName = "idx_appointments_policy_preview",
                alternativeIndexNames = setOf("idx_appointments_clinic_date_status"),
                sql = """
                    SELECT id
                      FROM scheduling_appointments
                     WHERE clinic_id = ?
                       AND status = ?
                       AND appointment_date >= ?
                     ORDER BY appointment_date, start_time, id
                     LIMIT 5000
                """.trimIndent(),
                parameters = listOf(
                    scopes[MAX_PAGE_PARTITION].clinicId,
                    "REQUESTED",
                    Date.valueOf(BASE_DATE),
                ),
                maxEstimatedRows = PREVIEW_PAGE_SIZE,
                maxActualRows = PREVIEW_PAGE_SIZE,
            )
            maximumPreviewPage.assertBounded()
            (maximumPreviewPage.actualRows == PREVIEW_PAGE_SIZE).shouldBeTrue()

            val plannedTreatmentPreview = explainAndRead(
                connection = connection,
                indexName = "idx_treatment_plan_status_window",
                alternativeIndexNames = setOf(
                    "idx_plan_tenant_clinic_status",
                    "uq_plan_source_purchase",
                ),
                sql = """
                    SELECT treatment.id
                      FROM scheduling_planned_treatments treatment
                      JOIN scheduling_appointment_plans plan ON plan.id = treatment.plan_id
                     WHERE plan.tenant_group_id = ?
                       AND plan.clinic_id = ?
                       AND plan.status IN (?, ?)
                       AND treatment.earliest_start_at IS NOT NULL
                       AND treatment.earliest_start_at >= ?
                       AND treatment.earliest_start_at < ?
                       AND treatment.status IN (?, ?, ?)
                     ORDER BY treatment.earliest_start_at, treatment.id
                     LIMIT 5000
                """.trimIndent(),
                parameters = listOf(
                    target.tenantGroupId,
                    target.clinicId,
                    "ACTIVE",
                    "PARTIALLY_FULFILLED",
                    Timestamp.from(NOW),
                    Timestamp.from(NOW.plusSeconds(365L * 86_400L)),
                    "PLANNED",
                    "SCHEDULED",
                    "BLOCKED_REVIEW",
                ),
                maxEstimatedRows = INDEX_PROOF_ROW_COUNT + INDEX_PROOF_NOISE_ROW_COUNT + 1,
                maxActualRows = INDEX_PROOF_ROW_COUNT,
            )
            plannedTreatmentPreview.assertBounded()
            plannedTreatmentPreview.assertUsesIndex("idx_treatment_plan_status_window")
            plannedTreatmentPreview.assertUsesAnyIndex(
                "idx_plan_tenant_clinic_status",
                "uq_plan_source_purchase",
            )

            val maximumPlannedTreatmentPage = explainAndRead(
                connection = connection,
                indexName = "idx_treatment_plan_status_window",
                alternativeIndexNames = setOf(
                    "idx_plan_tenant_clinic_status",
                    "uq_plan_source_purchase",
                ),
                sql = """
                    SELECT treatment.id
                      FROM scheduling_planned_treatments treatment
                      JOIN scheduling_appointment_plans plan ON plan.id = treatment.plan_id
                     WHERE plan.tenant_group_id = ?
                       AND plan.clinic_id = ?
                       AND plan.status IN (?, ?)
                       AND treatment.earliest_start_at IS NOT NULL
                       AND treatment.earliest_start_at >= ?
                       AND treatment.earliest_start_at < ?
                       AND treatment.status IN (?, ?, ?)
                     ORDER BY treatment.earliest_start_at, treatment.id
                     LIMIT 5000
                """.trimIndent(),
                parameters = listOf(
                    scopes[MAX_PAGE_PARTITION].tenantGroupId,
                    scopes[MAX_PAGE_PARTITION].clinicId,
                    "ACTIVE",
                    "PARTIALLY_FULFILLED",
                    Timestamp.from(NOW),
                    Timestamp.from(NOW.plusSeconds(365L * 86_400L)),
                    "PLANNED",
                    "SCHEDULED",
                    "BLOCKED_REVIEW",
                ),
                maxEstimatedRows = PREVIEW_PAGE_SIZE + 1,
                maxActualRows = PREVIEW_PAGE_SIZE,
            )
            maximumPlannedTreatmentPage.assertBounded()
            maximumPlannedTreatmentPage.assertUsesIndex("idx_treatment_plan_status_window")
            maximumPlannedTreatmentPage.assertUsesAnyIndex(
                "idx_plan_tenant_clinic_status",
                "uq_plan_source_purchase",
            )
            (maximumPlannedTreatmentPage.actualRows == PREVIEW_PAGE_SIZE).shouldBeTrue()

            val overlap = explainAndRead(
                connection = connection,
                indexName = "idx_policy_definition_effective",
                sql = """
                    SELECT id
                      FROM scheduling_policy_definitions
                     WHERE tenant_group_id = ?
                       AND scope = ?
                       AND clinic_scope_key = ?
                       AND policy_kind = ?
                       AND lifecycle = ?
                       AND effective_from < ?
                       AND (effective_until IS NULL OR effective_until > ?)
                     ORDER BY effective_from, version
                     LIMIT 25
                """.trimIndent(),
                parameters = listOf(
                    target.tenantGroupId,
                    "TENANT_DEFAULT",
                    0L,
                    "BOOKING_COMMITMENT",
                    "ACTIVE",
                    Timestamp.from(NOW.plusSeconds(3_600)),
                    Timestamp.from(NOW),
                ),
                maxEstimatedRows = ROWS_PER_PARTITION,
                maxActualRows = 1,
            )
            overlap.assertBounded()

            val activation = explainAndRead(
                connection = connection,
                indexName = "idx_policy_activation_due",
                sql = """
                    SELECT id
                      FROM scheduling_policy_activation_commands
                     WHERE status = ?
                       AND next_attempt_at <= ?
                     ORDER BY next_attempt_at, id
                     LIMIT 25
                """.trimIndent(),
                parameters = listOf("PENDING", Timestamp.from(NOW)),
                maxEstimatedRows = HOT_TABLE_ROW_COUNT / DUE_ROW_INTERVAL,
                maxActualRows = 25,
            )
            activation.assertBounded()

            val outbox = explainAndRead(
                connection = connection,
                indexName = "idx_outbox_status_next_attempt",
// MySQL 8.4는 범위가 제한된 skip scan에서 V22 covering index를 선택할 수 있다.
                alternativeIndexNames = setOf("idx_outbox_appointment_ready"),
                sql = """
                    SELECT id
                      FROM scheduling_outbox_events
                     WHERE status = ?
                       AND next_attempt_at <= ?
                     ORDER BY next_attempt_at
                     LIMIT 25
                """.trimIndent(),
                parameters = listOf("PENDING", Timestamp.from(NOW)),
                maxEstimatedRows = HOT_TABLE_ROW_COUNT / DUE_ROW_INTERVAL,
                maxActualRows = 25,
            )
            outbox.assertBounded()
        }
    }

    private fun seedScopes(connection: Connection) {
        val nameColumn = if (dialect == Dialect.MYSQL) "name" else "\"name\""
        repeat(PARTITION_COUNT) { partition ->
            val tenantGroupId = fixtureId()
            val clinicId = fixtureId()
            val doctorId = fixtureId()
            val treatmentTypeId = fixtureId()
            val catalogProjectionId = fixtureId()
            connection.execute(
                """
                INSERT INTO scheduling_tenant_groups(id, tenant_code, display_name, active)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
                tenantGroupId,
                "$fixturePrefix-$partition",
                "Policy Performance Tenant $partition",
                true,
            )
            connection.execute(
                """
                INSERT INTO scheduling_clinics(id, tenant_group_id, $nameColumn)
                VALUES (?, ?, ?)
                """.trimIndent(),
                clinicId,
                tenantGroupId,
                "Policy Performance Clinic $partition",
            )
            connection.execute(
                """
                INSERT INTO scheduling_product_catalog_projections(
                    id, tenant_group_id, clinic_id, source_authority, product_id,
                    catalog_version, catalog_status, product_name, schema_version,
                    source_updated_at, payload_hash
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                catalogProjectionId,
                tenantGroupId,
                clinicId,
                "policy-performance-catalog",
                "$fixturePrefix-product-$partition",
                1L,
                "ACTIVE",
                "Policy Performance Product $partition",
                1,
                Timestamp.from(NOW),
                hex(partition),
            )
            connection.execute(
                """
                INSERT INTO scheduling_doctors(id, clinic_id, $nameColumn)
                VALUES (?, ?, ?)
                """.trimIndent(),
                doctorId,
                clinicId,
                "Policy Performance Doctor $partition",
            )
            connection.execute(
                """
                INSERT INTO scheduling_treatment_types(
                    id, clinic_id, $nameColumn, default_duration_minutes
                ) VALUES (?, ?, ?, ?)
                """.trimIndent(),
                treatmentTypeId,
                clinicId,
                "Policy Performance Treatment $partition",
                30,
            )
            scopes += FixtureScope(
                tenantGroupId,
                clinicId,
                doctorId,
                treatmentTypeId,
                catalogProjectionId,
            )
        }
    }

    private fun seedAppointments(connection: Connection) {
        val nonPreviewPartitions = scopes.indices
            .filterNot { it == TARGET_PARTITION || it == MAX_PAGE_PARTITION }
        connection.prepareStatement(
            """
            INSERT INTO scheduling_appointments(
                id, clinic_id, doctor_id, treatment_type_id, patient_name,
                appointment_date, start_time, end_time, status
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            repeat(HOT_TABLE_ROW_COUNT) { index ->
                val isIndexProofRow = index < INDEX_PROOF_ROW_COUNT
                val isMaximumPageRow = index in INDEX_PROOF_ROW_COUNT until
                    INDEX_PROOF_ROW_COUNT + PREVIEW_PAGE_SIZE
                val isIndexProofNoiseRow = index in
                    INDEX_PROOF_ROW_COUNT + PREVIEW_PAGE_SIZE until
                    INDEX_PROOF_ROW_COUNT + PREVIEW_PAGE_SIZE + INDEX_PROOF_NOISE_ROW_COUNT
                val scope = when {
                    isIndexProofRow -> scopes[TARGET_PARTITION]
                    isMaximumPageRow -> scopes[MAX_PAGE_PARTITION]
                    isIndexProofNoiseRow -> scopes[TARGET_PARTITION]
                    else -> {
                        val remainingIndex = index -
                            INDEX_PROOF_ROW_COUNT -
                            PREVIEW_PAGE_SIZE -
                            INDEX_PROOF_NOISE_ROW_COUNT
                        scopes[nonPreviewPartitions[remainingIndex % nonPreviewPartitions.size]]
                    }
                }
                val minuteOffset = when {
                    isIndexProofRow -> index
                    isMaximumPageRow -> index - INDEX_PROOF_ROW_COUNT
                    isIndexProofNoiseRow -> index - INDEX_PROOF_ROW_COUNT - PREVIEW_PAGE_SIZE
                    else -> index -
                        INDEX_PROOF_ROW_COUNT -
                        PREVIEW_PAGE_SIZE -
                        INDEX_PROOF_NOISE_ROW_COUNT
                }
                statement.bind(
                    fixtureId(),
                    scope.clinicId,
                    scope.doctorId,
                    scope.treatmentTypeId,
                    "$fixturePrefix-patient-$index",
                    Date.valueOf(BASE_DATE.plusDays((minuteOffset / 48).toLong())),
                    Time.valueOf(BASE_TIME.plusMinutes((minuteOffset % 48 * 15).toLong())),
                    Time.valueOf(BASE_TIME.plusMinutes((minuteOffset % 48 * 15 + 30).toLong())),
                    if (isIndexProofRow || isMaximumPageRow) "REQUESTED" else "COMPLETED",
                )
                statement.addBatchAndFlush(index)
            }
            statement.executeBatch()
        }
    }

    private fun seedAppointmentPlans(connection: Connection) {
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
            """.trimIndent(),
        ).use { statement ->
            repeat(HOT_TABLE_ROW_COUNT) { index ->
                val partition = if (index < PARTITION_COUNT) index else PLAN_NOISE_PARTITION
                val scope = scopes[partition]
                val planId = fixtureId()
                if (index < PARTITION_COUNT) planIds += planId
                statement.bind(
                    planId,
                    scope.tenantGroupId,
                    scope.clinicId,
                    scope.catalogProjectionId,
                    "policy-performance-commerce",
                    "$fixturePrefix-purchase-$index",
                    "encrypted-$fixturePrefix-$index",
                    "policy-performance-key",
                    hex(index + HOT_TABLE_ROW_COUNT),
                    "policy-performance-catalog",
                    "$fixturePrefix-product-$partition",
                    1L,
                    hex(partition),
                    "Policy Performance Product $partition",
                    "NONE",
                    "{}",
                    "ACTIVE",
                )
                statement.addBatchAndFlush(index)
            }
            statement.executeBatch()
        }
    }

    private fun seedPlannedTreatments(connection: Connection) {
        val nonPreviewPartitions = scopes.indices
            .filterNot { it == TARGET_PARTITION || it == MAX_PAGE_PARTITION }
        connection.prepareStatement(
            """
            INSERT INTO scheduling_planned_treatments(
                id, plan_id, bom_item_id, sequence_no, bom_order,
                representative_treatment_name, detailed_treatment_codes_json,
                duration_minutes, practitioner_qualifications_json,
                equipment_types_json, room_types_json, earliest_start_at, status
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            repeat(HOT_TABLE_ROW_COUNT) { index ->
                val isIndexProofRow = index < INDEX_PROOF_ROW_COUNT
                val isMaximumPageRow = index in INDEX_PROOF_ROW_COUNT until
                    INDEX_PROOF_ROW_COUNT + PREVIEW_PAGE_SIZE
                val isIndexProofNoiseRow = index in
                    INDEX_PROOF_ROW_COUNT + PREVIEW_PAGE_SIZE until
                    INDEX_PROOF_ROW_COUNT + PREVIEW_PAGE_SIZE + INDEX_PROOF_NOISE_ROW_COUNT
                val partition = when {
                    isIndexProofRow -> TARGET_PARTITION
                    isMaximumPageRow -> MAX_PAGE_PARTITION
                    isIndexProofNoiseRow -> TARGET_PARTITION
                    else -> {
                        val remainingIndex = index -
                            INDEX_PROOF_ROW_COUNT -
                            PREVIEW_PAGE_SIZE -
                            INDEX_PROOF_NOISE_ROW_COUNT
                        nonPreviewPartitions[remainingIndex % nonPreviewPartitions.size]
                    }
                }
                val minuteOffset = when {
                    isIndexProofRow -> index
                    isMaximumPageRow -> index - INDEX_PROOF_ROW_COUNT
                    isIndexProofNoiseRow -> index - INDEX_PROOF_ROW_COUNT - PREVIEW_PAGE_SIZE
                    else -> index -
                        INDEX_PROOF_ROW_COUNT -
                        PREVIEW_PAGE_SIZE -
                        INDEX_PROOF_NOISE_ROW_COUNT
                }
                statement.bind(
                    fixtureId(),
                    planIds[partition],
                    "$fixturePrefix-treatment-$index",
                    index + 1,
                    index,
                    "Policy Performance Planned Treatment $index",
                    """["policy-performance"]""",
                    30,
                    "[]",
                    "[]",
                    "[]",
                    Timestamp.from(NOW.plusSeconds(minuteOffset * 60L)),
                    if (isIndexProofRow || isMaximumPageRow) "PLANNED" else "COMPLETED",
                )
                statement.addBatchAndFlush(index)
            }
            statement.executeBatch()
        }
    }

    private fun seedPolicyDefinitions(connection: Connection) {
        connection.prepareStatement(
            """
            INSERT INTO scheduling_policy_definitions(
                id, tenant_group_id, scope, clinic_id, clinic_scope_key, policy_kind,
                version, schema_version, lifecycle, effective_from, effective_until,
                revision, payload_hash, payload_json, created_by_actor_id,
                created_by_actor_role, change_reason
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            repeat(HOT_TABLE_ROW_COUNT) { index ->
                val scope = scopes[index % scopes.size]
                val version = index / scopes.size + 1L
                statement.bind(
                    fixtureId(),
                    scope.tenantGroupId,
                    "TENANT_DEFAULT",
                    null,
                    0L,
                    "BOOKING_COMMITMENT",
                    version,
                    1,
                    if (version == 1L) "ACTIVE" else "RETIRED",
                    Timestamp.from(NOW.minusSeconds(version * 60)),
                    if (version == 1L) null else Timestamp.from(NOW.minusSeconds((version - 1L) * 60)),
                    version,
                    hex(index),
                    """{"version":$version}""",
                    "policy-performance",
                    "ADMIN",
                    "10,000-row indexed read proof",
                )
                statement.addBatchAndFlush(index)
            }
            statement.executeBatch()
        }
    }

    private fun seedActivationCommands(connection: Connection) {
        connection.prepareStatement(
            """
            INSERT INTO scheduling_policy_activation_commands(
                id, tenant_group_id, scope, clinic_id, clinic_scope_key, definition_id,
                expected_draft_revision, expected_active_revision,
                expected_tenant_generation, expected_clinic_generation,
                preview_evidence_token, idempotency_key_hash, request_fingerprint,
                status, effective_from, next_attempt_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            repeat(HOT_TABLE_ROW_COUNT) { index ->
                val scope = scopes[index % scopes.size]
                val due = index % DUE_ROW_INTERVAL == 0
                statement.bind(
                    fixtureId(),
                    scope.tenantGroupId,
                    "TENANT_DEFAULT",
                    null,
                    0L,
                    fixtureHighId - HOT_TABLE_ROW_COUNT - index,
                    1L,
                    0L,
                    0L,
                    0L,
                    "$fixturePrefix-evidence-$index",
                    hex(index + HOT_TABLE_ROW_COUNT),
                    hex(index + HOT_TABLE_ROW_COUNT * 2),
                    if (due) "PENDING" else "MISSED",
                    Timestamp.from(NOW.minusSeconds(86_400)),
                    Timestamp.from(if (due) NOW.minusSeconds(index.toLong()) else NOW.plusSeconds(86_400)),
                )
                statement.addBatchAndFlush(index)
            }
            statement.executeBatch()
        }
    }

    private fun seedOutboxEvents(connection: Connection) {
        connection.prepareStatement(
            """
            INSERT INTO scheduling_outbox_events(
                id, event_id, correlation_id, event_type, tenant_group_id,
                schema_version, payload_json, status, attempt_count, next_attempt_at,
                aggregate_type, aggregate_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            repeat(HOT_TABLE_ROW_COUNT) { index ->
                val scope = scopes[index % scopes.size]
                val due = index % DUE_ROW_INTERVAL == 0
                statement.bind(
                    fixtureId(),
                    "$fixturePrefix-event-$index",
                    "$fixturePrefix-correlation-$index",
                    "SchedulingPolicyActivated",
                    scope.tenantGroupId,
                    1,
                    """{"index":$index}""",
                    if (due) "PENDING" else "PUBLISHED",
                    0,
                    Timestamp.from(if (due) NOW.minusSeconds(index.toLong()) else NOW.plusSeconds(86_400)),
                    "SCHEDULING_POLICY",
                    "$fixturePrefix-policy-$index",
                )
                statement.addBatchAndFlush(index)
            }
            statement.executeBatch()
        }
    }

    private fun analyzeTables(connection: Connection) {
        val statements = when (dialect) {
            Dialect.POSTGRESQL -> HOT_TABLES.map { "ANALYZE $it" }
            Dialect.MYSQL -> HOT_TABLES.map { "ANALYZE TABLE $it" }
            Dialect.H2 -> HOT_TABLES.map { "ANALYZE" }.distinct()
        }
        statements.forEach { sql ->
            connection.createStatement().use { it.execute(sql) }
        }
    }

    private fun explainAndRead(
        connection: Connection,
        indexName: String,
        alternativeIndexNames: Set<String> = emptySet(),
        sql: String,
        parameters: List<Any>,
        maxEstimatedRows: Int,
        maxActualRows: Int,
    ): QueryEvidence {
        val expectedIndexNames = alternativeIndexNames + indexName
        val plan = explain(connection, expectedIndexNames, sql, parameters)
        var actualRows = 0
        val elapsedMillis = measureTimeMillis {
            connection.prepareStatement(sql).use { statement ->
                statement.bind(*parameters.toTypedArray())
                statement.executeQuery().use { rows ->
                    while (rows.next()) actualRows++
                }
            }
        }
        return QueryEvidence(
            expectedIndexNames = expectedIndexNames,
            planText = plan.text.sanitize(),
            estimatedRows = plan.estimatedRows,
            maxEstimatedRows = maxEstimatedRows.toLong(),
            actualRows = actualRows,
            maxActualRows = maxActualRows,
            elapsedMillis = elapsedMillis,
            fullTableScan = plan.fullTableScan,
        )
    }

    private fun explain(
        connection: Connection,
        expectedIndexNames: Set<String>,
        sql: String,
        parameters: List<Any>,
    ): RawPlan =
        when (dialect) {
            Dialect.POSTGRESQL -> connection.prepareStatement("EXPLAIN (FORMAT JSON) $sql").use { statement ->
                statement.bind(*parameters.toTypedArray())
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "PostgreSQL EXPLAIN returned no rows" }
                    val text = rows.getString(1)
                    val document = PLAN_MAPPER.readTree(text)
                    val rootPlan = document.get(0)?.get("Plan")
                        ?: error("PostgreSQL EXPLAIN JSON has no root Plan")
                    val indexNode = findPlanNode(rootPlan) { node ->
                        node.get("Index Name")?.asText()?.let { selected ->
                            expectedIndexNames.any { it.equals(selected, ignoreCase = true) }
                        } == true
                    } ?: error("PostgreSQL plan did not use expected indexes $expectedIndexNames: $text")
                    val sequentialScan = findPlanNode(rootPlan) { node ->
                        node.get("Node Type")?.asText() == "Seq Scan"
                    }
                    RawPlan(
                        text,
                        indexNode.get("Plan Rows")?.asLong(),
                        sequentialScan != null,
                    )
                }
            }

            Dialect.MYSQL -> connection.prepareStatement("EXPLAIN $sql").use { statement ->
                statement.bind(*parameters.toTypedArray())
                statement.executeQuery().use { rows ->
                    val nodes = ArrayList<String>()
                    var estimatedRows = 0L
                    var fullTableScan = false
                    while (rows.next()) {
                        val type = rows.getString("type")
                        nodes +=
                            "table=${rows.getString("table")}; key=${rows.getString("key")}; " +
                                "rows=${rows.getLong("rows")}; type=$type; extra=${rows.getString("Extra")}"
                        estimatedRows += rows.getLong("rows")
                        fullTableScan = fullTableScan || type.equals("ALL", ignoreCase = true)
                    }
                    check(nodes.isNotEmpty()) { "MySQL EXPLAIN returned no rows" }
                    RawPlan(nodes.joinToString(" | "), estimatedRows, fullTableScan)
                }
            }

            Dialect.H2 -> connection.prepareStatement("EXPLAIN $sql").use { statement ->
                statement.bind(*parameters.toTypedArray())
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "H2 EXPLAIN returned no rows" }
                    RawPlan(rows.getString(1), null, false)
                }
            }
        }

    private fun findPlanNode(
        node: JsonNode,
        predicate: (JsonNode) -> Boolean,
    ): JsonNode? {
        if (predicate(node)) return node
        val children = node.get("Plans") ?: return null
        for (index in 0 until children.size()) {
            findPlanNode(children.get(index), predicate)?.let { return it }
        }
        return null
    }

    private fun deleteFixture(connection: Connection) {
        listOf(
            "scheduling_appointments",
            "scheduling_planned_treatments",
            "scheduling_appointment_plans",
            "scheduling_product_catalog_projections",
            "scheduling_outbox_events",
            "scheduling_policy_activation_commands",
            "scheduling_policy_definitions",
            "scheduling_doctors",
            "scheduling_treatment_types",
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
        scopes.clear()
        planIds.clear()
    }

    private fun Connection.execute(
        sql: String,
        vararg parameters: Any?,
    ) {
        prepareStatement(sql).use { statement ->
            statement.bind(*parameters)
            statement.executeUpdate()
        }
    }

    private fun PreparedStatement.bind(vararg parameters: Any?) {
        parameters.forEachIndexed { index, value ->
            setObject(index + 1, value)
        }
    }

    private fun PreparedStatement.addBatchAndFlush(index: Int) {
        addBatch()
        if ((index + 1) % INSERT_BATCH_SIZE == 0) {
            executeBatch()
        }
    }

    private fun fixtureId(): Long = nextFixtureId--

    private fun hex(value: Int): String = value.toUInt().toString(16).padStart(64, '0')

    private fun String.sanitize(): String =
        replace(fixturePrefix, "<fixture>")
            .lines()
            .joinToString(" ") { it.trim() }

    private fun QueryEvidence.assertBounded() {
        // MySQL InnoDB는 ANALYZE TABLE에서 표본 통계를 갱신하므로, 같은 인덱스 실행 계획도
        // CI 실행마다 정확한 예상 행 수 경계를 넘을 수 있다.
        val estimatedRowCeiling =
            if (dialect == Dialect.MYSQL) {
                maxEstimatedRows + maxOf(
                    maxEstimatedRows * MYSQL_ESTIMATE_TOLERANCE_PERCENT / 100,
                    MYSQL_MINIMUM_ESTIMATE_TOLERANCE,
                )
            } else {
                maxEstimatedRows
            }
        println(
            "SCHEDULING_POLICY_QUERY expectedIndexes=$expectedIndexNames estimatedRows=$estimatedRows " +
                "estimatedRowCeiling=$estimatedRowCeiling actualRows=$actualRows " +
                "elapsedMs=$elapsedMillis plan=$planText"
        )
        expectedIndexNames.any { planText.contains(it, ignoreCase = true) }.shouldBeTrue()
        (estimatedRows == null || estimatedRows in 0..estimatedRowCeiling).shouldBeTrue()
        (actualRows in 1..maxActualRows).shouldBeTrue()
        (!fullTableScan).shouldBeTrue()
    }

    private fun QueryEvidence.assertUsesIndex(requiredIndexName: String) {
        planText.contains(requiredIndexName, ignoreCase = true).shouldBeTrue()
    }

    private fun QueryEvidence.assertUsesAnyIndex(vararg compatibleIndexNames: String) {
        compatibleIndexNames.any { planText.contains(it, ignoreCase = true) }.shouldBeTrue()
    }

    private val dialect: Dialect
        get() = when {
            "test-postgresql" in environment.activeProfiles -> Dialect.POSTGRESQL
            "test-mysql" in environment.activeProfiles -> Dialect.MYSQL
            else -> Dialect.H2
        }

    private data class FixtureScope(
        val tenantGroupId: Long,
        val clinicId: Long,
        val doctorId: Long,
        val treatmentTypeId: Long,
        val catalogProjectionId: Long,
    )

    private data class RawPlan(
        val text: String,
        val estimatedRows: Long?,
        val fullTableScan: Boolean,
    )

    private data class QueryEvidence(
        val expectedIndexNames: Set<String>,
        val planText: String,
        val estimatedRows: Long?,
        val maxEstimatedRows: Long,
        val actualRows: Int,
        val maxActualRows: Int,
        val elapsedMillis: Long,
        val fullTableScan: Boolean,
    )

    private enum class Dialect {
        H2,
        POSTGRESQL,
        MYSQL,
    }

    private companion object {
        private const val PARTITION_COUNT = 20
        private const val HOT_TABLE_ROW_COUNT = 10_000
        private const val PREVIEW_PAGE_SIZE = 5_000
        private const val INDEX_PROOF_ROW_COUNT = 250
        private const val INDEX_PROOF_NOISE_ROW_COUNT = 1_000
        private const val ROWS_PER_PARTITION = HOT_TABLE_ROW_COUNT / PARTITION_COUNT
        private const val TARGET_PARTITION = 7
        private const val MAX_PAGE_PARTITION = 8
        private const val PLAN_NOISE_PARTITION = 0
        private const val DUE_ROW_INTERVAL = 100
        private const val INSERT_BATCH_SIZE = 500
        private const val MYSQL_ESTIMATE_TOLERANCE_PERCENT = 50
        private const val MYSQL_MINIMUM_ESTIMATE_TOLERANCE = 25L
        private val NOW = Instant.parse("2026-07-28T03:00:00Z")
        private val BASE_DATE = LocalDate.of(2026, 8, 1)
        private val BASE_TIME = LocalTime.of(8, 0)
        private val PLAN_MAPPER = JsonMapper.builder().build()
        private val HOT_TABLES = listOf(
            "scheduling_appointments",
            "scheduling_planned_treatments",
            "scheduling_appointment_plans",
            "scheduling_policy_definitions",
            "scheduling_policy_activation_commands",
            "scheduling_outbox_events",
        )
    }
}
