package io.bluetape4k.clinic.appointment.api.reliability

import java.time.Instant

/** legal hold와 retention class를 먼저 적용하는 삭제 계획입니다. raw PII를 읽지 않습니다. */
class BookingReliabilityRetentionService(
    private val executor: BookingReliabilityRetentionExecutor = BookingReliabilityRetentionExecutor { 0L },
) {
    fun execute(request: BookingReliabilityRetentionRequest): BookingReliabilityRetentionResult {
        if (request.legalHold) return BookingReliabilityRetentionResult.skipped(request, "LEGAL_HOLD")
        val deleted = executor.deleteBounded(request)
        return BookingReliabilityRetentionResult(request, deleted, skipped = false, reason = null)
    }
}

fun interface BookingReliabilityRetentionExecutor {
    fun deleteBounded(request: BookingReliabilityRetentionRequest): Long
}

data class BookingReliabilityRetentionRequest(
    val tenantGroupId: Long,
    val clinicId: Long,
    val cutoff: Instant,
    val retentionClass: String,
    val legalHold: Boolean = false,
) {
    init {
        require(tenantGroupId > 0) { "tenantGroupId must be positive" }
        require(clinicId > 0) { "clinicId must be positive" }
        require(retentionClass.isNotBlank() && retentionClass.length <= 32) {
            "retentionClass must contain 1..32 characters"
        }
    }
}

data class BookingReliabilityRetentionResult(
    val request: BookingReliabilityRetentionRequest,
    val deletedRows: Long,
    val skipped: Boolean,
    val reason: String?,
) {
    init {
        require(deletedRows >= 0) { "deletedRows must be non-negative" }
    }

    companion object {
        fun skipped(request: BookingReliabilityRetentionRequest, reason: String) =
            BookingReliabilityRetentionResult(request, 0, skipped = true, reason = reason)
    }
}
