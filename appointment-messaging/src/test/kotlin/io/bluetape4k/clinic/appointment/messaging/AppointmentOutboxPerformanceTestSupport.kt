package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxEvents
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlans
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogProjections
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.time.Instant
import java.time.Duration
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.system.measureNanoTime

/** 범위가 제한된 query/claim 계약을 위한 결정적 payload-free backlog fixture. */
internal object AppointmentOutboxPerformanceTestSupport {
    const val SEED = 41
    const val ROW_COUNT = 20_000
    const val PAGE_SIZE = 128
    const val CLINIC_ID = 31L
    const val TENANT_GROUP_ID = 1L

    val databaseNow: Instant = Instant.parse("2026-08-05T08:30:00Z")

    data class SeedSummary(
        val seed: Int,
        val totalRows: Int,
        val appointmentRows: Int,
        val legacyRows: Int,
        val pendingAppointmentRows: Int,
    )

    data class BoundedPageBenchmark(
        val seed: Int,
        val totalRows: Int,
        val samplesNanos: List<Long>,
        val maxPageSize: Int,
    )

    data class ClaimBenchmark(
        val seed: Int,
        val totalRows: Int,
        val warmupRounds: Int,
        val measurementRounds: Int,
        val samplesNanos: List<Long>,
        val p50Nanos: Long,
        val p95Nanos: Long,
        val p99Nanos: Long,
        val maxClaimed: Int,
        val contentionClaims: Int,
        val contentionDistinctIds: Int,
        val contentionSamplesNanos: List<Long>,
    )

    fun connectAndCreateSchema(label: String): String {
        val url = "jdbc:h2:mem:appointment_outbox_performance_${label}_${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
        Database.connect(url, driver = "org.h2.Driver")
        transaction {
            SchemaUtils.create(TenantGroups, Clinics, ProductCatalogProjections, AppointmentPlans, SchedulingOutboxEvents)
            TenantGroups.insert {
                it[id] = EntityID(TENANT_GROUP_ID, TenantGroups)
                it[tenantCode] = "tenant-performance"
                it[displayName] = "Performance Tenant"
                it[active] = true
            }
            Clinics.insert {
                it[id] = EntityID(CLINIC_ID, Clinics)
                it[tenantGroupId] = TENANT_GROUP_ID
                it[name] = "Performance Clinic"
            }
        }
        return url
    }

    fun seedBacklog(connection: Connection, rowCount: Int = ROW_COUNT): SeedSummary {
        require(rowCount > 0) { "rowCount must be positive" }
        val random = Random(SEED)
        var appointmentRows = 0
        var legacyRows = 0
        var pendingAppointmentRows = 0
        connection.autoCommit = false
        try {
            connection.prepareStatement(INSERT_SQL).use { statement ->
                repeat(rowCount) { offset ->
                    val id = offset + 1L
                    val isAppointment = id % 2L == 0L
                    val status = when {
                        random.nextInt(100) < 78 -> "PENDING"
                        random.nextBoolean() -> "PUBLISHED"
                        else -> "FAILED"
                    }
                    val nextAttemptAt = when {
                        status != "PENDING" -> null
                        random.nextInt(100) < 82 -> databaseNow.minusSeconds(id % 97L)
                        else -> databaseNow.plusSeconds(60L + id % 97L)
                    }
                    val leaseUntil = when {
                        status != "PENDING" -> null
                        random.nextInt(100) < 90 -> null
                        else -> databaseNow.minusSeconds(1)
                    }
                    val createdAt = databaseNow.minusSeconds((rowCount - offset).toLong())
                    val aggregateId = (100_000L + id).toString()
                    val eventType = if (isAppointment) {
                        AppointmentEventType.entries[(id % AppointmentEventType.entries.size).toInt()].wireName
                    } else {
                        "AppointmentPlanCreated"
                    }
                    bindRow(
                        statement = statement,
                        eventId = "performance-event-$id",
                        correlationId = "performance-correlation-$id",
                        eventType = eventType,
                        aggregateType = if (isAppointment) AppointmentEventEnvelope.AGGREGATE_TYPE else "APPOINTMENT_PLAN",
                        aggregateId = aggregateId,
                        occurredAt = if (isAppointment) createdAt else null,
                        topic = if (isAppointment) DefaultAppointmentOutboxWriter.DEFAULT_TOPIC else null,
                        partitionKey = if (isAppointment) {
                            AppointmentPartitionKeyFactory.create(TENANT_GROUP_ID, CLINIC_ID, aggregateId.toLong()).value
                        } else {
                            null
                        },
                        status = status,
                        attemptCount = if (status == "PENDING") 0 else 1,
                        nextAttemptAt = nextAttemptAt,
                        leaseUntil = leaseUntil,
                        createdAt = createdAt,
                    )
                    statement.addBatch()
                    if (id % BATCH_SIZE == 0L) {
                        statement.executeBatch()
                        statement.clearBatch()
                    }
                    if (isAppointment) {
                        appointmentRows++
                        if (status == "PENDING") pendingAppointmentRows++
                    } else {
                        legacyRows++
                    }
                }
                statement.executeBatch()
                statement.clearBatch()
            }
            connection.commit()
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = true
        }
        return SeedSummary(SEED, rowCount, appointmentRows, legacyRows, pendingAppointmentRows)
    }

    fun benchmarkReadyPage(
        connection: Connection,
        readySql: String,
        repetitions: Int = 5,
    ): BoundedPageBenchmark {
        require(repetitions > 0) { "repetitions must be positive" }
        val samples = mutableListOf<Long>()
        var maxPage = 0
        repeat(repetitions) {
            var pageSize = 0
            val nanos = measureNanoTime {
                connection.prepareStatement(readySql).use { statement ->
                    statement.executeQuery().use { rows ->
                        while (rows.next()) {
                            pageSize++
                        }
                    }
                }
            }
            samples += nanos
            maxPage = maxOf(maxPage, pageSize)
        }
        return BoundedPageBenchmark(SEED, ROW_COUNT, samples, maxPage)
    }

    /**
     * 고정된 혼합 backlog에 운영 Exposed claim 경로를 실행한다. 이 profile은 unit-test
     * lane에서 충분히 짧게 유지하며, 배포 SLO 증거는 별도의 PostgreSQL/MySQL/Kafka rollout
     * gate로 남긴다.
     */
    fun benchmarkClaim(
        connection: Connection,
        store: JdbcAppointmentOutboxStore,
        warmupRounds: Int = 3,
        measurementRounds: Int = 15,
    ): ClaimBenchmark {
        require(warmupRounds > 0) { "warmupRounds must be positive" }
        require(measurementRounds > 4) { "measurementRounds must be greater than four" }
        repeat(warmupRounds) { round ->
            resetReadyRows(connection)
            store.claim("benchmark-warmup-$round", PAGE_SIZE / 4, Duration.ofSeconds(5))
        }

        val samples = buildList {
            repeat(measurementRounds) { round ->
                resetReadyRows(connection)
                var claimed = 0
                val nanos = measureNanoTime {
                    claimed = store.claim("benchmark-measure-$round", PAGE_SIZE / 4, Duration.ofSeconds(5)).size
                }
                add(nanos to claimed)
            }
        }
        resetReadyRows(connection)
        val contention = contentionSample(store, connection)
        val durations = samples.map { it.first }
        return ClaimBenchmark(
            seed = SEED,
            totalRows = ROW_COUNT,
            warmupRounds = warmupRounds,
            measurementRounds = measurementRounds,
            samplesNanos = durations,
            p50Nanos = percentile(durations, 0.50),
            p95Nanos = percentile(durations, 0.95),
            p99Nanos = percentile(durations, 0.99),
            maxClaimed = samples.maxOf { it.second },
            contentionClaims = contention.claimedIds.size,
            contentionDistinctIds = contention.claimedIds.toSet().size,
            contentionSamplesNanos = contention.samplesNanos,
        )
    }

    /** 범위가 제한된 benchmark 메타데이터만 기록하며 payload JSON과 식별자는 저장하지 않는다. */
    fun writeBenchmarkReport(
        benchmark: BoundedPageBenchmark,
        path: Path = Paths.get("build/reports/appointment-messaging/benchmark.json"),
    ) {
        Files.createDirectories(path.parent)
        val sampleValues = benchmark.samplesNanos.joinToString(",")
        Files.writeString(
            path,
            """
            {
              "seed": ${benchmark.seed},
              "totalRows": ${benchmark.totalRows},
              "sampleCount": ${benchmark.samplesNanos.size},
              "samplesNanos": [$sampleValues],
              "maxPageSize": ${benchmark.maxPageSize},
              "rawPayloadIncluded": false,
              "mode": "bounded-query-contract"
            }
            """.trimIndent() + System.lineSeparator(),
        )
    }

    fun writeBenchmarkReport(
        benchmark: ClaimBenchmark,
        path: Path = Paths.get("build/reports/appointment-messaging/benchmark.json"),
    ) {
        Files.createDirectories(path.parent)
        val samples = benchmark.samplesNanos.joinToString(",")
        val contentionSamples = benchmark.contentionSamplesNanos.joinToString(",")
        Files.writeString(
            path,
            """
            {
              "seed": ${benchmark.seed},
              "totalRows": ${benchmark.totalRows},
              "warmupRounds": ${benchmark.warmupRounds},
              "measurementRounds": ${benchmark.measurementRounds},
              "samplesNanos": [$samples],
              "p50Nanos": ${benchmark.p50Nanos},
              "p95Nanos": ${benchmark.p95Nanos},
              "p99Nanos": ${benchmark.p99Nanos},
              "maxClaimed": ${benchmark.maxClaimed},
              "contentionClaims": ${benchmark.contentionClaims},
              "contentionDistinctIds": ${benchmark.contentionDistinctIds},
              "contentionSamplesNanos": [$contentionSamples],
              "rawPayloadIncluded": false,
              "mode": "bounded-production-claim-contract",
              "deploymentSloEvidence": false
            }
            """.trimIndent() + System.lineSeparator(),
        )
    }

    private fun resetReadyRows(connection: Connection) {
        connection.prepareStatement(
            """
            UPDATE scheduling_outbox_events
            SET status = 'PENDING', attempt_count = 0, next_attempt_at = ?,
                lease_owner = NULL, lease_token = NULL, lease_until = NULL,
                last_failure_code = NULL, last_failure_at = NULL, published_at = NULL
            WHERE aggregate_type = 'APPOINTMENT'
            """.trimIndent(),
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(databaseNow.minusSeconds(1)))
            statement.executeUpdate()
        }
    }

    private fun contentionSample(
        store: JdbcAppointmentOutboxStore,
        connection: Connection,
    ): ContentionSample {
        resetReadyRows(connection)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val start = System.nanoTime()
            val futures = listOf("benchmark-contention-a", "benchmark-contention-b").map { owner ->
                executor.submit(Callable {
                    val taskStart = System.nanoTime()
                    val claims = store.claim(owner, 1, Duration.ofSeconds(5))
                    (System.nanoTime() - taskStart) to claims
                })
            }
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }
            val elapsed = System.nanoTime() - start
            val samples = results.map { it.first } + elapsed
            return ContentionSample(
                claimedIds = results.flatMap { result -> result.second.map { it.id } },
                samplesNanos = samples,
            )
        } finally {
            executor.shutdownNow()
        }
    }

    private fun percentile(values: List<Long>, quantile: Double): Long {
        val sorted = values.sorted()
        val index = ((sorted.size * quantile).toInt().coerceAtLeast(1) - 1).coerceAtMost(sorted.lastIndex)
        return sorted[index]
    }

    private data class ContentionSample(
        val claimedIds: List<Long>,
        val samplesNanos: List<Long>,
    )

    private fun bindRow(
        statement: PreparedStatement,
        eventId: String,
        correlationId: String,
        eventType: String,
        aggregateType: String,
        aggregateId: String,
        occurredAt: Instant?,
        topic: String?,
        partitionKey: String?,
        status: String,
        attemptCount: Int,
        nextAttemptAt: Instant?,
        leaseUntil: Instant?,
        createdAt: Instant,
    ) {
        statement.setString(1, eventId)
        statement.setObject(2, null)
        statement.setString(3, correlationId)
        statement.setString(4, eventType)
        statement.setLong(5, TENANT_GROUP_ID)
        statement.setLong(6, CLINIC_ID)
        statement.setObject(7, null)
        statement.setString(8, aggregateType)
        statement.setString(9, aggregateId)
        statement.setTimestamp(10, occurredAt?.let(Timestamp::from))
        statement.setString(11, topic)
        statement.setString(12, partitionKey)
        statement.setObject(13, null)
        statement.setObject(14, null)
        statement.setTimestamp(15, leaseUntil?.let(Timestamp::from))
        statement.setObject(16, null)
        statement.setObject(17, null)
        statement.setInt(18, 1)
        statement.setString(19, "{}")
        statement.setString(20, status)
        statement.setInt(21, attemptCount)
        statement.setTimestamp(22, nextAttemptAt?.let(Timestamp::from))
        statement.setTimestamp(23, Timestamp.from(createdAt))
        statement.setObject(24, null)
    }

    private const val BATCH_SIZE = 500L
    private val INSERT_SQL = """
        INSERT INTO scheduling_outbox_events(
            event_id, causation_event_id, correlation_id, event_type,
            tenant_group_id, clinic_id, plan_id, aggregate_type, aggregate_id,
            occurred_at, topic, partition_key, lease_owner, lease_token,
            lease_until, last_failure_code, last_failure_at, schema_version,
            payload_json, status, attempt_count, next_attempt_at, created_at, published_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """.trimIndent()
}
