package io.bluetape4k.clinic.appointment.benchmark

import io.bluetape4k.clinic.appointment.messaging.AppointmentConsumerBeginResult
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.time.Instant

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

    @TearDown(Level.Trial)
    fun tearDownTrial() {
        fixture.close()
    }

    companion object {
        const val CLEANUP_BATCH_SIZE = 32
    }
}
