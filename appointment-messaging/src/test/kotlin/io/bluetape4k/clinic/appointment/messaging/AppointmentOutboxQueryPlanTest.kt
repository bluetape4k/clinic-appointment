package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxEvents
import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxStatus
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PostgreSQL Testcontainers에서 V22 ready-index와 fenced lease predicate를 검증하는 증거. */
class AppointmentOutboxQueryPlanTest {
    private var previousDefaultDatabase: Database? = null

    @BeforeEach
    fun setup() {
        previousDefaultDatabase = TransactionManager.defaultDatabase
        AppointmentOutboxPerformanceTestSupport.connectAndCreateSchema("query_plan")
        val summary = AppointmentOutboxPerformanceTestSupport.seedBacklog()
        assertEquals(AppointmentOutboxPerformanceTestSupport.SEED, summary.seed)
        assertEquals(AppointmentOutboxPerformanceTestSupport.ROW_COUNT, summary.totalRows)
        assertTrue(summary.appointmentRows > 0)
        assertTrue(summary.legacyRows > 0)
        assertTrue(summary.pendingAppointmentRows > 0)
        transaction {
            ensureDeterministicCandidates()
            TransactionManager.current().exec("ANALYZE scheduling_outbox_events")
        }
    }

    @AfterEach
    fun restoreDefaultDatabase() {
        TransactionManager.defaultDatabase = previousDefaultDatabase
    }

    @Test
    fun `ready claim uses the V22 appointment index and bounded page`() {
        transaction {
            val indexColumns = indexColumns(READY_INDEX)
            assertEquals(
                listOf(
                    "STATUS",
                    "AGGREGATE_TYPE",
                    "EVENT_TYPE",
                    "NEXT_ATTEMPT_AT",
                    "LEASE_UNTIL",
                    "CREATED_AT",
                    "ID",
                ),
                indexColumns,
            )

            val plan = explain(READY_CLAIM_SQL)
            assertTrue(
                plan.contains("Index Scan using $READY_INDEX", ignoreCase = true) ||
                    plan.contains("Bitmap Index Scan on $READY_INDEX", ignoreCase = true) ||
                    plan.contains("Index Scan using $LEASE_RECOVERY_INDEX", ignoreCase = true) ||
                    plan.contains("Bitmap Index Scan on $LEASE_RECOVERY_INDEX", ignoreCase = true),
                plan,
            )

            val count = TransactionManager.current().exec(READY_CLAIM_SQL) { rows ->
                var result = 0
                while (rows.next()) result++
                result
            } ?: 0
            assertTrue(count <= AppointmentOutboxPerformanceTestSupport.PAGE_SIZE)
        }

        val claim = JdbcAppointmentOutboxStore(maxClinicBatch = 4)
            .claim("query-plan-relay", 1, Duration.ofSeconds(30))
            .single()
        assertEquals(1, claim.attemptNumber)
        assertEquals(AppointmentTopic(DefaultAppointmentOutboxWriter.DEFAULT_TOPIC), claim.topic)
        assertTrue(claim.partitionKey.value.startsWith("tenant-1:CLINIC:clinic-31:APPOINTMENT:apt-"))
    }

    @Test
    fun `claim terminalizes invalid event and null metadata before a valid successor`() {
        val invalidIds = transaction { installInvalidReadyRows() }
        val store = JdbcAppointmentOutboxStore(maxClinicBatch = 4)

        assertTrue(store.claim("invalid-metadata-relay", 1, Duration.ofSeconds(30)).isEmpty())
        transaction {
            invalidIds.forEach { id ->
                assertEquals(SchedulingOutboxStatus.FAILED.name, readString(id, "status"))
                assertEquals(
                    AppointmentOutboxRelay.FAILURE_INVALID_METADATA,
                    readString(id, "last_failure_code"),
                )
            }
        }

        val successor = store.claim("invalid-metadata-relay", 1, Duration.ofSeconds(30)).single()
        assertEquals(1, successor.attemptNumber)
    }

    @Test
    fun `conditional lease update repeats due expiry and candidate attempt predicates`() {
        transaction {
            val readyId = selectId("""
                SELECT id FROM scheduling_outbox_events
                WHERE status = 'PENDING' AND aggregate_type = 'APPOINTMENT'
                  AND (next_attempt_at IS NULL OR next_attempt_at <= TIMESTAMP '2026-08-05 08:30:00')
                  AND (lease_until IS NULL OR lease_until <= TIMESTAMP '2026-08-05 08:30:00')
                ORDER BY created_at, id LIMIT 1
            """.trimIndent())
            val futureId = selectId("""
                SELECT id FROM scheduling_outbox_events
                WHERE status = 'PENDING' AND aggregate_type = 'APPOINTMENT'
                  AND next_attempt_at > TIMESTAMP '2026-08-05 08:30:00'
                ORDER BY id LIMIT 1
            """.trimIndent())

            val updated = conditionalLeaseUpdate(
                owner = "query-plan-relay",
                token = "query-plan-token",
                leaseUntil = "2026-08-05 08:31:00",
                firstId = readyId,
                secondId = futureId,
            )
            assertEquals(1, updated)
            assertEquals("query-plan-relay", readString(readyId, "lease_owner"))
            assertEquals("query-plan-token", readString(readyId, "lease_token"))
            assertEquals(1, readInt(readyId, "attempt_count"))
            assertEquals(null, readString(futureId, "lease_owner"))
            assertEquals(0, readInt(futureId, "attempt_count"))

            // 오래된 candidate version은 fenced lease를 덮어쓸 수 없다.
            val staleUpdate = conditionalLeaseUpdate(
                owner = "stale-relay",
                token = "stale-token",
                leaseUntil = "2026-08-05 08:31:00",
                firstId = readyId,
                secondId = readyId,
            )
            assertEquals(0, staleUpdate)
        }
    }

    @Test
    fun `fixed seed benchmark exercises production claim with percentile and contention evidence`() {
        val benchmark = AppointmentOutboxPerformanceTestSupport.benchmarkClaim(
            store = JdbcAppointmentOutboxStore(maxClinicBatch = 4),
        )
        assertEquals(AppointmentOutboxPerformanceTestSupport.SEED, benchmark.seed)
        assertEquals(AppointmentOutboxPerformanceTestSupport.ROW_COUNT, benchmark.totalRows)
        assertEquals(3, benchmark.warmupRounds)
        assertEquals(15, benchmark.measurementRounds)
        assertTrue(benchmark.samplesNanos.all { it > 0L })
        assertTrue(benchmark.p50Nanos > 0L)
        assertTrue(benchmark.p95Nanos >= benchmark.p50Nanos)
        assertTrue(benchmark.p99Nanos >= benchmark.p95Nanos)
        assertTrue(benchmark.maxClaimed <= AppointmentOutboxPerformanceTestSupport.PAGE_SIZE / 4)
        assertEquals(benchmark.contentionClaims, benchmark.contentionDistinctIds)
        assertTrue(benchmark.contentionSamplesNanos.all { it > 0L })
        AppointmentOutboxPerformanceTestSupport.writeBenchmarkReport(benchmark)
    }

    private fun installInvalidReadyRows(): List<Long> {
        val ids = TransactionManager.current().exec(
            """
            SELECT id FROM scheduling_outbox_events
            WHERE aggregate_type = 'APPOINTMENT'
            ORDER BY id LIMIT 3 OFFSET 2
            """.trimIndent(),
        ) { rows ->
            buildList {
                while (rows.next()) add(rows.getLong(1))
            }
        } ?: emptyList()
        assertEquals(3, ids.size)
        val invalidRows = listOf(
            Triple(ids[0], "AppointmentUnknown", DefaultAppointmentOutboxWriter.DEFAULT_TOPIC),
            Triple(ids[1], AppointmentEventType.CREATED.wireName, null),
            Triple(ids[2], AppointmentEventType.CREATED.wireName, DefaultAppointmentOutboxWriter.DEFAULT_TOPIC),
        )
        invalidRows.forEachIndexed { index, (id, eventType, topic) ->
            val partitionKey: String? = when (index) {
                1 -> "tenant-1:CLINIC:clinic-31:APPOINTMENT:apt-100004"
                2 -> null
                else -> "tenant-1:CLINIC:clinic-31:APPOINTMENT:apt-100002"
            }
            TransactionManager.current().exec(
                """
            UPDATE scheduling_outbox_events
            SET status = 'PENDING', attempt_count = 0,
                next_attempt_at = TIMESTAMP '2026-08-05 08:29:00', lease_until = NULL,
                event_type = ${sqlString(eventType)}, topic = ${sqlString(topic)},
                partition_key = ${sqlString(partitionKey)}
            WHERE id = $id
            """.trimIndent(),
            )
        }
        val invalidReadyRows = TransactionManager.current().exec(
            """
            SELECT COUNT(*)
            FROM scheduling_outbox_events
            WHERE id IN (${ids.joinToString()})
              AND status = 'PENDING'
              AND aggregate_type = 'APPOINTMENT'
              AND (next_attempt_at IS NULL OR next_attempt_at <= CURRENT_TIMESTAMP)
              AND (lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP)
              AND (
                  event_type NOT IN (
                      'AppointmentCreated',
                      'AppointmentStatusChanged',
                      'AppointmentCancelled',
                      'AppointmentRescheduled'
                  )
                  OR topic IS NULL
                  OR partition_key IS NULL
              )
            """.trimIndent(),
        ) { rows ->
            check(rows.next()) { "invalid fixture query returned no row" }
            rows.getInt(1)
        } ?: 0
        assertEquals(ids.size, invalidReadyRows, "invalid fixture must satisfy the production invalid-row predicate")
        return ids
    }

    private fun ensureDeterministicCandidates() {
        val ids = TransactionManager.current().exec(
            "SELECT id FROM scheduling_outbox_events WHERE aggregate_type = 'APPOINTMENT' ORDER BY id LIMIT 2",
        ) { rows ->
            buildList {
                while (rows.next()) add(rows.getLong(1))
            }
        } ?: emptyList()
        assertTrue(ids.size == 2, "fixture must contain two appointment rows")
        TransactionManager.current().exec(
            """
            UPDATE scheduling_outbox_events
            SET status = 'PENDING', attempt_count = 0,
                next_attempt_at = TIMESTAMP '2026-08-05 08:29:00',
                lease_until = TIMESTAMP '2026-08-05 08:29:00'
            WHERE id = ${ids[0]}
            """.trimIndent(),
        )
        TransactionManager.current().exec(
            """
            UPDATE scheduling_outbox_events
            SET status = 'PENDING', attempt_count = 0,
                next_attempt_at = TIMESTAMP '2026-08-05 08:31:00', lease_until = NULL
            WHERE id = ${ids[1]}
            """.trimIndent(),
        )
    }

    private fun explain(sql: String): String =
        TransactionManager.current().exec(
            stmt = "EXPLAIN $sql",
            explicitStatementType = StatementType.SELECT,
        ) { rows ->
            buildString {
                while (rows.next()) appendLine(rows.getString(1))
            }
        } ?: error("EXPLAIN returned no plan")

    private fun indexColumns(indexName: String): List<String> =
        TransactionManager.current().exec(
            """
            SELECT attribute.attname
            FROM pg_class table_ref
            JOIN pg_index index_ref ON index_ref.indrelid = table_ref.oid
            JOIN pg_class index_table ON index_table.oid = index_ref.indexrelid
            JOIN LATERAL unnest(index_ref.indkey) WITH ORDINALITY keys(attnum, position) ON TRUE
            JOIN pg_attribute attribute
              ON attribute.attrelid = table_ref.oid AND attribute.attnum = keys.attnum
            WHERE table_ref.relname = '${SchedulingOutboxEvents.tableName}'
              AND index_table.relname = '${indexName.lowercase()}'
            ORDER BY keys.position
            """.trimIndent(),
        ) { rows ->
            buildList {
                while (rows.next()) {
                    add(rows.getString("attname").uppercase())
                }
            }
        } ?: emptyList()

    private fun selectId(sql: String): Long =
        TransactionManager.current().exec(sql) { rows ->
            assertTrue(rows.next(), "fixture did not contain a matching appointment row")
            rows.getLong(1)
        } ?: error("query returned no result set")

    private fun readString(id: Long, column: String): String? =
        TransactionManager.current().exec(
            "SELECT $column FROM scheduling_outbox_events WHERE id = $id",
        ) { rows ->
            assertTrue(rows.next())
            rows.getString(1)
        }

    private fun readInt(id: Long, column: String): Int =
        TransactionManager.current().exec(
            "SELECT $column FROM scheduling_outbox_events WHERE id = $id",
        ) { rows ->
            assertTrue(rows.next())
            rows.getInt(1)
        } ?: error("query returned no result set")

    private fun conditionalLeaseUpdate(
        owner: String,
        token: String,
        leaseUntil: String,
        firstId: Long,
        secondId: Long,
    ): Int {
        TransactionManager.current().exec(
            """
            UPDATE scheduling_outbox_events
            SET lease_owner = ${sqlString(owner)},
                lease_token = ${sqlString(token)},
                lease_until = TIMESTAMP '$leaseUntil',
                attempt_count = attempt_count + 1
            WHERE id IN ($firstId, $secondId)
              AND status = 'PENDING'
              AND attempt_count = 0
              AND (next_attempt_at IS NULL OR next_attempt_at <= TIMESTAMP '2026-08-05 08:30:00')
              AND (lease_until IS NULL OR lease_until <= TIMESTAMP '2026-08-05 08:30:00')
            """.trimIndent(),
        )
        return TransactionManager.current().exec(
            """
            SELECT COUNT(*)
            FROM scheduling_outbox_events
            WHERE id IN ($firstId, $secondId)
              AND lease_owner = ${sqlString(owner)}
              AND lease_token = ${sqlString(token)}
            """.trimIndent(),
        ) { rows ->
            check(rows.next()) { "lease update count query returned no row" }
            rows.getInt(1)
        } ?: 0
    }

    private fun sqlString(value: String?): String =
        value?.let { "'${it.replace("'", "''")}'" } ?: "NULL"

    private companion object {
        const val READY_INDEX = "IDX_OUTBOX_APPOINTMENT_READY"
        const val LEASE_RECOVERY_INDEX = "IDX_OUTBOX_APPOINTMENT_LEASE_RECOVERY"
        const val READY_CLAIM_SQL = """
            SELECT candidate.id
            FROM scheduling_outbox_events candidate
            WHERE candidate.status = 'PENDING'
              AND candidate.aggregate_type = 'APPOINTMENT'
              AND candidate.event_type IN (
                  'AppointmentCreated',
                  'AppointmentStatusChanged',
                  'AppointmentCancelled',
                  'AppointmentRescheduled'
              )
              AND candidate.topic IS NOT NULL
              AND candidate.partition_key IS NOT NULL
              AND (candidate.next_attempt_at IS NULL OR candidate.next_attempt_at <= TIMESTAMP '2026-08-05 08:30:00')
              AND (candidate.lease_until IS NULL OR candidate.lease_until <= TIMESTAMP '2026-08-05 08:30:00')
              AND NOT EXISTS (
                  SELECT 1
                  FROM scheduling_outbox_events predecessor
                  WHERE predecessor.aggregate_type = candidate.aggregate_type
                    AND predecessor.aggregate_id = candidate.aggregate_id
                    AND predecessor.aggregate_type = 'APPOINTMENT'
                    AND predecessor.status = 'PENDING'
                    AND (
                        predecessor.created_at < candidate.created_at
                        OR (
                            predecessor.created_at = candidate.created_at
                            AND predecessor.id < candidate.id
                        )
                    )
              )
            ORDER BY candidate.created_at, candidate.id
            LIMIT 128
        """
    }
}
