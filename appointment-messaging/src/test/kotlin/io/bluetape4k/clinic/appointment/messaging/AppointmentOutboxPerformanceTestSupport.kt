package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxEvents
import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxStatus
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlans
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogProjections
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Duration
import java.time.Instant
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
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

    private val POSTGRESQL = PostgreSQLServer.Launcher.postgres

    val databaseNow: Instant = Instant.parse("2026-08-05T08:30:00Z")

    data class SeedSummary(
        val seed: Int,
        val totalRows: Int,
        val appointmentRows: Int,
        val legacyRows: Int,
        val pendingAppointmentRows: Int,
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

    /**
     * 성능 benchmark는 매 실행마다 동일한 빈 PostgreSQL schema를 만들어 migration 비용과
     * claim 계획을 격리하므로 일반 fixture의 incremental schema 계약에서 예외로 둔다.
     */
    fun connectAndCreateSchema(label: String) {
        require(label.isNotBlank()) { "label must not be blank" }
        val database = Database.connect(
            POSTGRESQL.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = POSTGRESQL.username ?: PostgreSQLServer.USERNAME,
            password = POSTGRESQL.password ?: PostgreSQLServer.PASSWORD,
        )
        TransactionManager.defaultDatabase = database
        transaction {
            SchemaUtils.create(TenantGroups, Clinics, ProductCatalogProjections, AppointmentPlans, SchedulingOutboxEvents)
            SchedulingOutboxEvents.deleteAll()
            AppointmentPlans.deleteAll()
            ProductCatalogProjections.deleteAll()
            Clinics.deleteAll()
            TenantGroups.deleteAll()
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
    }

    fun seedBacklog(rowCount: Int = ROW_COUNT): SeedSummary {
        require(rowCount > 0) { "rowCount must be positive" }
        val random = Random(SEED)
        var appointmentRows = 0
        var legacyRows = 0
        var pendingAppointmentRows = 0
        transaction {
            SchedulingOutboxEvents.batchInsert(
                data = 1L..rowCount.toLong(),
                shouldReturnGeneratedValues = false,
            ) { id ->
                val isAppointment = id % 2L == 0L
                val status = when {
                    random.nextInt(100) < 78 -> SchedulingOutboxStatus.PENDING
                    random.nextBoolean() -> SchedulingOutboxStatus.PUBLISHED
                    else -> SchedulingOutboxStatus.FAILED
                }
                val nextAttemptAt = when {
                    status != SchedulingOutboxStatus.PENDING -> null
                    random.nextInt(100) < 82 -> databaseNow.minusSeconds(id % 97L)
                    else -> databaseNow.plusSeconds(60L + id % 97L)
                }
                val leaseUntil = when {
                    status != SchedulingOutboxStatus.PENDING -> null
                    random.nextInt(100) < 90 -> null
                    else -> databaseNow.minusSeconds(1)
                }
                val createdAt = databaseNow.minusSeconds((rowCount - id.toInt() + 1).toLong())
                val aggregateId = (100_000L + id).toString()
                val eventType = if (isAppointment) {
                    AppointmentEventType.entries[(id % AppointmentEventType.entries.size).toInt()].wireName
                } else {
                    "AppointmentPlanCreated"
                }
                this[SchedulingOutboxEvents.eventId] = "performance-event-$id"
                this[SchedulingOutboxEvents.correlationId] = "performance-correlation-$id"
                this[SchedulingOutboxEvents.eventType] = eventType
                this[SchedulingOutboxEvents.tenantGroupId] = TENANT_GROUP_ID
                this[SchedulingOutboxEvents.clinicId] = if (isAppointment) CLINIC_ID else null
                this[SchedulingOutboxEvents.aggregateType] =
                    if (isAppointment) AppointmentEventEnvelope.AGGREGATE_TYPE else "APPOINTMENT_PLAN"
                this[SchedulingOutboxEvents.aggregateId] = aggregateId
                this[SchedulingOutboxEvents.occurredAt] = if (isAppointment) createdAt else null
                this[SchedulingOutboxEvents.topic] = if (isAppointment) {
                    DefaultAppointmentOutboxWriter.DEFAULT_TOPIC
                } else {
                    null
                }
                this[SchedulingOutboxEvents.partitionKey] = if (isAppointment) {
                    AppointmentPartitionKeyFactory.create(TENANT_GROUP_ID, CLINIC_ID, aggregateId.toLong()).value
                } else {
                    null
                }
                this[SchedulingOutboxEvents.schemaVersion] = 1
                this[SchedulingOutboxEvents.payloadJson] = "{}"
                this[SchedulingOutboxEvents.status] = status
                this[SchedulingOutboxEvents.attemptCount] = if (status == SchedulingOutboxStatus.PENDING) 0 else 1
                this[SchedulingOutboxEvents.nextAttemptAt] = nextAttemptAt
                this[SchedulingOutboxEvents.createdAt] = createdAt
                this[SchedulingOutboxEvents.leaseUntil] = leaseUntil
                if (isAppointment) {
                    appointmentRows++
                    if (status == SchedulingOutboxStatus.PENDING) pendingAppointmentRows++
                } else {
                    legacyRows++
                }
            }
        }
        return SeedSummary(SEED, rowCount, appointmentRows, legacyRows, pendingAppointmentRows)
    }

    /**
     * PostgreSQL Testcontainers의 고정된 혼합 backlog에 운영 Exposed claim 경로를 실행한다.
     * 이 수치는 SQL·claim 계약을 검증하는 범위이며 배포 SLO 증거로 해석하지 않는다.
     */
    fun benchmarkClaim(
        store: JdbcAppointmentOutboxStore,
        warmupRounds: Int = 3,
        measurementRounds: Int = 15,
    ): ClaimBenchmark {
        require(warmupRounds > 0) { "warmupRounds must be positive" }
        require(measurementRounds > 4) { "measurementRounds must be greater than four" }
        repeat(warmupRounds) { round ->
            resetReadyRows()
            store.claim("benchmark-warmup-$round", PAGE_SIZE / 4, Duration.ofSeconds(5))
        }

        val samples = buildList {
            repeat(measurementRounds) { round ->
                resetReadyRows()
                var claimed = 0
                val nanos = measureNanoTime {
                    claimed = store.claim("benchmark-measure-$round", PAGE_SIZE / 4, Duration.ofSeconds(5)).size
                }
                add(nanos to claimed)
            }
        }
        resetReadyRows()
        val contention = contentionSample(store)
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

    private fun resetReadyRows() {
        transaction {
            TransactionManager.current().exec(
                """
                UPDATE scheduling_outbox_events
                SET status = 'PENDING', attempt_count = 0,
                    next_attempt_at = TIMESTAMP '2026-08-05 08:29:59',
                    lease_owner = NULL, lease_token = NULL, lease_until = NULL,
                    last_failure_code = NULL, last_failure_at = NULL, published_at = NULL
                WHERE aggregate_type = 'APPOINTMENT'
                """.trimIndent(),
            )
        }
    }

    private fun contentionSample(
        store: JdbcAppointmentOutboxStore,
    ): ContentionSample {
        resetReadyRows()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val start = System.nanoTime()
            val futures = listOf("benchmark-contention-a", "benchmark-contention-b").map { owner ->
                executor.submit<Pair<Long, List<AppointmentOutboxClaim>>> {
                    val taskStart = System.nanoTime()
                    val claims = store.claim(owner, 1, Duration.ofSeconds(5))
                    (System.nanoTime() - taskStart) to claims
                }
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

}
