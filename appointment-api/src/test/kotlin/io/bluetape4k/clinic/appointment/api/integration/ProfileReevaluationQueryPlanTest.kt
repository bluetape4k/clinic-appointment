package io.bluetape4k.clinic.appointment.api.integration

import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.api.test.API_INTEGRATION_RESOURCE
import io.bluetape4k.clinic.appointment.api.test.Containers
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
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

/**
 * 프로필 재평가 hot path가 PostgreSQL과 MySQL에서 bounded index 조회를 유지하는지 검증합니다.
 */
@ResourceLock(value = API_INTEGRATION_RESOURCE, mode = ResourceAccessMode.READ_WRITE)
class ProfileReevaluationQueryPlanTest {

    @Test
    fun `PostgreSQL은 프로필 재평가 조회에 bounded index를 사용한다`() {
        val postgres = Containers.Postgres
        verifyQueryPlans(
            dialect = Dialect.POSTGRESQL,
            dataSource =
                SimpleDriverDataSource(
                    driver("org.postgresql.Driver"),
                    postgres.jdbcUrl,
                    postgres.username ?: "test",
                    postgres.password ?: "",
                ),
            migrationLocation = "classpath:db/migration/postgresql",
        )
    }

    @Test
    fun `MySQL은 프로필 재평가 조회에 bounded index를 사용한다`() {
        val mysql = Containers.MySql8
        verifyQueryPlans(
            dialect = Dialect.MYSQL,
            dataSource =
                SimpleDriverDataSource(
                    driver("com.mysql.cj.jdbc.Driver"),
                    mysql.jdbcUrl,
                    mysql.username ?: "test",
                    mysql.password ?: "",
                ),
            migrationLocation = "classpath:db/migration/mysql",
        )
    }

    private fun verifyQueryPlans(
        dialect: Dialect,
        dataSource: DataSource,
        migrationLocation: String,
    ) {
        Flyway.configure()
            .dataSource(dataSource)
            .locations(migrationLocation)
            .cleanDisabled(false)
            .load()
            .apply {
                clean()
                migrate()
            }

        dataSource.connection.use { connection ->
            seed(connection)
            analyze(connection, dialect)
            if (dialect == Dialect.POSTGRESQL) {
                connection.createStatement().use { it.execute("SET enable_seqscan = off") }
            }

            val heldExistence =
                explain(
                    connection = connection,
                    dialect = dialect,
                    sql =
                        """
                        SELECT commitment.appointment_id
                        FROM scheduling_appointment_commitments commitment
                        JOIN scheduling_appointments appointment
                          ON appointment.id = commitment.appointment_id
                        JOIN scheduling_clinics clinic
                          ON clinic.id = appointment.clinic_id
                        WHERE commitment.commitment_status = 'HELD'
                          AND clinic.tenant_group_id = ?
                          AND appointment.clinic_id = ?
                          AND appointment.patient_reference_fingerprint = ?
                        LIMIT 1
                        """.trimIndent(),
                    parameters = listOf(1L, TARGET_CLINIC_ID, TARGET_FINGERPRINT),
                )
            val appointmentPage =
                explain(
                    connection = connection,
                    dialect = dialect,
                    sql =
                        """
                        SELECT appointment.id, commitment.id
                        FROM scheduling_appointments appointment
                        JOIN scheduling_clinics clinic
                          ON clinic.id = appointment.clinic_id
                        JOIN scheduling_appointment_commitments commitment
                          ON commitment.appointment_id = appointment.id
                        WHERE clinic.tenant_group_id = ?
                          AND appointment.clinic_id = ?
                          AND appointment.patient_reference_fingerprint = ?
                          AND commitment.commitment_status IN ('PROPOSED', 'HELD')
                          AND appointment.id > ?
                        ORDER BY appointment.id
                        LIMIT 100
                        """.trimIndent(),
                    parameters = listOf(1L, TARGET_CLINIC_ID, TARGET_FINGERPRINT, 0L),
                )
            val dueClaim =
                explain(
                    connection = connection,
                    dialect = dialect,
                    sql =
                        """
                        SELECT id
                        FROM scheduling_profile_reevaluation_jobs
                        WHERE status = 'PENDING'
                          AND next_attempt_at <= ?
                        ORDER BY next_attempt_at, clinic_id, id
                        LIMIT 100
                        """.trimIndent(),
                    parameters = listOf(Timestamp.from(NOW)),
                )
            val nextClinic =
                explain(
                    connection = connection,
                    dialect = dialect,
                    sql =
                        """
                        SELECT tenant_group_id, clinic_id
                        FROM scheduling_profile_reevaluation_jobs
                        WHERE (
                            (status IN ('PENDING', 'RETRY_WAIT') AND next_attempt_at <= ?)
                            OR (status = 'RUNNING' AND lease_expires_at <= ?)
                        )
                          AND (
                            tenant_group_id > ?
                            OR (tenant_group_id = ? AND clinic_id > ?)
                          )
                        ORDER BY tenant_group_id, clinic_id
                        LIMIT 1
                        """.trimIndent(),
                    parameters =
                        listOf(
                            Timestamp.from(NOW),
                            Timestamp.from(NOW),
                            1L,
                            1L,
                            TARGET_CLINIC_ID - 1L,
                        ),
                )
            val pendingClinicCandidates =
                explain(
                    connection = connection,
                    dialect = dialect,
                    sql =
                        """
                        SELECT id, due_at
                        FROM scheduling_profile_reevaluation_jobs
                        WHERE status = 'PENDING'
                          AND next_attempt_at <= ?
                          AND tenant_group_id = ?
                          AND clinic_id = ?
                        ORDER BY next_attempt_at, due_at, id
                        LIMIT 2
                        """.trimIndent(),
                    parameters =
                        listOf(
                            Timestamp.from(NOW),
                            1L,
                            TARGET_CLINIC_ID,
                        ),
                )
            val retryWaitClinicCandidates =
                explain(
                    connection = connection,
                    dialect = dialect,
                    sql =
                        """
                        SELECT id, due_at
                        FROM scheduling_profile_reevaluation_jobs
                        WHERE status = 'RETRY_WAIT'
                          AND next_attempt_at <= ?
                          AND tenant_group_id = ?
                          AND clinic_id = ?
                        ORDER BY next_attempt_at, due_at, id
                        LIMIT 2
                        """.trimIndent(),
                    parameters =
                        listOf(
                            Timestamp.from(NOW),
                            1L,
                            TARGET_CLINIC_ID,
                        ),
                )
            val expiredLeaseClinicCandidates =
                explain(
                    connection = connection,
                    dialect = dialect,
                    sql =
                        """
                        SELECT id, due_at
                        FROM scheduling_profile_reevaluation_jobs
                        WHERE status = 'RUNNING'
                          AND lease_expires_at <= ?
                          AND tenant_group_id = ?
                          AND clinic_id = ?
                        ORDER BY lease_expires_at, due_at, id
                        LIMIT 2
                        """.trimIndent(),
                    parameters =
                        listOf(
                            Timestamp.from(NOW),
                            1L,
                            TARGET_CLINIC_ID,
                        ),
                )
            val leaseRecovery =
                explain(
                    connection = connection,
                    dialect = dialect,
                    sql =
                        """
                        SELECT id
                        FROM scheduling_profile_reevaluation_jobs
                        WHERE status = 'RUNNING'
                          AND lease_expires_at <= ?
                        ORDER BY lease_expires_at, clinic_id, id
                        LIMIT 100
                        """.trimIndent(),
                    parameters = listOf(Timestamp.from(NOW)),
                )

            val report =
                Path.of(
                    "build/reports/performance/profile-reevaluation-${dialect.name.lowercase()}-explain.txt",
                )
            Files.createDirectories(report.parent)
            Files.writeString(
                report,
                """
                # HELD existence
                ${heldExistence.text}

                # appointment keyset page
                ${appointmentPage.text}

                # due claim
                ${dueClaim.text}

                # next clinic keyset
                ${nextClinic.text}

                # pending clinic candidates
                ${pendingClinicCandidates.text}

                # retry wait clinic candidates
                ${retryWaitClinicCandidates.text}

                # expired lease clinic candidates
                ${expiredLeaseClinicCandidates.text}

                    # lease recovery
                    ${leaseRecovery.text}
                    """.trimIndent(),
            )

            heldExistence.uses("idx_commitment_profile_reevaluation").shouldBeTrue()
            appointmentPage.uses("idx_appointment_profile_reevaluation").shouldBeTrue()
            appointmentPage.usesAny("uq_commitment_appointment", "idx_commitment_profile_reevaluation").shouldBeTrue()
            dueClaim.uses("idx_profile_reevaluation_due").shouldBeTrue()
            nextClinic
                .usesAny("idx_profile_reevaluation_clinic_ready", "idx_profile_reevaluation_clinic_lease")
                .shouldBeTrue()
            pendingClinicCandidates.uses("idx_profile_reevaluation_clinic_ready").shouldBeTrue()
            retryWaitClinicCandidates.uses("idx_profile_reevaluation_clinic_ready").shouldBeTrue()
            expiredLeaseClinicCandidates.uses("idx_profile_reevaluation_clinic_lease").shouldBeTrue()
            leaseRecovery.uses("idx_profile_reevaluation_lease").shouldBeTrue()
            listOf(
                heldExistence,
                appointmentPage,
                dueClaim,
                nextClinic,
                pendingClinicCandidates,
                retryWaitClinicCandidates,
                expiredLeaseClinicCandidates,
                leaseRecovery,
            )
                .none(QueryPlan::fullTableScan)
                .shouldBeTrue()
        }
    }

    private fun seed(connection: Connection) {
        connection.autoCommit = false
        try {
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "INSERT INTO scheduling_clinics(id, tenant_group_id, name) " +
                        "VALUES ($TARGET_CLINIC_ID, 1, 'Profile Query Clinic')",
                )
                statement.executeUpdate(
                    """
                    INSERT INTO scheduling_profile_reevaluation_heads(
                        id, tenant_group_id, clinic_id, patient_reference_fingerprint,
                        latest_revision, latest_event_id, assessment_ref, assessment_hash, occurred_at
                    ) VALUES (
                        $HEAD_ID, 1, $TARGET_CLINIC_ID, '$TARGET_FINGERPRINT',
                        $FIXTURE_ROWS, 'event-latest', 'assessment/latest',
                        '${"c".repeat(64)}', CURRENT_TIMESTAMP
                    )
                    """.trimIndent(),
                )
            }
            seedAppointments(connection)
            seedJobs(connection)
            connection.commit()
        } catch (error: Exception) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = true
        }
    }

    private fun seedAppointments(connection: Connection) {
        connection.prepareStatement(
            """
            INSERT INTO scheduling_appointments(
                id, clinic_id, model_version, patient_name,
                patient_reference_fingerprint, status
            ) VALUES (?, ?, 'COMMITMENT_V2', ?, ?, 'REQUESTED')
            """.trimIndent(),
        ).use { appointment ->
            connection.prepareStatement(
                """
                INSERT INTO scheduling_appointment_commitments(
                    id, appointment_id, commitment_status, origin,
                    confirmed_proposal_id, effective_policy_snapshot_id, version
                ) VALUES (?, ?, ?, 'SYSTEM', ?, 1, 1)
                """.trimIndent(),
            ).use { commitment ->
                for (sequence in 1..FIXTURE_ROWS) {
                    val appointmentId = APPOINTMENT_ID_BASE + sequence
                    val targetProfile = sequence % ELIGIBLE_INTERVAL == 0
                    val status =
                        when {
                            sequence % HELD_INTERVAL == 0 -> "HELD"
                            sequence % 3 == 0 -> "CONFIRMED"
                            else -> "PROPOSED"
                        }
                    appointment.setLong(1, appointmentId)
                    appointment.setLong(2, TARGET_CLINIC_ID)
                    appointment.setString(3, "Patient $sequence")
                    appointment.setString(4, if (targetProfile) TARGET_FINGERPRINT else NOISE_FINGERPRINT)
                    appointment.addBatch()

                    commitment.setLong(1, COMMITMENT_ID_BASE + sequence)
                    commitment.setLong(2, appointmentId)
                    commitment.setString(3, status)
                    if (status == "CONFIRMED") {
                        commitment.setLong(4, PROPOSAL_ID_BASE + sequence)
                    } else {
                        commitment.setObject(4, null)
                    }
                    commitment.addBatch()
                    if (sequence % BATCH_SIZE == 0) {
                        appointment.executeBatch()
                        commitment.executeBatch()
                    }
                }
                appointment.executeBatch()
                commitment.executeBatch()
            }
        }
    }

    private fun seedJobs(connection: Connection) {
        connection.prepareStatement(
            """
            INSERT INTO scheduling_profile_reevaluation_jobs(
                id, head_id, tenant_group_id, clinic_id, patient_reference_fingerprint,
                target_revision, event_id, assessment_ref, assessment_hash, status,
                occurred_at, due_at, target_duration_seconds, held_target_seconds,
                proposed_target_seconds, target_policy_ref, target_policy_generation,
                next_attempt_at, lease_owner, lease_expires_at, priority_class
            ) VALUES (
                ?, ?, 1, ?, ?, ?, ?, 'assessment/ref', ?, ?,
                ?, ?, 300, 300, 3600, 'policy/default', 1,
                ?, ?, ?, 'UNCLASSIFIED'
            )
            """.trimIndent(),
        ).use { statement ->
            for (sequence in 1..FIXTURE_ROWS) {
                val status =
                    when (sequence % 3) {
                        0 -> "RUNNING"
                        1 -> "PENDING"
                        else -> "RETRY_WAIT"
                    }
                val running = status == "RUNNING"
                val clinicId = TARGET_CLINIC_ID + (sequence % CLINIC_BUCKETS)
                statement.setLong(1, JOB_ID_BASE + sequence)
                statement.setLong(2, HEAD_ID)
                statement.setLong(3, clinicId)
                statement.setString(4, TARGET_FINGERPRINT)
                statement.setLong(5, sequence.toLong())
                statement.setString(6, "event-$sequence")
                statement.setString(7, "d".repeat(64))
                statement.setString(8, status)
                statement.setTimestamp(9, Timestamp.from(NOW.minusSeconds(sequence.toLong())))
                statement.setTimestamp(10, Timestamp.from(NOW.minusSeconds(sequence.toLong())))
                statement.setTimestamp(11, Timestamp.from(NOW.minusSeconds(sequence.toLong())))
                statement.setString(12, if (running) "worker-$sequence" else null)
                statement.setTimestamp(
                    13,
                    if (running) Timestamp.from(NOW.minusSeconds(sequence.toLong())) else null,
                )
                statement.addBatch()
                if (sequence % BATCH_SIZE == 0) statement.executeBatch()
            }
            statement.executeBatch()
        }
    }

    private fun analyze(
        connection: Connection,
        dialect: Dialect,
    ) {
        val statements =
            when (dialect) {
                Dialect.POSTGRESQL ->
                    listOf(
                        "ANALYZE scheduling_clinics",
                        "ANALYZE scheduling_appointments",
                        "ANALYZE scheduling_appointment_commitments",
                        "ANALYZE scheduling_profile_reevaluation_jobs",
                    )

                Dialect.MYSQL ->
                    listOf(
                        "ANALYZE TABLE scheduling_clinics",
                        "ANALYZE TABLE scheduling_appointments",
                        "ANALYZE TABLE scheduling_appointment_commitments",
                        "ANALYZE TABLE scheduling_profile_reevaluation_jobs",
                    )
            }
        connection.createStatement().use { statement ->
            statements.forEach(statement::execute)
        }
    }

    private fun explain(
        connection: Connection,
        dialect: Dialect,
        sql: String,
        parameters: List<Any>,
    ): QueryPlan =
        when (dialect) {
            Dialect.POSTGRESQL ->
                connection.prepareStatement("EXPLAIN (FORMAT JSON) $sql").use { statement ->
                    statement.bind(parameters)
                    statement.executeQuery().use { rows ->
                        check(rows.next()) { "PostgreSQL EXPLAIN returned no rows" }
                        val text = rows.getString(1)
                        val indexes = linkedSetOf<String>()
                        var fullTableScan = false
                        val root = PLAN_MAPPER.readTree(text).get(0)?.get("Plan")
                            ?: error("PostgreSQL EXPLAIN JSON has no root Plan")
                        walk(root) { node ->
                            node.get("Index Name")?.stringValue()?.let(indexes::add)
                            fullTableScan =
                                fullTableScan || node.get("Node Type")?.stringValue() == "Seq Scan"
                        }
                        QueryPlan(text, indexes, fullTableScan)
                    }
                }

            Dialect.MYSQL ->
                connection.prepareStatement("EXPLAIN $sql").use { statement ->
                    statement.bind(parameters)
                    statement.executeQuery().use { rows ->
                        val nodes = ArrayList<String>()
                        val indexes = linkedSetOf<String>()
                        var fullTableScan = false
                        while (rows.next()) {
                            val type = rows.getString("type")
                            rows.getString("key")?.let(indexes::add)
                            fullTableScan = fullTableScan || type.equals("ALL", ignoreCase = true)
                            nodes +=
                                "table=${rows.getString("table")}; key=${rows.getString("key")}; " +
                                    "rows=${rows.getLong("rows")}; type=$type; extra=${rows.getString("Extra")}"
                        }
                        check(nodes.isNotEmpty()) { "MySQL EXPLAIN returned no rows" }
                        QueryPlan(nodes.joinToString(" | "), indexes, fullTableScan)
                    }
                }
        }

    private fun PreparedStatement.bind(parameters: List<Any>) {
        parameters.forEachIndexed { index, value -> setObject(index + 1, value) }
    }

    private fun walk(
        node: JsonNode,
        visitor: (JsonNode) -> Unit,
    ) {
        visitor(node)
        val children = node.get("Plans") ?: return
        for (index in 0 until children.size()) {
            walk(children.get(index), visitor)
        }
    }

    private fun driver(className: String): Driver =
        Class.forName(className).getDeclaredConstructor().newInstance() as Driver

    private data class QueryPlan(
        val text: String,
        val indexes: Set<String>,
        val fullTableScan: Boolean,
    ) {
        fun uses(indexName: String): Boolean =
            indexes.any { it.equals(indexName, ignoreCase = true) }

        fun usesAny(vararg indexNames: String): Boolean =
            indexNames.any(::uses)
    }

    private enum class Dialect {
        POSTGRESQL,
        MYSQL,
    }

    private companion object {
        val PLAN_MAPPER: JsonMapper = JsonMapper.builder().build()
        val NOW: Instant = Instant.parse("2026-07-30T00:00:00Z")
        const val FIXTURE_ROWS = 4_000
        const val BATCH_SIZE = 500
        const val CLINIC_BUCKETS = 32
        const val ELIGIBLE_INTERVAL = 100
        const val HELD_INTERVAL = 1_000
        const val TARGET_CLINIC_ID = 91_001L
        const val HEAD_ID = 92_001L
        const val APPOINTMENT_ID_BASE = 100_000L
        const val COMMITMENT_ID_BASE = 200_000L
        const val PROPOSAL_ID_BASE = 300_000L
        const val JOB_ID_BASE = 400_000L
        val TARGET_FINGERPRINT = "a".repeat(64)
        val NOISE_FINGERPRINT = "b".repeat(64)
    }
}
