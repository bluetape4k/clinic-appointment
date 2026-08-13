package io.bluetape4k.clinic.appointment.api.commitment

import io.bluetape4k.testcontainers.database.PostgreSQLServer
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.math.ceil

/** PostgreSQL cancel load에서 고정하는 arrival mix입니다. */
internal enum class CancellationLoadOperation(
    val expectedOutcome: String,
) {
    SUCCESS_PATIENT("success"),
    SUCCESS_ADMIN("success"),
    IDEMPOTENT_REPLAY("replay"),
    PRECONDITION_CONFLICT("precondition-conflict"),
    RETRY_EXHAUSTION("retry-exhaustion"),
}

/** Gatling 요청이 속한 benchmark phase입니다. */
internal enum class BenchmarkPhase {
    WARMUP,
    MEASUREMENT,
}

/** loopback HTTP handler와 report writer 사이에 전달하는 한 번의 관측값입니다. */
internal data class CancellationObservation(
    val operation: CancellationLoadOperation,
    val statusCode: Int,
    val errorCode: String?,
    val elapsedNanos: Long,
    val scenarioMatched: Boolean,
)

/**
 * 실제 PostgreSQL Testcontainers와 production command service를 연결하는 cancel fixture입니다.
 *
 * active slot은 항상 100개를 유지하려고 비동기 replacement를 사용한다. replacement는 측정
 * 요청의 latency에 포함하지 않으며, command 경로 자체는 [VisitCommitmentCommandInvoker]를
 * 통해 동일한 Exposed transaction과 idempotency/lock 코드를 실행한다. 모든 virtual user가
 * warm-up을 끝낸 뒤 barrier를 통과해야 측정과 lock-wait sampling을 시작하며, 모든 측정 요청이
 * 끝난 뒤 두 번째 barrier에서 측정 구간을 닫는다. 초기 fixture만 별도 seed 단계에서 생성하고
 * 측정 중 synthetic response는 만들지 않는다.
 */
internal class PatientAppointmentCancelPostgresFixture(
    private val datasetSize: Int = DATASET_SIZE,
) : AutoCloseable {
    private val server = PostgreSQLServer.Launcher.postgres
    private val database =
        Database.connect(
            url = server.getJdbcUrl(),
            driver = server.getDriverClassName(),
            user = requireNotNull(server.getUsername()),
            password = requireNotNull(server.getPassword()),
        )
    private val schema = VisitCommitmentGatlingFixture()
    private val clinic = schema.initialize(database, useProductionOutboxSchema = true)
    private val invoker = VisitCommitmentCommandInvoker(database)
    private val slots = AtomicReferenceArray<CancelSlot>(datasetSize)
    private val replacementExecutor = Executors.newVirtualThreadPerTaskExecutor()
    private val contentionExecutor = Executors.newVirtualThreadPerTaskExecutor()
    private val observations = ConcurrentLinkedQueue<CancellationObservation>()
    private val cancelDurationsNanos = ConcurrentLinkedQueue<Long>()
    private val lockWaitMillis = ConcurrentLinkedQueue<Double>()
    private val lockWaitSampleQueries = AtomicInteger(0)
    private val lockWaitSampleFailures = AtomicInteger(0)
    private val warmupRequests = AtomicInteger(0)
    private val measurementStartedAtEpochMillis = AtomicLong(0)
    private val measurementEndedAtEpochMillis = AtomicLong(0)
    private val measurementStartedAtNanos = AtomicLong(0)
    private val measurementEndedAtNanos = AtomicLong(0)
    private val replacementSequence = AtomicLong(0)
    private val differentSlotCursor = AtomicInteger(1)
    private val sampling = AtomicBoolean(false)
    private val samplerExecutor = Executors.newSingleThreadExecutor()
    private val measurementStartBarrier =
        CyclicBarrier(TOTAL_CONCURRENCY) {
            check(measurementStartedAtNanos.compareAndSet(0, System.nanoTime())) {
                "measurement monotonic start boundary must be crossed exactly once"
            }
            check(measurementStartedAtEpochMillis.compareAndSet(0, System.currentTimeMillis())) {
                "measurement start boundary must be crossed exactly once"
            }
            startLockWaitSampling()
        }
    private val measurementEndBarrier =
        CyclicBarrier(TOTAL_CONCURRENCY) {
            check(measurementStartedAtEpochMillis.get() > 0) { "measurement must start before it ends" }
            stopLockWaitSampling()
            check(measurementEndedAtNanos.compareAndSet(0, System.nanoTime())) {
                "measurement monotonic end boundary must be crossed exactly once"
            }
            check(measurementEndedAtEpochMillis.compareAndSet(0, System.currentTimeMillis())) {
                "measurement end boundary must be crossed exactly once"
            }
        }
    private var samplerFuture: Future<*>? = null
    private lateinit var replayTarget: CancelSlot

    init {
        require(datasetSize == DATASET_SIZE) { "Issue 34 benchmark dataset must contain exactly 100 appointments" }
        seedDataset()
    }

    /** 모든 virtual user가 warm-up을 끝낼 때까지 기다린 뒤 측정 경계를 연다. */
    fun awaitMeasurementStart() {
        measurementStartBarrier.await(PHASE_BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    /** 모든 virtual user의 측정 요청이 끝날 때까지 기다린 뒤 측정 경계를 닫는다. */
    fun awaitMeasurementEnd() {
        measurementEndBarrier.await(PHASE_BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun startLockWaitSampling() {
        if (!sampling.compareAndSet(false, true)) return
        samplerFuture =
            samplerExecutor.submit {
                while (sampling.get()) {
                    sampleLockWaits()
                    Thread.sleep(20L)
                }
            }
    }

    private fun stopLockWaitSampling() {
        if (!sampling.compareAndSet(true, false)) return
        val future = samplerFuture
        try {
            future?.get(SAMPLER_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (failure: InterruptedException) {
            future?.cancel(true)
            Thread.currentThread().interrupt()
            throw failure
        } catch (failure: Exception) {
            future?.cancel(true)
            throw failure
        } finally {
            samplerFuture = null
        }
    }

    /**
     * one HTTP request에 해당하는 실제 command 실행입니다.
     * 같은 appointment lane은 slot 1을 고정해 row/resource 경합을 만들고, 다른 appointment
     * lane은 나머지 99개를 round-robin으로 선택한다.
     */
    fun execute(
        operation: CancellationLoadOperation,
        sameAppointment: Boolean,
        phase: BenchmarkPhase,
    ): CancellationObservation {
        verifyPhaseOpen(phase)
        val started = System.nanoTime()
        val observation =
            runCatching {
                when (operation) {
                    CancellationLoadOperation.SUCCESS_PATIENT ->
                        cancelActiveSlot(
                            index = if (sameAppointment) 1 else nextDifferentSlot(),
                            actorRole = "PATIENT",
                            allowTerminalConflict = sameAppointment,
                        )
                    CancellationLoadOperation.SUCCESS_ADMIN ->
                        cancelActiveSlot(
                            index = if (sameAppointment) 1 else nextDifferentSlot(),
                            actorRole = "ADMIN",
                            allowTerminalConflict = sameAppointment,
                        )
                    CancellationLoadOperation.IDEMPOTENT_REPLAY -> replayCancelledSlot()
                    CancellationLoadOperation.PRECONDITION_CONFLICT -> preconditionConflict(sameAppointment)
                    CancellationLoadOperation.RETRY_EXHAUSTION -> concurrentContention(sameAppointment)
                }
            }.getOrElse { failure ->
                CommandExecution(
                    statusCode = HTTP_INTERNAL_ERROR,
                    errorCode = failure.javaClass.simpleName,
                    scenarioMatched = false,
                )
            }
        val elapsed = System.nanoTime() - started
        val result =
            CancellationObservation(
                operation = operation,
                statusCode = observation.statusCode,
                errorCode = observation.errorCode,
                elapsedNanos = elapsed,
                scenarioMatched = observation.scenarioMatched,
            )
        verifyPhaseOpen(phase)
        when (phase) {
            BenchmarkPhase.WARMUP -> {
                warmupRequests.incrementAndGet()
            }
            BenchmarkPhase.MEASUREMENT -> {
                cancelDurationsNanos += elapsed
                observations += result
            }
        }
        return result
    }

    private fun verifyPhaseOpen(phase: BenchmarkPhase) {
        when (phase) {
            BenchmarkPhase.WARMUP ->
                check(measurementStartedAtEpochMillis.get() == 0L) {
                    "warm-up request crossed the measurement start barrier"
                }
            BenchmarkPhase.MEASUREMENT -> {
                check(measurementStartedAtEpochMillis.get() > 0L) {
                    "measurement request started before the measurement barrier"
                }
                check(measurementEndedAtEpochMillis.get() == 0L) {
                    "measurement request crossed the measurement end barrier"
                }
            }
        }
    }

    /** current process의 세 번 중 한 번을 artifact로 저장한다. */
    fun writeReport(path: Path, runNumber: Int) {
        require(runNumber in 1..3) { "issue-34 benchmark run must be between 1 and 3" }
        stopLockWaitSampling()
        check(warmupRequests.get() > 0) { "warm-up must execute at least one request" }
        check(observations.isNotEmpty()) { "measurement must execute at least one request" }
        check(lockWaitSampleQueries.get() > 0) { "lock-wait sampling must execute at least one successful query" }
        check(lockWaitSampleFailures.get() == 0) { "lock-wait sampling failures must be zero" }
        val measurementStartedAt = measurementStartedAtEpochMillis.get()
        val measurementEndedAt = measurementEndedAtEpochMillis.get()
        val measurementStartedAtMonotonic = measurementStartedAtNanos.get()
        val measurementEndedAtMonotonic = measurementEndedAtNanos.get()
        check(measurementStartedAt > 0L) { "measurement start boundary was not recorded" }
        check(measurementEndedAt > measurementStartedAt) { "measurement end boundary must follow its start" }
        check(measurementStartedAtMonotonic > 0L) { "measurement monotonic start boundary was not recorded" }
        check(measurementEndedAtMonotonic > measurementStartedAtMonotonic) {
            "measurement monotonic end boundary must follow its start"
        }
        val measurementSpanMillis =
            TimeUnit.NANOSECONDS.toMillis(measurementEndedAtMonotonic - measurementStartedAtMonotonic)
        check(measurementSpanMillis > 0L) { "measurement monotonic span must be positive" }
        val environmentJson = environmentJson()
        val environmentFingerprint = environmentFingerprint(environmentJson)
        val reportEnvironmentJson =
            "${environmentJson.dropLast(1)},\"environmentFingerprint\":\"$environmentFingerprint\"}"
        val runJson =
            """
            {
              "run": $runNumber,
              "sourceCommit": "${escapeJson(sourceCommit())}",
              "environmentFingerprint": "$environmentFingerprint",
              "environment": $reportEnvironmentJson,
              "measurementStartedAtEpochMillis": $measurementStartedAt,
              "measurementEndedAtEpochMillis": $measurementEndedAt,
              "measurementClock": "SYSTEM_NANO_TIME",
              "measurementSpanMillis": $measurementSpanMillis,
              "cancelP95Millis": ${percentileMillis(cancelDurationsNanos, 0.95)},
              "cancelP99Millis": ${percentileMillis(cancelDurationsNanos, 0.99)},
              "unexpectedErrorRate": ${unexpectedErrorRate()},
              "unintendedRetryExhaustionRate": ${unintendedRetryExhaustionRate()},
              "lockWaitP95Millis": ${percentile(lockWaitMillis, 0.95)},
              "lockWaitSampleQueries": ${lockWaitSampleQueries.get()},
              "lockWaitSampleFailures": ${lockWaitSampleFailures.get()},
              "expectedConflictRate": ${expectedConflictRate()},
              "expectedRetryExhaustionRate": ${expectedRetryExhaustionRate()},
              "warmupRequests": ${warmupRequests.get()},
              "requests": ${observations.size},
              "scenarioMismatches": ${observations.count { !it.scenarioMatched }},
              "scenarioMismatchRate": ${scenarioMismatchRate()},
              "scenarioMismatchByOperation": ${countsJson(observations.filterNot(CancellationObservation::scenarioMatched).map { it.operation.name })},
              "errorCodeCounts": ${countsJson(observations.mapNotNull(CancellationObservation::errorCode))}
            }
            """.trimIndent()
        val content =
            if (Files.exists(path)) {
                appendRun(Files.readString(path), runJson, runNumber)
            } else {
                """
                {
                  "schemaVersion": 1,
                  "benchmark": "issue-34-patient-appointment-cancel",
                  "mode": "${reportMode()}",
                  "environment": $reportEnvironmentJson,
                  "runs": [$runJson]
                }
                """.trimIndent()
            }
        path.parent?.let(Files::createDirectories)
        Files.writeString(path, content)
    }

    private fun seedDataset() {
        repeat(datasetSize) { index ->
            val key = "issue34-seed-$index"
            val source = invoker.confirmDirect(clinic, key, "doctor-issue34-$index")
            check(source.success) { "seed confirmation failed: index=$index code=${source.errorCode}" }
            slots.set(
                index,
                CancelSlot(
                    index = index,
                    source = source,
                    cancelKey = "issue34-cancel-$index",
                    active = true,
                ),
            )
        }
        val replaySlot = slots[0] ?: error("replay slot was not seeded")
        val cancelled = invoker.cancel(clinic, replaySlot.source, replaySlot.cancelKey, "PATIENT")
        check(cancelled.success) { "replay fixture cancellation failed: code=${cancelled.errorCode}" }
        replayTarget = replaySlot.copy(active = false, lastCancellation = cancelled)
        slots.set(0, replayTarget)
        replenish(0, lastCancellation = cancelled)
    }

    private fun cancelActiveSlot(
        index: Int?,
        actorRole: String,
        allowTerminalConflict: Boolean,
    ): CommandExecution {
        index ?: return CommandExecution(HTTP_SERVICE_UNAVAILABLE, "SLOT_NOT_READY", false)
        val slot =
            if (allowTerminalConflict) {
                slots[index]
            } else {
                awaitActiveSlot(index)
            } ?: return CommandExecution(HTTP_SERVICE_UNAVAILABLE, "SLOT_NOT_READY", false)
        if (!slot.active) {
            if (!allowTerminalConflict) {
                return CommandExecution(HTTP_SERVICE_UNAVAILABLE, "SLOT_NOT_READY", false)
            }
            val terminalConflict =
                invoker.cancel(
                    clinic,
                    slot.source,
                    "issue34-terminal-conflict-${slot.index}-${System.nanoTime()}",
                    actorRole,
                )
            return mapOutcome(terminalConflict, "success-or-conflict")
        }
        val outcome = invoker.cancel(clinic, slot.source, slot.cancelKey, actorRole)
        if (outcome.success && !outcome.replay) {
            val replaced = slots.compareAndSet(index, slot, slot.copy(active = false, lastCancellation = outcome))
            if (replaced) replacementExecutor.submit { replenish(index) }
        }
        return mapOutcome(outcome, "success")
    }

    private fun replayCancelledSlot(): CommandExecution {
        val cancellation = replayTarget.lastCancellation ?: return CommandExecution(HTTP_SERVICE_UNAVAILABLE, "REPLAY_NOT_READY", false)
        val outcome = invoker.cancel(clinic, replayTarget.source, replayTarget.cancelKey, "PATIENT")
        check(outcome.success && outcome.replay) {
            "idempotent replay must reuse the cancelled result: expected=${cancellation.commitmentId} actual=${outcome.commitmentId}"
        }
        return CommandExecution(HTTP_OK, null, true)
    }

    private fun preconditionConflict(sameAppointment: Boolean): CommandExecution {
        val slot =
            if (sameAppointment) {
                slots[1]
            } else {
                nextDifferentSlot()?.let(::awaitActiveSlot)
            }
                ?: return CommandExecution(HTTP_SERVICE_UNAVAILABLE, "SLOT_NOT_READY", false)
        val stale = slot.source.copy(version = slot.source.version + 1)
        val outcome = invoker.cancel(clinic, stale, "issue34-conflict-${slot.index}-${System.nanoTime()}", "PATIENT")
        return mapOutcome(outcome, CancellationLoadOperation.PRECONDITION_CONFLICT.expectedOutcome)
    }

    private fun concurrentContention(sameAppointment: Boolean): CommandExecution {
        val slot =
            if (sameAppointment) {
                slots[1]
            } else {
                nextDifferentSlot()?.let(::awaitActiveSlot)
            }
                ?: return CommandExecution(HTTP_SERVICE_UNAVAILABLE, "SLOT_NOT_READY", false)
        val jobs =
            List(2) { attempt ->
                contentionExecutor.submit<CommandOutcome> {
                    invoker.cancel(
                        clinic,
                        slot.source,
                        "issue34-contention-${slot.index}-${System.nanoTime()}-$attempt",
                        "PATIENT",
                    )
                }
            }
        val outcomes = jobs.map(Future<CommandOutcome>::get)
        val selected =
            outcomes.firstOrNull { it.errorCode == "RETRY_EXHAUSTED" }
                ?: outcomes.firstOrNull { it.errorCode in CONFLICT_CODES }
                ?: outcomes.first()
        if (outcomes.any { it.success && !it.replay }) {
            val replaced =
                slots.compareAndSet(
                    slot.index,
                    slot,
                    slot.copy(active = false, lastCancellation = outcomes.first { it.success }),
                )
            if (replaced) replacementExecutor.submit { replenish(slot.index) }
        }
        return mapOutcome(selected, CancellationLoadOperation.RETRY_EXHAUSTION.expectedOutcome)
    }

    private fun awaitActiveSlot(index: Int): CancelSlot? {
        repeat(MAX_SLOT_WAIT_ATTEMPTS) {
            slots[index]?.takeIf(CancelSlot::active)?.let { return it }
            Thread.sleep(SLOT_WAIT_MILLIS)
        }
        return null
    }

    private fun replenish(index: Int, lastCancellation: CommandOutcome? = null) {
        val sequence = replacementSequence.incrementAndGet()
        val key = "issue34-replacement-$index-$sequence"
        val source = invoker.confirmDirect(clinic, key, "doctor-issue34-$index-$sequence")
        if (source.success) {
            val replacement =
                CancelSlot(
                    index = index,
                    source = source,
                    cancelKey = "issue34-cancel-$index-$sequence",
                    active = true,
                    lastCancellation = lastCancellation,
                )
            slots.set(index, replacement)
        }
    }

    /**
     * 비동기 replacement가 진행 중인 slot은 건너뛰고 현재 active인 slot을 고른다.
     * replacement transaction을 요청 latency에 포함시키지 않으면서 SLOT_NOT_READY를
     * 의도된 contention 결과로 오인하지 않도록 하는 bounded scan이다.
     */
    private fun nextDifferentSlot(): Int? {
        val first = 1 + (differentSlotCursor.getAndIncrement() - 1) % (datasetSize - 1)
        repeat(datasetSize - 1) { offset ->
            val index = 1 + (first - 1 + offset) % (datasetSize - 1)
            if (slots[index]?.active == true) return index
        }
        return null
    }

    private fun mapOutcome(
        outcome: CommandOutcome,
        expectedOutcome: String,
    ): CommandExecution {
        val status =
            when {
                outcome.success -> HTTP_OK
                outcome.errorCode in CONFLICT_CODES -> HTTP_PRECONDITION_FAILED
                outcome.errorCode == "RETRY_EXHAUSTED" -> HTTP_SERVICE_UNAVAILABLE
                else -> HTTP_INTERNAL_ERROR
            }
        val matched =
            when (expectedOutcome) {
                "success" -> outcome.success
                "success-or-conflict" -> outcome.success || status == HTTP_PRECONDITION_FAILED
                "replay" -> outcome.success && outcome.replay
                "precondition-conflict" -> status == HTTP_PRECONDITION_FAILED
                // PostgreSQL row contention can resolve as a domain VERSION_CONFLICT
                // before the bounded serialization retry reaches RETRY_EXHAUSTED.
                // Both are intentional outcomes of this hostile contention lane;
                // only an unrelated 5xx is an unexpected scenario mismatch.
                "retry-exhaustion" ->
                    status == HTTP_PRECONDITION_FAILED ||
                        (status == HTTP_SERVICE_UNAVAILABLE && outcome.errorCode == "RETRY_EXHAUSTED")
                else -> false
            }
        return CommandExecution(status, outcome.errorCode, matched)
    }

    private fun sampleLockWaits() {
        try {
            transaction(database) {
                val jdbcConnection =
                    TransactionManager.current().connection.connection as java.sql.Connection
                val statement =
                    jdbcConnection.prepareStatement(
                        """
                        select extract(epoch from (clock_timestamp() - query_start)) * 1000
                        from pg_stat_activity
                        where datname = current_database() and wait_event_type = 'Lock'
                        """.trimIndent(),
                    )
                statement.use { query ->
                    query.queryTimeout = LOCK_WAIT_QUERY_TIMEOUT_SECONDS
                    query.executeQuery().use { rows ->
                        while (rows.next()) lockWaitMillis += rows.getDouble(1)
                    }
                }
            }
            lockWaitSampleQueries.incrementAndGet()
        } catch (failure: Exception) {
            lockWaitSampleFailures.incrementAndGet()
            throw failure
        }
    }

    private fun unexpectedErrorRate(): Double =
        observations.count {
            it.statusCode >= HTTP_INTERNAL_ERROR &&
                !(it.operation == CancellationLoadOperation.RETRY_EXHAUSTION && it.errorCode == "RETRY_EXHAUSTED")
        }.toDouble() /
            observations.size.coerceAtLeast(1)

    private fun unintendedRetryExhaustionRate(): Double =
        observations.count {
            it.errorCode == "RETRY_EXHAUSTED" && it.operation.expectedOutcome != "retry-exhaustion"
        }.toDouble() / observations.size.coerceAtLeast(1)

    private fun expectedConflictRate(): Double =
        observations.count {
            it.statusCode == HTTP_PRECONDITION_FAILED
        }.toDouble() / observations.size.coerceAtLeast(1)

    private fun expectedRetryExhaustionRate(): Double =
        observations.count {
            it.operation == CancellationLoadOperation.RETRY_EXHAUSTION && it.errorCode == "RETRY_EXHAUSTED"
        }.toDouble() / observations.size.coerceAtLeast(1)

    private fun scenarioMismatchRate(): Double =
        observations.count { !it.scenarioMatched }.toDouble() / observations.size.coerceAtLeast(1)

    private fun percentileMillis(values: Collection<Long>, percentile: Double): Double =
        percentile(values.map { it / 1_000_000.0 }, percentile)

    private fun percentile(values: Collection<Double>, percentile: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val index = ceil((sorted.size - 1) * percentile).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    override fun close() {
        try {
            stopLockWaitSampling()
        } finally {
            samplerExecutor.shutdownNow()
            try {
                awaitTermination(replacementExecutor)
            } finally {
                awaitTermination(contentionExecutor)
            }
        }
    }

    private fun awaitTermination(executor: java.util.concurrent.ExecutorService) {
        if (!executor.isShutdown) executor.shutdown()
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow()
    }

    private data class CancelSlot(
        val index: Int,
        val source: CommandOutcome,
        val cancelKey: String,
        val active: Boolean,
        val lastCancellation: CommandOutcome? = null,
    )

    private data class CommandExecution(
        val statusCode: Int,
        val errorCode: String?,
        val scenarioMatched: Boolean,
    )

    private companion object {
        const val DATASET_SIZE = 100
        const val SEED = 34
        const val WARMUP_SECONDS = 30L
        const val MEASURE_SECONDS = 300L
        const val TOTAL_CONCURRENCY = 30
        const val PHASE_BARRIER_TIMEOUT_SECONDS = 60L
        const val SAMPLER_SHUTDOWN_TIMEOUT_SECONDS = 10L
        const val LOCK_WAIT_QUERY_TIMEOUT_SECONDS = 5
        const val PAUSE_MILLIS = 1_000L
        const val HTTP_OK = 200
        const val HTTP_PRECONDITION_FAILED = 412
        const val HTTP_INTERNAL_ERROR = 500
        const val HTTP_SERVICE_UNAVAILABLE = 503
        const val MAX_SLOT_WAIT_ATTEMPTS = 200
        const val SLOT_WAIT_MILLIS = 5L
        val CONFLICT_CODES =
            setOf(
                "VERSION_CONFLICT",
                "INVALID_TRANSITION",
                "PROPOSAL_NOT_CURRENT",
                "RESOURCE_CONFLICT",
            )

        fun systemSeconds(name: String, defaultValue: Long): Long =
            System.getProperty(name)?.toLongOrNull()?.coerceAtLeast(1L) ?: defaultValue

        fun systemMillis(name: String, defaultValue: Long): Long =
            System.getProperty(name)?.toLongOrNull()?.coerceAtLeast(0L) ?: defaultValue

        fun reportMode(): String =
            System.getProperty("issue34.mode", "candidate").also {
                require(it == "baseline" || it == "candidate") { "issue34.mode must be baseline or candidate" }
            }

        fun sourceCommit(): String =
            System.getProperty("issue34.sourceCommit")
                ?: System.getenv("GITHUB_SHA")
                ?: "unknown"

        fun environmentJson(): String =
            """{"datasetAppointments":$DATASET_SIZE,"warmupSeconds":${systemSeconds("issue34.warmupSeconds", WARMUP_SECONDS)},"measureSeconds":${systemSeconds("issue34.measureSeconds", MEASURE_SECONDS)},"sameAppointmentConcurrency":10,"differentAppointmentConcurrency":20,"pauseMillis":${systemMillis("issue34.pauseMillis", PAUSE_MILLIS)},"seed":$SEED,"postgresqlImage":"${PostgreSQLServer.IMAGE}:${PostgreSQLServer.TAG}","jdk":"${escapeJson(System.getProperty("java.runtime.version"))}","vm":"${escapeJson(System.getProperty("java.vm.name"))}","sourceCommit":"${escapeJson(sourceCommit())}"}"""

        fun environmentFingerprint(environmentJson: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(environmentJson.toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { byte -> "%02x".format(byte) }

        fun appendRun(existing: String, runJson: String, runNumber: Int): String {
            val report = REPORT_MAPPER.readTree(existing) as? ObjectNode
            requireNotNull(report) { "issue-34 report root must be an object" }
            require(report.path("mode").stringValue() == reportMode()) {
                "issue-34 report mode does not match the current run"
            }
            val environment = report.path("environment") as? ObjectNode
            requireNotNull(environment) { "issue-34 report is missing environment object" }
            val existingSourceCommit = environment.path("sourceCommit").stringValue()
            require(existingSourceCommit == sourceCommit()) {
                "issue-34 report sourceCommit does not match the current run"
            }
            val existingEnvironmentFingerprint = environment.path("environmentFingerprint").stringValue()
            require(existingEnvironmentFingerprint == environmentFingerprint(environmentJson())) {
                "issue-34 report environmentFingerprint does not match the current run"
            }
            val runs = report.path("runs") as? ArrayNode
            requireNotNull(runs) { "issue-34 report is missing runs array" }
            require(runs.none { it.path("run").asInt(-1) == runNumber }) {
                "issue-34 report already contains run=$runNumber"
            }
            val runNode = REPORT_MAPPER.readTree(runJson) as? ObjectNode
            requireNotNull(runNode) { "issue-34 run must be an object" }
            require(runNode.path("run").asInt(-1) == runNumber) {
                "issue-34 run payload does not match run=$runNumber"
            }
            runs.add(runNode)
            return REPORT_MAPPER.writeValueAsString(report)
        }

        fun escapeJson(value: String): String =
            value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

        fun countsJson(values: List<String>): String =
            values.groupingBy { it }.eachCount().entries.joinToString(
                prefix = "{",
                postfix = "}",
                separator = ",",
            ) { (key, count) -> "\"${escapeJson(key)}\":$count" }

        private val REPORT_MAPPER: JsonMapper = JsonMapper.builder().build()
    }
}
