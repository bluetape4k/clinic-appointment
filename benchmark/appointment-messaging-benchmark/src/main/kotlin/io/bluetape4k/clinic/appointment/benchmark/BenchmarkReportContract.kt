package io.bluetape4k.clinic.appointment.benchmark

/**
 * 저장소의 PostgreSQL benchmark 증거에 필요한 안정적인 필드입니다.
 *
 * raw JSON schema의 소유권은 Gradle plugin에 있습니다. 이 작은 계약은
 * downstream 문서와 chart가 사용하는 필드만 의도적으로 검증합니다.
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
