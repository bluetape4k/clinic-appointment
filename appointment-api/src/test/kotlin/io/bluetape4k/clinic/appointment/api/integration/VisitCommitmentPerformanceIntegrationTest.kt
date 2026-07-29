package io.bluetape4k.clinic.appointment.api.integration

import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.api.test.API_INTEGRATION_RESOURCE
import io.bluetape4k.clinic.appointment.api.test.Containers
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.Driver

/**
 * PostgreSQL 100,000 allocation과 고 cardinality 보존 fixture에서 핵심 조회의 실제 실행 계획을
 * `ANALYZE, BUFFERS`로 검증합니다.
 *
 * allocation overlap, current proposal, Plan dirty-set, tenant·clinic 보존 조회가 승인된 복합 index를
 * 사용해야 하며 `Seq Scan on`이 나오면 실패합니다. 원 실행 계획은 build report에
 * 보존해 검토 문서의 source evidence로 사용합니다.
 */
@ResourceLock(value = API_INTEGRATION_RESOURCE, mode = ResourceAccessMode.READ_WRITE)
class VisitCommitmentPerformanceIntegrationTest {

    @Test
    fun `PostgreSQL uses bounded indexes for commitment and retention queries`() {
        val postgres = Containers.Postgres
        val dataSource =
            SimpleDriverDataSource(
                driver("org.postgresql.Driver"),
                postgres.jdbcUrl,
                postgres.username ?: "test",
                postgres.password ?: "",
            )
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration/postgresql")
            .cleanDisabled(false)
            .load()
            .apply {
                clean()
                migrate()
            }

        dataSource.connection.use { connection ->
            seed(connection)
            val overlap =
                explain(
                    connection,
                    """
                    SELECT id
                    FROM scheduling_resource_allocations
                    WHERE tenant_group_id = 1
                      AND clinic_id = 91001
                      AND resource_type = 'PRACTITIONER'
                      AND resource_id = 'resource-42'
                      AND allocation_status = 'ACTIVE'
                      AND starts_at < TIMESTAMP '2026-08-01 11:00:00'
                      AND ends_at > TIMESTAMP '2026-08-01 09:00:00'
                    """.trimIndent(),
                )
            val currentProposal =
                explain(
                    connection,
                    """
                    SELECT id, revision
                    FROM scheduling_appointment_proposals
                    WHERE commitment_id = 92011
                    ORDER BY revision DESC
                    LIMIT 1
                    """.trimIndent(),
                )
            val dirtySet =
                explain(
                    connection,
                    """
                    SELECT successor_treatment_key
                    FROM scheduling_plan_revision_dependencies
                    WHERE plan_revision_id = 92022
                      AND predecessor_treatment_key = 'treatment-42'
                    """.trimIndent(),
                )
            val idempotencyRetention =
                explain(
                    connection,
                    """
                    SELECT id
                    FROM scheduling_appointment_command_idempotencies
                    WHERE tenant_group_id = 1
                      AND clinic_id = 91001
                      AND created_at < TIMESTAMP '2026-07-01 00:00:00'
                    ORDER BY created_at, id
                    LIMIT 100
                    """.trimIndent(),
                )
            val inboxRetention =
                explain(
                    connection,
                    """
                    SELECT id
                    FROM scheduling_inbox_events
                    WHERE tenant_group_id = 1
                      AND clinic_id = 91001
                      AND status = 'PROCESSED'
                      AND received_at < TIMESTAMP '2026-07-01 00:00:00'
                    ORDER BY received_at, id
                    LIMIT 100
                    """.trimIndent(),
                )
            val outboxRetention =
                explain(
                    connection,
                    """
                    SELECT id
                    FROM scheduling_outbox_events
                    WHERE tenant_group_id = 1
                      AND clinic_id = 91001
                      AND status = 'PUBLISHED'
                      AND published_at < TIMESTAMP '2026-07-01 00:00:00'
                    ORDER BY published_at, id
                    LIMIT 100
                    """.trimIndent(),
                )

            overlap.usesAny("idx_resource_allocation_overlap").shouldBeTrue()
            currentProposal.usesAny("idx_proposal_current", "uq_proposal_commitment_revision").shouldBeTrue()
            dirtySet.usesAny("uq_plan_revision_dependency").shouldBeTrue()
            idempotencyRetention.usesAny("idx_appointment_idempotency_retention").shouldBeTrue()
            inboxRetention.usesAny("idx_inbox_retention").shouldBeTrue()
            outboxRetention.usesAny("idx_outbox_retention").shouldBeTrue()
            listOf(
                overlap,
                currentProposal,
                dirtySet,
                idempotencyRetention,
                inboxRetention,
                outboxRetention,
            ).none { "Seq Scan on" in it }.shouldBeTrue()

            val report = Path.of("build/reports/performance/visit-commitment-postgresql-explain.txt")
            Files.createDirectories(report.parent)
            Files.writeString(
                report,
                """
                # allocation overlap
                $overlap

                # current proposal
                $currentProposal

                # dirty set
                $dirtySet

                # idempotency retention
                $idempotencyRetention

                # inbox retention
                $inboxRetention

                # outbox retention
                $outboxRetention
                """.trimIndent(),
            )
        }
    }

    private fun seed(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "INSERT INTO scheduling_clinics(id, tenant_group_id, name) VALUES (91001, 1, 'Performance Clinic')",
            )
            statement.executeUpdate(
                """
                INSERT INTO scheduling_clinics(id, tenant_group_id, name)
                SELECT 91001 + n, 1, 'Retention Clinic ' || n
                FROM generate_series(1, 99) AS n
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                INSERT INTO scheduling_appointments(
                    id, clinic_id, model_version, patient_name, status
                ) VALUES (92010, 91001, 'COMMITMENT_V2', 'Performance Patient', 'REQUESTED')
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                INSERT INTO scheduling_appointment_commitments(
                    id, appointment_id, commitment_status, origin,
                    effective_policy_snapshot_id, version
                ) VALUES (92011, 92010, 'PROPOSED', 'CLINIC', 1, 1)
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                INSERT INTO scheduling_product_catalog_projections(
                    id, tenant_group_id, clinic_id, source_authority, product_id,
                    catalog_version, catalog_status, product_name, schema_version,
                    source_updated_at, payload_hash
                ) VALUES (
                    92020, 1, 91001, 'catalog', 'product', 1, 'ACTIVE',
                    'Performance Product', 1, CURRENT_TIMESTAMP, '${"a".repeat(64)}'
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                INSERT INTO scheduling_appointment_plans(
                    id, tenant_group_id, clinic_id, catalog_projection_id,
                    source_purchase_authority, source_purchase_id,
                    patient_reference_ciphertext, patient_reference_key_id,
                    patient_reference_fingerprint, catalog_source_authority,
                    product_id, catalog_version, catalog_payload_hash, product_name,
                    booking_preference_type, booking_preference_payload, status
                ) VALUES (
                    92021, 1, 91001, 92020, 'commerce', 'purchase-performance',
                    'ciphertext', 'key', '${"b".repeat(64)}', 'catalog',
                    'product', 1, '${"a".repeat(64)}', 'Performance Product',
                    'EXACT_DATE_TIME', '{}', 'ACTIVE'
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                INSERT INTO scheduling_appointment_plan_revisions(
                    id, plan_id, revision, product_version_id, snapshot_hash, active
                ) VALUES (92022, 92021, 1, 'product-v1', '${"c".repeat(64)}', TRUE)
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                INSERT INTO scheduling_appointment_proposals(
                    commitment_id, revision, proposed_start_at, proposed_end_at,
                    expires_at, representative_treatment_name, proposal_hash,
                    policy_snapshot_id, created_by_actor
                )
                SELECT 92011, n,
                       TIMESTAMP '2026-08-01 09:00:00' + n * INTERVAL '1 minute',
                       TIMESTAMP '2026-08-01 09:30:00' + n * INTERVAL '1 minute',
                       TIMESTAMP '2026-09-01 00:00:00',
                       'Proposal ' || n, md5(n::text) || md5(n::text), 1, 'performance'
                FROM generate_series(1, 10000) AS n
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                INSERT INTO scheduling_plan_revision_dependencies(
                    plan_revision_id, predecessor_treatment_key,
                    successor_treatment_key, dependency_type, minimum_interval_days
                )
                SELECT 92022, 'treatment-' || n, 'treatment-' || (n + 1), 'BLOCKING', 1
                FROM generate_series(1, 10000) AS n
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                INSERT INTO scheduling_resource_allocations(
                    tenant_group_id, clinic_id, proposal_id, resource_type, resource_id,
                    starts_at, ends_at, capacity_units, maximum_capacity,
                    allocation_mode, allocation_status
                )
                SELECT 1, 91001, 1 + (n % 10000), 'PRACTITIONER', 'resource-' || (n % 1000),
                       TIMESTAMP '2026-08-01 09:00:00' + (n % 1440) * INTERVAL '1 minute',
                       TIMESTAMP '2026-08-01 09:30:00' + (n % 1440) * INTERVAL '1 minute',
                       1, 1, 'EXCLUSIVE', 'ACTIVE'
                FROM generate_series(1, 100000) AS n
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                INSERT INTO scheduling_appointment_command_idempotencies(
                    tenant_group_id, clinic_id, actor_scope_hash, idempotency_key,
                    command_hash, created_at
                )
                SELECT 1, 91001 + (n % 100), md5('actor-' || n),
                       md5('key-' || n), md5('command-' || n) || md5('command-' || n),
                       TIMESTAMP '2026-06-01 00:00:00' + (n % 1000) * INTERVAL '1 second'
                FROM generate_series(1, 20000) AS n
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                INSERT INTO scheduling_inbox_events(
                    event_id, event_type, producer, source_authority,
                    source_aggregate_id, source_aggregate_version,
                    tenant_group_id, clinic_id, payload_hash, status,
                    occurred_at, received_at, processed_at
                )
                SELECT 'retention-inbox-' || n, 'TEST', 'performance', 'catalog',
                       'aggregate-' || n, n, 1, 91001 + (n % 100),
                       md5('payload-' || n) || md5('payload-' || n), 'PROCESSED',
                       TIMESTAMP '2026-06-01 00:00:00',
                       TIMESTAMP '2026-06-01 00:00:00' + (n % 1000) * INTERVAL '1 second',
                       TIMESTAMP '2026-06-01 00:01:00'
                FROM generate_series(1, 20000) AS n
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                INSERT INTO scheduling_outbox_events(
                    event_id, correlation_id, event_type, tenant_group_id, clinic_id,
                    aggregate_type, aggregate_id, schema_version, payload_json,
                    status, created_at, published_at
                )
                SELECT 'retention-outbox-' || n, 'correlation-' || n, 'TEST',
                       1, 91001 + (n % 100), 'TEST', n::text, 1, '{}',
                       'PUBLISHED', TIMESTAMP '2026-06-01 00:00:00',
                       TIMESTAMP '2026-06-01 00:00:00' + (n % 1000) * INTERVAL '1 second'
                FROM generate_series(1, 20000) AS n
                """.trimIndent(),
            )
            statement.execute("ANALYZE scheduling_resource_allocations")
            statement.execute("ANALYZE scheduling_appointment_proposals")
            statement.execute("ANALYZE scheduling_plan_revision_dependencies")
            statement.execute("ANALYZE scheduling_appointment_command_idempotencies")
            statement.execute("ANALYZE scheduling_inbox_events")
            statement.execute("ANALYZE scheduling_outbox_events")
        }
    }

    private fun explain(connection: Connection, sql: String): String =
        connection.prepareStatement("EXPLAIN (ANALYZE, BUFFERS) $sql").use { statement ->
            statement.executeQuery().use { rows ->
                buildString {
                    while (rows.next()) {
                        appendLine(rows.getString(1))
                    }
                }
            }
        }

    private fun String.usesAny(vararg indexNames: String): Boolean =
        indexNames.any(::contains)

    /** 명시한 JDBC driver를 reflection으로 생성한다. */
    private fun driver(className: String): Driver =
        Class.forName(className).getDeclaredConstructor().newInstance() as Driver
}
