package io.bluetape4k.clinic.appointment.benchmark

import io.bluetape4k.clinic.appointment.messaging.AppointmentConsumerBeginResult
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.time.Instant
import java.util.concurrent.TimeUnit

/** PostgreSQL V23 consumer inbox duplicate and cleanup benchmark. */
@State(Scope.Benchmark)
open class PostgreSqlAppointmentConsumerBenchmark {
    private lateinit var fixture: PostgreSqlBenchmarkFixture

    @Param("10000", "100000")
    var consumerRows: Int = 10_000

    @Setup(Level.Trial)
    fun setUpTrial() {
        fixture = PostgreSqlBenchmarkFixture().also {
            it.start()
            it.seedDuplicateConsumerRow()
            it.seedProcessedConsumerRows(consumerRows)
        }
    }

    @Benchmark
    fun duplicateInboxLookup(): Boolean =
        fixture.consumerInboxStore.begin(
            identity = PostgreSqlBenchmarkFixture.DUPLICATE_CONSUMER_IDENTITY,
            eventId = PostgreSqlBenchmarkFixture.DUPLICATE_EVENT_ID,
            provenance = PostgreSqlBenchmarkFixture.DUPLICATE_PROVENANCE,
        ) is AppointmentConsumerBeginResult.Duplicate

    @Setup(Level.Invocation)
    fun seedCleanupRows() {
        fixture.seedProcessedConsumerRows(CLEANUP_BATCH_SIZE)
    }

    @Benchmark
    fun boundedCleanup(): Int = fixture.consumerInboxStore.cleanupProcessed(
        cutoff = Instant.now(),
        batchSize = CLEANUP_BATCH_SIZE,
    )

    /**
     * Same-key inbox inserts intentionally race on the composite primary key.
     * The returned sample is the slower participant's transaction time, which
     * keeps the unique-index/row-lock wait visible without using production data.
     */
    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    fun duplicateInboxInsertContention(): Long = fixture.measureDuplicateInsertContention()

    @TearDown(Level.Trial)
    fun tearDownTrial() {
        fixture.close()
    }

    companion object {
        const val CLEANUP_BATCH_SIZE = 32
    }
}
