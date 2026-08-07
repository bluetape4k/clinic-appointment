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

/** PostgreSQL V23 consumer inbox 중복 조회와 정리 benchmark입니다. */
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
     * 동일 key inbox insert는 composite primary key에서 의도적으로 경합합니다.
     * 반환 sample은 더 느린 참여자의 트랜잭션 시간이며, 운영 데이터를 사용하지
     * 않고도 unique-index/row-lock 대기를 드러냅니다.
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
