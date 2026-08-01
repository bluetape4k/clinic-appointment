package io.bluetape4k.clinic.appointment.api.reliability

/** retention batch 하나의 부분 실패를 다른 clinic 처리와 격리하는 runner입니다. */
class BookingReliabilityRetentionRunner(
    private val service: BookingReliabilityRetentionService,
) {
    fun run(requests: Iterable<BookingReliabilityRetentionRequest>): BookingReliabilityRetentionBatchResult {
        val results = mutableListOf<BookingReliabilityRetentionResult>()
        val failures = mutableListOf<BookingReliabilityRetentionFailure>()
        requests.forEach { request ->
            try {
                results += service.execute(request)
            } catch (error: RuntimeException) {
                failures += BookingReliabilityRetentionFailure(request, "RETENTION_EXECUTION_FAILED")
            }
        }
        return BookingReliabilityRetentionBatchResult(results, failures)
    }
}

data class BookingReliabilityRetentionFailure(
    val request: BookingReliabilityRetentionRequest,
    val code: String,
)

data class BookingReliabilityRetentionBatchResult(
    val results: List<BookingReliabilityRetentionResult>,
    val failures: List<BookingReliabilityRetentionFailure>,
)
