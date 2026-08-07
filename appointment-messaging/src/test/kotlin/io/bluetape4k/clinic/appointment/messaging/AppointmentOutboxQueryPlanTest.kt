package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxEvents
import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxStatus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** V22 ready-index 선택과 fenced lease predicate에 대한 H2 증거. */
class AppointmentOutboxQueryPlanTest {
    private lateinit var jdbcUrl: String

    @BeforeEach
    fun setup() {
        jdbcUrl = AppointmentOutboxPerformanceTestSupport.connectAndCreateSchema("query_plan")
        connection().use { connection ->
            val summary = AppointmentOutboxPerformanceTestSupport.seedBacklog(connection)
            assertEquals(AppointmentOutboxPerformanceTestSupport.SEED, summary.seed)
            assertEquals(AppointmentOutboxPerformanceTestSupport.ROW_COUNT, summary.totalRows)
            assertTrue(summary.appointmentRows > 0)
            assertTrue(summary.legacyRows > 0)
            assertTrue(summary.pendingAppointmentRows > 0)
            ensureDeterministicCandidates(connection)
            connection.createStatement().use { statement -> statement.execute("ANALYZE") }
        }
    }

    @Test
    fun `ready claim uses the V22 appointment index and bounded page`() {
        connection().use { connection ->
            val indexColumns = indexColumns(connection, READY_INDEX)
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

            val plan = explain(connection, READY_CLAIM_SQL)
            assertTrue(
                plan.contains(READY_INDEX, ignoreCase = true) ||
                    plan.contains(LEASE_RECOVERY_INDEX, ignoreCase = true),
                plan,
            )
            assertFalse(plan.contains("tableScan", ignoreCase = true), plan)

            connection.prepareStatement(READY_CLAIM_SQL).use { statement ->
                statement.executeQuery().use { rows ->
                    var count = 0
                    while (rows.next()) count++
                    assertTrue(count <= AppointmentOutboxPerformanceTestSupport.PAGE_SIZE)
                }
            }

            val claim = JdbcAppointmentOutboxStore(maxClinicBatch = 4)
                .claim("query-plan-relay", 1, Duration.ofSeconds(30))
                .single()
            assertEquals(1, claim.attemptNumber)
            assertEquals(AppointmentTopic(DefaultAppointmentOutboxWriter.DEFAULT_TOPIC), claim.topic)
            assertTrue(claim.partitionKey.value.startsWith("tenant-1:CLINIC:clinic-31:APPOINTMENT:apt-"))
        }
    }

    @Test
    fun `claim terminalizes invalid event and null metadata before a valid successor`() {
        val invalidIds = connection().use { connection -> installInvalidReadyRows(connection) }
        val store = JdbcAppointmentOutboxStore(maxClinicBatch = 4)

        assertTrue(store.claim("invalid-metadata-relay", 1, Duration.ofSeconds(30)).isEmpty())
        connection().use { connection ->
            invalidIds.forEach { id ->
                assertEquals(SchedulingOutboxStatus.FAILED.name, readString(connection, id, "status"))
                assertEquals(
                    AppointmentOutboxRelay.FAILURE_INVALID_METADATA,
                    readString(connection, id, "last_failure_code"),
                )
            }
        }

        val successor = store.claim("invalid-metadata-relay", 1, Duration.ofSeconds(30)).single()
        assertEquals(1, successor.attemptNumber)
    }

    @Test
    fun `conditional lease update repeats due expiry and candidate attempt predicates`() {
        connection().use { connection ->
            val readyId = selectId(connection, """
                SELECT id FROM scheduling_outbox_events
                WHERE status = 'PENDING' AND aggregate_type = 'APPOINTMENT'
                  AND (next_attempt_at IS NULL OR next_attempt_at <= TIMESTAMP '2026-08-05 08:30:00')
                  AND (lease_until IS NULL OR lease_until <= TIMESTAMP '2026-08-05 08:30:00')
                ORDER BY created_at, id LIMIT 1
            """.trimIndent())
            val futureId = selectId(connection, """
                SELECT id FROM scheduling_outbox_events
                WHERE status = 'PENDING' AND aggregate_type = 'APPOINTMENT'
                  AND next_attempt_at > TIMESTAMP '2026-08-05 08:30:00'
                ORDER BY id LIMIT 1
            """.trimIndent())

            val updated = connection.prepareStatement(CONDITIONAL_LEASE_UPDATE_SQL).use { statement ->
                statement.setString(1, "query-plan-relay")
                statement.setString(2, "query-plan-token")
                statement.setTimestamp(3, Timestamp.valueOf("2026-08-05 08:31:00"))
                statement.setLong(4, readyId)
                statement.setLong(5, futureId)
                statement.executeUpdate()
            }
            assertEquals(1, updated)
            assertEquals("query-plan-relay", readString(connection, readyId, "lease_owner"))
            assertEquals("query-plan-token", readString(connection, readyId, "lease_token"))
            assertEquals(1, readInt(connection, readyId, "attempt_count"))
            assertEquals(null, readString(connection, futureId, "lease_owner"))
            assertEquals(0, readInt(connection, futureId, "attempt_count"))

// 오래된 candidate version은 fenced lease를 덮어쓸 수 없다.
            val staleUpdate = connection.prepareStatement(CONDITIONAL_LEASE_UPDATE_SQL).use { statement ->
                statement.setString(1, "stale-relay")
                statement.setString(2, "stale-token")
                statement.setTimestamp(3, Timestamp.valueOf("2026-08-05 08:31:00"))
                statement.setLong(4, readyId)
                statement.setLong(5, readyId)
                statement.executeUpdate()
            }
            assertEquals(0, staleUpdate)
        }
    }

    @Test
    fun `fixed seed benchmark exercises production claim with percentile and contention evidence`() {
        connection().use { connection ->
            val benchmark = AppointmentOutboxPerformanceTestSupport.benchmarkClaim(
                connection = connection,
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
    }

    private fun connection(): Connection = DriverManager.getConnection(jdbcUrl)

    private fun installInvalidReadyRows(connection: Connection): List<Long> {
        val ids = connection.prepareStatement(
            """
            SELECT id FROM scheduling_outbox_events
            WHERE aggregate_type = 'APPOINTMENT'
            ORDER BY id LIMIT 3 OFFSET 2
            """.trimIndent(),
        ).use { statement ->
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(rows.getLong(1))
                }
            }
        }
        assertEquals(3, ids.size)
        connection.prepareStatement(
            """
            UPDATE scheduling_outbox_events
            SET status = 'PENDING', attempt_count = 0,
                next_attempt_at = TIMESTAMP '2026-08-05 08:29:00', lease_until = NULL,
                event_type = ?, topic = ?, partition_key = ?
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, "AppointmentUnknown")
            statement.setString(2, DefaultAppointmentOutboxWriter.DEFAULT_TOPIC)
            statement.setString(3, "tenant-1:CLINIC:clinic-31:APPOINTMENT:apt-100002")
            statement.setLong(4, ids[0])
            assertEquals(1, statement.executeUpdate())

            statement.setString(1, AppointmentEventType.CREATED.wireName)
            statement.setObject(2, null)
            statement.setString(3, "tenant-1:CLINIC:clinic-31:APPOINTMENT:apt-100004")
            statement.setLong(4, ids[1])
            assertEquals(1, statement.executeUpdate())

            statement.setString(1, AppointmentEventType.CREATED.wireName)
            statement.setString(2, DefaultAppointmentOutboxWriter.DEFAULT_TOPIC)
            statement.setObject(3, null)
            statement.setLong(4, ids[2])
            assertEquals(1, statement.executeUpdate())
        }
        return ids
    }

    private fun ensureDeterministicCandidates(connection: Connection) {
        val ids = connection.prepareStatement(
            "SELECT id FROM scheduling_outbox_events WHERE aggregate_type = 'APPOINTMENT' ORDER BY id LIMIT 2",
        ).use { statement ->
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(rows.getLong(1))
                }
            }
        }
        assertTrue(ids.size == 2, "fixture must contain two appointment rows")
        connection.prepareStatement(
            """
            UPDATE scheduling_outbox_events
            SET status = 'PENDING', attempt_count = 0,
                next_attempt_at = ?, lease_until = ?
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.valueOf("2026-08-05 08:29:00"))
            statement.setTimestamp(2, Timestamp.valueOf("2026-08-05 08:29:00"))
            statement.setLong(3, ids[0])
            assertEquals(1, statement.executeUpdate())
            statement.setTimestamp(1, Timestamp.valueOf("2026-08-05 08:31:00"))
            statement.setTimestamp(2, null)
            statement.setLong(3, ids[1])
            assertEquals(1, statement.executeUpdate())
        }
    }

    private fun explain(connection: Connection, sql: String): String =
        connection.prepareStatement("EXPLAIN $sql").use { statement ->
            statement.executeQuery().use { rows ->
                buildString {
                    while (rows.next()) appendLine(rows.getString(1))
                }
            }
        }

    private fun indexColumns(connection: Connection, indexName: String): List<String> {
        val columns = mutableListOf<Pair<Short, String>>()
        connection.metaData.getIndexInfo(
            null,
            "PUBLIC",
            SchedulingOutboxEvents.tableName.uppercase(),
            false,
            false,
        ).use { rows ->
            while (rows.next()) {
                if (rows.getString("INDEX_NAME").equals(indexName, ignoreCase = true)) {
                    rows.getString("COLUMN_NAME")?.let { column ->
                        columns += rows.getShort("ORDINAL_POSITION") to column.uppercase()
                    }
                }
            }
        }
        return columns.sortedBy { it.first }.map { it.second }
    }

    private fun selectId(connection: Connection, sql: String): Long =
        connection.prepareStatement(sql).use { statement ->
            statement.executeQuery().use { rows ->
                assertTrue(rows.next(), "fixture did not contain a matching appointment row")
                rows.getLong(1)
            }
        }

    private fun readString(connection: Connection, id: Long, column: String): String? =
        connection.prepareStatement("SELECT $column FROM scheduling_outbox_events WHERE id = ?").use { statement ->
            statement.setLong(1, id)
            statement.executeQuery().use { rows ->
                assertTrue(rows.next())
                rows.getString(1)
            }
        }

    private fun readInt(connection: Connection, id: Long, column: String): Int =
        connection.prepareStatement("SELECT $column FROM scheduling_outbox_events WHERE id = ?").use { statement ->
            statement.setLong(1, id)
            statement.executeQuery().use { rows ->
                assertTrue(rows.next())
                rows.getInt(1)
            }
        }

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
        const val CONDITIONAL_LEASE_UPDATE_SQL = """
            UPDATE scheduling_outbox_events
            SET lease_owner = ?,
                lease_token = ?,
                lease_until = ?,
                attempt_count = attempt_count + 1
            WHERE id IN (?, ?)
              AND status = 'PENDING'
              AND attempt_count = 0
              AND (next_attempt_at IS NULL OR next_attempt_at <= TIMESTAMP '2026-08-05 08:30:00')
              AND (lease_until IS NULL OR lease_until <= TIMESTAMP '2026-08-05 08:30:00')
        """
    }
}
