package io.bluetape4k.clinic.appointment.api.waitlist

import java.time.Clock
import java.time.Instant

/** purge 대상 종류를 닫힌 enum으로 유지해 arbitrary table delete를 막습니다. */
enum class WaitlistRetentionKind {
    COMMAND_RECORD,
    TERMINAL_VACANCY,
    TERMINAL_OFFER,
    AUDIT_EVENT,
    ADJUSTMENT_EVENT,
}

data class WaitlistRetentionRequest(
    val kind: WaitlistRetentionKind,
    val cutoff: Instant,
    val limit: Int,
) {
    init {
        require(limit in 1..100) { "retention limit must be between 1 and 100" }
    }
}

data class WaitlistRetentionResult(
    val kind: WaitlistRetentionKind,
    val deleted: Int,
    val skipped: Int = 0,
) {
    init {
        require(deleted >= 0 && skipped >= 0) { "retention counts must be non-negative" }
    }
}

fun interface WaitlistRetentionStore {
    fun purge(request: WaitlistRetentionRequest): WaitlistRetentionResult
}

data class WaitlistRetentionBatchResult(
    val results: List<WaitlistRetentionResult>,
    val failures: List<WaitlistRetentionFailure>,
)

data class WaitlistRetentionFailure(
    val kind: WaitlistRetentionKind,
    val reasonCode: String = "RETENTION_EXECUTION_FAILED",
)

/** terminal/legal-hold row을 store가 제외하면서 kind별 bounded transaction을 수행합니다. */
class WaitlistRetentionRunner(
    private val store: WaitlistRetentionStore,
    private val properties: WaitlistDeliveryProperties,
    private val clock: Clock = Clock.systemUTC(),
    private val retentionByKind: Map<WaitlistRetentionKind, java.time.Duration> = DEFAULT_RETENTION,
) {
    init {
        require(retentionByKind.keys.containsAll(WaitlistRetentionKind.entries)) {
            "retentionByKind must define every waitlist retention kind"
        }
        require(retentionByKind.values.all { it.isPositive }) {
            "retention durations must be positive"
        }
    }

    fun run(): WaitlistRetentionBatchResult {
        val now = clock.instant()
        val results = mutableListOf<WaitlistRetentionResult>()
        val failures = mutableListOf<WaitlistRetentionFailure>()
        WaitlistRetentionKind.entries.forEach { kind ->
            try {
                results += store.purge(
                    WaitlistRetentionRequest(
                        kind = kind,
                        cutoff = now.minus(retentionByKind.getValue(kind)),
                        limit = properties.retentionBatchSize,
                    ),
                )
            } catch (_: RuntimeException) {
                failures += WaitlistRetentionFailure(kind)
            }
        }
        return WaitlistRetentionBatchResult(results, failures)
    }

    companion object {
        val DEFAULT_RETENTION: Map<WaitlistRetentionKind, java.time.Duration> = mapOf(
            WaitlistRetentionKind.COMMAND_RECORD to java.time.Duration.ofHours(24),
            WaitlistRetentionKind.TERMINAL_VACANCY to java.time.Duration.ofDays(90),
            WaitlistRetentionKind.TERMINAL_OFFER to java.time.Duration.ofDays(365),
            WaitlistRetentionKind.AUDIT_EVENT to java.time.Duration.ofDays(365),
            WaitlistRetentionKind.ADJUSTMENT_EVENT to java.time.Duration.ofDays(365),
        )
    }
}
