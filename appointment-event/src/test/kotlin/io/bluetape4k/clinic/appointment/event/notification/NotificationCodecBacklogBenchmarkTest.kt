package io.bluetape4k.clinic.appointment.event.notification

import io.bluetape4k.clinic.appointment.commitment.CancellationReasonRegistry
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.math.ceil
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * 실제 notification outbox row에서 v1/v2 JSON을 읽어 backlog를 비우는 성능 계약이다.
 *
 * 일반 `:appointment-event:test`에서는 작은 smoke만 실행한다. `issue34.codec.benchmark=true`
 * 를 명시하면 계획된 10,000건, warm-up 30초, 측정 5분 창을 사용한다. synthetic queue
 * 비용 모델은 이 테스트의 입력이나 결과에 포함하지 않는다.
 */
class NotificationCodecBacklogBenchmarkTest {

    @Test
    fun `mixed notification codec backlog drains from the real outbox table`() {
        val configuration = CodecBenchmarkConfiguration.fromSystemProperties()
        val database = Database.connect(
            "jdbc:h2:mem:notification_codec_backlog_${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver",
        )
        val codec = NotificationOutboxCodec()
        val repository = NotificationOutboxRepository(
            codec = codec,
            leaseDuration = Duration.ofMinutes(5),
        )

        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(
                NotificationOutboxEvents,
                NotificationDeliveryAttempts,
            )
            NotificationDeliveryAttempts.deleteAll()
            NotificationOutboxEvents.deleteAll()
        }

        val drafts = buildDrafts(configuration)
        transaction(database) {
            drafts.forEach(repository::enqueue)
        }

        warmUp(database, codec, configuration)
        val measurements = measure(database, codec, configuration)
        require(measurements.decodedRows > 0L) { "codec benchmark must decode at least one row" }

        val report = CodecBenchmarkReport(
            schemaVersion = 1,
            benchmark = BENCHMARK_NAME,
            mode = configuration.mode,
            mix = configuration.mix,
            run = configuration.run,
            environment = CodecBenchmarkEnvironment(
                database = "h2",
                datasetRows = configuration.rows,
                warmupSeconds = configuration.warmupSeconds,
                measureSeconds = configuration.measureSeconds,
                detailLength = CancellationReasonRegistry.CLINIC_SCHEDULE_CHANGED_DETAIL.length,
                batchSize = BATCH_SIZE,
                legacyRatio = configuration.legacyRatio,
                jdk = System.getProperty("java.runtime.version", "unknown"),
                vm = System.getProperty("java.vm.name", "unknown"),
                sourceCommit = sourceCommit(),
            ),
            metrics = CodecBenchmarkMetrics(
                throughputRowsPerSecond = measurements.decodedRows /
                    (measurements.drainNanos.coerceAtLeast(1L).toDouble() / NANOS_PER_SECOND),
                decodeP95Millis = measurements.samples.percentile(0.95),
                decodeP99Millis = measurements.samples.percentile(0.99),
                decodeFailures = measurements.decodeFailures,
                drainTimeMillis = measurements.drainNanos.toDouble() / NANOS_PER_MILLISECOND,
                decodedRows = measurements.decodedRows,
                latencySamples = measurements.samples.size,
                passes = measurements.passes,
            ),
        )
        writeReport(configuration.artifact, report)

        println(
            "Issue #34 codec ${configuration.mode}/${configuration.mix} run ${configuration.run}: " +
                "rows=${measurements.decodedRows}, " +
                "throughput=${"%.2f".format(report.metrics.throughputRowsPerSecond)}/s, " +
                "p95=${"%.3f".format(report.metrics.decodeP95Millis)}ms, " +
                "p99=${"%.3f".format(report.metrics.decodeP99Millis)}ms, " +
                "failures=${report.metrics.decodeFailures}, artifact=${configuration.artifact}",
        )
    }

    private fun buildDrafts(configuration: CodecBenchmarkConfiguration): List<SendableNotificationDraft> =
        (0 until configuration.rows).map { index ->
            val legacy = index < configuration.legacyRows
            val schemaVersion = if (legacy) {
                NotificationOutboxEnvelope.LEGACY_SCHEMA_VERSION
            } else {
                NotificationOutboxEnvelope.CURRENT_SCHEMA_VERSION
            }
            val detail = if (legacy) null else detailFor(index)
            val appointmentId = 10_000L + index
            val digest = "issue34-${configuration.mode}-${configuration.mix}-$index"
            SendableNotificationDraft(
                envelope = NotificationOutboxEnvelope(
                    schemaVersion = schemaVersion,
                    eventId = NotificationEventId("issue34-event-$index"),
                    idempotencyKey = NotificationIdempotencyKey("issue34-idempotency-$index"),
                    tenantGroupId = TenantGroupId(34L),
                    clinicId = ClinicId(34L),
                    appointmentId = AppointmentId(appointmentId),
                    memberId = MemberId("issue34-member-$index"),
                    channel = NotificationChannelType.SMS,
                    eventType = NotificationEventType.CANCELLED,
                    notificationSlot = NotificationSlot.CANCELLED,
                    templateKey = NotificationTemplateKey("appointment-cancelled"),
                    templateVersion = NotificationTemplateVersion(if (legacy) 1 else 2),
                    parameterType = NotificationParameterType.APPOINTMENT_CANCELLED,
                    parameters = AppointmentCancelledParameters(
                        clinicDisplayName = "Issue 34 Clinic",
                        appointmentDate = java.time.LocalDate.of(2026, 8, 13),
                        startTime = java.time.LocalTime.of(10, 30),
                        cancellationReasonCode = CancellationReasonCode("CUSTOMER_REQUEST"),
                        cancellationReasonDetail = detail,
                    ),
                    occurredAt = Instant.parse("2026-08-13T00:00:00Z"),
                    availableAt = Instant.parse("2026-08-13T00:00:00Z"),
                ),
                idempotencyDigest = NotificationIdempotencyDigest(
                    keyId = "issue34",
                    version = 1,
                    value = digest,
                ),
                auditFingerprint = NotificationAuditFingerprint(
                    keyId = "issue34",
                    version = 1,
                    value = "issue34-audit-$index",
                ),
                providerKey = "dummy",
            )
        }

    private fun warmUp(
        database: Database,
        codec: NotificationOutboxCodec,
        configuration: CodecBenchmarkConfiguration,
    ) {
        val deadline = System.nanoTime() + configuration.warmupSeconds * NANOS_PER_SECOND
        while (configuration.warmupSeconds > 0L && System.nanoTime() < deadline) {
            resetRows(database)
            drain(database, codec, samples = null)
        }
    }

    private fun measure(
        database: Database,
        codec: NotificationOutboxCodec,
        configuration: CodecBenchmarkConfiguration,
    ): MeasurementSummary {
        val deadline = System.nanoTime() + configuration.measureSeconds * NANOS_PER_SECOND
        val samples = LatencySamples()
        var decodedRows = 0L
        var drainNanos = 0L
        var decodeFailures = 0L
        var passes = 0
        do {
            resetRows(database)
            val pass = drain(database, codec, samples)
            decodedRows += pass.decodedRows
            drainNanos += pass.drainNanos
            decodeFailures += pass.decodeFailures
            passes++
        } while (configuration.measureSeconds > 0L && System.nanoTime() < deadline)

        return MeasurementSummary(
            decodedRows = decodedRows,
            drainNanos = drainNanos,
            decodeFailures = decodeFailures,
            passes = passes,
            samples = samples,
        )
    }

    private fun resetRows(database: Database) {
        transaction(database) {
            NotificationOutboxEvents.update({ NotificationOutboxEvents.status eq NotificationOutboxStatus.SENT }) {
                it[status] = NotificationOutboxStatus.PENDING
                it[updatedAt] = Instant.now()
                it[terminalAt] = null
            }
        }
    }

    private fun drain(
        database: Database,
        codec: NotificationOutboxCodec,
        samples: LatencySamples?,
    ): DrainPass {
        val startedAt = System.nanoTime()
        var decodedRows = 0L
        var decodeFailures = 0L
        while (true) {
            val rows = transaction(database) {
                NotificationOutboxEvents
                    .selectAll()
                    .where { NotificationOutboxEvents.status eq NotificationOutboxStatus.PENDING }
                    .orderBy(NotificationOutboxEvents.id to SortOrder.ASC)
                    .limit(BATCH_SIZE)
                    .map {
                        PendingRow(
                            id = it[NotificationOutboxEvents.id].value,
                            parametersJson = checkNotNull(it[NotificationOutboxEvents.parametersJson]),
                        )
                    }
            }
            if (rows.isEmpty()) break

            rows.forEach { row ->
                val decodeStartedAt = System.nanoTime()
                try {
                    codec.decode(row.parametersJson)
                } catch (_: NotificationContractException) {
                    decodeFailures++
                } finally {
                    samples?.add(System.nanoTime() - decodeStartedAt)
                }
                decodedRows++
            }
            val ids = rows.map { it.id }
            transaction(database) {
                NotificationOutboxEvents.update({ NotificationOutboxEvents.id inList ids }) {
                    it[status] = NotificationOutboxStatus.SENT
                    it[updatedAt] = Instant.now()
                    it[terminalAt] = Instant.now()
                }
            }
        }
        return DrainPass(
            decodedRows = decodedRows,
            drainNanos = System.nanoTime() - startedAt,
            decodeFailures = decodeFailures,
        )
    }

    private fun writeReport(path: Path, report: CodecBenchmarkReport) {
        path.parent?.let(Files::createDirectories)
        val json = jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report)
        Files.writeString(path, "$json\n")
    }

    private fun detailFor(@Suppress("UNUSED_PARAMETER") index: Int): String =
        CancellationReasonRegistry.CLINIC_SCHEDULE_CHANGED_DETAIL

    private data class PendingRow(
        val id: Long,
        val parametersJson: String,
    )

    private data class DrainPass(
        val decodedRows: Long,
        val drainNanos: Long,
        val decodeFailures: Long,
    )

    private data class MeasurementSummary(
        val decodedRows: Long,
        val drainNanos: Long,
        val decodeFailures: Long,
        val passes: Int,
        val samples: LatencySamples,
    )

    private class LatencySamples {
        private val values = ArrayList<Double>()

        val size: Int get() = values.size

        fun add(nanos: Long) {
            if (values.size < MAX_LATENCY_SAMPLES) {
                values += nanos.toDouble() / NANOS_PER_MILLISECOND
            }
        }

        fun percentile(percent: Double): Double {
            if (values.isEmpty()) return 0.0
            val sorted = values.sorted()
            val index = (ceil(sorted.size * percent).toInt() - 1).coerceIn(0, sorted.lastIndex)
            return sorted[index]
        }
    }

    private data class CodecBenchmarkReport(
        val schemaVersion: Int,
        val benchmark: String,
        val mode: String,
        val mix: String,
        val run: Int,
        val environment: CodecBenchmarkEnvironment,
        val metrics: CodecBenchmarkMetrics,
    )

    private data class CodecBenchmarkEnvironment(
        val database: String,
        val datasetRows: Int,
        val warmupSeconds: Long,
        val measureSeconds: Long,
        val detailLength: Int,
        val batchSize: Int,
        val legacyRatio: Double,
        val jdk: String,
        val vm: String,
        val sourceCommit: String,
    )

    private data class CodecBenchmarkMetrics(
        val throughputRowsPerSecond: Double,
        val decodeP95Millis: Double,
        val decodeP99Millis: Double,
        val decodeFailures: Long,
        val drainTimeMillis: Double,
        val decodedRows: Long,
        val latencySamples: Int,
        val passes: Int,
    )

    private data class CodecBenchmarkConfiguration(
        val mode: String,
        val mix: String,
        val run: Int,
        val rows: Int,
        val warmupSeconds: Long,
        val measureSeconds: Long,
        val artifact: Path,
    ) {
        val legacyRatio: Double
            get() = if (mix == LEGACY_HEAVY) 0.8 else 0.2

        val legacyRows: Int
            get() = (rows * legacyRatio).toInt()

        companion object {
            fun fromSystemProperties(): CodecBenchmarkConfiguration {
                val fullBenchmark = System.getProperty("issue34.codec.benchmark") == "true"
                val mode = System.getProperty("issue34.codec.mode", "candidate")
                val mix = System.getProperty("issue34.codec.mix", LEGACY_HEAVY)
                val run = propertyInt("issue34.codec.run", 1)
                val rows = propertyInt("issue34.codec.rows", if (fullBenchmark) 10_000 else 100)
                val warmupSeconds = propertyLong("issue34.codec.warmupSeconds", if (fullBenchmark) 30L else 0L)
                val measureSeconds = propertyLong("issue34.codec.measureSeconds", if (fullBenchmark) 300L else 0L)
                require(mode in setOf("baseline", "candidate")) { "issue34.codec.mode must be baseline or candidate" }
                require(mix in setOf(LEGACY_HEAVY, CURRENT_HEAVY)) {
                    "issue34.codec.mix must be legacy-heavy or current-heavy"
                }
                require(run in 1..3) { "issue34.codec.run must be between 1 and 3" }
                require(rows >= 10 && rows % 10 == 0) { "issue34.codec.rows must be a positive multiple of 10" }
                require(warmupSeconds >= 0L && measureSeconds >= 0L) {
                    "codec benchmark windows must not be negative"
                }
                val defaultArtifact = Path.of(
                    "build/reports/issue-34/codec/$mode-$mix-run$run.json",
                ).toAbsolutePath()
                val artifact = Path.of(System.getProperty("issue34.codec.artifact") ?: defaultArtifact.toString())
                    .toAbsolutePath()
                return CodecBenchmarkConfiguration(
                    mode = mode,
                    mix = mix,
                    run = run,
                    rows = rows,
                    warmupSeconds = warmupSeconds,
                    measureSeconds = measureSeconds,
                    artifact = artifact,
                )
            }

            private fun propertyInt(name: String, defaultValue: Int): Int =
                System.getProperty(name)?.toIntOrNull() ?: defaultValue

            private fun propertyLong(name: String, defaultValue: Long): Long =
                System.getProperty(name)?.toLongOrNull() ?: defaultValue
        }
    }

    companion object {
        private const val BENCHMARK_NAME = "issue-34-notification-codec-backlog"
        private const val LEGACY_HEAVY = "legacy-heavy"
        private const val CURRENT_HEAVY = "current-heavy"
        private const val BATCH_SIZE = 500
        private const val MAX_LATENCY_SAMPLES = 500_000
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        private fun sourceCommit(): String =
            System.getProperty("issue34.sourceCommit")
                ?: System.getenv("GITHUB_SHA")
                ?: "unknown"
    }
}
