package io.bluetape4k.clinic.appointment.api.integration

import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.api.test.API_INTEGRATION_RESOURCE
import io.bluetape4k.clinic.appointment.api.test.Containers
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.Driver
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

/** PostgreSQL 실제 migration schema에서 환자 취소 이력 keyset query의 인덱스 사용을 증명합니다. */
@ResourceLock(value = API_INTEGRATION_RESOURCE, mode = ResourceAccessMode.READ_WRITE)
@Execution(ExecutionMode.SAME_THREAD)
class PatientCancellationHistoryQueryPlanTest {

    @Test
    fun `PostgreSQL patient cancellation history page uses the patient scope index`() {
        val postgres = Containers.Postgres
        val dataSource = SimpleDriverDataSource(
            driver("org.postgresql.Driver"),
            postgres.jdbcUrl,
            postgres.username ?: "test",
            postgres.password ?: "",
        )
        migrate(dataSource)
        dataSource.connection.use { connection ->
            seed(connection)
            connection.createStatement().use { statement ->
                statement.execute("ANALYZE scheduling_appointment_cancellation_details")
                statement.execute("ANALYZE scheduling_appointment_proposals")
                statement.execute("ANALYZE scheduling_appointment_commitments")
                statement.execute("ANALYZE scheduling_appointments")
            }

            val plan = explain(
                connection = connection,
                sql = """
                    SELECT detail.id, detail.occurred_at, detail.appointment_id,
                           detail.commitment_id, detail.proposal_id
                      FROM scheduling_appointment_cancellation_details detail
                      JOIN scheduling_appointment_proposals proposal
                        ON proposal.id = detail.proposal_id
                      JOIN scheduling_appointment_commitments commitment
                        ON commitment.id = detail.commitment_id
                      JOIN scheduling_appointments appointment
                        ON appointment.id = detail.appointment_id
                     WHERE detail.tenant_group_id = ?
                       AND detail.patient_scope_fingerprint = ?
                       AND (
                           detail.occurred_at < ?
                           OR (detail.occurred_at = ? AND detail.id < ?)
                       )
                     ORDER BY detail.occurred_at DESC, detail.id DESC
                     LIMIT 51
                """.trimIndent(),
                parameters = listOf(
                    TENANT_ID,
                    TARGET_FINGERPRINT,
                    Timestamp.from(NOW),
                    Timestamp.from(NOW),
                    Long.MAX_VALUE,
                ),
            )
            val report = Path.of("build/reports/performance/patient-cancellation-history-postgresql-explain.txt")
            Files.createDirectories(report.parent)
            Files.writeString(
                report,
                """# Issue #305 PostgreSQL EXPLAIN
                |index=${plan.indexes.joinToString()}
                |historyTableFullTableScan=${plan.historyTableFullTableScan}
                |${plan.text}
                """.trimMargin(),
            )

            plan.uses(INDEX_NAME).shouldBeTrue()
            plan.historyTableFullTableScan.not().shouldBeTrue()
        }
    }

    private fun migrate(dataSource: DataSource) {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration/postgresql")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
            .cleanDisabled(false)
            .load()
            .apply {
                clean()
                migrate()
            }
    }

    private fun seed(connection: Connection) {
        connection.autoCommit = false
        try {
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    INSERT INTO scheduling_tenant_groups(id, tenant_code, display_name, active)
                    VALUES ($TENANT_ID, 'history-explain', 'History Explain', true)
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO scheduling_clinics(id, tenant_group_id, name)
                    VALUES ($CLINIC_ID, $TENANT_ID, 'History Explain Clinic')
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO scheduling_doctors(id, clinic_id, name)
                    VALUES ($DOCTOR_ID, $CLINIC_ID, 'History Explain Doctor')
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO scheduling_treatment_types(id, clinic_id, name, default_duration_minutes)
                    VALUES ($TREATMENT_ID, $CLINIC_ID, 'History Explain Treatment', 30)
                    """.trimIndent(),
                )
            }
            connection.prepareStatement(
                """
                INSERT INTO scheduling_appointments(
                    id, clinic_id, doctor_id, treatment_type_id, patient_name,
                    model_version, patient_reference_fingerprint, status
                ) VALUES (?, ?, ?, ?, ?, 'COMMITMENT_V2', ?, 'REQUESTED')
                """.trimIndent(),
            ).use { appointment ->
                connection.prepareStatement(
                    """
                    INSERT INTO scheduling_appointment_commitments(
                        id, appointment_id, commitment_status, origin,
                        effective_policy_snapshot_id, version
                    ) VALUES (?, ?, 'CANCELLED', 'SYSTEM', 1, 1)
                    """.trimIndent(),
                ).use { commitment ->
                    connection.prepareStatement(
                        """
                        INSERT INTO scheduling_appointment_proposals(
                            id, commitment_id, revision, proposed_start_at, proposed_end_at,
                            expires_at, representative_treatment_name, proposal_hash,
                            policy_snapshot_id, created_by_actor
                        ) VALUES (?, ?, 1, ?, ?, ?, 'History Explain Treatment', ?, 1, 'test')
                        """.trimIndent(),
                    ).use { proposal ->
                        connection.prepareStatement(
                            """
                            INSERT INTO scheduling_appointment_cancellation_details(
                                id, tenant_group_id, clinic_id, appointment_id, commitment_id,
                                proposal_id, reason_code, actor_role, actor_scope_hash,
                                detail_hash, occurred_at, from_commitment_status,
                                patient_scope_fingerprint
                            ) VALUES (?, ?, ?, ?, ?, ?, 'CUSTOMER_REQUEST', 'PATIENT', ?, ?, ?, 'CONFIRMED', ?)
                            """.trimIndent(),
                        ).use { detail ->
                            for (sequence in 1..FIXTURE_ROWS) {
                                val appointmentId = APPOINTMENT_ID_BASE + sequence
                                val commitmentId = COMMITMENT_ID_BASE + sequence
                                val proposalId = PROPOSAL_ID_BASE + sequence
                                val fingerprint = if (sequence % TARGET_INTERVAL == 0) {
                                    TARGET_FINGERPRINT
                                } else {
                                    "${sequence.toString(16).padStart(8, '0')}$NOISE_SUFFIX".take(64)
                                }
                                val occurredAt = Timestamp.from(NOW.minusSeconds(sequence.toLong()))

                                appointment.setLong(1, appointmentId)
                                appointment.setLong(2, CLINIC_ID)
                                appointment.setLong(3, DOCTOR_ID)
                                appointment.setLong(4, TREATMENT_ID)
                                appointment.setString(5, "History patient $sequence")
                                appointment.setString(6, fingerprint)
                                appointment.addBatch()

                                commitment.setLong(1, commitmentId)
                                commitment.setLong(2, appointmentId)
                                commitment.addBatch()

                                proposal.setLong(1, proposalId)
                                proposal.setLong(2, commitmentId)
                                proposal.setTimestamp(3, occurredAt)
                                proposal.setTimestamp(4, Timestamp.from(NOW.plusSeconds(3_600)))
                                proposal.setTimestamp(5, Timestamp.from(NOW.plusSeconds(7_200)))
                                proposal.setString(6, "${sequence.toString(16)}".padStart(64, '0'))
                                proposal.addBatch()

                                detail.setLong(1, DETAIL_ID_BASE + sequence)
                                detail.setLong(2, TENANT_ID)
                                detail.setLong(3, CLINIC_ID)
                                detail.setLong(4, appointmentId)
                                detail.setLong(5, commitmentId)
                                detail.setLong(6, proposalId)
                                detail.setString(7, "s".repeat(64))
                                detail.setString(8, "d".repeat(64))
                                detail.setTimestamp(9, occurredAt)
                                detail.setString(10, fingerprint)
                                detail.addBatch()
                                if (sequence % BATCH_SIZE == 0) {
                                    appointment.executeBatch()
                                    commitment.executeBatch()
                                    proposal.executeBatch()
                                    detail.executeBatch()
                                }
                            }
                            appointment.executeBatch()
                            commitment.executeBatch()
                            proposal.executeBatch()
                            detail.executeBatch()
                        }
                    }
                }
            }
            connection.commit()
        } catch (failure: Exception) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    private fun explain(connection: Connection, sql: String, parameters: List<Any>): QueryPlan =
        connection.prepareStatement("EXPLAIN (FORMAT JSON) $sql").use { statement ->
            parameters.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { rows ->
                check(rows.next()) { "PostgreSQL EXPLAIN returned no rows" }
                val text = rows.getString(1)
                val root = PLAN_MAPPER.readTree(text).get(0)?.get("Plan")
                    ?: error("PostgreSQL EXPLAIN JSON has no root Plan")
                val indexes = linkedSetOf<String>()
                var historyTableFullTableScan = false
                walk(root) { node ->
                    node.get("Index Name")?.stringValue()?.let(indexes::add)
                    historyTableFullTableScan = historyTableFullTableScan ||
                        (node.get("Node Type")?.stringValue() == "Seq Scan" &&
                            node.get("Relation Name")?.stringValue() == HISTORY_TABLE)
                }
                QueryPlan(text, indexes, historyTableFullTableScan)
            }
        }

    private fun driver(className: String): Driver =
        Class.forName(className).getDeclaredConstructor().newInstance() as Driver

    private fun walk(node: JsonNode, visitor: (JsonNode) -> Unit) {
        visitor(node)
        node.get("Plans")?.let { children ->
            for (index in 0 until children.size()) walk(children.get(index), visitor)
        }
    }

    private data class QueryPlan(
        val text: String,
        val indexes: Set<String>,
        val historyTableFullTableScan: Boolean,
    ) {
        fun uses(indexName: String): Boolean = indexes.any { it.equals(indexName, ignoreCase = true) }
    }

    private companion object {
        val PLAN_MAPPER: JsonMapper = JsonMapper.builder().build()
        val NOW: Instant = Instant.parse("2026-08-15T00:00:00Z")
        const val FIXTURE_ROWS = 4_000
        const val BATCH_SIZE = 500
        const val TARGET_INTERVAL = 200
        const val TENANT_ID = 1_000_001L
        const val CLINIC_ID = 1_000_002L
        const val DOCTOR_ID = 1_000_003L
        const val TREATMENT_ID = 1_000_004L
        const val APPOINTMENT_ID_BASE = 2_000_000L
        const val COMMITMENT_ID_BASE = 3_000_000L
        const val PROPOSAL_ID_BASE = 4_000_000L
        const val DETAIL_ID_BASE = 5_000_000L
        const val INDEX_NAME = "idx_cancellation_detail_patient_scope_time"
        const val HISTORY_TABLE = "scheduling_appointment_cancellation_details"
        val TARGET_FINGERPRINT = "a".repeat(64)
        val NOISE_SUFFIX = "b".repeat(56)
    }
}
