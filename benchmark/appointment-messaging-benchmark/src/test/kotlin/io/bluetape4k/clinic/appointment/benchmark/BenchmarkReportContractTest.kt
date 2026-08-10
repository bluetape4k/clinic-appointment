package io.bluetape4k.clinic.appointment.benchmark

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Test

class BenchmarkReportContractTest {

    @Test
    fun `report validation accepts production PostgreSQL evidence`() {
        BenchmarkReportContract.validate(
            """
            {
              "benchmark": "io.bluetape4k.clinic.appointment.benchmark.PostgreSqlAppointmentOutboxBenchmark.claimBatch",
              "database": "postgresql",
              "rows": 20000,
              "score": 0.0018,
              "percentiles": {"p50": 0.0018, "p95": 0.0019, "p99": 0.0020}
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `report validation rejects missing PostgreSQL percentile evidence`() {
        assertFailsWith<IllegalArgumentException> {
            BenchmarkReportContract.validate(
                """{"benchmark":"claimBatch","database":"h2","rows":0}"""
            )
        }
    }

    @Test
    fun `consumer report validation accepts both inbox row scenarios`() {
        BenchmarkReportContract.validateConsumer(
            """
            {
              "benchmarkFamily": "io.bluetape4k.clinic.appointment.benchmark.PostgreSqlAppointmentConsumerBenchmark",
              "database": "postgresql",
              "rowCounts": [10000, 100000],
              "cleanupBatchSize": 32,
              "measurements": [
                {"operation":"boundedCleanup","rows":10000,"score":0.04,"percentiles":{"p50":0.04,"p95":0.05,"p99":0.05}},
                {"operation":"duplicateInboxLookup","rows":100000,"score":0.55,"percentiles":{"p50":0.55,"p95":0.57,"p99":0.57}},
                {"operation":"duplicateInboxInsertContention","rows":100000,"score":0.12,"percentiles":{"p50":0.12,"p95":0.14,"p99":0.15}}
              ]
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `production PostgreSQL migrations remain available to the isolated benchmark`() {
        javaClass.classLoader
            .getResource("db/migration/postgresql/V1__init_schema.sql")
            .shouldNotBeNull()
        javaClass.classLoader
            .getResource("db/migration/postgresql/V25__bind_appointment_replay_hash_to_partition.sql")
            .shouldNotBeNull()
    }
}
