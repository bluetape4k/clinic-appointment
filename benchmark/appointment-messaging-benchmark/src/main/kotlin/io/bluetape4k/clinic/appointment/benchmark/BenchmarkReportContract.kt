package io.bluetape4k.clinic.appointment.benchmark

/**
 * Stable fields required by the repository's PostgreSQL benchmark evidence.
 *
 * The Gradle plugin owns the raw JSON schema. This small contract deliberately
 * validates only the fields that downstream documentation and charts consume.
 */
object BenchmarkReportContract {

    fun validate(json: String) {
        require(
            Regex("\\\"benchmark\\\"\\s*:\\s*\\\"[^\\\"]*PostgreSqlAppointmentOutboxBenchmark\\.claimBatch\\\"")
                .containsMatchIn(json),
        ) {
            "benchmark report must identify claimBatch"
        }
        require(Regex("\\\"database\\\"\\s*:\\s*\\\"postgresql\\\"").containsMatchIn(json)) {
            "benchmark report must identify PostgreSQL"
        }
        require(positiveNumber(json, "rows")) { "benchmark report must contain positive rows" }
        require(positiveNumber(json, "score")) { "benchmark report must contain positive score" }
        listOf("p50", "p95", "p99").forEach { percentile ->
            require(positiveNumber(json, percentile)) {
                "benchmark report must contain positive $percentile"
            }
        }
    }

    fun validateConsumer(json: String) {
        require(
            Regex("\\\"benchmarkFamily\\\"\\s*:\\s*\\\"[^\\\"]*PostgreSqlAppointmentConsumerBenchmark\\\"")
                .containsMatchIn(json),
        ) {
            "consumer report must identify the PostgreSQL consumer benchmark family"
        }
        require(Regex("\\\"database\\\"\\s*:\\s*\\\"postgresql\\\"").containsMatchIn(json)) {
            "consumer report must identify PostgreSQL"
        }
        require(positiveNumber(json, "cleanupBatchSize")) {
            "consumer report must contain a positive cleanup batch size"
        }
        require(Regex("\\\"rowCounts\\\"\\s*:\\s*\\[[^]]*10000[^]]*100000[^]]*]").containsMatchIn(json)) {
            "consumer report must contain the 10000 and 100000 row scenarios"
        }
        listOf("boundedCleanup", "duplicateInboxLookup", "duplicateInboxInsertContention").forEach { operation ->
            require(Regex("\\\"operation\\\"\\s*:\\s*\\\"$operation\\\"").containsMatchIn(json)) {
                "consumer report must contain $operation"
            }
        }
        listOf("score", "p50", "p95", "p99").forEach { field ->
            require(positiveNumber(json, field)) {
                "consumer report must contain positive $field"
            }
        }
    }

    private fun positiveNumber(json: String, field: String): Boolean =
        Regex("\\\"$field\\\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)")
            .find(json)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
            ?.let { it > 0.0 } == true
}
