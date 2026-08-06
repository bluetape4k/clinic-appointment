package io.bluetape4k.clinic.appointment.benchmark

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.time.Duration

/** PostgreSQL production-schema claim benchmark for the appointment outbox relay. */
@State(Scope.Benchmark)
open class PostgreSqlAppointmentOutboxBenchmark {

    private lateinit var fixture: PostgreSqlBenchmarkFixture

    @Setup(Level.Trial)
    fun setUpTrial() {
        fixture = PostgreSqlBenchmarkFixture().also { it.start() }
    }

    @Benchmark
    fun claimBatch(): Int = fixture.store
        .claim(
            owner = "benchmark",
            limit = 32,
            leaseDuration = Duration.ofSeconds(30),
        )
        .size

    @TearDown(Level.Trial)
    fun tearDownTrial() {
        fixture.close()
    }
}
